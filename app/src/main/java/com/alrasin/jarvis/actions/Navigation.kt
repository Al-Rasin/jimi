package com.alrasin.jarvis.actions

import android.content.Context
import android.location.Geocoder
import android.location.Location
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * "navigate to X" / "show X on the map" / "how far is X" (§H.2, §I.3): google.navigation: / geo: / route links,
 * checked before opening. Distance = straight line from my position (GPS works offline; finding the place needs the
 * Geocoder, usually online), then Maps' route preview shows the road distance and time.
 */
fun navigationTool(context: Context, location: CurrentLocation): Tool =
    SimpleTool(
        "open_map",
        "Show a place on the map, start turn-by-turn directions to it, or say how far it is",
        listOf(
            arg("place", "place, address or business to find"),
            arg("mode", "navigate = directions, show = just show it on the map, distance = how far it is", required = false, choices = listOf("navigate", "show", "distance")),
            arg("travel", "how to travel when navigating", required = false, choices = listOf("drive", "walk", "bike", "motorbike")),
        ),
    ) { c, _ ->
        val place = c.arg("place") ?: return@SimpleTool ToolResult.fail("Which place?")
        val travel = AppLinks.travel(c.arg("travel"))
        when (c.arg("mode")) {
            "navigate" -> {
                val used = context.openFirst(AppLinks.navigate(place, travel)) ?: return@SimpleTool ToolResult.fail("I couldn't open a map app.")
                if (used.uri?.startsWith("google.navigation:") == true) ToolResult.ok("Starting directions to $place.")
                else ToolResult.ok("I couldn't start directions, so here is $place on the map.")
            }
            "distance" -> {
                val say = straightLine(context, location, place)
                val opened = context.openFirst(AppLinks.route(place, travel)) != null
                when {
                    say != null && opened -> ToolResult.ok("$say Maps shows the road distance.")
                    say != null -> ToolResult.ok(say)
                    opened -> ToolResult.ok("Here is the route to $place. Maps shows how far it is.")
                    else -> ToolResult.fail("I couldn't find $place or open a map app.")
                }
            }
            else -> {
                context.openFirst(AppLinks.mapSearch(place)) ?: return@SimpleTool ToolResult.fail("I couldn't open a map app.")
                ToolResult.ok("Here is $place on the map.")
            }
        }
    }

/** Spoken straight-line distance, or null when my position or the place can't be found (no internet for Geocoder). */
private suspend fun straightLine(context: Context, location: CurrentLocation, place: String): String? {
    val me = location.get() ?: return null
    val target = withTimeoutOrNull(6_000) { geocode(context, place, me) } ?: return null
    val out = FloatArray(1)
    Location.distanceBetween(me.lat, me.lon, target.first, target.second, out)
    JarvisLog.i("open_map distance: \"$place\" ${out[0].toInt()} m")
    return AppLinks.distanceSay(place, out[0])
}

/** Place → lat/lon, looked up near my position first (so "the mosque" is a near one), then anywhere. */
@Suppress("DEPRECATION")
private suspend fun geocode(context: Context, place: String, near: CurrentLocation.Fix): Pair<Double, Double>? = withContext(Dispatchers.IO) {
    if (!Geocoder.isPresent()) return@withContext null
    val g = Geocoder(context, Locale.getDefault())
    val box = 0.5
    val hit = runCatching { g.getFromLocationName(place, 1, near.lat - box, near.lon - box, near.lat + box, near.lon + box)?.firstOrNull() }.getOrNull()
        ?: runCatching { g.getFromLocationName(place, 1)?.firstOrNull() }.getOrNull()
    hit?.let { it.latitude to it.longitude }
}
