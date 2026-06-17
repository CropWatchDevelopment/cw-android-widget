package io.cropwatch.widget

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Maps CropWatch data-table column names (device_type.primary_data_v2 /
 * secondary_data_v2) to a display unit + an icon key, formats reading values,
 * and renders "last seen" relative times.
 */
object Reading {

    /** column name -> unit suffix shown after the value. */
    private val UNITS = mapOf(
        "temperature_c" to "°C", "temperature" to "°C", "temp" to "°C",
        "humidity" to "%RH", "rh" to "%RH",
        "moisture" to "%", "soil_moisture" to "%",
        "ec" to "mS/cm",
        "ph" to "pH",
        "co2" to "ppm", "co" to "ppm",
        "pressure" to "hPa",
        "lux" to "lx",
        "uv_index" to "UV",
        "rainfall" to "mm",
        "wind_speed" to "m/s", "wind_direction" to "°",
        "deapth_cm" to "cm", "depth_cm" to "cm", "depth" to "cm",
        "spo2" to "%",
        "pm25" to "µg/m³", "pm10" to "µg/m³",
        "battery" to "%", "voltage" to "V",
    )

    /** column name -> drawable resource for the secondary-reading icon. */
    private val ICONS = mapOf(
        "temperature_c" to R.drawable.ic_thermostat, "temperature" to R.drawable.ic_thermostat,
        "humidity" to R.drawable.ic_humidity_percentage, "rh" to R.drawable.ic_humidity_percentage,
        "moisture" to R.drawable.ic_water_drop, "soil_moisture" to R.drawable.ic_water_drop,
        "ec" to R.drawable.ic_bolt,
        "ph" to R.drawable.ic_science,
        "co2" to R.drawable.ic_co2, "co" to R.drawable.ic_air,
        "pressure" to R.drawable.ic_speed,
        "lux" to R.drawable.ic_light_mode,
        "uv_index" to R.drawable.ic_wb_sunny,
        "rainfall" to R.drawable.ic_rainy,
        "wind_speed" to R.drawable.ic_air, "wind_direction" to R.drawable.ic_air,
        "deapth_cm" to R.drawable.ic_water_drop, "depth_cm" to R.drawable.ic_water_drop,
        "spo2" to R.drawable.ic_water_drop,
        "pm25" to R.drawable.ic_air, "pm10" to R.drawable.ic_air,
    )

    fun unitFor(column: String?): String = UNITS[column?.lowercase()] ?: ""

    /** Icon key string stored in the cache (resolved to a drawable in the factory). */
    fun iconKeyFor(column: String?): String = column?.lowercase()?.takeIf { ICONS.containsKey(it) } ?: "sensors"

    fun iconResForKey(key: String): Int = when (key) {
        "sensors" -> R.drawable.ic_sensors
        "signal_disconnected" -> R.drawable.ic_signal_disconnected
        "priority_high" -> R.drawable.ic_priority_high
        else -> ICONS[key] ?: R.drawable.ic_sensors
    }

    /** Format a numeric reading, using a typographic minus sign like the design. */
    fun formatValue(v: Double): String {
        val rounded = Math.round(v * 10.0) / 10.0
        val s = if (rounded == Math.rint(rounded)) rounded.toLong().toString()
        else rounded.toString()
        return s.replace('-', '−')
    }

    fun parseInstant(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return try {
            Instant.parse(iso)
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(iso).toInstant()
            } catch (_: Exception) {
                try {
                    // assume UTC if no zone/offset present
                    OffsetDateTime.parse(iso + "Z").toInstant()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun relativeTime(iso: String?, now: Instant = Instant.now()): String {
        val then = parseInstant(iso) ?: return "—"
        val d = Duration.between(then, now)
        val mins = d.toMinutes()
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 60 * 24 -> "${d.toHours()}h ago"
            else -> "${d.toDays()}d ago"
        }
    }

    /** Online if the last reading is within the device's upload interval (minutes). */
    fun isOnline(lastIso: String?, uploadIntervalMin: Int, now: Instant = Instant.now()): Boolean {
        val then = parseInstant(lastIso) ?: return false
        val mins = Duration.between(then, now).toMinutes()
        val window = if (uploadIntervalMin > 0) uploadIntervalMin.toLong() else 60L
        return mins <= window
    }
}
