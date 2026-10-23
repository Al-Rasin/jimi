package com.alrasin.jarvis.actions

import com.alrasin.jarvis.core.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherReportTest {
    private val data = Json.parse("""{"current":{"temperature_2m":30.6,"weather_code":2},
        "daily":{"temperature_2m_max":[33,32],"temperature_2m_min":[26,25],
        "precipitation_probability_max":[40,80],"weather_code":[2,61]}}""") as Map<*, *>

    @Test fun currentAndDailyForecastUseOneTemplate() {
        assertEquals("It's 31 degrees and partly cloudy in Dhaka. High 33, low 26, rain chance 40 percent.",
            WeatherReport.render(data, "Dhaka"))
    }

    @Test fun tomorrowUsesSecondDailyEntry() {
        assertEquals("Tomorrow in Dhaka, rainy. High 32, low 25, rain chance 80 percent.",
            WeatherReport.render(data, "Dhaka", true))
    }

    @Test(expected = RuntimeException::class) fun missingMeasurementsNeverBecomeZeroDegrees() {
        WeatherReport.render(mapOf("current" to emptyMap<String, Any>(), "daily" to emptyMap<String, Any>()), "Dhaka")
    }
}
