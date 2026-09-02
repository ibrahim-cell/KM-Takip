package com.kmtakip.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.max

class TrackingService : Service() {

    companion object {
        const val CHANNEL_ID = "km_takip_tracking"
        const val NOTIFICATION_ID = 6001
        const val ACTION_PAUSE = "com.kmtakip.app.action.PAUSE"
        const val ACTION_STOP = "com.kmtakip.app.action.STOP"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isMotionDetected: Boolean = false
            private set

        @Volatile
        var isPaused: Boolean = false
            private set

        @Volatile
        private var instance: TrackingService? = null

        fun updateFromBridge(
            context: android.content.Context,
            distanceKm: Double,
            speedKmh: Double,
            elapsedSeconds: Long
        ) {
            instance?.updateFromUi(distanceKm, speedKmh, elapsedSeconds)
        }

        fun setUiVisible(visible: Boolean) {
            instance?.uiVisible = visible
        }

        fun getTrackingSnapshot(): String {
            return instance?.snapshot() ?: "{\"running\":false,\"paused\":false,\"distance\":0,\"speed\":0,\"elapsed\":0,\"motion\":false,\"uiVisible\":false}"
        }
    }

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastMotionAt = 0L
    private val prefs by lazy { getSharedPreferences("km_tracking", MODE_PRIVATE) }
    private var lastNotificationAt = 0L
    private var lastNativeLocationAt = 0L
    private var lastLocation: Location? = null
    private var uiVisible = true

    private var elapsedBaseSeconds = 0L
    private var sessionStartedAtElapsedRealtime = 0L
    private var sessionRunning = false

    private val notificationHandler = Handler(Looper.getMainLooper())
    private val notificationTicker = object : Runnable {
        override fun run() {
            if (!isRunning) return

            if (!isPaused) {
                val elapsed = currentElapsedSeconds()
                prefs.edit().putLong("elapsed", elapsed).apply()
                notificationManager().notify(NOTIFICATION_ID, buildNotification())
                KmTrackingWidget.updateAll(this@TrackingService)
            }

            notificationHandler.postDelayed(this, 1000L)
        }
    }

    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION || isPaused) return

            val ax = event.values.getOrNull(0) ?: 0f
            val ay = event.values.getOrNull(1) ?: 0f
            val az = event.values.getOrNull(2) ?: 0f
            val magnitude = kotlin.math.sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
            val now = System.currentTimeMillis()

            if (magnitude >= 0.80f) {
                lastMotionAt = now
                isMotionDetected = true
            } else if (isMotionDetected && now - lastMotionAt >= 2500L) {
                isMotionDetected = false
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (isPaused) return
            processLocation(location)
        }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        isPaused = prefs.getBoolean("paused", false)
        instance = this
        isRunning = true

        elapsedBaseSeconds = prefs.getLong("elapsed", 0L)
        if (!isPaused) startElapsedSession()

        startForegroundCompat()
        startMotionSensor()
        startLocationUpdates()
        notificationHandler.post(notificationTicker)
        KmTrackingWidget.updateAll(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> togglePause()
            ACTION_STOP -> stopTrackingInternal()
        }

        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startElapsedSession() {
        if (sessionRunning) return
        sessionRunning = true
        sessionStartedAtElapsedRealtime = SystemClock.elapsedRealtime()
    }

    private fun stopElapsedSession() {
        if (!sessionRunning) return
        elapsedBaseSeconds = currentElapsedSeconds()
        sessionRunning = false
        prefs.edit().putLong("elapsed", elapsedBaseSeconds).apply()
    }

    private fun currentElapsedSeconds(): Long {
        if (!sessionRunning || isPaused) return elapsedBaseSeconds
        val delta = max(0L, SystemClock.elapsedRealtime() - sessionStartedAtElapsedRealtime) / 1000L
        return elapsedBaseSeconds + delta
    }

    private fun processLocation(location: Location) {
        if (!location.hasAccuracy() || location.accuracy > 50f) return

        val previous = lastLocation
        lastLocation = Location(location)
        lastNativeLocationAt = System.currentTimeMillis()

        var distanceKm = prefs.getFloat("distance", 0f).toDouble()
        var speedKmh = 0.0

        if (previous != null) {
            val distanceMeters = previous.distanceTo(location).toDouble()
            val dtSeconds = (location.time - previous.time).coerceAtLeast(0L) / 1000.0

            val locationSpeed = if (location.hasSpeed() && location.speed >= 0f) {
                location.speed * 3.6
            } else {
                0.0
            }

            val derivedSpeed = if (dtSeconds > 0.0 && distanceMeters >= 2.0) {
                (distanceMeters / dtSeconds) * 3.6
            } else {
                0.0
            }

            val plausibleMovement = distanceMeters >= 2.0 &&
                (dtSeconds <= 0.0 || derivedSpeed <= 180.0)
            if (plausibleMovement) {
                distanceKm += distanceMeters / 1000.0
            }

            speedKmh = when {
                locationSpeed in 2.0..180.0 -> locationSpeed
                derivedSpeed in 2.0..180.0 -> derivedSpeed
                else -> 0.0
            }
        } else if (location.hasSpeed() && location.speed >= 0f) {
            speedKmh = (location.speed * 3.6).coerceIn(0.0, 180.0)
        }

        isMotionDetected = speedKmh >= 2.0 || isMotionDetected
        if (speedKmh < 1.0 && System.currentTimeMillis() - lastMotionAt > 2500L) {
            isMotionDetected = false
        }

        prefs.edit()
            .putFloat("distance", distanceKm.toFloat())
            .putFloat("speed", speedKmh.toFloat())
            .putLong("elapsed", currentElapsedSeconds())
            .apply()

        pushLiveState()
    }

    private fun pushLiveState() {
        val now = System.currentTimeMillis()
        if (now - lastNotificationAt < 500L) return
        lastNotificationAt = now
        notificationManager().notify(NOTIFICATION_ID, buildNotification())
        KmTrackingWidget.updateAll(this)
    }

    private fun snapshot(): String {
        return org.json.JSONObject()
            .put("running", isRunning)
            .put("paused", isPaused)
            .put("distance", prefs.getFloat("distance", 0f).toDouble())
            .put("speed", prefs.getFloat("speed", 0f).toDouble())
            .put("elapsed", currentElapsedSeconds())
            .put("motion", isMotionDetected)
            .put("uiVisible", uiVisible)
            .toString()
    }

    private fun updateFromUi(distanceKm: Double, speedKmh: Double, elapsedSeconds: Long) {
        if (!isRunning || !uiVisible) return

        if (System.currentTimeMillis() - lastNativeLocationAt > 5000L) {
            prefs.edit().putLong("elapsed", max(currentElapsedSeconds(), elapsedSeconds)).apply()
            pushLiveState()
        }
    }

    private fun stopTrackingInternal() {
        stopElapsedSession()
        isRunning = false
        isPaused = false
        isMotionDetected = false
        sessionRunning = false

        notificationHandler.removeCallbacks(notificationTicker)
        stopMotionSensor()
        removeLocationUpdates()
        lastLocation = null

        prefs.edit().putBoolean("paused", false).apply()
        notificationManager().cancel(NOTIFICATION_ID)
        KmTrackingWidget.updateAll(this)
        instance = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
    }

    private fun togglePause() {
        isPaused = !isPaused
        prefs.edit().putBoolean("paused", isPaused).apply()

        if (isPaused) {
            stopElapsedSession()
            stopMotionSensor()
            removeLocationUpdates()
            lastLocation = null
            prefs.edit().putFloat("speed", 0f).apply()
        } else {
            startElapsedSession()
            startMotionSensor()
            startLocationUpdates()
        }

        notificationManager().notify(NOTIFICATION_ID, buildNotification())
        KmTrackingWidget.updateAll(this)
    }

    private fun removeLocationUpdates() {
        if (::locationManager.isInitialized) {
            try {
                locationManager.removeUpdates(locationListener)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun buildNotification(): Notification {
        val distance = prefs.getFloat("distance", 0f).toDouble()
        val speed = prefs.getFloat("speed", 0f).toDouble()
        val elapsed = currentElapsedSeconds()
        val title = when {
            isPaused -> "⏸ KM Takip • Duraklatıldı"
            !uiVisible -> "🟢 KM Takip • Arka planda aktif"
            else -> "🟢 KM Takip • Sürüş aktif"
        }
        val text = String.format(
            Locale.US,
            "%.2f km · %.0f km/h · %s",
            distance,
            speed,
            formatShortDuration(elapsed)
        ).replace('.', ',')

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            6002,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = Intent(this, TrackingService::class.java).setAction(ACTION_PAUSE)
        val stopIntent = Intent(this, TrackingService::class.java).setAction(ACTION_STOP)
        val pausePending = PendingIntent.getService(
            this,
            6003,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getService(
            this,
            6004,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (isPaused) "Devam" else "Duraklat",
                pausePending
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Durdur",
                stopPending
            )
            .build()
    }

    private fun formatShortDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        return if (h > 0) String.format(Locale.US, "%02d:%02d", h, m)
        else String.format(Locale.US, "%02d:%02d", m, totalSeconds % 60)
    }

    private fun startMotionSensor() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        isMotionDetected = false
        lastMotionAt = 0L

        accelerometer?.let {
            sensorManager.registerListener(
                motionListener,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }

    private fun stopMotionSensor() {
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(motionListener)
        }
        isMotionDetected = false
        lastMotionAt = 0L
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            stopTrackingInternal()
            return
        }

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        lastLocation = null

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    val ageMs = System.currentTimeMillis() - it.time
                    if (ageMs in 0L..30_000L && (!it.hasAccuracy() || it.accuracy <= 50f)) {
                        lastLocation = Location(it)
                    }
                }
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {
            stopTrackingInternal()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KM Takip sürüş takibi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ekran kapalıyken KM Takip sürüşünü ve canlı GPS hesabını aktif tutar"
                setShowBadge(false)
            }

            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (isRunning && !isPaused) {
            notificationManager().notify(NOTIFICATION_ID, buildNotification())
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopElapsedSession()
        stopMotionSensor()
        removeLocationUpdates()
        lastLocation = null
        isRunning = false
        isPaused = false
        notificationHandler.removeCallbacks(notificationTicker)
        instance = null
        prefs.edit().putBoolean("paused", false).apply()
        KmTrackingWidget.updateAll(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
