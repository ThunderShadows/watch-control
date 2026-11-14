package com.watchcoach.app.data.settings

import com.watchcoach.app.ble.gloryfit.WatchEventKind
import com.watchcoach.app.gestures.MappedAction

data class FeatureSettings(
    val backgroundSyncEnabled: Boolean = false,
    val lastBackgroundSyncEpochMs: Long = 0L,
    val lastBackgroundSyncOk: Boolean? = null,
    val lastBackgroundSyncMessage: String? = null,
    val weeklyReportEnabled: Boolean = true,
    val lastWeeklyReportEpochMs: Long = 0L,
    val ageYears: Int = 30,
    val desktopHost: String = "",
    val desktopPort: Int = 8765,
    val desktopToken: String = "",
    val heightCm: Int = 170,
    val weightKg: Int = 70,
    val screenTimeoutSec: Int = 5,
    val stepsGoal: Int = 8_000,
    val userMale: Boolean = true,
    val celsius: Boolean = true,
    val mappings: List<GestureMapping> = GestureMapping.defaults(),
)

data class GestureMapping(
    val kind: WatchEventKind,
    val action: MappedAction,
    val payload: String = "",
) {
    companion object {
        fun defaults(): List<GestureMapping> = listOf(
            GestureMapping(WatchEventKind.MUSIC_PLAY_PAUSE, MappedAction.PHONE_MEDIA, "playpause"),
            GestureMapping(WatchEventKind.MUSIC_NEXT, MappedAction.DESKTOP_KEY, "right"),
            GestureMapping(WatchEventKind.MUSIC_PREV, MappedAction.DESKTOP_KEY, "left"),
            GestureMapping(WatchEventKind.MUSIC_VOL_UP, MappedAction.PHONE_MEDIA, "volup"),
            GestureMapping(WatchEventKind.MUSIC_VOL_DOWN, MappedAction.PHONE_MEDIA, "voldown"),
            GestureMapping(WatchEventKind.CAMERA_TRIGGER, MappedAction.OPEN_CAMERA, ""),
            GestureMapping(WatchEventKind.CAMERA_OPEN, MappedAction.OPEN_CAMERA, ""),
            GestureMapping(WatchEventKind.FIND_PHONE_START, MappedAction.FIND_PHONE, "start"),
            GestureMapping(WatchEventKind.FIND_PHONE_STOP, MappedAction.FIND_PHONE, "stop"),
        )
    }
}
