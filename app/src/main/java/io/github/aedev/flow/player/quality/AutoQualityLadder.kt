package io.github.aedev.flow.player.quality

import io.github.aedev.flow.data.local.DataSaver

/**
 * Auto's quality for a connection speed, the same on Wi-Fi and mobile data and for videos and Shorts.
 * Speeds are in kilobytes per second (KB/s), the unit speed meters show; 100 KB/s is about 0.8 Mbps:
 *
 * - under 260 KB/s (90 to 260 and anything slower): 480p, never lower
 * - 260 to 400 KB/s: 720p
 * - 400 to 660 KB/s: 1080p
 * - 660 to 1100 KB/s: 1440p
 * - 1100 KB/s and up: 2160p
 *
 * Data Saver caps the result at 720p. A video that does not have the named height plays at the
 * nearest height below it.
 */
object AutoQualityLadder {
    private const val BITS_PER_KILOBYTE = 8_000L
    private const val UP_TO_480P_KB_PER_S = 260L
    private const val UP_TO_720P_KB_PER_S = 400L
    private const val UP_TO_1080P_KB_PER_S = 660L
    private const val UP_TO_1440P_KB_PER_S = 1100L

    /** The tallest height Auto may use right now: unlimited, or 720p while Data Saver is on. */
    fun currentMaxHeight(): Int = if (DataSaver.enabled) DataSaver.MAX_VIDEO_HEIGHT else Int.MAX_VALUE

    fun targetHeight(
        estimateBps: Long,
        maxHeight: Int = currentMaxHeight(),
    ): Int {
        val kiloBytesPerSecond = estimateBps / BITS_PER_KILOBYTE
        val height =
            when {
                kiloBytesPerSecond < UP_TO_480P_KB_PER_S -> 480
                kiloBytesPerSecond < UP_TO_720P_KB_PER_S -> 720
                kiloBytesPerSecond < UP_TO_1080P_KB_PER_S -> 1080
                kiloBytesPerSecond < UP_TO_1440P_KB_PER_S -> 1440
                else -> 2160
            }
        return minOf(height, maxHeight)
    }

    /**
     * The measured speed Shorts Auto is currently using. Sampled a few times while a short is
     * playing (YouTube-style continuous estimation) so quality can step with the connection.
     * 0 means no speed has been measured yet.
     */
    @Volatile
    var shortsEstimateBps: Long = 0L

    /** The height a Short set to Auto plays at. With no measured speed yet it starts at the lowest, 480p. */
    fun shortsHeight(): Int = if (shortsEstimateBps <= 0L) minOf(480, currentMaxHeight()) else targetHeight(shortsEstimateBps)
}
