package io.cropwatch.widget

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(message: String, val code: Int = 0, val unauthorized: Boolean = false) :
    Exception(message)

/** Thin CropWatch REST client built on HttpURLConnection (no third-party deps). */
object Api {

    private const val TIMEOUT_MS = 15000

    /** POST /v1/auth/login -> access token. */
    fun login(email: String, password: String): String {
        val body = JSONObject().put("email", email).put("password", password)
        val (status, text) = request("POST", "${Cw.API_BASE}/v1/auth/login", null, body.toString())
        if (status !in 200..299) throw errorFrom(status, text)

        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: throw ApiException("Unexpected response from server", status)
        // Source returns { message, result: { accessToken, ... } }; be tolerant of shape.
        val container = json.optJSONObject("result")
            ?: json.optJSONObject("data")
            ?: json
        val token = container.optString("accessToken").ifBlank {
            container.optString("access_token")
        }.ifBlank {
            json.optString("accessToken")
        }
        if (token.isBlank()) throw ApiException("No access token in response", status)
        return token
    }

    /** GET /v1/dashboard/devices -> array of device rows (with latest readings), optionally filtered. */
    fun dashboardDevices(token: String, take: Int = 60, locationId: Int = -1, group: String? = null): JSONArray {
        val q = StringBuilder("${Cw.API_BASE}/v1/dashboard/devices?take=$take")
        if (locationId > 0) q.append("&location=$locationId")
        if (!group.isNullOrBlank()) q.append("&group=").append(URLEncoder.encode(group, "UTF-8"))
        val (status, text) = request("GET", q.toString(), token, null)
        if (status == 401) throw ApiException("Session expired", status, unauthorized = true)
        if (status !in 200..299) throw errorFrom(status, text)
        val obj = JSONObject(text)
        return obj.optJSONArray("rows") ?: JSONArray()
    }

    /** GET /v1/gateway -> array of gateways. */
    fun gateways(token: String): JSONArray {
        val (status, text) = request("GET", "${Cw.API_BASE}/v1/gateway", token, null)
        if (status == 401) throw ApiException("Session expired", status, unauthorized = true)
        if (status !in 200..299) throw errorFrom(status, text)
        return JSONArray(text)
    }

    private fun errorFrom(status: Int, text: String): ApiException {
        val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "Request failed ($status)"
        return ApiException(msg, status, unauthorized = status == 401)
    }

    private fun request(
        method: String,
        urlStr: String,
        token: String?,
        body: String?,
    ): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            status to text
        } finally {
            conn.disconnect()
        }
    }
}
