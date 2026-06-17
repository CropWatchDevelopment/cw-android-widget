package io.cropwatch.widget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import io.cropwatch.widget.databinding.ActivityLoginBinding
import java.util.concurrent.Executors

/**
 * Native sign-in for the widget. When signed in it becomes the account/settings
 * screen (the widget's gear + Sign-in button both open this).
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnLogin.setOnClickListener { attemptLogin() }

        b.btnOpenDashboard.setOnClickListener { open(Cw.APP_BASE) }
        b.btnWidgetSettings.setOnClickListener { open("${Cw.APP_BASE}/settings") }
        b.btnAlerts.setOnClickListener { open("${Cw.APP_BASE}/rules") }
        b.btnLocations.setOnClickListener { open("${Cw.APP_BASE}/locations") }
        b.btnAccount.setOnClickListener { open("${Cw.APP_BASE}/account") }
        b.btnSignOut.setOnClickListener { signOut() }

        render()
    }

    private fun render() {
        if (Session.isSignedIn(this)) {
            b.loginContainer.visibility = View.GONE
            b.accountContainer.visibility = View.VISIBLE
            b.accountEmail.text = Session.email(this) ?: "Signed in"
        } else {
            b.accountContainer.visibility = View.GONE
            b.loginContainer.visibility = View.VISIBLE
        }
    }

    private fun attemptLogin() {
        val email = b.inputEmail.text?.toString()?.trim().orEmpty()
        val password = b.inputPassword.text?.toString().orEmpty()
        b.loginError.visibility = View.GONE

        if (email.isBlank() || password.isBlank()) {
            showError(getString(R.string.login_error_generic))
            return
        }
        if (!Net.isOnline(this)) {
            showError(getString(R.string.login_error_no_network))
            return
        }
        setLoading(true)
        io.execute {
            try {
                val token = Api.login(email, password)
                Session.saveLogin(this, token, email)
                // Remember the credentials (encrypted) for silent re-login on expiry.
                Credentials.save(this, email, password)
                // Warm the cache so the widget has data the instant it re-renders.
                WidgetRepository.refreshBlocking(this)
                CropWatchWidgetProvider.renderAll(applicationContext)
                runOnUiThread {
                    setLoading(false)
                    render()
                }
            } catch (e: ApiException) {
                runOnUiThread {
                    setLoading(false)
                    showError(e.message ?: getString(R.string.login_error_generic))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setLoading(false)
                    showError(getString(R.string.login_error_network))
                }
            }
        }
    }

    private fun signOut() {
        Session.clear(this)
        Credentials.clear(this)
        CropWatchWidgetProvider.requestRefresh(applicationContext)
        b.inputEmail.setText("")
        b.inputPassword.setText("")
        render()
    }

    private fun setLoading(loading: Boolean) {
        b.loginProgress.visibility = if (loading) View.VISIBLE else View.GONE
        b.btnLogin.isEnabled = !loading
        b.btnLogin.text = getString(if (loading) R.string.login_signing_in else R.string.login_button)
    }

    private fun showError(msg: String) {
        b.loginError.text = msg
        b.loginError.visibility = View.VISIBLE
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
