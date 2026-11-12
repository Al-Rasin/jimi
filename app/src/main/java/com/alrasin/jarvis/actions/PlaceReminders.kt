package com.alrasin.jarvis.actions

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import com.alrasin.jarvis.brain.Persona
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.data.Reminder
import com.alrasin.jarvis.service.JarvisCoreService
import com.alrasin.jarvis.service.Notifications
import java.util.Locale

/**
 * Location reminders (ROADMAP #29): "this is home" saves a place, "remind me to call mom when I reach home"
 * fires through a LocationManager proximity alert (no Play services). Pure text part is unit tested.
 */
object PlaceText {
    const val RADIUS_M = 150f

    private val ALIAS = mapOf("house" to "home", "my house" to "home", "work" to "office", "workplace" to "office", "masjid" to "mosque")

    /** "my home" / "the office" / "Work" → "home" / "office" / "office". */
    fun key(name: String): String {
        val n = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            .removePrefix("my ").removePrefix("the ").trim()
        return ALIAS[n] ?: n
    }

    data class Rem(val id: Long, val place: String, val leave: Boolean, val armed: Boolean, val text: String)

    fun encode(r: Rem) = "${if (r.leave) "leave" else "arrive"}|${if (r.armed) 1 else 0}|${r.place}|${r.text}"

    fun decode(id: Long, s: String): Rem? {
        val p = s.split("|", limit = 4)
        if (p.size < 4 || p[2].isBlank() || p[3].isBlank()) return null
        return Rem(id, p[2], p[0] == "leave", p[1] == "1", p[3])
    }

    fun encodePlace(lat: Double, lon: Double) = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)

    fun decodePlace(s: String): Pair<Double, Double>? {
        val p = s.split(",")
        val lat = p.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lon = p.getOrNull(1)?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    /** What the user hears when the alert fires. */
    fun fireSay(r: Rem) = "${Persona.ADDRESS}, reminder: ${r.text}"

    fun setSay(r: Rem) = "Okay, I'll remind you to ${r.text} when you ${if (r.leave) "leave" else "reach"} ${spoken(r.place)}."

    fun spoken(place: String) = if (place == "home") "home" else "the $place"
}

/** Saved places and pending place reminders, kept in a small private prefs file (survives app updates). */
class PlaceReminders(private val context: Context) {
    private val prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE)
    private val lm get() = context.getSystemService(LocationManager::class.java)

    fun savePlace(name: String, lat: Double, lon: Double) {
        prefs.edit().putString(P + PlaceText.key(name), PlaceText.encodePlace(lat, lon)).apply()
        all().filter { it.place == PlaceText.key(name) }.forEach(::arm)   // moved place: alerts follow it
    }

    fun place(name: String): Pair<Double, Double>? = prefs.getString(P + PlaceText.key(name), null)?.let(PlaceText::decodePlace)

    fun places(): List<String> = prefs.all.keys.filter { it.startsWith(P) }.map { it.removePrefix(P) }.sorted()

    fun all(): List<PlaceText.Rem> = prefs.all.mapNotNull { (k, v) ->
        if (!k.startsWith(R)) null else k.removePrefix(R).toLongOrNull()?.let { id -> (v as? String)?.let { PlaceText.decode(id, it) } }
    }.sortedBy { it.id }

    fun get(id: Long): PlaceText.Rem? = prefs.getString(R + id, null)?.let { PlaceText.decode(id, it) }

    /** [insideNow] = the user is already there: an "arrive" alert waits until they leave and come back. */
    fun add(text: String, place: String, leave: Boolean, insideNow: Boolean): PlaceText.Rem {
        val r = PlaceText.Rem(System.currentTimeMillis(), PlaceText.key(place), leave, armed = leave || !insideNow, text)
        put(r)
        arm(r)
        return r
    }

    fun put(r: PlaceText.Rem) = prefs.edit().putString(R + r.id, PlaceText.encode(r)).apply()

    fun remove(id: Long) {
        runCatching { lm.removeProximityAlert(pending(id)) }
        prefs.edit().remove(R + id).apply()
    }

    /** Proximity alerts are lost on reboot and app update: called from BootReceiver and app start. */
    fun restoreAll() = all().forEach(::arm)

    @SuppressLint("MissingPermission")
    private fun arm(r: PlaceText.Rem) {
        val (lat, lon) = place(r.place) ?: return
        try {
            lm.addProximityAlert(lat, lon, PlaceText.RADIUS_M, -1, pending(r.id))
            JarvisLog.i("place reminder ${r.id} armed at ${r.place}")
        } catch (e: SecurityException) {
            JarvisLog.e("place reminder: location permission missing", e)
        }
    }

    /** Mutable: the system adds KEY_PROXIMITY_ENTERING. Explicit component, so this is safe. */
    private fun pending(id: Long) = PendingIntent.getBroadcast(
        context, (id % Int.MAX_VALUE).toInt(),
        Intent(context, PlaceReminderReceiver::class.java).putExtra(PlaceReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val P = "p:"
        private const val R = "r:"

        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val out = FloatArray(1)
            Location.distanceBetween(lat1, lon1, lat2, lon2, out)
            return out[0]
        }
    }
}

class PlaceReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (!intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        val store = PlaceReminders(context)
        val r = store.get(id) ?: run { store.remove(id); return }
        JarvisLog.i("place reminder $id ${if (entering) "enter" else "exit"} ${r.place}")
        if (!r.armed) {
            if (!entering) store.put(r.copy(armed = true))   // left the place: next arrival fires
            return
        }
        if (entering == r.leave) return
        Notifications.showReminder(context, Reminder(id = id, text = r.text, dueMs = System.currentTimeMillis()))
        JarvisCoreService.say(context, PlaceText.fireSay(r))
        store.remove(id)
    }

    companion object { const val EXTRA_ID = "place_reminder_id" }
}

fun placeTools(store: PlaceReminders, location: CurrentLocation): List<Tool> = listOf(
    SimpleTool("save_place", "Save the current location as a named place (home, office, gym)", listOf(arg("name", "place name"))) { c, _ ->
        val name = PlaceText.key(c.require("name", "What should I call this place?"))
        if (name.isEmpty()) return@SimpleTool ToolResult.fail("What should I call this place?")
        val fix = location.get(15_000) ?: return@SimpleTool ToolResult.fail("I can't get your location right now.")
        if (fix.accuracyM > 300) return@SimpleTool ToolResult.fail("Your location is not accurate enough. Try again outside or with GPS on.")
        store.savePlace(name, fix.lat, fix.lon)
        ToolResult.ok("Saved this place as ${PlaceText.spoken(name).removePrefix("the ")}.")
    },
    SimpleTool(
        "place_reminder", "Remind when the user reaches or leaves a saved place",
        listOf(
            arg("text", "what to remind"),
            arg("place", "saved place name, e.g. home or office"),
            arg("when", "arrive or leave", required = false, choices = listOf("arrive", "leave")),
        ),
    ) { c, _ ->
        val text = c.require("text", "Remind you about what?")
        val place = PlaceText.key(c.require("place", "Which place?"))
        val at = store.place(place)
            ?: return@SimpleTool ToolResult.fail("I don't know where ${PlaceText.spoken(place)} is. Go there and say \"this is $place\".")
        val leave = c.arg("when") == "leave"
        val here = location.get(5_000)
        val inside = here != null && PlaceReminders.distanceM(here.lat, here.lon, at.first, at.second) < PlaceText.RADIUS_M
        val r = store.add(text, place, leave, inside)
        ToolResult.ok(PlaceText.setSay(r) + if (inside && !leave) " You're there now, so I'll wait until you come back." else "")
    },
)
