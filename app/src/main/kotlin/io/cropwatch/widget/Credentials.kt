package io.cropwatch.widget

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Email + password kept in an Android-Keystore-backed encrypted store, used to
 * silently re-authenticate when the short-lived access token expires.
 */
object Credentials {
    private const val FILE = "cw_secure"
    private const val K_EMAIL = "email"
    private const val K_PASSWORD = "password"

    private fun prefs(ctx: Context): SharedPreferences? = try {
        val key = MasterKey.Builder(ctx.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx.applicationContext,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Exception) {
        null
    }

    fun save(ctx: Context, email: String, password: String) {
        prefs(ctx)?.edit()?.putString(K_EMAIL, email)?.putString(K_PASSWORD, password)?.apply()
    }

    /** Stored (email, password), or null if none are saved. */
    fun get(ctx: Context): Pair<String, String>? {
        val p = prefs(ctx) ?: return null
        val email = p.getString(K_EMAIL, null) ?: return null
        val password = p.getString(K_PASSWORD, null) ?: return null
        return email to password
    }

    fun has(ctx: Context): Boolean = get(ctx) != null

    fun clear(ctx: Context) {
        prefs(ctx)?.edit()?.clear()?.apply()
    }
}

/** Connectivity check so we never mistake "offline" for "token rejected". */
object Net {
    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
