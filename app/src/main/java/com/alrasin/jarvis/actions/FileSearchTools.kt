package com.alrasin.jarvis.actions

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.alrasin.jarvis.brain.FileKind
import com.alrasin.jarvis.brain.PhotoWhen
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.core.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Photo and file search (ROADMAP #35, offline): MediaStore date + EXIF place for photos; names + small-PDF text for files. */
fun fileSearchTools(context: Context, places: PlaceReminders): List<Tool> = listOf(
    SimpleTool(
        "find_photos", "Show photos from a time (last week, march) or a place (Cox's Bazar, home) in the gallery",
        listOf(
            arg("when", "time words like today, last week, march 2024", required = false),
            arg("place", "a town or a saved place where the photos were taken", required = false),
        ),
    ) { c, _ -> findPhotos(context, places, c.arg("when"), c.arg("place")) },
    SimpleTool(
        "find_file", "Find a PDF or document on the phone by its name or what it is about, and open it",
        listOf(
            arg("query", "words from the file name or its topic"),
            arg("type", "kind of file", required = false, choices = listOf("pdf", "doc", "sheet", "slides", "text")),
        ),
    ) { c, _ -> findFile(context, c.require("query", "Which file should I look for?"), c.arg("type")) },
)

private const val MAX_PHOTOS_EXIF = 1500
private const val TOWN_RADIUS_M = 15_000f
private const val SAVED_PLACE_RADIUS_M = 300f

private suspend fun findPhotos(context: Context, places: PlaceReminders, whenSaid: String?, placeSaid: String?): ToolResult {
    val range = whenSaid?.let { PhotoWhen.range(it, LocalDate.now()) ?: return ToolResult.fail("I didn't get the time \"$it\".") }
    val target = placeSaid?.let { p ->
        places.place(p)?.let { Triple(it.first, it.second, SAVED_PLACE_RADIUS_M) }
            ?: geocode(context, p)?.let { Triple(it.first, it.second, TOWN_RADIUS_M) }
            ?: return ToolResult.fail("I couldn't find $p on the map.")
    }
    if (range == null && target == null) return ToolResult.fail("Which photos? Say a time like last week, or a place.")
    val found = withContext(Dispatchers.IO) { queryPhotos(context, range, target) }
        ?: return ToolResult.fail("I can't read your photos. Allow photo access in Setup.")
    val what = listOfNotNull(whenSaid?.let { "from $it" }, placeSaid?.let { "in $it" }).joinToString(" ")
    if (found.isEmpty()) return ToolResult.fail("I found no photos $what.")
    val open = Intent(Intent.ACTION_VIEW).setDataAndType(found.first(), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val opened = context.launch(open)
    val n = found.size
    val count = if (n == 1) "one photo" else "$n photos"
    return if (opened) ToolResult.ok("Found $count $what. Opening the newest; swipe for more.")
    else ToolResult.ok("Found $count $what, but no gallery app opened.")
}

/** Newest first. Null when the gallery can't be read. */
private fun queryPhotos(context: Context, range: Pair<LocalDate, LocalDate>?, target: Triple<Double, Double, Float>?): List<Uri>? {
    val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val sel: String?
    val args: Array<String>?
    if (range != null) {
        val zone = ZoneId.systemDefault()
        val from = range.first.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = range.second.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        sel = "(${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?) OR " +
            "(${MediaStore.Images.Media.DATE_TAKEN} IS NULL AND ${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.DATE_ADDED} < ?)"
        args = arrayOf(from.toString(), to.toString(), (from / 1000).toString(), (to / 1000).toString())
    } else {
        sel = null
        args = null
    }
    val cursor = runCatching {
        context.contentResolver.query(col, arrayOf(MediaStore.Images.Media._ID), sel, args, "${MediaStore.Images.Media.DATE_TAKEN} DESC")
    }.getOrNull() ?: return null
    val out = ArrayList<Uri>()
    var checked = 0
    cursor.use {
        while (it.moveToNext()) {
            val uri = ContentUris.withAppendedId(col, it.getLong(0))
            if (target == null) { out += uri; continue }
            if (checked++ >= MAX_PHOTOS_EXIF) break
            val ll = latLong(context, uri) ?: continue
            val d = FloatArray(1)
            Location.distanceBetween(ll.first, ll.second, target.first, target.second, d)
            if (d[0] <= target.third) out += uri
        }
    }
    JarvisLog.i("find_photos: ${out.size} match, exif checked $checked")
    return out
}

// Framework ExifInterface is fine at minSdk 30; avoids a new dependency.
@SuppressLint("ExifInterface")
private fun latLong(context: Context, uri: Uri): Pair<Double, Double>? = runCatching {
    context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))?.use { s ->
        val ll = FloatArray(2)
        if (android.media.ExifInterface(s).getLatLong(ll)) ll[0].toDouble() to ll[1].toDouble() else null
    }
}.getOrNull()

@Suppress("DEPRECATION")
private suspend fun geocode(context: Context, place: String): Pair<Double, Double>? = withTimeoutOrNull(6_000) {
    withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val g = Geocoder(context, Locale.getDefault())
        val names = listOf(place, place.replace(Regex("\\b(\\w+)s (bazar|bazaar|hill|island|beach)\\b"), "$1's $2")).distinct()
        names.firstNotNullOfOrNull { n -> runCatching { g.getFromLocationName(n, 1)?.firstOrNull() }.getOrNull() }
            ?.let { it.latitude to it.longitude }
    }
}

private const val MAX_FILES = 20_000
private const val MAX_PDF_BYTES = 2_000_000L
private const val MAX_PDF_READS = 60
private const val PDF_TIME_MS = 8_000L

private suspend fun findFile(context: Context, query: String, type: String?): ToolResult {
    val exts = FileKind.extensions(type)
    val files = withContext(Dispatchers.IO) { listFiles(exts) }
        ?: return ToolResult.fail("I can't read your files. Allow all-files access in Setup.")
    if (files.isEmpty()) return ToolResult.fail("I found no ${type ?: "document"} files on the phone.")
    val byName = files.map { it to FileKind.score(query, it.nameWithoutExtension) }.filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<File, Int>> { it.second }.thenByDescending { it.first.lastModified() })
    val best = byName.firstOrNull()?.first ?: withContext(Dispatchers.IO) { byPdfText(files, query) }
        ?: return ToolResult.fail("I found no file about \"$query\".")
    val how = if (byName.isEmpty()) " (it mentions \"$query\")" else ""
    return if (openFile(context, best)) ToolResult.ok("Opening ${best.name}$how.", best.path)
    else ToolResult.fail("I found ${best.name} in ${best.parentFile?.name ?: "your files"}, but no app opened it.")
}

/** Document files under shared storage, newest first; skips Android/ and hidden folders. Null when not allowed. */
private fun listFiles(exts: Set<String>): List<File>? {
    val root = Environment.getExternalStorageDirectory()
    if (!Environment.isExternalStorageManager()) return null
    val out = ArrayList<File>()
    var seen = 0
    root.walkTopDown()
        .onEnter { d -> d == root || (!d.name.startsWith(".") && !(d.parentFile == root && d.name == "Android")) }
        .maxDepth(8)
        .forEach { f -> if (seen++ < MAX_FILES && f.isFile && f.extension.lowercase() in exts) out += f }
    return out.sortedByDescending { it.lastModified() }
}

private suspend fun byPdfText(files: List<File>, query: String): File? = withTimeoutOrNull(PDF_TIME_MS) {
    files.asSequence().filter { it.extension.equals("pdf", true) && it.length() in 1..MAX_PDF_BYTES }.take(MAX_PDF_READS)
        .map { it to FileKind.score(query, runCatching { PdfText.extract(it.readBytes()) }.getOrDefault("")) }
        .filter { it.second > 0 }.maxByOrNull { it.second }?.first
}

/** Opens through MediaStore's content URI when indexed, else via root `am start` with a file:// path. */
private suspend fun openFile(context: Context, file: File): Boolean {
    val mime = FileKind.mime(file.extension)
    mediaUri(context, file)?.let { uri ->
        if (context.launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))) return true
    }
    if (!RootShell.available) return false
    val r = RootShell.run(
        "am start -a android.intent.action.VIEW -d ${RootShell.quote(Uri.fromFile(file).toString())} -t ${RootShell.quote(mime)}",
        log = false,
    )
    return r.ok && !r.out.contains("Error")
}

private fun mediaUri(context: Context, file: File): Uri? = runCatching {
    val col = MediaStore.Files.getContentUri("external")
    @Suppress("DEPRECATION") val data = MediaStore.Files.FileColumns.DATA
    context.contentResolver.query(col, arrayOf(MediaStore.Files.FileColumns._ID), "$data = ?", arrayOf(file.path), null)?.use {
        if (it.moveToFirst()) ContentUris.withAppendedId(col, it.getLong(0)) else null
    }
}.getOrNull()
