package com.alrasin.jarvis.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * openWakeWord streaming detector (D-021), ported from openwakeword/utils.py + model.py.
 * Feed exactly [CHUNK] samples (80 ms @ 16 kHz) per call to [process]; returns the wake score 0..1.
 *
 * Pipeline per chunk: melspectrogram(last 1280+480 samples) → spec/10+2 → 8 new mel frames;
 * embedding(last 76 mel frames) → 96-dim feature; wake model(last 16 features) → score.
 */
class WakeWordDetector(
    melModel: ByteArray,
    embeddingModel: ByteArray,
    wakeModel: ByteArray,
) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }
    private val mel = env.createSession(melModel, opts)
    private val emb = env.createSession(embeddingModel, opts)
    private val wake = env.createSession(wakeModel, opts)
    private val wakeInput = wake.inputNames.first()

    private val raw = AudioRing(CHUNK + MEL_CONTEXT)
    private val melFrames = ArrayDeque<FloatArray>()      // each 32 floats
    private val features = ArrayDeque<FloatArray>()       // each 96 floats
    private var calls = 0

    init {
        reset()
    }

    fun reset() {
        melFrames.clear()
        features.clear()
        repeat(EMB_WINDOW) { melFrames.addLast(FloatArray(MEL_BINS) { 1f }) }
        // openWakeWord starts with embeddings of random noise; zeros-ish noise is close enough.
        repeat(FEATURE_WINDOW) { features.addLast(FloatArray(EMB_DIM)) }
        calls = 0
    }

    fun process(chunk: ShortArray): Float {
        require(chunk.size == CHUNK) { "need $CHUNK samples" }
        raw.write(chunk)
        val audio = raw.last(CHUNK + MEL_CONTEXT)
        if (audio.size < CHUNK + MEL_CONTEXT) return 0f

        for (frame in melspectrogram(audio)) {
            melFrames.addLast(frame)
            if (melFrames.size > EMB_WINDOW) melFrames.removeFirst()
        }
        features.addLast(embedding())
        if (features.size > FEATURE_WINDOW) features.removeFirst()

        val score = wakeScore()
        calls++
        return if (calls < 5) 0f else score   // model.py ignores the first 5 predictions
    }

    private fun melspectrogram(audio: ShortArray): List<FloatArray> {
        val input = FloatArray(audio.size) { audio[it].toFloat() }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { t ->
            mel.run(mapOf("input" to t)).use { out ->
                val tensor = out[0] as OnnxTensor
                val buf = tensor.floatBuffer
                val n = buf.remaining() / MEL_BINS
                return List(n) { FloatArray(MEL_BINS) { buf.get() / 10f + 2f } }
            }
        }
    }

    private fun embedding(): FloatArray {
        val input = FloatArray(EMB_WINDOW * MEL_BINS)
        melFrames.forEachIndexed { i, f -> f.copyInto(input, i * MEL_BINS) }
        val shape = longArrayOf(1, EMB_WINDOW.toLong(), MEL_BINS.toLong(), 1)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { t ->
            emb.run(mapOf("input_1" to t)).use { out ->
                val buf = (out[0] as OnnxTensor).floatBuffer
                return FloatArray(EMB_DIM) { buf.get() }
            }
        }
    }

    private fun wakeScore(): Float {
        val input = FloatArray(FEATURE_WINDOW * EMB_DIM)
        features.forEachIndexed { i, f -> f.copyInto(input, i * EMB_DIM) }
        val shape = longArrayOf(1, FEATURE_WINDOW.toLong(), EMB_DIM.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { t ->
            wake.run(mapOf(wakeInput to t)).use { out ->
                return (out[0] as OnnxTensor).floatBuffer.get()
            }
        }
    }

    override fun close() {
        mel.close()
        emb.close()
        wake.close()
        opts.close()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 1280
        private const val MEL_CONTEXT = 480
        private const val MEL_BINS = 32
        private const val EMB_WINDOW = 76
        private const val EMB_DIM = 96
        private const val FEATURE_WINDOW = 16
    }
}
