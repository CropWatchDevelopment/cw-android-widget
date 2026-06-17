package io.cropwatch.widget

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import io.cropwatch.widget.databinding.ActivityFilterBinding
import io.cropwatch.widget.databinding.ItemFilterOptionBinding
import java.util.concurrent.Executors

/**
 * Lightweight picker the widget opens (location or group). It pulls the full,
 * unfiltered device list once, derives the distinct options, and stores the
 * chosen filter — then asks the widget to re-fetch.
 */
class FilterActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_LOCATION = "location"
        const val MODE_GROUP = "group"
    }

    private lateinit var b: ActivityFilterBinding
    private val io = Executors.newSingleThreadExecutor()
    private val mode by lazy { intent.getStringExtra(EXTRA_MODE) ?: MODE_LOCATION }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFilterBinding.inflate(layoutInflater)
        setContentView(b.root)
        setFinishOnTouchOutside(true)

        b.filterTitle.setText(if (mode == MODE_GROUP) R.string.filter_group else R.string.filter_location)
        load()
    }

    private fun load() {
        val token = Session.token(this)
        if (token.isNullOrBlank()) { finish(); return }

        b.filterProgress.visibility = View.VISIBLE
        io.execute {
            try {
                val rows = Api.dashboardDevices(token, take = 500)
                if (mode == MODE_GROUP) {
                    val groups = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
                    for (i in 0 until rows.length()) {
                        val g = rows.optJSONObject(i)?.optString("group").orEmpty()
                        if (g.isNotBlank() && !g.equals("null", true)) groups.add(g)
                    }
                    runOnUiThread { showGroups(groups.toList()) }
                } else {
                    val locs = LinkedHashMap<Int, String>()
                    for (i in 0 until rows.length()) {
                        val loc = rows.optJSONObject(i)?.optJSONObject("location") ?: continue
                        val id = loc.optInt("location_id", -1)
                        if (id > 0) locs[id] = loc.optString("name").ifBlank { "Location $id" }
                    }
                    val sorted = locs.entries.sortedBy { it.value.lowercase() }
                    runOnUiThread { showLocations(sorted.map { it.key to it.value }) }
                }
            } catch (e: Exception) {
                runOnUiThread { showMessage(getString(R.string.filter_error)) }
            }
        }
    }

    private fun showGroups(groups: List<String>) {
        b.filterProgress.visibility = View.GONE
        if (groups.isEmpty()) { showMessage(getString(R.string.filter_none)); return }
        val current = Session.groupFilter(this)
        addRow(getString(R.string.all_groups), current == null) {
            Session.setGroupFilter(this, null); apply()
        }
        for (g in groups) addRow(g, g.equals(current, true)) {
            Session.setGroupFilter(this, g); apply()
        }
    }

    private fun showLocations(locs: List<Pair<Int, String>>) {
        b.filterProgress.visibility = View.GONE
        if (locs.isEmpty()) { showMessage(getString(R.string.filter_none)); return }
        val current = Session.locationFilter(this)
        addRow(getString(R.string.all_locations), current <= 0) {
            Session.setLocationFilter(this, -1, null); apply()
        }
        for ((id, name) in locs) addRow(name, id == current) {
            Session.setLocationFilter(this, id, name); apply()
        }
    }

    private fun addRow(label: String, selected: Boolean, onPick: () -> Unit) {
        val row = ItemFilterOptionBinding.inflate(layoutInflater, b.filterOptions, false)
        row.optName.text = label
        row.optCheck.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        row.root.setOnClickListener { onPick() }
        b.filterOptions.addView(row.root)
    }

    private fun showMessage(msg: String) {
        b.filterProgress.visibility = View.GONE
        b.filterMessage.text = msg
        b.filterMessage.visibility = View.VISIBLE
    }

    /** Persist the choice, kick a refresh, and close. */
    private fun apply() {
        CropWatchWidgetProvider.requestRefresh(applicationContext)
        finish()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
