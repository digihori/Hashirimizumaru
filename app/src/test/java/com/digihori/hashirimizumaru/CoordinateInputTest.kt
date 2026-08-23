package com.digihori.hashirimizumaru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinateInputTest {
    @Test
    fun decimalAndDegreeMinute_areConverted() {
        val latitude = decimalToDegreeMinute(35.0 + 16.433 / 60.0)
        val longitude = decimalToDegreeMinute(139.0 + 42.520 / 60.0)
        assertEquals(35, latitude.degrees)
        assertEquals(16.433, latitude.minutes, 0.000001)
        assertEquals(139, longitude.degrees)
        assertEquals(42.520, longitude.minutes, 0.000001)
        assertEquals(35.273883333, degreeMinuteToDecimal(35, 16.433, 90)!!, 0.000000001)
        assertEquals(139.708666667, degreeMinuteToDecimal(139, 42.520, 180)!!, 0.000000001)
    }

    @Test
    fun invalidMinutes_areRejected() {
        assertNull(degreeMinuteToDecimal(35, 60.0, 90))
        assertNull(degreeMinuteToDecimal(91, 0.0, 90))
        assertNull(degreeMinuteToDecimal(180, 0.001, 180))
    }
}
