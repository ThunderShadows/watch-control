package com.watchcoach.app.gestures

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.watchcoach.app.desktop.DesktopCompanionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Air-mouse / tilt control using the *phone's* gyroscope — not the watch.
 * GloryFit/UTE does not document raw accelerometer streaming from the band.
 */
class PhoneAirMouse(
    context: Context,
    private val scope: CoroutineScope,
    private val client: DesktopCompanionClient = DesktopCompanionClient(),
) : SensorEventListener {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var job: Job? = null
    @Volatile private var pendingDx = 0
    @Volatile private var pendingDy = 0
    @Volatile var host: String = ""
    @Volatile var port: Int = 8765
    @Volatile var token: String = ""
    @Volatile var running: Boolean = false
        private set

    fun available(): Boolean = gyro != null

    fun start() {
        if (running) return
        if (gyro == null) return
        running = true
        sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        running = false
        sensors.unregisterListener(this)
        job?.cancel()
        pendingDx = 0
        pendingDy = 0
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        // Rotation around Y (yaw-ish when phone is landscape-ish) → X mouse; around X → Y mouse.
        val dx = (-event.values[2] * 28f).toInt()
        val dy = (event.values[0] * 28f).toInt()
        if (abs(dx) < 1 && abs(dy) < 1) return
        pendingDx += dx
        pendingDy += dy
        if (job?.isActive == true) return
        if (host.isBlank()) return
        val sendX = pendingDx
        val sendY = pendingDy
        pendingDx = 0
        pendingDy = 0
        job = scope.launch(Dispatchers.IO) {
            client.mouse(host, port, token, sendX, sendY)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
