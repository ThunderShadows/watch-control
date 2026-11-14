package com.watchcoach.app.insights

import java.time.LocalDate

data class DayTotal(val date: LocalDate, val steps: Int, val avgHr: Int?, val rhr: Int?)

data class Trend(
    val days: List<DayTotal>,
    val stepChangePct: Double?,
    val rhrChangeBpm: Double?,
)

data class HourHeatmap(
    /** steps[dayIndex 0=Mon..6][hour 0..23] for the current week, or last 7 days. */
    val byHour: IntArray,
    val mostActiveHour: Int?,
    val leastActiveHour: Int?,
    val mostActiveLabel: String,
    val leastActiveLabel: String,
)

data class HrZones(
    val age: Int,
    val maxHr: Int,
    val counts: Map<String, Int>,
    val labels: List<Pair<String, String>>,
)

data class Insights(
    val lastNightRhr: Int?,
    val rhr7: Int?,
    val rhr30: Int?,
    val rhrMethod: String,
    val trend7: Trend,
    val trend30: Trend,
    val adaptiveGoal: Int,
    val adaptiveGoalNote: String,
    val heatmap: HourHeatmap,
    val zones: HrZones,
    val todaySteps: Int,
    val weekSteps: Int,
)

data class WeeklyReport(
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val daily: List<DayTotal>,
    val totalSteps: Int,
    val avgSteps: Int,
    val goal: Int,
    val daysGoalMet: Int,
    val lastNightRhr: Int?,
    val weekRhr: Int?,
    val heatmap: HourHeatmap,
    val zones: HrZones,
    val notes: List<String>,
) {
    fun asText(): String {
        val lines = mutableListOf("Watch Coach weekly report")
        lines += "Week: $weekStart – $weekEnd"
        lines += "Steps: $totalSteps total, $avgSteps / day (adaptive goal $goal, met $daysGoalMet/7 days)"
        daily.forEach { d ->
            lines += "  ${d.date}: ${d.steps} steps" +
                (d.rhr?.let { ", night RHR $it" } ?: "") +
                (d.avgHr?.let { ", avg HR $it" } ?: "")
        }
        lines += "Resting HR (night, lowest 10-min bins): " +
            (weekRhr?.let { "$it bpm (week)" } ?: "—") +
            (lastNightRhr?.let { ", last night $it" } ?: "")
        lines += "Most active hour: ${heatmap.mostActiveLabel}"
        lines += "Least active hour: ${heatmap.leastActiveLabel}"
        lines += "HR zones (history samples, max HR ${zones.maxHr} from 220−${zones.age}):"
        zones.labels.forEach { (name, range) ->
            lines += "  $name $range: ${zones.counts[name] ?: 0}"
        }
        notes.forEach { lines += it }
        return lines.joinToString("\n")
    }
}
