package com.watchcoach.app.insights

import com.watchcoach.app.data.local.HeartRateEntity
import com.watchcoach.app.data.local.StepHourEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

object InsightsCalculator {
    private const val NIGHT_START_HOUR = 23
    private const val NIGHT_END_HOUR = 7

    fun compute(
        steps: List<StepHourEntity>,
        heartRates: List<HeartRateEntity>,
        age: Int,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): Insights {
        val trend7 = trend(steps, heartRates, today.minusDays(6), today, zone)
        val trend30 = trend(steps, heartRates, today.minusDays(29), today, zone)
        val lastNight = nightlyRhr(heartRates, today, zone)
        val rhr7 = median(nightlyRhrs(heartRates, today.minusDays(6), today, zone))
        val rhr30 = median(nightlyRhrs(heartRates, today.minusDays(29), today, zone))
        val last14Totals = dailySteps(steps, today.minusDays(13), today, zone).map { it.steps }
        val complete = last14Totals.filter { it > 0 }
        val median14 = median(complete) ?: 8_000
        val goal = ((median14 * 1.10 / 100.0).roundToInt() * 100).coerceIn(1_000, 30_000)
        val goalNote = if (complete.size >= 7) {
            "Median of ${complete.size} active days in the last 14, plus 10%."
        } else {
            "Not enough complete days yet (${complete.size} of 14); goal uses what is stored, plus 10%."
        }
        return Insights(
            lastNightRhr = lastNight,
            rhr7 = rhr7,
            rhr30 = rhr30,
            rhrMethod = "Night RHR is the median of the lowest three 10-minute history bins " +
                "between 23:00 and 07:00 (live samples ignored). 7- and 30-day values are the " +
                "median of those nightly estimates.",
            trend7 = trend7,
            trend30 = trend30,
            adaptiveGoal = goal,
            adaptiveGoalNote = goalNote,
            heatmap = heatmap(steps, today, zone),
            zones = zones(heartRates, today.minusDays(6), today.plusDays(1), age, zone),
            todaySteps = dailySteps(steps, today, today, zone).firstOrNull()?.steps ?: 0,
            weekSteps = dailySteps(steps, today.minusDays(6), today, zone).sumOf { it.steps },
        )
    }

    fun weekReport(
        steps: List<StepHourEntity>,
        heartRates: List<HeartRateEntity>,
        age: Int,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        weekStart: LocalDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
    ): WeeklyReport {
        val weekEnd = weekStart.plusDays(6)
        val insights = compute(steps, heartRates, age, zone, today = weekEnd.coerceAtMost(today))
        val daily = dailySteps(steps, weekStart, weekEnd, zone).map { day ->
            val rhr = nightlyRhr(heartRates, day.date.plusDays(1), zone)
            val avg = avgHr(heartRates, day.date, zone)
            day.copy(avgHr = avg, rhr = rhr)
        }
        val total = daily.sumOf { it.steps }
        val avg = if (daily.isNotEmpty()) total / daily.size else 0
        val goal = insights.adaptiveGoal
        return WeeklyReport(
            weekStart = weekStart,
            weekEnd = weekEnd,
            daily = daily,
            totalSteps = total,
            avgSteps = avg,
            goal = goal,
            daysGoalMet = daily.count { it.steps >= goal },
            lastNightRhr = insights.lastNightRhr,
            weekRhr = median(daily.mapNotNull { it.rhr }),
            heatmap = heatmap(steps, weekEnd, zone, start = weekStart),
            zones = zones(heartRates, weekStart, weekEnd.plusDays(1), age, zone),
            notes = listOf(insights.rhrMethod, insights.adaptiveGoalNote),
        )
    }

    private fun dailySteps(
        steps: List<StepHourEntity>,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
    ): List<DayTotal> {
        val byDate = steps.groupBy { Instant.ofEpochMilli(it.epochHourMs).atZone(zone).toLocalDate() }
        val days = mutableListOf<DayTotal>()
        var d = from
        while (!d.isAfter(to)) {
            days += DayTotal(d, byDate[d]?.sumOf { it.totalSteps } ?: 0, null, null)
            d = d.plusDays(1)
        }
        return days
    }

    private fun trend(
        steps: List<StepHourEntity>,
        hrs: List<HeartRateEntity>,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
    ): Trend {
        val days = dailySteps(steps, from, to, zone).map { day ->
            day.copy(
                avgHr = avgHr(hrs, day.date, zone),
                rhr = nightlyRhr(hrs, day.date.plusDays(1), zone),
            )
        }
        val n = days.size
        val first = days.take((n / 2).coerceAtLeast(1)).map { it.steps }.average()
        val second = days.takeLast((n / 2).coerceAtLeast(1)).map { it.steps }.average()
        val pct = if (first > 0) ((second - first) / first) * 100.0 else null
        val rhrFirst = days.take((n / 2).coerceAtLeast(1)).mapNotNull { it.rhr }
        val rhrSecond = days.takeLast((n / 2).coerceAtLeast(1)).mapNotNull { it.rhr }
        val rhrDelta = if (rhrFirst.isNotEmpty() && rhrSecond.isNotEmpty()) {
            rhrSecond.average() - rhrFirst.average()
        } else null
        return Trend(days, pct, rhrDelta)
    }

    /** Night ending at [morning]'s 07:00, starting previous day 23:00. */
    fun nightlyRhr(hrs: List<HeartRateEntity>, morning: LocalDate, zone: ZoneId): Int? {
        val start = morning.minusDays(1).atTime(LocalTime.of(NIGHT_START_HOUR, 0)).atZone(zone).toInstant().toEpochMilli()
        val end = morning.atTime(LocalTime.of(NIGHT_END_HOUR, 0)).atZone(zone).toInstant().toEpochMilli()
        val bins = hrs.filter { !it.live && it.epochMs >= start && it.epochMs < end && it.bpm in 30..120 }
            .map { it.bpm }
            .sorted()
        if (bins.isEmpty()) return null
        val lowest = bins.take(3)
        return median(lowest)
    }

    private fun nightlyRhrs(
        hrs: List<HeartRateEntity>,
        fromMorning: LocalDate,
        toMorning: LocalDate,
        zone: ZoneId,
    ): List<Int> {
        val out = mutableListOf<Int>()
        var d = fromMorning
        while (!d.isAfter(toMorning)) {
            nightlyRhr(hrs, d, zone)?.let { out += it }
            d = d.plusDays(1)
        }
        return out
    }

    private fun avgHr(hrs: List<HeartRateEntity>, date: LocalDate, zone: ZoneId): Int? {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val vals = hrs.filter { !it.live && it.epochMs >= start && it.epochMs < end && it.bpm in 30..220 }
        if (vals.isEmpty()) return null
        return vals.map { it.bpm }.average().roundToInt()
    }

    private fun heatmap(
        steps: List<StepHourEntity>,
        end: LocalDate,
        zone: ZoneId,
        start: LocalDate = end.minusDays(6),
    ): HourHeatmap {
        val totals = IntArray(24)
        steps.forEach { row ->
            val t = Instant.ofEpochMilli(row.epochHourMs).atZone(zone)
            val d = t.toLocalDate()
            if (!d.isBefore(start) && !d.isAfter(end)) {
                totals[t.hour] += row.totalSteps
            }
        }
        val hoursWith = totals.withIndex().filter { it.value > 0 }
        val most = hoursWith.maxByOrNull { it.value }?.index
        val least = hoursWith.minByOrNull { it.value }?.index
        val fmt: (Int?) -> String = { h ->
            if (h == null) "—" else DateTimeFormatter.ofPattern("h a").format(LocalTime.of(h, 0))
        }
        return HourHeatmap(totals, most, least, fmt(most), fmt(least))
    }

    fun zones(
        hrs: List<HeartRateEntity>,
        from: LocalDate,
        toExclusive: LocalDate,
        age: Int,
        zone: ZoneId,
    ): HrZones {
        val max = (220 - age.coerceIn(3, 100)).coerceIn(120, 210)
        val labels = listOf(
            "Rest / < Z1" to "< ${pct(max, 50)} bpm",
            "Zone 1" to "${pct(max, 50)}–${pct(max, 60) - 1} bpm (50–60%)",
            "Zone 2" to "${pct(max, 60)}–${pct(max, 70) - 1} bpm (60–70%)",
            "Zone 3" to "${pct(max, 70)}–${pct(max, 80) - 1} bpm (70–80%)",
            "Zone 4" to "${pct(max, 80)}–${pct(max, 90) - 1} bpm (80–90%)",
            "Zone 5" to "${pct(max, 90)}+ bpm (90%+)",
        )
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = toExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        val counts = mutableMapOf<String, Int>()
        labels.forEach { counts[it.first] = 0 }
        hrs.filter { !it.live && it.epochMs >= start && it.epochMs < end && it.bpm in 30..220 }
            .forEach { s ->
                val name = when {
                    s.bpm < pct(max, 50) -> "Rest / < Z1"
                    s.bpm < pct(max, 60) -> "Zone 1"
                    s.bpm < pct(max, 70) -> "Zone 2"
                    s.bpm < pct(max, 80) -> "Zone 3"
                    s.bpm < pct(max, 90) -> "Zone 4"
                    else -> "Zone 5"
                }
                counts[name] = (counts[name] ?: 0) + 1
            }
        return HrZones(age, max, counts, labels)
    }

    private fun pct(max: Int, percent: Int): Int = (max * percent / 100.0).roundToInt()

    private fun median(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 0) ((s[mid - 1] + s[mid]) / 2) else s[mid]
    }
}
