package com.kmtakip.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

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

        fun updateFromBridge(context: android.content.Context, distanceKm: Double, speedKmh: Double, elapsedSeconds: Long) {
            instance?.updateNotification(distanceKm, speedKmh, elapsedSeconds)
        }
    }

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastMotionAt = 0L
    private val prefs by lazy { getSharedPreferences("km_tracking", MODE_PRIVATE) }
    private var lastNotificationAt = 0L
    private var lastBridgeUpdateAt = 0L
    private var trackingStartedAt = 0L
    private val notificationHandler = Handler(Looper.getMainLooper())
    private val notificationTicker = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (!isPaused) {
                val now = System.currentTimeMillis()
                if (now - lastBridgeUpdateAt > 2000L) {
                    val current = prefs.getLong("elapsed", 0L)
                    prefs.edit().putLong("elapsed", current + 1L).apply()
                }
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
        }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        isPaused = prefs.getBoolean("paused", false)
        trackingStartedAt = System.currentTimeMillis()
        lastBridgeUpdateAt = System.currentTimeMillis()
        instance = this

        startForeground(NOTIFICATION_ID, buildNotification())

        isRunning = true
        startMotionSensor()
        startLocationUpdates()
        notificationHandler.postDelayed(notificationTicker, 1000L)
        KmTrackingWidget.updateAll(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                togglePause()
                return START_STICKY
            }

            ACTION_STOP -> {
                stopTrackingInternal()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    private fun stopTrackingInternal() {
        isRunning = false
        isPaused = false
        isMotionDetected = false

        notificationHandler.removeCallbacks(notificationTicker)
        stopMotionSensor()

        if (::locationManager.isInitialized) {
            try {
                locationManager.removeUpdates(locationListener)
            } catch (_: SecurityException) {
            }
        }

        prefs.edit()
            .putBoolean("paused", false)
            .apply()

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
            stopMotionSensor()
            if (::locationManager.isInitialized) {
                try { locationManager.removeUpdates(locationListener) } catch (_: SecurityException) {}
            }
        } else {
            startMotionSensor()
            startLocationUpdates()
        }
        notificationManager().notify(NOTIFICATION_ID, buildNotification())
        KmTrackingWidget.updateAll(this)
    }

    fun updateNotification(distanceKm: Double, speedKmh: Double, elapsedSeconds: Long) {
        if (!isRunning) return
        val now = System.currentTimeMillis()
        lastBridgeUpdateAt = now
        prefs.edit()
            .putFloat("distance", distanceKm.toFloat())
            .putFloat("speed", speedKmh.toFloat())
            .putLong("elapsed", elapsedSeconds)
            .apply()
        if (now - lastNotificationAt >= 700L) {
            lastNotificationAt = now
            notificationManager().notify(NOTIFICATION_ID, buildNotification())
            KmTrackingWidget.updateAll(this)
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun buildNotification(): Notification {
        val distance = prefs.getFloat("distance", 0f).toDouble()
        val speed = prefs.getFloat("speed", 0f).toDouble()
        val elapsed = prefs.getLong("elapsed", 0L)
        val title = if (isPaused) "⏸ KM Takip • Duraklatıldı" else "🟢 KM Takip • Sürüş aktif"
        val time = formatShortDuration(elapsed)
        val text = String.format(java.util.Locale.US, "%.2f km · %.0f km/h · %s", distance, speed, time)
            .replace('.', ',')

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 6002, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = Intent(this, TrackingService::class.java).setAction(ACTION_PAUSE)
        val stopIntent = Intent(this, TrackingService::class.java).setAction(ACTION_STOP)
        val pausePending = PendingIntent.getService(
            this, 6003, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getService(
            this, 6004, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
            .addAction(android.R.drawable.ic_media_pause, if (isPaused) "Devam" else "Duraklat", pausePending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Durdur", stopPending)
            .build()
    }

    private fun formatShortDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        return if (h > 0) String.format(java.util.Locale.US, "%02d:%02d", h, m)
        else String.format(java.util.Locale.US, "%02d:%02d", m, totalSeconds % 60)
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
            stopSelf()
            return
        }

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    2f,
                    locationListener
                )
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    5f,
                    locationListener
                )
            }
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KM Takip sürüş takibi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ekran kapalıyken sürüş takibini aktif tutar"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopMotionSensor()

        if (::locationManager.isInitialized) {
            try {
                locationManager.removeUpdates(locationListener)
            } catch (_: SecurityException) {
            }
        }

        isRunning = false
        isPaused = false
        notificationHandler.removeCallbacks(notificationTicker)
        instance = null
        prefs.edit().putBoolean("paused", false).apply()
        notificationManager().cancel(NOTIFICATION_ID)
        KmTrackingWidget.updateAll(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
