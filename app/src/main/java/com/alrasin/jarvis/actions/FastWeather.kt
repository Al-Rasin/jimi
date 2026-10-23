package com.alrasin.jarvis.actions

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class FastWeather(private val context: Context? = null) {
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    private val places = java.util.concurrent.ConcurrentHashMap<String, Map<*, *>>()

    /** Weather at a known position (current location, D-046). */
    suspend fun at(lat: Double, lon: Double, name: String, tomorrow: Boolean): ToolResult =
        get(name, tomorrow, mapOf("latitude" to lat, "longitude" to lon, "name" to name))

    suspend fun get(place: String, tomorrow: Boolean, known: Map<*, *>? = null): ToolResult = withContext(Dispatchers.IO) {
        val cm = context?.getSystemService(ConnectivityManager::class.java)
        if (cm != null && cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            return@withContext ToolResult.fail("No internet. I can't check the weather right now.")
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(4000)
        fun fetch(url: String): Map<*, *> {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw java.net.SocketTimeoutException()
            val call = http.newCall(Request.Builder().url(url).build())
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            return call.execute().use { response ->
                check(response.isSuccessful) { "Weather HTTP ${response.code}" }
                Json.parse(checkNotNull(response.body).string()) as Map<*, *>
            }
        }
        try {
            val key = place.trim().lowercase(java.util.Locale.ROOT)
            val geo = known ?: places[key] ?: run {
                val results = fetch("https://geocoding-api.open-meteo.com/v1/search?count=1&name=${Web.enc(place)}")
                val found = (results["results"] as? List<*>)?.firstOrNull() as? Map<*, *>
                    ?: return@withContext ToolResult.fail("I couldn't find $place. Set your weather city in Settings.")
                places[key] = found
                found
            }
            val data = fetch("https://api.open-meteo.com/v1/forecast?latitude=${geo["latitude"]}&longitude=${geo["longitude"]}" +
                "&current=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&temperature_unit=celsius&timezone=auto&forecast_days=2")
            val report = WeatherReport.render(data, geo["name"] as? String ?: place, tomorrow)
            JarvisLog.i("weather: current and forecast received")
            ToolResult.ok(report)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: java.io.InterruptedIOException) {
            ToolResult.fail("The weather service is taking too long. Please try again.")
        } catch (_: java.io.IOException) {
            ToolResult.fail("No internet or weather service connection. Please try again.")
        } catch (_: RuntimeException) {
            ToolResult.fail("The weather service returned incomplete information. Please try again.")
        }
    }
}

internal object WeatherReport {
    fun render(data: Map<*, *>, place: String, tomorrow: Boolean = false): String {
        val daily = data["daily"] as Map<*, *>
        val i = if (tomorrow) 1 else 0
        fun dailyNumber(key: String): Int = number((daily[key] as List<*>)[i])
        val high = dailyNumber("temperature_2m_max")
        val low = dailyNumber("temperature_2m_min")
        val rain = dailyNumber("precipitation_probability_max")
        require(rain in 0..100 && low <= high)
        val start = if (tomorrow) "Tomorrow in $place, ${condition(dailyNumber("weather_code"))}."
        else {
            val current = data["current"] as Map<*, *>
            "It's ${number(current["temperature_2m"])} degrees and ${condition(number(current["weather_code"]))} in $place."
        }
        return "$start High $high, low $low, rain chance $rain percent."
    }

    private fun number(value: Any?): Int = (value as Number).toDouble().also { require(it.isFinite()) }.roundToInt()
    private fun condition(code: Int) = when (code) {
        0 -> "clear"; 1, 2 -> "partly cloudy"; 3 -> "cloudy"; 45, 48 -> "foggy"
        51, 53, 55, 56, 57 -> "drizzling"; 61, 63, 65, 66, 67 -> "rainy"
        71, 73, 75, 77, 85, 86 -> "snowy"; 80, 81, 82 -> "showery"
        95, 96, 99 -> "stormy"; else -> "mixed weather"
    }
}
