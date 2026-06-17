package io.cropwatch.widget

import android.content.Context

/** App-wide constants. */
object Cw {
    const val API_BASE = "https://api.cropwatch.io"
    const val APP_BASE = "https://app.cropwatch.io"
    const val ACTION_REFRESH = "io.cropwatch.widget.ACTION_REFRESH"
    const val ACTION_CLEAR_FILTERS = "io.cropwatch.widget.ACTION_CLEAR_FILTERS"

    /** A device's detail page lives under its location: /locations/{id}/devices/{dev_eui}. */
    fun deviceUrl(locationId: Int, devEui: String) =
        if (locationId > 0) "$APP_BASE/locations/$locationId/devices/$devEui" else APP_BASE
}

/**
 * Tiny persistence layer backed by SharedPreferences: the auth token, the
 * signed-in email, and the last computed widget payload (so the carousel
 * factory can render without hitting the network).
 */
object Session {
    private const val PREFS = "cw_widget"
    private const val K_TOKEN = "token"
    private const val K_EMAIL = "email"
    private const val K_CACHE = "cache_json"
    private const val K_LOC_ID = "filter_loc_id"
    private const val K_LOC_NAME = "filter_loc_name"
    private const val K_GROUP = "filter_group"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(ctx: Context): String? = prefs(ctx).getString(K_TOKEN, null)
    fun isSignedIn(ctx: Context): Boolean = !token(ctx).isNullOrBlank()
    fun email(ctx: Context): String? = prefs(ctx).getString(K_EMAIL, null)

    fun saveLogin(ctx: Context, token: String, email: String) {
        prefs(ctx).edit().putString(K_TOKEN, token).putString(K_EMAIL, email).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit()
            .remove(K_TOKEN).remove(K_EMAIL).remove(K_CACHE)
            .remove(K_LOC_ID).remove(K_LOC_NAME).remove(K_GROUP)
            .apply()
    }

    fun cache(ctx: Context): String? = prefs(ctx).getString(K_CACHE, null)
    fun saveCache(ctx: Context, json: String) {
        prefs(ctx).edit().putString(K_CACHE, json).apply()
    }

    // ── Filters ──────────────────────────────────────────────
    /** Selected location id, or -1 for "all locations". */
    fun locationFilter(ctx: Context): Int = prefs(ctx).getInt(K_LOC_ID, -1)
    fun locationFilterName(ctx: Context): String? = prefs(ctx).getString(K_LOC_NAME, null)
    fun setLocationFilter(ctx: Context, id: Int, name: String?) {
        prefs(ctx).edit().apply {
            if (id > 0) putInt(K_LOC_ID, id).putString(K_LOC_NAME, name)
            else remove(K_LOC_ID).remove(K_LOC_NAME)
        }.apply()
    }

    /** Selected device group, or null/blank for "all groups". */
    fun groupFilter(ctx: Context): String? = prefs(ctx).getString(K_GROUP, null)?.takeIf { it.isNotBlank() }
    fun setGroupFilter(ctx: Context, group: String?) {
        prefs(ctx).edit().apply {
            if (!group.isNullOrBlank()) putString(K_GROUP, group) else remove(K_GROUP)
        }.apply()
    }
}
