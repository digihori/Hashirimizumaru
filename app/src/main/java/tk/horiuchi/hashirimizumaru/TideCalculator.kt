package tk.horiuchi.hashirimizumaru

import java.time.Instant
import kotlin.math.cos
import kotlin.math.floor

enum class TideTrend(val label: String, val symbol: String) {
    RISING("上げ", "↑"), FALLING("下げ", "↓"), SLACK("潮止まり", "→")
}

enum class TideExtremumType(val label: String) { HIGH("満潮"), LOW("干潮") }

data class TideExtremum(val type: TideExtremumType, val timeMillis: Long, val heightCm: Double)
data class TideSnapshot(
    val heightCm: Double,
    val trend: TideTrend,
    val tideCycle: String,
    val nextExtremum: TideExtremum,
    val graph: List<Pair<Long, Double>>
)

object YokosukaTideCalculator {
    // 海上保安庁「横須賀」調和定数の主要8分潮。位相は下記基準時刻の推算値に合わせた値。
    private data class Constituent(val speedDegreesPerHour: Double, val amplitudeCm: Double, val phaseAtEpoch: Double)
    private val constituents = listOf(
        Constituent(28.9841042, 40.75, 151.2), // M2
        Constituent(30.0000000, 19.96, 80.9),  // S2
        Constituent(15.0410686, 24.02, 316.1), // K1
        Constituent(13.9430356, 18.89, 356.7), // O1
        Constituent(28.4397295, 6.21, 24.0),   // N2
        Constituent(30.0821373, 5.44, 295.6),  // K2
        Constituent(14.9589314, 7.85, 316.0),  // P1
        Constituent(13.3986609, 3.89, 159.2)   // Q1
    )
    private const val MEAN_LEVEL_CM = 110.0
    private val phaseEpoch = Instant.parse("2026-07-27T15:00:00Z").toEpochMilli()
    private val newMoonEpoch = Instant.parse("2000-01-06T18:14:00Z").toEpochMilli()

    fun heightCm(timeMillis: Long): Double {
        val hours = (timeMillis - phaseEpoch) / 3_600_000.0
        return MEAN_LEVEL_CM + constituents.sumOf { part ->
            part.amplitudeCm * cos(Math.toRadians(part.speedDegreesPerHour * hours - part.phaseAtEpoch))
        }
    }

    fun snapshot(timeMillis: Long = System.currentTimeMillis()): TideSnapshot {
        val current = heightCm(timeMillis)
        val change = heightCm(timeMillis + 30 * 60_000L) - heightCm(timeMillis - 30 * 60_000L)
        val trend = when {
            change > 3.0 -> TideTrend.RISING
            change < -3.0 -> TideTrend.FALLING
            else -> TideTrend.SLACK
        }
        val points = (-12..12).map { offset ->
            val time = timeMillis + offset * 30 * 60_000L
            time to heightCm(time)
        }
        return TideSnapshot(current, trend, tideCycle(timeMillis), findNextExtremum(timeMillis), points)
    }

    private fun findNextExtremum(now: Long): TideExtremum {
        val step = 5 * 60_000L
        var previous = heightCm(now)
        var current = heightCm(now + step)
        var time = now + 2 * step
        while (time <= now + 15 * 60 * 60_000L) {
            val next = heightCm(time)
            if (current >= previous && current > next) return TideExtremum(TideExtremumType.HIGH, time - step, current)
            if (current <= previous && current < next) return TideExtremum(TideExtremumType.LOW, time - step, current)
            previous = current
            current = next
            time += step
        }
        return TideExtremum(TideExtremumType.HIGH, now, current)
    }

    private fun tideCycle(timeMillis: Long): String {
        val synodicDays = 29.530588853
        val age = ((timeMillis - newMoonEpoch) / 86_400_000.0).mod(synodicDays)
        val lunarDay = floor(age).toInt() + 1
        return when (lunarDay) {
            1, 2, 14, 15, 16, 17, 29, 30 -> "大潮"
            3, 4, 5, 6, 12, 13, 18, 19, 20, 21, 27, 28 -> "中潮"
            7, 8, 9, 22, 23, 24 -> "小潮"
            10, 25 -> "長潮"
            else -> "若潮"
        }
    }
}
