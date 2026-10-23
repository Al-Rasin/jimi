package com.alrasin.jarvis.actions

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The phone's current position (D-046). A recent last-known fix is used at once; otherwise a fresh fix is asked
 * from network then GPS. If location is switched off, root turns it on (personal rooted phone).
 * Works offline with GPS; the place name needs the Geocoder (usually online) and is optional.
 */
class CurrentLocation(private val context: Context) {
    data class Fix(val lat: Double, val lon: Double, val accuracyM: Float, val name: String?)

    private val lm get() = context.getSystemService(LocationManager::class.java)

    suspend fun get(timeoutMs: Long = 6_000): Fix? {
        if (!allowed()) { JarvisLog.i("location: permission missing"); return null }
        if (!lm.isLocationEnabled && RootShell.available) {
            RootShell.run("settings put secure location_mode 3")
            JarvisLog.i("location: switched on with root")
        }
        if (!lm.isLocationEnabled) return null
        val loc = recent() ?: fresh(timeoutMs) ?: return null
        return Fix(loc.latitude, loc.longitude, loc.accuracy, name(loc))
    }

    private fun allowed() = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission")
    private fun recent(): Location? = providers().mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .filter { ageMs(it) < MAX_AGE_MS }
        .minByOrNull { it.accuracy }

    @SuppressLint("MissingPermission")
    private suspend fun fresh(timeoutMs: Long): Location? = withTimeoutOrNull(timeoutMs) {
        for (p in providers()) {
            val loc = suspendCancellableCoroutine<Location?> { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                runCatching { lm.getCurrentLocation(p, cancel, context.mainExecutor) { cont.resume(it) } }
                    .onFailure { cont.resume(null) }
            }
            if (loc != null) return@withTimeoutOrNull loc
        }
        null
    } ?: providers().mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }

    private fun providers() = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

    private fun ageMs(l: Location) = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000

    @Suppress("DEPRECATION")
    private suspend fun name(l: Location): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching { Geocoder(context, Locale.getDefault()).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull() }
            .getOrNull()?.let { placeName(it.subLocality, it.locality, it.subAdminArea, it.adminArea) }
    }

    companion object {
        private const val MAX_AGE_MS = 10 * 60_000L

        /** Short spoken place name, e.g. "Dhanmondi, Dhaka". */
        fun placeName(vararg parts: String?): String? = parts.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }
            .distinct().take(2).joinToString(", ").ifEmpty { null }
    }
}

/** "Where am I?" — spoken place name plus a map link and coordinates in the text. */
fun myLocationTool(location: CurrentLocation) = SimpleTool("my_location", "Where the user is right now (current location)") { _, _ ->
    val fix = location.get() ?: return@SimpleTool ToolResult.fail(
        "I can't get your location right now. Check that location is on.",
    )
    ToolResult.ok(locationSay(fix), locationData(fix))
}

internal fun locationSay(fix: CurrentLocation.Fix) =
    if (fix.name != null) "You're in ${fix.name}." else "I have your position, but not the place name. It's on the map link."

internal fun locationData(fix: CurrentLocation.Fix) = String.format(Locale.ROOT, "%.5f, %.5f (±%d m)\nhttps://maps.google.com/?q=%.5f,%.5f",
    fix.lat, fix.lon, fix.accuracyM.toInt(), fix.lat, fix.lon)
