package io.github.aedev.flow.player.cache

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * Least-recently-used evictor whose ceiling can change while the cache is open.
 *
 * Media3's own [androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor] fixes its limit at
 * construction, so changing the size setting did nothing until the process restarted, and a limit of
 * zero meant [androidx.media3.datasource.cache.NoOpCacheEvictor], i.e. a cache that only ever grows.
 * This one never runs unbounded: a non-positive limit is clamped to [MIN_BYTES].
 *
 * Besides size pressure it also drops spans nobody has read for a while ([evictIdleSince]), which is
 * what keeps a cache that is under its ceiling from holding on to videos watched months ago.
 */
@UnstableApi
class AdjustableLruCacheEvictor(
    maxBytes: Long,
) : CacheEvictor {
    @Volatile
    var maxBytes: Long = sanitize(maxBytes)
        private set

    private val lock = Any()
    private val leastRecentlyUsed = TreeSet<CacheSpan>(Comparator { lhs, rhs -> compareSpans(lhs, rhs) })
    private var currentSize = 0L

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() {
        // Spans found on disk were reported through onSpanAdded; nothing else to do.
    }

    override fun onStartFile(
        cache: Cache,
        key: String,
        position: Long,
        length: Long,
    ) {
        if (length != C.LENGTH_UNSET.toLong()) evictToFit(cache, length)
    }

    override fun onSpanAdded(
        cache: Cache,
        span: CacheSpan,
    ) {
        synchronized(lock) {
            leastRecentlyUsed.add(span)
            currentSize += span.length
        }
        evictToFit(cache, 0L)
    }

    override fun onSpanRemoved(
        cache: Cache,
        span: CacheSpan,
    ) {
        synchronized(lock) {
            if (leastRecentlyUsed.remove(span)) currentSize -= span.length
        }
    }

    override fun onSpanTouched(
        cache: Cache,
        oldSpan: CacheSpan,
        newSpan: CacheSpan,
    ) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    /** Applies a new ceiling right away, evicting the oldest spans if the cache is now over it. */
    fun setMaxBytes(
        cache: Cache,
        bytes: Long,
    ) {
        maxBytes = sanitize(bytes)
        evictToFit(cache, 0L)
    }

    /** Removes every span last read before [cutoffMillis]; returns how many bytes that freed. */
    fun evictIdleSince(
        cache: Cache,
        cutoffMillis: Long,
    ): Long {
        var freed = 0L
        while (true) {
            val oldest = synchronized(lock) { leastRecentlyUsed.firstOrNull() } ?: break
            if (oldest.lastTouchTimestamp >= cutoffMillis) break
            if (!removeQuietly(cache, oldest)) break
            freed += oldest.length
        }
        return freed
    }

    private fun evictToFit(
        cache: Cache,
        requiredSpace: Long,
    ) {
        while (true) {
            val oldest =
                synchronized(lock) {
                    if (currentSize + requiredSpace <= maxBytes) null else leastRecentlyUsed.firstOrNull()
                } ?: return
            if (!removeQuietly(cache, oldest)) return
        }
    }

    // removeSpan calls back into onSpanRemoved; the lock is never held across it, so the evictor and
    // the cache can't wait on each other from two threads.
    private fun removeQuietly(
        cache: Cache,
        span: CacheSpan,
    ): Boolean =
        try {
            cache.removeSpan(span)
            true
        } catch (_: Cache.CacheException) {
            // A span we cannot delete must not wedge the loop; forget it so eviction moves on.
            synchronized(lock) {
                if (leastRecentlyUsed.remove(span)) currentSize -= span.length
            }
            false
        }

    companion object {
        /** Floor for a limit that was stored as "no limit" by an older version. */
        const val MIN_BYTES = 64L * 1024L * 1024L

        private fun sanitize(bytes: Long): Long = if (bytes <= 0L) MIN_BYTES else bytes

        private fun compareSpans(
            lhs: CacheSpan,
            rhs: CacheSpan,
        ): Int {
            val delta = lhs.lastTouchTimestamp - rhs.lastTouchTimestamp
            return if (delta == 0L) lhs.compareTo(rhs) else if (lhs.lastTouchTimestamp < rhs.lastTouchTimestamp) -1 else 1
        }
    }
}
