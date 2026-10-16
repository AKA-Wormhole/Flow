package io.github.aedev.flow.player.quality

import android.content.Context

/**
 * The download speed the app has actually seen, kept apart for Wi-Fi and mobile data and remembered
 * between launches. Shorts Auto reads it as a visit starts, because the player's own estimate is a
 * guess for your country until it has downloaded enough, and a wrong guess would pick the highest
 * quality on a slow connection.
 */
object MeasuredSpeed {
    private const val PREFS = "measured_speed"
    private const val KEY_WIFI = "wifi_bps"
    private const val KEY_MOBILE = "mobile_bps"
    private const val MIN_SAMPLE_BYTES = 100_000L
    private const val BITS_PER_BYTE_MS = 8_000L
    private const val OLD_WEIGHT = 0.7
    private const val NEW_WEIGHT = 0.3
    private const val SAVE_INTERVAL_MS = 5_000L

    @Volatile private var wifiBps = 0L

    @Volatile private var mobileBps = 0L

    @Volatile private var lastSaveAt = 0L

    /** Bits per second last seen on this kind of network, or 0 when nothing has been measured yet. */
    fun read(
        context: Context,
        onWifi: Boolean,
    ): Long {
        val cached = if (onWifi) wifiBps else mobileBps
        if (cached > 0L) return cached
        val stored = prefs(context).getLong(if (onWifi) KEY_WIFI else KEY_MOBILE, 0L)
        if (onWifi) wifiBps = stored else mobileBps = stored
        return stored
    }

    /** Folds one finished download into the running figure. Small downloads say little, so they are skipped. */
    fun record(
        context: Context,
        onWifi: Boolean,
        bytesLoaded: Long,
        loadTimeMs: Long,
    ) {
        if (bytesLoaded < MIN_SAMPLE_BYTES || loadTimeMs <= 0L) return
        val sample = bytesLoaded * BITS_PER_BYTE_MS / loadTimeMs
        val previous = read(context, onWifi)
        val blended = if (previous <= 0L) sample else (previous * OLD_WEIGHT + sample * NEW_WEIGHT).toLong()
        if (onWifi) wifiBps = blended else mobileBps = blended
        val now = System.currentTimeMillis()
        if (now - lastSaveAt < SAVE_INTERVAL_MS) return
        lastSaveAt = now
        prefs(context)
            .edit()
            .putLong(KEY_WIFI, wifiBps)
            .putLong(KEY_MOBILE, mobileBps)
            .apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
