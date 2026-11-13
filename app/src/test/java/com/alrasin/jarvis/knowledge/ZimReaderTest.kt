package com.alrasin.jarvis.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ZimReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val einstein = """<html><body><h1>Albert Einstein</h1>
        <p>Albert Einstein was a German-born theoretical physicist who is best known for developing the theory of relativity.[1]</p>
        <p>Einstein was born in Ulm, in the Kingdom of Württemberg in the German Empire, on 14 March 1879.[2]</p>
        <p>He received the 1921 Nobel Prize in Physics for his services to theoretical physics and the photoelectric effect.</p>
        <p>Short.</p></body></html>"""
    private val dhaka = """<p>Dhaka is the capital and largest city of Bangladesh, on the Buriganga River in the middle of the country.</p>
        <p>The city has a population of over ten million people and is one of the most densely populated places on Earth.</p>"""

    /** Entry for the test writer: content (blob) or a redirect to another url. */
    private data class E(val ns: Char, val url: String, val title: String, val html: String? = null, val redirectTo: String? = null)

    @Test fun findsArticleByUrlTitleAndRedirect() {
        for (compression in listOf(1, 4)) {
            val zim = ZimReader(writeZim(compression))
            assertEquals('C', zim.articleNs)
            val e = WikiOffline.find(zim, "albert einstein")
            assertNotNull(e)
            assertEquals("Albert Einstein", zim.resolve(e!!)!!.title)
            val html = zim.content(e)!!.toString(Charsets.UTF_8)
            assertTrue(html.contains("theoretical physicist"))
            assertTrue(zim.content(WikiOffline.find(zim, "Dhaka")!!)!!.toString(Charsets.UTF_8).contains("Buriganga"))
            assertNull(WikiOffline.find(zim, "Nonexistent topic"))
            zim.close()
        }
    }

    @Test fun titlePrefixFindsArticleWhenUrlDiffers() {
        val zim = ZimReader(writeZim(1))
        val e = WikiOffline.find(zim, "Einst")
        assertEquals("Einstein", e?.url)   // redirect entry; resolves to the article
        assertEquals("Albert_Einstein", zim.resolve(e!!)!!.url)
        zim.close()
    }

    @Test fun lookupPicksIntroOrMatchingParagraph() {
        val dir = tmp.newFolder("wiki")
        writeZim(4).copyTo(File(dir, "wikipedia_en_test.zim"))
        val wiki = WikiOffline({ listOf(dir, File(dir, "missing")) })
        assertTrue(wiki.available())
        val intro = wiki.lookup("Albert Einstein", "who is albert einstein")!!
        assertEquals("Albert Einstein", intro.title)
        assertTrue(intro.text.startsWith("Albert Einstein was a German-born"))
        assertTrue(!intro.text.contains("[1]"))
        val born = wiki.lookup("Albert Einstein", "when was albert einstein born")!!
        assertTrue(born.text, born.text.contains("Ulm"))
        val prize = wiki.lookup("einstein", "what nobel prize did einstein get")!!
        assertTrue(prize.text, prize.text.contains("1921"))
        assertNull(wiki.lookup("Nothing here"))
    }

    @Test fun noZimMeansNotAvailable() {
        val empty = tmp.newFolder("empty")
        val wiki = WikiOffline({ listOf(empty) })
        assertTrue(!wiki.available())
        assertNull(wiki.lookup("Dhaka"))
    }

    @Test fun paragraphsDropTinyBitsAndCitations() {
        val p = WikiOffline.paragraphs(einstein)
        assertEquals(3, p.size)
        assertTrue(p.none { it.contains("[") })
    }

    @Test fun variantsCoverCommonCasing() {
        assertEquals(listOf("Albert einstein", "Albert Einstein"), WikiOffline.variants("albert einstein"))
        assertEquals(listOf("NASA", "Nasa"), WikiOffline.variants("NASA"))
    }

    @Test fun wordPieceSplitsLikeBert() {
        val wp = WordPiece(listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "the", "play", "##ing", "cafe", ",", "!"))
        assertEquals(listOf("[CLS]", "the", "play", "##ing", ",", "cafe", "!", "[UNK]", "[SEP]"), wp.tokens("The PLAYING, café! zzz"))
        assertEquals(4, wp.encode("the the the the the", 4).size)
    }

    @Test fun rankerWithoutEmbedderUsesKeywords() {
        val r = ChunkRanker()
        val paras = WikiOffline.paragraphs(dhaka)
        assertTrue(r.best("population", paras)!!.contains("ten million"))
        assertNull(r.best("what is", paras))
        assertNull(r.best("volcano", paras))
        assertEquals(1f, ChunkRanker.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-5f)
        assertEquals(listOf(2f, 3f).toList(), Embedder.meanPool(listOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))).toList())
    }

    // ---- tiny ZIM writer (format 6.1: 'C' namespace, one cluster) ----

    private fun writeZim(compression: Int): File {
        val entries = listOf(
            E('C', "Albert_Einstein", "Albert Einstein", html = einstein),
            E('C', "Albert_einstein", "Albert einstein", redirectTo = "Albert_Einstein"),
            E('C', "Dhaka", "", html = dhaka),
            E('C', "Einstein", "Einstein", redirectTo = "Albert_Einstein"),
            E('M', "Title", "", html = "Test wiki"),
        ).sortedWith(compareBy({ it.ns }, { it.url }))
        val urlIndex = entries.withIndex().associate { it.value.url to it.index }
        val blobs = entries.filter { it.html != null }
        val blobIndex = blobs.withIndex().associate { it.value.url to it.index }
        val mimes = listOf("text/html", "text/plain")

        val mimeBytes = ByteArrayOutputStream().apply { mimes.forEach { write(it.toByteArray()); write(0) }; write(0) }.toByteArray()
        val dirents = entries.map { e ->
            val b = ByteArrayOutputStream()
            if (e.redirectTo != null) {
                b.write(le(2, 0xFFFF)); b.write(0); b.write(e.ns.code); b.write(le(4, 0)); b.write(le(4, urlIndex.getValue(e.redirectTo)))
            } else {
                b.write(le(2, if (e.ns == 'M') 1 else 0)); b.write(0); b.write(e.ns.code); b.write(le(4, 0))
                b.write(le(4, 0)); b.write(le(4, blobIndex.getValue(e.url)))
            }
            b.write(e.url.toByteArray()); b.write(0); b.write(e.title.toByteArray()); b.write(0)
            b.toByteArray()
        }
        val titleOrder = entries.indices.sortedWith(compareBy({ entries[it].ns }, { entries[it].title.ifEmpty { entries[it].url } }))

        val blobBytes = blobs.map { it.html!!.toByteArray() }
        val raw = ByteArrayOutputStream()
        var off = (blobBytes.size + 1) * 4
        raw.write(le(4, off))
        blobBytes.forEach { off += it.size; raw.write(le(4, off)) }
        blobBytes.forEach { raw.write(it) }
        val body = if (compression == 4) {
            val out = ByteArrayOutputStream()
            org.tukaani.xz.XZOutputStream(out, org.tukaani.xz.LZMA2Options()).use { it.write(raw.toByteArray()) }
            out.toByteArray()
        } else raw.toByteArray()
        val cluster = byteArrayOf(compression.toByte()) + body

        val mimePos = 80L
        val urlPtrPos = mimePos + mimeBytes.size
        val titlePtrPos = urlPtrPos + entries.size * 8L
        val direntPos = titlePtrPos + entries.size * 4L
        val direntOffsets = dirents.runningFold(direntPos) { acc, d -> acc + d.size }
        val clusterPtrPos = direntOffsets.last()
        val clusterPos = clusterPtrPos + 8
        val checksumPos = clusterPos + cluster.size

        val header = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(ZimReader.MAGIC).putShort(6).putShort(1)
        header.position(24)
        header.putInt(entries.size).putInt(1).putLong(urlPtrPos).putLong(titlePtrPos).putLong(clusterPtrPos).putLong(mimePos)
        header.putInt(urlIndex.getValue("Albert_Einstein")).putInt(-1).putLong(checksumPos)

        val f = tmp.newFile("test-$compression.zim")
        f.outputStream().use { o ->
            o.write(header.array())
            o.write(mimeBytes)
            entries.indices.forEach { o.write(le(8, direntOffsets[it])) }
            titleOrder.forEach { o.write(le(4, it.toLong())) }
            dirents.forEach { o.write(it) }
            o.write(le(8, clusterPos))
            o.write(cluster)
            o.write(ByteArray(16))
        }
        return f
    }

    private fun le(size: Int, v: Number): ByteArray {
        val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v.toLong()).array()
        return b.copyOf(size)
    }
}
