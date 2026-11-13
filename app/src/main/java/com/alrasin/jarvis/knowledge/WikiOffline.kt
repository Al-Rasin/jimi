package com.alrasin.jarvis.knowledge

import android.content.Context
import android.os.Environment
import com.alrasin.jarvis.core.JarvisLog
import org.jsoup.Jsoup
import java.io.File

/**
 * Offline Wikipedia from a Kiwix .zim file (ROADMAP #42, §K 11.4). Used by `wiki_lookup` when the internet is down.
 * The .zim is not shipped: the user puts it in `models/wiki/`, `/sdcard/Kiwix` or `/sdcard/Download`.
 */
class WikiOffline(
    private val dirs: () -> List<File>,
    private val ranker: ChunkRanker = ChunkRanker(),
) {
    data class Article(val title: String, val paragraphs: List<String>)
    data class Answer(val title: String, val text: String)

    private var reader: ZimReader? = null
    private var readerFile: File? = null

    /** The .zim to use: the biggest Wikipedia file found (full beats mini), else any .zim. */
    fun file(): File? {
        val zims = dirs().flatMap { d -> d.listFiles { f -> f.isFile && f.name.endsWith(".zim", true) }.orEmpty().toList() }
        return zims.filter { it.name.contains("wikipedia", true) }.maxByOrNull { it.length() } ?: zims.maxByOrNull { it.length() }
    }

    fun available(): Boolean = file() != null

    /** Short answer for [topic]; [question] (the whole sentence) picks a paragraph when it asks more than the topic. */
    fun lookup(topic: String, question: String = ""): Answer? {
        val article = article(topic) ?: return null
        val extra = ChunkRanker.words(question).toSet() - ChunkRanker.words("$topic ${article.title}").toSet()
        val pick = if (extra.isEmpty()) null else ranker.best(extra.joinToString(" "), article.paragraphs)
        return Answer(article.title, pick ?: article.paragraphs.first())
    }

    @Synchronized
    fun article(topic: String): Article? {
        val zim = open() ?: return null
        return runCatching {
            val entry = find(zim, topic.trim()) ?: return null
            val html = zim.content(entry)?.toString(Charsets.UTF_8) ?: return null
            val paragraphs = paragraphs(html)
            if (paragraphs.isEmpty()) null else Article(zim.resolve(entry)?.title ?: entry.title, paragraphs)
        }.onFailure { JarvisLog.e("offline wiki read failed", it) }.getOrNull()
    }

    private fun open(): ZimReader? {
        val f = file() ?: return null
        if (f != readerFile) {
            reader?.close()
            reader = runCatching { ZimReader(f) }.onFailure { JarvisLog.e("cannot open ${f.name}", it) }.getOrNull()
            readerFile = f
        }
        return reader
    }

    companion object {
        private const val MAX_PARAGRAPHS = 40

        /** Looks in the app's models/wiki, /sdcard/Kiwix and /sdcard/Download; embedder from models/embed if there. */
        fun forPhone(context: Context): WikiOffline {
            val models = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
            val sd = Environment.getExternalStorageDirectory()
            val ranker = ChunkRanker { SharedEmbedder.get(context) }
            return WikiOffline({ listOf(File(models, "wiki"), File(sd, "Kiwix"), File(sd, "Download")) }, ranker)
        }
        private val CITE_RE = Regex("\\[(?:\\d+|[a-z]|citation needed|note \\d+)]")

        /** Exact URL first (Wikipedia redirects cover most spellings), then a title-prefix match. */
        internal fun find(zim: ZimReader, topic: String): ZimReader.Entry? {
            if (topic.isBlank()) return null
            val namespaces = listOf(zim.articleNs, if (zim.articleNs == 'C') 'A' else 'C')
            val variants = variants(topic)
            for (ns in namespaces) {
                for (v in variants) {
                    for (url in listOf(v.replace(' ', '_'), v)) {
                        zim.findByUrl(ns, url)?.takeIf { it.isArticle }?.let { return it }
                    }
                }
            }
            for (ns in namespaces) {
                for (v in variants) {
                    val hits = zim.titlesStartingWith(ns, v, limit = 10).filter { it.isArticle }
                    hits.firstOrNull { it.title.equals(v, true) }?.let { return it }
                    hits.firstOrNull()?.let { return it }
                }
            }
            return null
        }

        /** As typed with a capital first letter, Title Case, and lower case after the first letter. */
        internal fun variants(topic: String): List<String> {
            val t = topic.trim().replace(Regex("\\s+"), " ")
            val first = t.replaceFirstChar { it.uppercase() }
            val title = t.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            val lower = t.lowercase().replaceFirstChar { it.uppercase() }
            return listOf(first, title, lower).distinct()
        }

        /** Readable paragraphs from article HTML (no citation marks, no tiny bits). */
        fun paragraphs(html: String): List<String> = Jsoup.parse(html).select("p")
            .asSequence()
            .map { it.text().replace(CITE_RE, "").replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 40 }
            .take(MAX_PARAGRAPHS)
            .toList()
    }
}
