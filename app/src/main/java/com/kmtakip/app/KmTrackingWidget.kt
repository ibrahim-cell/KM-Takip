package com.kmtakip.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.util.Locale

class KmTrackingWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_START = "com.kmtakip.app.widget.START"
        const val ACTION_WIDGET_PAUSE = "com.kmtakip.app.widget.PAUSE"
        const val ACTION_WIDGET_STOP = "com.kmtakip.app.widget.STOP"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, KmTrackingWidget::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) update(context, manager, ids)
        }

        fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val prefs = context.getSharedPreferences("km_tracking", Context.MODE_PRIVATE)
            val running = TrackingService.isRunning
            val paused = TrackingService.isPaused
            val distance = prefs.getFloat("distance", 0f).toDouble()
            val speed = prefs.getFloat("speed", 0f).toDouble()
            val elapsed = prefs.getLong("elapsed", 0L)

            val views = RemoteViews(context.packageName, R.layout.widget_km_tracking)
            val stateText = when {
                !running -> "KM Takip • Hazır"
                paused -> "KM Takip • Duraklatıldı"
                else -> "KM Takip • Sürüş aktif"
            }
            val stateIcon = if (running && !paused) "🟢" else "⚪"
            views.setTextViewText(R.id.widget_title, "$stateIcon  $stateText")
            views.setTextViewText(
                R.id.widget_stats,
                String.format(Locale.US, "%.2f km · %.0f km/h · %s", distance, speed, formatTime(elapsed)).replace('.', ',')
            )

            if (!running) {
                views.setTextViewText(R.id.widget_action_primary, "▶ Başlat")
                views.setTextViewText(R.id.widget_action_secondary, "Aç")
                views.setOnClickPendingIntent(R.id.widget_action_primary, servicePendingIntent(context, ACTION_WIDGET_START, 7101))
            } else {
                views.setTextViewText(R.id.widget_action_primary, if (paused) "▶ Devam" else "⏸ Duraklat")
                views.setOnClickPendingIntent(R.id.widget_action_primary, servicePendingIntent(context, ACTION_WIDGET_PAUSE, 7102))
                views.setTextViewText(R.id.widget_action_secondary, "⏹ Durdur")
                views.setOnClickPendingIntent(R.id.widget_action_secondary, servicePendingIntent(context, ACTION_WIDGET_STOP, 7103))
            }

            views.setOnClickPendingIntent(R.id.widget_title, openAppPendingIntent(context))
            views.setOnClickPendingIntent(R.id.widget_stats, openAppPendingIntent(context))
            manager.updateAppWidget(ids, views)
        }

        private fun servicePendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, KmTrackingWidget::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun openAppPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                7100,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun formatTime(totalSeconds: Long): String {
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            return if (h > 0) String.format(Locale.US, "%02d:%02d", h, m)
            else String.format(Locale.US, "%02d:%02d", m, totalSeconds % 60)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        update(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_WIDGET_START -> {
                val serviceIntent = Intent(context, TrackingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            ACTION_WIDGET_PAUSE -> context.startService(
                Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_PAUSE)
            )
            ACTION_WIDGET_STOP -> context.startService(
                Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_STOP)
            )
        }
        super.onReceive(context, intent)
        if (intent.action?.startsWith("com.kmtakip.app.widget.") == true) updateAll(context)
    }
}
