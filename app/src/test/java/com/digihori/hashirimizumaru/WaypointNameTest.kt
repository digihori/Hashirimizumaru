package com.digihori.hashirimizumaru

import org.junit.Assert.assertEquals
import org.junit.Test

class WaypointNameTest {
    @Test
    fun noWaypoints_startsAtOne() {
        assertEquals("ポイント1", nextWaypointName(emptyList()))
    }

    @Test
    fun defaultNames_continueFromLargestNumber() {
        val waypoints = listOf(
            waypoint("ポイント1"),
            waypoint("ポイント3"),
            waypoint("東京湾口")
        )

        assertEquals("ポイント4", nextWaypointName(waypoints))
    }

    @Test
    fun similarlyNamedWaypoints_areNotTreatedAsSequenceNumbers() {
        val waypoints = listOf(
            waypoint("ポイント12付近"),
            waypoint("ポイント01"),
            waypoint("ポイント2")
        )

        assertEquals("ポイント3", nextWaypointName(waypoints))
    }

    private fun waypoint(name: String) = Waypoint(
        name = name,
        latitude = 35.0,
        longitude = 139.0
    )
}
