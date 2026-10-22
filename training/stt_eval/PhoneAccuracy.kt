package com.alrasin.jarvis.eval

import com.alrasin.jarvis.voice.SttCorrector
import com.alrasin.jarvis.voice.SttVocabulary
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale

/** app_process runner; private inputs stay outside the repository, stdout contains aggregates only. */
object PhoneAccuracy {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single())
        val model = File(root, "model")
        val recognizer = OnlineRecognizer(null, OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(model, "encoder.onnx").path,
                    decoder = File(model, "decoder.onnx").path,
                    joiner = File(model, "joiner.onnx").path,
                ),
                tokens = File(model, "tokens.txt").path, numThreads = 2,
            ),
            enableEndpoint = false, decodingMethod = "greedy_search",
        ))
        val vocabulary = SttVocabulary()
        val corrector = SttCorrector(vocabulary.contacts, vocabulary.apps)
        var words = 0
        var rawErrors = 0
        var correctedErrors = 0
        var count = 0
        var duration = 0.0
        var elapsed = 0.0
        try {
            File(root, "references").listFiles()!!.filter { it.extension == "txt" }.sortedBy { it.name }.forEach { ref ->
                val audio = readWave(File(root, "commands/${ref.nameWithoutExtension}.wav"))
                val stream = recognizer.createStream()
                val started = System.nanoTime()
                val text = try {
                    stream.acceptWaveform(FloatArray(8000), 16000)
                    for (offset in audio.indices step 1600) {
                        stream.acceptWaveform(audio.copyOfRange(offset, minOf(audio.size, offset + 1600)), 16000)
                        while (recognizer.isReady(stream)) recognizer.decode(stream)
                    }
                    stream.acceptWaveform(FloatArray(8000), 16000)
                    stream.inputFinished()
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    recognizer.getResult(stream).text
                } finally { stream.release() }
                elapsed += (System.nanoTime() - started) / 1e9
                duration += audio.size / 16000.0
                val reference = normalize(ref.readText())
                words += reference.size
                rawErrors += distance(reference, normalize(text))
                correctedErrors += distance(reference, normalize(corrector.correct(text)))
                count++
            }
        } finally { recognizer.release() }
        println("clips=$count words=$words sherpa_errors=$rawErrors corrected_errors=$correctedErrors decode_seconds=$elapsed audio_seconds=$duration")
    }

    private fun readWave(file: File): FloatArray {
        val data = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        check(data.int == 0x46464952)
        data.int
        check(data.int == 0x45564157)
        while (data.remaining() >= 8) {
            val kind = data.int
            val length = data.int
            check(length >= 0 && length <= data.remaining())
            val end = data.position() + length
            when (kind) {
                0x20746d66 -> {
                    check(data.short.toInt() == 1 && data.short.toInt() == 1 && data.int == 16000)
                    data.int; data.short
                    check(data.short.toInt() == 16)
                }
                0x61746164 -> return FloatArray(length / 2) { data.short / 32768f }
            }
            data.position(end + length % 2)
        }
        error("No PCM data")
    }

    private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('’', '\'').replace(Regex("\\p{P}"), "")
        .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun distance(a: List<String>, b: List<String>): Int {
        var row = IntArray(b.size + 1) { it }
        a.forEachIndexed { i, word ->
            val next = IntArray(b.size + 1)
            next[0] = i + 1
            b.forEachIndexed { j, other ->
                next[j + 1] = minOf(next[j] + 1, row[j + 1] + 1, row[j] + if (word == other) 0 else 1)
            }
            row = next
        }
        return row.last()
    }
}
