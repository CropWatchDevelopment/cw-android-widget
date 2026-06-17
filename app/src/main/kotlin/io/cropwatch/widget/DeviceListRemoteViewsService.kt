package io.cropwatch.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import org.json.JSONObject

class DeviceListRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        DeviceListFactory(applicationContext)
}

/** Builds the vertically-scrolling device rows from the cached payload. */
class DeviceListFactory(private val ctx: Context) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<JSONObject> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val arr = Session.cache(ctx)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONArray("devices")
        items = if (arr == null) emptyList()
        else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    override fun onDestroy() { items = emptyList() }

    override fun getCount(): Int = items.size
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_tile)
        val d = items.getOrNull(position) ?: return rv
        val status = d.optString("status", "online")

        // The status stripe is baked into the card background so it follows the rounded corners.
        // alert + stale = red, never = gray, online = green.
        rv.setInt(
            R.id.tile_root, "setBackgroundResource",
            when (status) {
                "alert", "stale" -> R.drawable.tile_bg_alert
                "never" -> R.drawable.tile_bg_offline
                else -> R.drawable.tile_bg_online
            },
        )

        val primaryColor = color(
            when (status) {
                "alert", "stale" -> R.color.cw_tone_danger_text
                "never" -> R.color.cw_text_disabled
                else -> R.color.cw_text_primary
            }
        )
        val seenColor = color(
            when (status) {
                "stale" -> R.color.cw_tone_danger_text
                "never" -> R.color.cw_text_disabled
                else -> R.color.cw_text_muted
            }
        )

        rv.setTextViewText(R.id.tile_location, d.optString("location").ifBlank { "—" })
        rv.setViewVisibility(R.id.tile_alert_badge, if (status == "alert") View.VISIBLE else View.GONE)
        rv.setTextViewText(R.id.tile_name, d.optString("name"))

        rv.setTextViewText(R.id.tile_primary, d.optString("primary", "—"))
        rv.setTextColor(R.id.tile_primary, primaryColor)
        val unit = d.optString("unit")
        rv.setTextViewText(R.id.tile_unit, unit)
        rv.setViewVisibility(R.id.tile_unit, if (unit.isBlank()) View.GONE else View.VISIBLE)

        rv.setImageViewResource(R.id.tile_sec_icon, Reading.iconResForKey(d.optString("secIcon", "sensors")))
        rv.setTextViewText(R.id.tile_secondary, d.optString("secText"))

        rv.setTextViewText(R.id.tile_seen, d.optString("seen", "—"))
        rv.setTextColor(R.id.tile_seen, seenColor)

        // Tapping the row opens that device on app.cropwatch.io (URL filled into the template).
        rv.setOnClickFillInIntent(R.id.tile_root, Intent().setData(Uri.parse(d.optString("href"))))
        return rv
    }

    private fun color(res: Int): Int = ContextCompat.getColor(ctx, res)
}
