package com.watchcoach.app.data.health

import java.time.Duration
import kotlin.math.roundToInt

object RecoveryCalculator {

    const val SLEEP_FULL_HOURS = 8.0
    const val SLEEP_ZERO_HOURS = 4.0
    const val HEART_DELTA_ZERO_SCORE_BPM = 15.0
    const val SLEEP_WEIGHT = 0.6
    const val HEART_WEIGHT = 0.4

    fun compute(
        sleepDuration: Duration?,
        currentRestingHr: Long?,
        baselineRestingHr: Long?,
    ): RecoveryScore {
        val sleepHours = sleepDuration?.let { it.seconds / 3600.0 }
        val sleepScore = sleepHours?.let { hoursToSleepScore(it) }
        val heartScore = if (currentRestingHr != null && baselineRestingHr != null && baselineRestingHr > 0) {
            heartDeltaToScore((currentRestingHr - baselineRestingHr).toDouble())
        } else {
            null
        }

        val value = when {
            sleepScore != null && heartScore != null -> {
                (SLEEP_WEIGHT * sleepScore + HEART_WEIGHT * heartScore).roundToInt()
                    .coerceIn(0, 100)
            }
            sleepScore != null -> sleepScore
            heartScore != null -> heartScore
            else -> null
        }

        val explanation = buildExplanation(
            value, sleepScore, heartScore, sleepHours, currentRestingHr, baselineRestingHr,
        )

        return RecoveryScore(
            value = value,
            sleepScore = sleepScore,
            heartScore = heartScore,
            sleepHours = sleepHours,
            currentRestingHr = currentRestingHr,
            baselineRestingHr = baselineRestingHr,
            explanation = explanation,
        )
    }

    fun hoursToSleepScore(hours: Double): Int {
        return when {
            hours >= SLEEP_FULL_HOURS -> 100
            hours <= SLEEP_ZERO_HOURS -> 0
            else -> (((hours - SLEEP_ZERO_HOURS) / (SLEEP_FULL_HOURS - SLEEP_ZERO_HOURS)) * 100)
                .roundToInt()
                .coerceIn(0, 100)
        }
    }

    fun heartDeltaToScore(deltaBpm: Double): Int {
        return when {
            deltaBpm <= 0 -> 100
            deltaBpm >= HEART_DELTA_ZERO_SCORE_BPM -> 0
            else -> (((HEART_DELTA_ZERO_SCORE_BPM - deltaBpm) / HEART_DELTA_ZERO_SCORE_BPM) * 100)
                .roundToInt()
                .coerceIn(0, 100)
        }
    }

    private fun buildExplanation(
        value: Int?,
        sleepScore: Int?,
        heartScore: Int?,
        sleepHours: Double?,
        currentRestingHr: Long?,
        baselineRestingHr: Long?,
    ): String {
        if (value == null) {
            return "Need last night's sleep or a resting heart-rate baseline to compute recovery."
        }
        val parts = mutableListOf<String>()
        if (sleepHours != null && sleepScore != null) {
            parts += "Sleep ${"%.1f".format(sleepHours)} h → $sleepScore/100"
        } else {
            parts += "Sleep data missing"
        }
        if (currentRestingHr != null && baselineRestingHr != null && heartScore != null) {
            val delta = currentRestingHr - baselineRestingHr
            val deltaLabel = if (delta >= 0) "+$delta" else "$delta"
            parts += "Resting HR $currentRestingHr bpm vs 7-day $baselineRestingHr ($deltaLabel) → $heartScore/100"
        } else {
            parts += "Resting HR baseline missing"
        }
        val mix = when {
            sleepScore != null && heartScore != null ->
                "Weighted 60% sleep + 40% heart."
            sleepScore != null ->
                "Score is sleep-only until a 7-day resting HR baseline is available."
            else ->
                "Score is heart-only until last night's sleep is available."
        }
        return parts.joinToString(". ") + ". $mix"
    }
}
