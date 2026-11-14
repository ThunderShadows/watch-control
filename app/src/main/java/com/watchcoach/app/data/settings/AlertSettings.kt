package com.watchcoach.app.data.settings

data class AlertSettings(
    val inactivityAlertsEnabled: Boolean = true,
    /** Steps taken in the last 2 hours below this value can trigger an inactivity alert. */
    val inactivityStepThreshold: Int = 200,
    /** Inclusive local hour (0–23) when daytime inactivity checks begin. */
    val daytimeStartHour: Int = 8,
    /** Exclusive local hour (1–24). 21 means checks run through 20:59. */
    val daytimeEndHour: Int = 21,
    val elevatedHrAlertsEnabled: Boolean = true,
    /** Fire when current resting HR is this many bpm above the 7-day baseline. */
    val elevatedHrBpmDelta: Int = 10,
    val lastInactivityNotificationEpochMs: Long = 0L,
    val lastElevatedHrNotificationEpochMs: Long = 0L,
) {
    fun isDaytime(hour: Int): Boolean {
        val start = daytimeStartHour.coerceIn(0, 23)
        val end = daytimeEndHour.coerceIn(1, 24)
        return if (start < end) {
            hour in start until end
        } else {
            // Overnight window, e.g. 20 → 6.
            hour >= start || hour < end
        }
    }
}
