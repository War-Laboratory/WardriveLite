package com.example.wardrive

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home screen widget: live counts plus a start / stop button. */
class ScanWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Prefs.init(context)
        for (id in ids) manager.updateAppWidget(id, build(context))
    }

    companion object {
        fun updateAll(ctx: Context) {
            try {
                val m = AppWidgetManager.getInstance(ctx) ?: return
                val ids = m.getAppWidgetIds(ComponentName(ctx, ScanWidget::class.java))
                if (ids.isEmpty()) return
                val v = build(ctx)
                for (id in ids) m.updateAppWidget(id, v)
            } catch (_: Exception) {}
        }

        private fun build(ctx: Context): RemoteViews {
            val p = Themes.current
            val all = Store.sightings.values
            val alerts = all.count { it.alert != null }
            val v = RemoteViews(ctx.packageName, R.layout.widget_scan)
            v.setTextViewText(R.id.w_wifi, all.count { it.kind == Kind.WIFI }.toString())
            v.setTextViewText(R.id.w_ble, all.count { it.kind == Kind.BLE }.toString())
            v.setTextViewText(R.id.w_cell, all.count { it.kind == Kind.CELL }.toString())
            v.setTextViewText(R.id.w_alerts, alerts.toString())
            v.setTextColor(R.id.w_alerts, if (alerts > 0) p.danger else p.accent)
            v.setTextColor(R.id.w_title, p.accent)
            v.setTextViewText(R.id.w_status, if (Store.scanning) "● LIVE" else "○ IDLE")
            v.setTextColor(R.id.w_status, if (Store.scanning) p.accent2 else p.dim)
            v.setTextViewText(R.id.w_button, if (Store.scanning) "■ STOP" else "▶ SCAN")

            val open = PendingIntent.getActivity(ctx, 10, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.w_root, open)
            // Starting goes through the app so it can ask for any missing permissions first.
            val btn = if (Store.scanning)
                PendingIntent.getService(ctx, 11, Intent(ctx, ScanService::class.java).setAction(ScanService.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            else
                PendingIntent.getActivity(ctx, 12, Intent(ctx, MainActivity::class.java).putExtra("autostart", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.w_button, btn)
            return v
        }
    }
}
