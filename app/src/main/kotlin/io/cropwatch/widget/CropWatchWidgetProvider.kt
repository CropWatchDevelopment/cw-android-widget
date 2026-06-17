package io.cropwatch.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.util.concurrent.Executors

class CropWatchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        backgroundRefresh(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Cw.ACTION_REFRESH -> backgroundRefresh(ctx)
            Cw.ACTION_CLEAR_FILTERS -> {
                Session.setLocationFilter(ctx, -1, null)
                Session.setGroupFilter(ctx, null)
                backgroundRefresh(ctx)
            }
            else -> super.onReceive(ctx, intent)
        }
    }

    private fun backgroundRefresh(ctx: Context) {
        // Flip into the "busy" state and paint it (spinning, disabled refresh icon)
        // before kicking off the work; clear it once the fetch settles, win or lose.
        Session.setRefreshing(ctx, true)
        renderAll(ctx)
        val pending = goAsync()
        EXEC.execute {
            try {
                WidgetRepository.refreshBlocking(ctx)
            } catch (_: Exception) {
            } finally {
                Session.setRefreshing(ctx, false)
                renderAll(ctx)
                pending.finish()
            }
        }
    }

    companion object {
        private val EXEC = Executors.newSingleThreadExecutor()

        fun renderAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CropWatchWidgetProvider::class.java))
            for (id in ids) mgr.updateAppWidget(id, buildViews(ctx, id))
            if (ids.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(ids, R.id.device_list)
        }

        fun requestRefresh(ctx: Context) {
            ctx.sendBroadcast(Intent(ctx, CropWatchWidgetProvider::class.java).setAction(Cw.ACTION_REFRESH))
        }

        private fun cached(ctx: Context): JSONObject? =
            Session.cache(ctx)?.let { runCatching { JSONObject(it) }.getOrNull() }

        private fun buildViews(ctx: Context, appWidgetId: Int): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_cropwatch)

            // Header — only the gear (native account screen), refresh, filters and
            // sign-in are interactive. The brand/logo is intentionally inert.
            rv.setOnClickPendingIntent(R.id.btn_settings, openApp(ctx, 12))
            rv.setOnClickPendingIntent(R.id.btn_sign_in, openApp(ctx, 13))
            rv.setOnClickPendingIntent(R.id.btn_filter_location, openFilter(ctx, 14, FilterActivity.MODE_LOCATION))
            rv.setOnClickPendingIntent(R.id.btn_filter_group, openFilter(ctx, 15, FilterActivity.MODE_GROUP))

            // Refresh control: while a refresh is in flight, swap the tappable icon
            // for an indeterminate spinner (the one RemoteViews-safe way to animate
            // in a widget). With no icon shown and no click wired, it reads as a
            // disabled, spinning button until the fetch settles.
            val refreshing = Session.isRefreshing(ctx)
            rv.setViewVisibility(R.id.refresh_spinner, if (refreshing) View.VISIBLE else View.GONE)
            rv.setViewVisibility(R.id.btn_refresh, if (refreshing) View.GONE else View.VISIBLE)
            rv.setOnClickPendingIntent(R.id.btn_refresh, if (refreshing) null else refreshIntent(ctx))

            // Tint the filter icons when a filter is active.
            val locName = Session.locationFilterName(ctx)
            val group = Session.groupFilter(ctx)
            val active = ctx.getColor(R.color.cw_accent)
            val muted = ctx.getColor(R.color.cw_text_muted)
            rv.setInt(R.id.btn_filter_location, "setColorFilter", if (locName != null) active else muted)
            rv.setInt(R.id.btn_filter_group, "setColorFilter", if (group != null) active else muted)

            if (!Session.isSignedIn(ctx)) {
                rv.setViewVisibility(R.id.signed_in_container, View.GONE)
                rv.setViewVisibility(R.id.signed_out_container, View.VISIBLE)
                return rv
            }

            rv.setViewVisibility(R.id.signed_out_container, View.GONE)
            rv.setViewVisibility(R.id.signed_in_container, View.VISIBLE)

            val cache = cached(ctx)
            rv.setTextViewText(R.id.stat_online, (cache?.optInt("online") ?: 0).toString())
            rv.setTextViewText(R.id.stat_offline, (cache?.optInt("offline") ?: 0).toString())
            rv.setTextViewText(R.id.stat_alerts, (cache?.optInt("alerts") ?: 0).toString())
            rv.setTextViewText(
                R.id.gateway_text,
                "${cache?.optInt("gwOnline") ?: 0} of ${cache?.optInt("gwTotal") ?: 0} online",
            )
            rv.setOnClickPendingIntent(R.id.gateway_row, openUrl(ctx, 16, "${Cw.APP_BASE}/gateways"))

            // Active-filter indicator (tap to clear).
            val filterParts = listOfNotNull(locName, group)
            if (filterParts.isNotEmpty()) {
                rv.setTextViewText(R.id.filter_bar, filterParts.joinToString("  ·  ") + "      ✕")
                rv.setViewVisibility(R.id.filter_bar, View.VISIBLE)
                rv.setOnClickPendingIntent(R.id.filter_bar, clearFiltersIntent(ctx))
            } else {
                rv.setViewVisibility(R.id.filter_bar, View.GONE)
            }

            // Offline indicator (last refresh had no connection; data may be stale).
            rv.setViewVisibility(
                R.id.offline_note,
                if (cache?.optBoolean("offline") == true) View.VISIBLE else View.GONE,
            )

            // Scrollable device list (collection adapter + per-row tap template).
            val svcIntent = Intent(ctx, DeviceListRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            rv.setRemoteAdapter(R.id.device_list, svcIntent)
            rv.setEmptyView(R.id.device_list, R.id.empty_view)
            rv.setPendingIntentTemplate(R.id.device_list, deviceTemplate(ctx))

            return rv
        }

        private fun openUrl(ctx: Context, req: Int, url: String): PendingIntent {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                ctx, req, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun openApp(ctx: Context, req: Int): PendingIntent {
            val intent = Intent(ctx, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                ctx, req, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun refreshIntent(ctx: Context): PendingIntent {
            val intent = Intent(ctx, CropWatchWidgetProvider::class.java).setAction(Cw.ACTION_REFRESH)
            return PendingIntent.getBroadcast(
                ctx, 1, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun clearFiltersIntent(ctx: Context): PendingIntent {
            val intent = Intent(ctx, CropWatchWidgetProvider::class.java).setAction(Cw.ACTION_CLEAR_FILTERS)
            return PendingIntent.getBroadcast(
                ctx, 5, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun openFilter(ctx: Context, req: Int, mode: String): PendingIntent {
            val intent = Intent(ctx, FilterActivity::class.java)
                .putExtra(FilterActivity.EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                ctx, req, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /** Template for row taps; each row fills in its own device URL as the data. */
        private fun deviceTemplate(ctx: Context): PendingIntent {
            val intent = Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                ctx, 2, intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
