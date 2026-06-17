package io.cropwatch.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Pulls the live fleet picture from the API and reduces it to a compact JSON
 * payload the widget + carousel render from. Runs on a background thread.
 */
object WidgetRepository {

    /** Fetch + recompute the cached widget payload. Safe to call off the main thread only. */
    fun refreshBlocking(ctx: Context) {
        // No connection? Keep the last data, flag it offline, never touch the session.
        if (!Net.isOnline(ctx)) {
            markOffline(ctx)
            return
        }

        var token = Session.token(ctx)
        if (token.isNullOrBlank()) {
            // No active token — establish one silently if we have saved credentials.
            token = silentLogin(ctx) ?: return
        }

        try {
            fetchAndCache(ctx, token)
        } catch (e: ApiException) {
            if (e.unauthorized) {
                // Session expired -> silent re-login, then retry the fetch once.
                val fresh = silentLogin(ctx) ?: return
                try {
                    fetchAndCache(ctx, fresh)
                } catch (e2: ApiException) {
                    if (e2.unauthorized) promptLogin(ctx) else keepOnError(ctx)
                } catch (_: Exception) {
                    markOffline(ctx)
                }
            } else {
                keepOnError(ctx)
            }
        } catch (_: Exception) {
            // Connection dropped mid-request.
            markOffline(ctx)
        }
    }

    private fun fetchAndCache(ctx: Context, token: String) {
        val rows = Api.dashboardDevices(
            token,
            take = 100,
            locationId = Session.locationFilter(ctx),
            group = Session.groupFilter(ctx),
        )
        val gateways = try {
            Api.gateways(token)
        } catch (e: ApiException) {
            if (e.unauthorized) throw e else JSONArray()
        }
        Session.saveCache(ctx, buildPayload(rows, gateways).toString())
    }

    /**
     * Re-authenticate with the stored credentials. Returns the new token, or null —
     * in which case the widget state has already been set appropriately:
     *  - no creds / invalid creds (e.g. password changed) -> prompt to sign in
     *  - network / server error -> keep the last data (offline / error)
     */
    private fun silentLogin(ctx: Context): String? {
        val (email, password) = Credentials.get(ctx) ?: run {
            promptLogin(ctx); return null
        }
        return try {
            val token = Api.login(email, password)
            Session.saveLogin(ctx, token, email)
            token
        } catch (e: ApiException) {
            if (e.unauthorized || e.code == 400) promptLogin(ctx) else keepOnError(ctx)
            null
        } catch (_: Exception) {
            markOffline(ctx)
            null
        }
    }

    /** Credentials are gone or no longer valid -> clear the session so "Sign in" shows. */
    private fun promptLogin(ctx: Context) {
        Session.clear(ctx)
        Credentials.clear(ctx)
        Session.saveCache(ctx, JSONObject().put("signedIn", false).toString())
    }

    /** Keep the last good cache on a transient error; only placeholder if there's nothing. */
    private fun keepOnError(ctx: Context) {
        if (Session.cache(ctx) == null) {
            Session.saveCache(
                ctx,
                JSONObject().put("signedIn", true).put("error", "Couldn't update")
                    .put("devices", JSONArray()).toString(),
            )
        }
    }

    /** Flag the current cache as offline (stale) without changing the auth state. */
    private fun markOffline(ctx: Context) {
        val existing = Session.cache(ctx)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (existing != null) {
            Session.saveCache(ctx, existing.put("offline", true).toString())
        } else if (Session.isSignedIn(ctx)) {
            Session.saveCache(
                ctx,
                JSONObject().put("signedIn", true).put("offline", true)
                    .put("devices", JSONArray()).toString(),
            )
        }
    }

    private fun buildPayload(rows: JSONArray, gateways: JSONArray): JSONObject {
        val now = Instant.now()
        var online = 0
        var offline = 0
        var alerts = 0
        val devices = JSONArray()

        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val devEui = row.optString("dev_eui")
            val name = row.optString("name").ifBlank { devEui }

            val deviceType = row.optJSONObject("device_type")
            val location = row.optJSONObject("location")
            val latest = row.optJSONObject("latest")

            val primaryCol = deviceType?.optString("primary_data_v2")
            val secCol = deviceType?.optString("secondary_data_v2")

            val interval = run {
                val ui = if (row.isNull("upload_interval")) 0 else row.optInt("upload_interval", 0)
                if (ui > 0) ui else deviceType?.optInt("default_upload_interval", 0) ?: 0
            }
            val lastIso = if (row.isNull("last_data_updated_at")) null else row.optString("last_data_updated_at")
            val isOnline = Reading.isOnline(lastIso, interval, now)

            val errStatus = if (row.isNull("error_status")) "" else row.optString("error_status")
            val isAlert = errStatus.isNotBlank() && !errStatus.equals("null", ignoreCase = true)

            // A device that has reported before but is overdue is "stale" (shown red);
            // one that has never sent an uplink is "never" (shown gray).
            val everSent = Reading.parseInstant(lastIso) != null
            val status = when {
                isAlert -> "alert"
                isOnline -> "online"
                everSent -> "stale"
                else -> "never"
            }
            if (isOnline) online++ else offline++
            if (isAlert) alerts++

            val unit = Reading.unitFor(primaryCol)
            val locName = location?.optString("name")?.takeIf { it.isNotBlank() }
                ?: (if (row.isNull("group")) "" else row.optString("group"))
            val locId = if (location != null && !location.isNull("location_id"))
                location.optInt("location_id", -1) else -1

            val isDown = status == "stale" || status == "never"
            val primaryText = if (isDown) "—" else valueToText(latest?.opt("primary")) ?: "—"

            var secIconKey: String
            var secText: String
            when (status) {
                "never" -> {
                    secIconKey = "signal_disconnected"; secText = "No data yet"
                }
                "stale" -> {
                    secIconKey = "signal_disconnected"; secText = "No signal"
                }
                "alert" -> {
                    secIconKey = "priority_high"; secText = errStatus
                }
                else -> {
                    secIconKey = Reading.iconKeyFor(secCol)
                    val sv = valueToText(latest?.opt("secondary"))
                    secText = if (sv != null) {
                        val u = Reading.unitFor(secCol)
                        if (u.isBlank()) sv else "$sv $u"
                    } else deviceType?.optString("name")?.takeIf { it.isNotBlank() } ?: "—"
                }
            }

            devices.put(
                JSONObject()
                    .put("name", name)
                    .put("location", locName)
                    .put("primary", primaryText)
                    .put("unit", unit)
                    .put("secIcon", secIconKey)
                    .put("secText", secText)
                    .put("seen", Reading.relativeTime(lastIso, now))
                    .put("status", status)
                    .put("href", Cw.deviceUrl(locId, devEui)),
            )
        }

        var gwOnline = 0
        for (i in 0 until gateways.length()) {
            if (gateways.optJSONObject(i)?.optBoolean("is_online") == true) gwOnline++
        }

        return JSONObject()
            .put("signedIn", true)
            .put("online", online)
            .put("offline", offline)
            .put("alerts", alerts)
            .put("gwOnline", gwOnline)
            .put("gwTotal", gateways.length())
            .put("updatedAt", System.currentTimeMillis())
            .put("devices", devices)
    }

    /** Render a primary/secondary reading value (number/string/boolean) to text. */
    private fun valueToText(v: Any?): String? = when (v) {
        null -> null
        is Int -> Reading.formatValue(v.toDouble())
        is Long -> Reading.formatValue(v.toDouble())
        is Double -> Reading.formatValue(v)
        is Number -> Reading.formatValue(v.toDouble())
        is Boolean -> if (v) "Yes" else "No"
        else -> v.toString().takeIf { it.isNotBlank() && it != "null" }
    }
}
