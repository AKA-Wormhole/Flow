package io.github.aedev.flow.player.quality

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoQualityLadderTest {
    private fun kBps(value: Long) = value * 8_000L

    @Test
    fun slowConnectionsNeverDropBelow480p() {
        assertEquals(480, AutoQualityLadder.targetHeight(kBps(0), Int.MAX_VALUE))
        assertEquals(480, AutoQualityLadder.targetHeight(kBps(90), Int.MAX_VALUE))
        assertEquals(480, AutoQualityLadder.targetHeight(kBps(200), Int.MAX_VALUE))
        assertEquals(480, AutoQualityLadder.targetHeight(kBps(259), Int.MAX_VALUE))
    }

    @Test
    fun speedStepsUpTheLadder() {
        assertEquals(720, AutoQualityLadder.targetHeight(kBps(260), Int.MAX_VALUE))
        assertEquals(720, AutoQualityLadder.targetHeight(kBps(399), Int.MAX_VALUE))
        assertEquals(1080, AutoQualityLadder.targetHeight(kBps(400), Int.MAX_VALUE))
        assertEquals(1080, AutoQualityLadder.targetHeight(kBps(659), Int.MAX_VALUE))
        assertEquals(1440, AutoQualityLadder.targetHeight(kBps(660), Int.MAX_VALUE))
        assertEquals(1440, AutoQualityLadder.targetHeight(kBps(1099), Int.MAX_VALUE))
        assertEquals(2160, AutoQualityLadder.targetHeight(kBps(1100), Int.MAX_VALUE))
        assertEquals(2160, AutoQualityLadder.targetHeight(kBps(50_000), Int.MAX_VALUE))
    }

    @Test
    fun shortsStartAt480pUntilASpeedHasBeenMeasured() {
        val saved = AutoQualityLadder.shortsEstimateBps
        try {
            AutoQualityLadder.shortsEstimateBps = 0L
            assertEquals(480, AutoQualityLadder.shortsHeight())
            AutoQualityLadder.shortsEstimateBps = kBps(500)
            assertEquals(1080, AutoQualityLadder.shortsHeight())
            AutoQualityLadder.shortsEstimateBps = kBps(100)
            assertEquals(480, AutoQualityLadder.shortsHeight())
        } finally {
            AutoQualityLadder.shortsEstimateBps = saved
        }
    }

    @Test
    fun theCapHoldsTheResultDown() {
        assertEquals(720, AutoQualityLadder.targetHeight(kBps(50_000), 720))
        assertEquals(480, AutoQualityLadder.targetHeight(kBps(100), 720))
    }
}
