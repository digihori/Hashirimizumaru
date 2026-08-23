package com.digihori.hashirimizumaru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TideCalculatorTest {
    @Test
    fun referenceDay_isCloseToPublishedHourlyPrediction() {
        val midnight = Instant.parse("2026-07-27T15:00:00Z").toEpochMilli()
        val published = listOf(133.0, 146.0, 157.0, 162.0, 159.0, 145.0, 122.0, 94.0,
            67.0, 46.0, 34.0, 34.0, 48.0, 72.0, 102.0, 132.0, 155.0, 167.0,
            167.0, 157.0, 142.0, 127.0, 117.0, 114.0, 121.0)
        published.forEachIndexed { hour, expected ->
            val actual = YokosukaTideCalculator.heightCm(midnight + hour * 3_600_000L)
            assertTrue("hour=$hour expected=$expected actual=$actual", kotlin.math.abs(expected - actual) < 15.0)
        }
    }

    @Test
    fun snapshot_hasGraphAndFutureExtremum() {
        val now = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli()
        val value = YokosukaTideCalculator.snapshot(now)
        assertEquals(25, value.graph.size)
        assertTrue(value.nextExtremum.timeMillis > now)
        assertTrue(value.nextExtremum.timeMillis <= now + 15 * 60 * 60_000L)
        assertTrue(value.heightCm in -20.0..250.0)
    }
}
