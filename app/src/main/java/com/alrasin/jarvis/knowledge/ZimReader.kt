package com.alrasin.jarvis.knowledge

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Small read-only reader for Kiwix .zim files (ROADMAP #42, §K 11.4): find an article by title and read its HTML.
 * Only what offline Wikipedia needs: header, URL/title lists, redirects, plain / xz / zstd clusters.
 * Not thread-safe; [WikiOffline] guards it with a lock.
 */
class ZimReader(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val entryCount: Int
    private val clusterCount: Int
    private val urlPtrPos: Long
    private val titlePtrPos: Long
    private val clusterPtrPos: Long
    private val checksumPos: Long
    private val mimeTypes: List<String>

    /** Article namespace: 'C' in files made since 2021, 'A' in older ones. */
    val articleNs: Char

    init {
        val h = read(0, HEADER_SIZE)
        require(h.int == MAGIC) { "not a zim file" }
        val major = h.short.toInt() and 0xFFFF
        val minor = h.short.toInt() and 0xFFFF
        h.position(24)
        entryCount = h.int
        clusterCount = h.int
        urlPtrPos = h.long
        titlePtrPos = h.long
        clusterPtrPos = h.long
        val mimeListPos = h.long
        h.position(72)
        checksumPos = h.long.takeIf { it > 0 } ?: raf.length()
        mimeTypes = readMimeList(mimeListPos)
        articleNs = if (major >= 6 && minor >= 1) 'C' else 'A'
    }

    /** One directory entry. [redirect] is an entry index; otherwise [cluster]/[blob] point at the content. */
    data class Entry(
        val index: Int,
        val ns: Char,
        val url: String,
        val title: String,
        val mime: String?,
        val redirect: Int? = null,
        val cluster: Int = -1,
        val blob: Int = -1,
    ) {
        val isArticle get() = redirect != null || mime?.startsWith("text/html") == true
    }

    fun entry(index: Int): Entry {
        val pos = read(urlPtrPos + index * 8L, 8).long
        val b = read(pos, 16)
        val mime = b.short.toInt() and 0xFFFF
        b.get() // parameter length
        val ns = (b.get().toInt() and 0xFF).toChar()
        b.int // revision
        return when (mime) {
            REDIRECT -> {
                val target = b.int
                val (url, title) = strings(pos + 12)
                Entry(index, ns, url, title, null, redirect = target)
            }
            LINK_TARGET, DELETED -> {
                val (url, title) = strings(pos + 8)
                Entry(index, ns, url, title, null)
            }
            else -> {
                val cluster = b.int
                val blob = b.int
                val (url, title) = strings(pos + 16)
                Entry(index, ns, url, title, mimeTypes.getOrNull(mime), cluster = cluster, blob = blob)
            }
        }
    }

    /** Follows redirects (at most a few hops) to the real entry. */
    fun resolve(e: Entry): Entry? {
        var cur = e
        repeat(MAX_REDIRECTS) {
            val next = cur.redirect ?: return cur
            if (next !in 0 until entryCount) return null
            cur = entry(next)
        }
        return cur.takeIf { it.redirect == null }
    }

    /** Exact URL match in [ns] (URLs are sorted by namespace then URL bytes). */
    fun findByUrl(ns: Char, url: String): Entry? {
        val idx = lowerBound(entryCount, key(ns, url)) { i -> entry(i).let { key(it.ns, it.url) } }
        return idx.takeIf { it < entryCount }?.let(::entry)?.takeIf { it.ns == ns && it.url == url }
    }

    /** Entries in [ns] whose title starts with [prefix] (case as given), in title order, at most [limit]. */
    fun titlesStartingWith(ns: Char, prefix: String, limit: Int = 20): List<Entry> {
        val k = key(ns, prefix)
        var i = lowerBound(entryCount, k) { titleKey(it) }
        val out = mutableListOf<Entry>()
        while (i < entryCount && out.size < limit) {
            val e = entry(titleIndex(i))
            if (e.ns != ns || !e.title.startsWith(prefix)) break
            out += e
            i++
        }
        return out
    }

    /** Raw bytes of an entry's content (resolves redirects). */
    fun content(e: Entry): ByteArray? {
        val r = resolve(e) ?: return null
        if (r.cluster !in 0 until clusterCount) return null
        return blob(r.cluster, r.blob)
    }

    override fun close() = raf.close()

    // ---- internals ----

    private var cachedCluster = -1
    private var cachedData: ByteArray? = null
    private var cachedWide = false

    private fun blob(cluster: Int, blob: Int): ByteArray? {
        val start = read(clusterPtrPos + cluster * 8L, 8).long
        val end = if (cluster + 1 < clusterCount) read(clusterPtrPos + (cluster + 1) * 8L, 8).long else checksumPos
        val info = read(start, 1).get().toInt() and 0xFF
        val wide = info and 0x10 != 0
        val offSize = if (wide) 8 else 4
        return when (val comp = info and 0x0F) {
            0, 1 -> {
                val first = offset(start + 1, 0, wide)
                if (blob < 0 || blob >= first / offSize - 1) return null
                val a = offset(start + 1, blob, wide)
                val b = offset(start + 1, blob + 1, wide)
                bytes(start + 1 + a, (b - a).toInt())
            }
            XZ, ZSTD -> {
                if (cachedCluster != cluster) {
                    val packed = bytes(start + 1, (end - start - 1).toInt())
                    cachedData = decompress(comp, ByteArrayInputStream(packed))
                    cachedCluster = cluster
                    cachedWide = wide
                }
                val data = cachedData ?: return null
                val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                fun off(i: Int) = if (cachedWide) buf.getLong(i * 8) else buf.getInt(i * 4).toLong() and 0xFFFFFFFFL
                if (blob < 0 || blob >= off(0) / offSize - 1) return null
                val a = off(blob).toInt()
                data.copyOfRange(a, off(blob + 1).toInt())
            }
            else -> null // zlib / bzip2 were only used by very old files
        }
    }

    private fun decompress(comp: Int, input: InputStream): ByteArray = when (comp) {
        XZ -> org.tukaani.xz.XZInputStream(input).use { it.readBytes() }
        else -> com.github.luben.zstd.ZstdInputStream(input).use { it.readBytes() }
    }

    private fun offset(base: Long, i: Int, wide: Boolean): Long =
        if (wide) read(base + i * 8L, 8).long else read(base + i * 4L, 4).int.toLong() and 0xFFFFFFFFL

    private fun titleIndex(i: Int): Int = read(titlePtrPos + i * 4L, 4).int

    private fun titleKey(i: Int): ByteArray = entry(titleIndex(i)).let { key(it.ns, it.title) }

    private fun readMimeList(pos: Long): List<String> {
        val out = mutableListOf<String>()
        var p = pos
        while (out.size < 256) {
            val s = cString(p)
            if (s.isEmpty()) break
            out += s
            p += s.toByteArray().size + 1
        }
        return out
    }

    private fun strings(pos: Long): Pair<String, String> {
        val url = cString(pos)
        val title = cString(pos + url.toByteArray().size + 1)
        return url to title.ifEmpty { url }
    }

    private fun cString(pos: Long): String {
        val out = java.io.ByteArrayOutputStream()
        raf.seek(pos)
        val chunk = ByteArray(64)
        while (true) {
            val n = raf.read(chunk)
            if (n <= 0) break
            val zero = (0 until n).firstOrNull { chunk[it] == 0.toByte() }
            if (zero != null) {
                out.write(chunk, 0, zero)
                break
            }
            out.write(chunk, 0, n)
            if (out.size() > MAX_STRING) break
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun bytes(pos: Long, len: Int): ByteArray {
        val b = ByteArray(len)
        raf.seek(pos)
        raf.readFully(b)
        return b
    }

    private fun read(pos: Long, len: Int): ByteBuffer = ByteBuffer.wrap(bytes(pos, len)).order(ByteOrder.LITTLE_ENDIAN)

    companion object {
        const val MAGIC = 72173914
        const val HEADER_SIZE = 80
        private const val REDIRECT = 0xFFFF
        private const val LINK_TARGET = 0xFFFE
        private const val DELETED = 0xFFFD
        private const val XZ = 4
        private const val ZSTD = 5
        private const val MAX_REDIRECTS = 5
        private const val MAX_STRING = 4096

        /** Sort key used by both lists: namespace byte, then the UTF-8 bytes of URL or title. */
        fun key(ns: Char, s: String): ByteArray = byteArrayOf(ns.code.toByte()) + s.toByteArray(Charsets.UTF_8)

        fun compare(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (d != 0) return d
            }
            return a.size - b.size
        }

        /** First index in 0 until [n] whose key is >= [target]. */
        internal inline fun lowerBound(n: Int, target: ByteArray, keyAt: (Int) -> ByteArray): Int {
            var lo = 0
            var hi = n
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (compare(keyAt(mid), target) < 0) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}
