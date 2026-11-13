package com.alrasin.jarvis.knowledge

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.text.Normalizer

/**
 * Small sentence embedder (ROADMAP #42, §K 11.3): a BERT-style ONNX model (e.g. all-MiniLM-L6-v2, ~23 MB int8)
 * with its WordPiece vocab, put by the user in `models/embed/` (model.onnx + vocab.txt). Not shipped in the APK.
 */
class Embedder private constructor(private val tokenizer: WordPiece, private val session: OrtSession) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()

    fun similarities(query: String, chunks: List<String>): FloatArray {
        val q = embed(query)
        return FloatArray(chunks.size) { ChunkRanker.cosine(q, embed(chunks[it])) }
    }

    @Synchronized
    fun embed(text: String): FloatArray {
        val ids = tokenizer.encode(text, MAX_TOKENS)
        val n = ids.size
        val inputs = mutableMapOf<String, OnnxTensor>()
        try {
            for (name in session.inputNames) {
                val data = when (name) {
                    "input_ids" -> ids
                    "attention_mask" -> LongArray(n) { 1 }
                    "token_type_ids" -> LongArray(n)
                    else -> continue
                }
                inputs[name] = OnnxTensor.createTensor(env, arrayOf(data))
            }
            session.run(inputs).use { out ->
                return when (val v = out[0].value) {
                    is Array<*> -> when (val first = v[0]) {
                        is FloatArray -> first // already pooled [1, dim]
                        is Array<*> -> meanPool(first.filterIsInstance<FloatArray>())
                        else -> error("unexpected embedder output")
                    }
                    else -> error("unexpected embedder output")
                }
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    override fun close() = session.close()

    companion object {
        private const val MAX_TOKENS = 128

        fun meanPool(tokens: List<FloatArray>): FloatArray {
            val out = FloatArray(tokens.firstOrNull()?.size ?: 0)
            tokens.forEach { t -> for (i in out.indices) out[i] += t[i] }
            for (i in out.indices) out[i] /= tokens.size.coerceAtLeast(1)
            return out
        }

        /** Loads from [dir] when model.onnx and vocab.txt are there, else null. */
        fun load(dir: File): Embedder? {
            val model = File(dir, "model.onnx")
            val vocab = File(dir, "vocab.txt")
            if (!model.isFile || !vocab.isFile) return null
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            val session = OrtEnvironment.getEnvironment().createSession(model.absolutePath, opts)
            return Embedder(WordPiece(vocab.readLines()), session)
        }
    }
}

/** BERT uncased WordPiece: lower-case, strip accents, split on punctuation, greedy longest match with "##". */
class WordPiece(vocabLines: List<String>) {
    private val vocab = vocabLines.withIndex().associate { (i, w) -> w.trimEnd() to i.toLong() }
    private val unk = vocab["[UNK]"] ?: 100L
    private val cls = vocab["[CLS]"] ?: 101L
    private val sep = vocab["[SEP]"] ?: 102L

    fun encode(text: String, maxTokens: Int): LongArray {
        val ids = mutableListOf(cls)
        for (word in basic(text)) {
            if (ids.size >= maxTokens - 1) break
            ids += piece(word)
        }
        return (ids.take(maxTokens - 1) + sep).toLongArray()
    }

    fun tokens(text: String): List<String> {
        val back = vocab.entries.associate { it.value to it.key }
        return encode(text, Int.MAX_VALUE).map { back[it] ?: "[UNK]" }
    }

    private fun basic(text: String): List<String> {
        val clean = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return Regex("[\\p{L}\\p{N}]+|[^\\s\\p{L}\\p{N}]").findAll(clean).map { it.value }.toList()
    }

    private fun piece(word: String): List<Long> {
        if (word.length > 100) return listOf(unk)
        val out = mutableListOf<Long>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: Long? = null
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                found = vocab[sub]
                if (found != null) break
                end--
            }
            if (found == null) return listOf(unk)
            out += found
            start = end
        }
        return out
    }
}
