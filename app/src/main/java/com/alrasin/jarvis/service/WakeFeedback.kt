package com.alrasin.jarvis.service

import android.content.Context
import com.alrasin.jarvis.data.WakeEvent
import com.alrasin.jarvis.data.WakeEventDao
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wake clips live in `<external files>/wake/` as `<time>_s<score>_<verdict>.wav` so the training
 * script can sort them by file name alone (D-023). The Room row keeps the same info.
 */
object WakeFeedback {
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun clipDir(context: Context): File = File(context.getExternalFilesDir(null), "wake")

    fun clipFile(context: Context, timeMs: Long, score: Float, verdict: String): File =
        File(clipDir(context), "${stamp.format(Date(timeMs))}_s${(score * 100).toInt()}_$verdict.wav")

    /** Sets the verdict and renames the clip to match. */
    suspend fun setVerdict(context: Context, dao: WakeEventDao, id: Long, verdict: String) {
        val event = dao.get(id) ?: return
        val old = File(event.clipPath)
        val new = clipFile(context, event.timeMs, event.score, verdict)
        val path = if (old.exists() && old.renameTo(new)) new.path else event.clipPath
        dao.setVerdict(id, verdict, path)
    }

    fun label(verdict: String): String = when (verdict) {
        WakeEvent.VERDICT_YES -> "✓ correct"
        WakeEvent.VERDICT_NO -> "✗ false wake"
        WakeEvent.VERDICT_MISSED -> "missed"
        WakeEvent.VERDICT_NEAR -> "near miss"
        else -> "no answer"
    }
}
