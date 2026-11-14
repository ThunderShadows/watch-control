package com.watchcoach.app.gestures

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.KeyEvent
import com.watchcoach.app.WatchCoachApplication
import com.watchcoach.app.ble.gloryfit.WatchEvent
import com.watchcoach.app.data.settings.GestureMapping
import com.watchcoach.app.desktop.DesktopCompanionClient
import com.watchcoach.app.notifications.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GestureActionDispatcher(
    private val context: Context,
    private val desktop: DesktopCompanionClient = DesktopCompanionClient(),
) {
    @Volatile
    private var findPhonePlayer: MediaPlayer? = null

    suspend fun dispatch(event: WatchEvent, mapping: GestureMapping?): String {
        if (mapping == null || mapping.action == MappedAction.NONE) {
            return "No action mapped for ${event.kind.label}"
        }
        val app = context.applicationContext as WatchCoachApplication
        val settings = app.featureSettings.current()
        return when (mapping.action) {
            MappedAction.NONE -> "Ignored"
            MappedAction.OPEN_APP -> openApp(mapping.payload)
            MappedAction.INTENT_URI -> runIntent(mapping.payload)
            MappedAction.DESKTOP_KEY -> {
                val key = mapping.payload.ifBlank { defaultKey(event) }
                desktop.key(settings.desktopHost, settings.desktopPort, settings.desktopToken, key)
                    .fold({ "Sent $key to laptop" }, { "Laptop: ${it.message}" })
            }
            MappedAction.DESKTOP_CLICK -> {
                desktop.click(settings.desktopHost, settings.desktopPort, settings.desktopToken, mapping.payload.ifBlank { "left" })
                    .fold({ "Clicked on laptop" }, { "Laptop: ${it.message}" })
            }
            MappedAction.PHONE_MEDIA -> phoneMedia(mapping.payload.ifBlank { defaultMedia(event) })
            MappedAction.FIND_PHONE -> findPhone(mapping.payload != "stop" && event.kind.name != "FIND_PHONE_STOP")
            MappedAction.OPEN_CAMERA -> openCamera()
        }
    }

    private fun defaultKey(event: WatchEvent): String = when (event.kind) {
        com.watchcoach.app.ble.gloryfit.WatchEventKind.MUSIC_NEXT -> "right"
        com.watchcoach.app.ble.gloryfit.WatchEventKind.MUSIC_PREV -> "left"
        com.watchcoach.app.ble.gloryfit.WatchEventKind.MUSIC_PLAY_PAUSE -> "space"
        com.watchcoach.app.ble.gloryfit.WatchEventKind.MUSIC_VOL_UP -> "volup"
        com.watchcoach.app.ble.gloryfit.WatchEventKind.MUSIC_VOL_DOWN -> "voldown"
        else -> "right"
    }

    private fun defaultMedia(event: WatchEvent): String = when (event.kind) {
        com.watchcoach.app.ble.gloryfit.WatchEventKind.MUSIC_NEXT -> "next"
        com.watchcoach.app.ble.gloryfit.WatchEventKind.MUSIC_PREV -> "prev"
        com.watchcoach.app.ble.gloryfit.WatchEventKind.MUSIC_VOL_UP -> "volup"
        com.watchcoach.app.ble.gloryfit.WatchEventKind.MUSIC_VOL_DOWN -> "voldown"
        else -> "playpause"
    }

    private fun openApp(packageName: String): String {
        if (packageName.isBlank()) return "No package set for Open app"
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "No launch intent for $packageName"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return "Opened $packageName"
    }

    private fun runIntent(uri: String): String {
        if (uri.isBlank()) return "No intent URI set"
        val intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Started intent"
    }

    private fun openCamera(): String {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Opened camera"
    }

    private fun phoneMedia(command: String): String {
        val key = when (command) {
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "volup" -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                return "Volume up"
            }
            "voldown" -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                return "Volume down"
            }
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val eventTime = System.currentTimeMillis()
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            val ev = KeyEvent(eventTime, eventTime, action, key, 0)
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, ev)
            }
            context.sendBroadcast(intent)
        }
        return "Phone media $command"
    }

    private suspend fun findPhone(start: Boolean): String = withContext(Dispatchers.Main) {
        if (!start) {
            findPhonePlayer?.runCatching { stop(); release() }
            findPhonePlayer = null
            NotificationHelper.postFindPhone(context, false)
            return@withContext "Find phone stopped"
        }
        NotificationHelper.postFindPhone(context, true)
        vibrate()
        if (findPhonePlayer == null) {
            val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            findPhonePlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                isLooping = true
                setAudioStreamType(AudioManager.STREAM_ALARM)
                prepare()
                start()
            }
        }
        "Ringing the phone (find phone)"
    }

    private fun vibrate() {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), 0))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(longArrayOf(0, 400, 200, 400), 0)
        }
    }

    fun stopFindPhone() {
        findPhonePlayer?.runCatching { stop(); release() }
        findPhonePlayer = null
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.applicationContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vib.cancel()
    }
}
