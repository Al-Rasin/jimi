package com.alrasin.jimi.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.alrasin.jimi.core.JimiLog
import java.nio.FloatBuffer

/**
 * openWakeWord streaming detector (D-021), ported from openwakeword/utils.py + model.py.
 * Feed exactly [CHUNK] samples (80 ms @ 16 kHz) per call to [process]; returns the wake score 0..1.
 *
 * Pipeline per chunk: melspectrogram(last 1280+480 samples) → spec/10+2 → 8 new mel frames;
 * embedding(last 76 mel frames) → 96-dim feature; wake model(last 16 features) → score.
 *
 * [phases] (D-100): the score swings with where the 80 ms grid falls in the word: one real "Hey Jimmy" scored
 * 0.00 or 0.99 after a 10 ms shift. Each phase runs embedding + wake model on mel frames shifted 2 frames
 * (20 ms) back, with its own feature history; the best phase wins. Mel is shared, so cost grows only by the
 * small embedding/wake runs.
 */
class WakeWordDetector(
    melModel: ByteArray,
    embeddingModel: ByteArray,
    wakeModel: ByteArray,
    private val phases: Int = PHASES,
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
    private val features = List(phases) { ArrayDeque<FloatArray>() }   // per phase, each 96 floats
    private var calls = 0
    private var bestPhase = 0
    private var timeNs = 0L

    init {
        reset()
    }

    fun reset() {
        melFrames.clear()
        repeat(melKeep()) { melFrames.addLast(FloatArray(MEL_BINS) { 1f }) }
        // openWakeWord starts with embeddings of random noise; zeros-ish noise is close enough.
        for (f in features) {
            f.clear()
            repeat(FEATURE_WINDOW) { f.addLast(FloatArray(EMB_DIM)) }
        }
        calls = 0
        timeNs = 0L
        bestPhase = 0
    }

    fun process(chunk: ShortArray): Float {
        require(chunk.size == CHUNK) { "need $CHUNK samples" }
        raw.write(chunk)
        val audio = raw.last(CHUNK + MEL_CONTEXT)
        if (audio.size < CHUNK + MEL_CONTEXT) return 0f

        val start = System.nanoTime()
        for (frame in melspectrogram(audio)) {
            melFrames.addLast(frame)
            if (melFrames.size > melKeep()) melFrames.removeFirst()
        }
        var score = 0f
        for (p in 0 until phases) {
            val f = features[p]
            f.addLast(embedding(melFrames.size - EMB_WINDOW - p * PHASE_STEP))
            if (f.size > FEATURE_WINDOW) f.removeFirst()
            val s = wakeScore(f)
            if (p == 0 || s > score) { score = s; bestPhase = p }
        }
        calls++
        timeNs += System.nanoTime() - start
        if (calls == TIMING_CALLS) JimiLog.i("wake detector: $phases phases, ${"%.1f".format(timeNs / 1e6 / calls)} ms per 80 ms chunk")
        return if (calls < 5) 0f else score   // model.py ignores the first 5 predictions
    }

    /** The 16x96 feature window behind the last score (best phase), for [WakeVerifier]. */
    fun peakWindow(): FloatArray {
        val out = FloatArray(FEATURE_WINDOW * EMB_DIM)
        features[bestPhase].forEachIndexed { i, f -> f.copyInto(out, i * EMB_DIM) }
        return out
    }

    private fun melKeep() = EMB_WINDOW + (phases - 1) * PHASE_STEP

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

    private fun embedding(from: Int): FloatArray {
        val input = FloatArray(EMB_WINDOW * MEL_BINS)
        for (i in 0 until EMB_WINDOW) melFrames[from + i].copyInto(input, i * MEL_BINS)
        val shape = longArrayOf(1, EMB_WINDOW.toLong(), MEL_BINS.toLong(), 1)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { t ->
            emb.run(mapOf("input_1" to t)).use { out ->
                val buf = (out[0] as OnnxTensor).floatBuffer
                return FloatArray(EMB_DIM) { buf.get() }
            }
        }
    }

    private fun wakeScore(features: ArrayDeque<FloatArray>): Float {
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
        const val EMB_DIM = 96
        const val FEATURE_WINDOW = 16
        const val PHASES = 4
        private const val PHASE_STEP = 2            // mel frames (20 ms) between phases
        private const val TIMING_CALLS = 500
    }
}
