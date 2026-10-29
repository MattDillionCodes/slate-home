package com.slatehome.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

data class Place(val name: String, val latitude: Double, val longitude: Double)

/** The broad conditions iOS Weather shows, collapsed from WMO weather codes. */
enum class Sky(val dayLabel: String, val nightLabel: String = dayLabel) {
    Clear("Sunny", "Clear"),
    PartlyCloudy("Partly Cloudy"),
    Cloudy("Cloudy"),
    Fog("Foggy"),
    Drizzle("Drizzle"),
    Rain("Rain"),
    Snow("Snow"),
    Storm("Thunderstorms");

    companion object {
        /** https://open-meteo.com/en/docs — "WMO Weather interpretation codes". */
        fun fromWmo(code: Int): Sky = when (code) {
            0 -> Clear
            1, 2 -> PartlyCloudy
            3 -> Cloudy
            45, 48 -> Fog
            in 51..57 -> Drizzle
            in 61..67, in 80..82 -> Rain
            in 71..77, 85, 86 -> Snow
            in 95..99 -> Storm
            else -> Cloudy
        }
    }
}

data class DayForecast(val date: LocalDate, val sky: Sky, val high: Int, val low: Int)

data class Forecast(
    val place: String,
    val temperature: Int,
    val sky: Sky,
    val isDay: Boolean,
    val days: List<DayForecast>,
)

sealed interface WeatherState {
    data object Loading : WeatherState
    data class Ready(val forecast: Forecast) : WeatherState
    data object Unavailable : WeatherState
}

/**
 * Today's conditions plus a 5-day forecast from Open-Meteo (free, no API key). The location
 * is estimated once from the TV's internet connection, unless [PINNED_PLACE] is set.
 */
class WeatherRepository {

    var state by mutableStateOf<WeatherState>(WeatherState.Loading)
        private set

    private var place: Place? = PINNED_PLACE
    private var lastSuccess = 0L

    suspend fun refreshIfStale() {
        val fresh = System.currentTimeMillis() - lastSuccess < STALE_AFTER_MS
        if (fresh && state is WeatherState.Ready) return
        val result = runCatching { withContext(Dispatchers.IO) { fetchForecast() } }
        result.onSuccess {
            lastSuccess = System.currentTimeMillis()
            state = WeatherState.Ready(it)
        }
        // A failed refresh keeps the last good forecast on screen rather than blanking it.
        result.onFailure { if (state !is WeatherState.Ready) state = WeatherState.Unavailable }
    }

    private fun fetchForecast(): Forecast {
        val where = place ?: locate().also { place = it }
        val unit = if (usesFahrenheit()) "fahrenheit" else "celsius"
        val json = JSONObject(
            httpGet(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=${where.latitude}&longitude=${where.longitude}" +
                    "&current=temperature_2m,weather_code,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                    "&temperature_unit=$unit&timezone=auto&forecast_days=$DAYS"
            )
        )
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val highs = daily.getJSONArray("temperature_2m_max")
        val lows = daily.getJSONArray("temperature_2m_min")
        return Forecast(
            place = where.name,
            temperature = current.getDouble("temperature_2m").roundToInt(),
            sky = Sky.fromWmo(current.getInt("weather_code")),
            isDay = current.getInt("is_day") == 1,
            days = (0 until dates.length()).map { i ->
                DayForecast(
                    date = LocalDate.parse(dates.getString(i)),
                    sky = Sky.fromWmo(codes.getInt(i)),
                    high = highs.getDouble(i).roundToInt(),
                    low = lows.getDouble(i).roundToInt(),
                )
            },
        )
    }

    /** City-level guess from the public IP — TVs have no GPS and a permission prompt is clumsy. */
    private fun locate(): Place {
        val json = JSONObject(httpGet("https://get.geojs.io/v1/ip/geo.json"))
        return Place(
            name = json.optString("city").ifBlank { "Your Area" },
            latitude = json.getString("latitude").toDouble(),
            longitude = json.getString("longitude").toDouble(),
        )
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode} from $url" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun usesFahrenheit() = Locale.getDefault().country in setOf("US", "LR", "MM")

    companion object {
        /** Set to e.g. Place("Chicago", 41.88, -87.63) to skip location lookup. */
        val PINNED_PLACE: Place? = null

        const val DAYS = 5
        const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L
        private const val STALE_AFTER_MS = 20 * 60 * 1000L
        private const val TIMEOUT_MS = 8_000
    }
}
