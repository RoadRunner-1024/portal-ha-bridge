package com.aeonos.portalha

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * openWakeWord second-stage verifier (Apache-2 models from dscripka/openWakeWord).
 *
 * Vosk decides WHAT was said from a tiny grammar, which makes it trigger-happy: any
 * sound that vaguely maps onto the grammar can decode as the wake phrase, and the
 * confidence gates can only reject so much. openWakeWord is the opposite — a small
 * network trained on ONE phrase, which answers "was this actually 'alexa'?" with a
 * calibrated 0..1 score. Running it on the audio around a Vosk match kills the false
 * positives while keeping Vosk's flexible, phrase-configurable front end.
 *
 * Pipeline (as in openWakeWord): raw int16 audio (as float, NOT normalized) ->
 * melspectrogram model -> mel/10+2 -> 76-frame windows, stride 8 -> embedding model
 * (96 features each) -> wake model over 16 consecutive embeddings -> score. We score
 * every 16-embedding window across the buffer and take the max, so the phrase can sit
 * anywhere in the verification window.
 *
 * ── Why [patchInputShape] exists ──────────────────────────────────────────────
 * melspectrogram.tflite ships with its input declared as shape [1, 1] and
 * shape_signature [-1, -1]: the real shape is [batch, numSamples] and [1,1] is only
 * the TF converter's placeholder, i.e. "one audio sample". Python gets away with it
 * because tf.lite.Interpreter does NOT allocate in its constructor — openWakeWord
 * calls resize_tensor_input(0, [1, N]) + allocate_tensors() before its first run.
 *
 * The TF Lite *Java* constructor allocates eagerly: NativeInterpreterWrapper.init()
 * ends with an unconditional allocateTensors(). So shape propagation ran on that
 * 1-sample default, and the STFT — a CONV_2D with a 512-wide kernel, VALID padding
 * and stride 160 — produced an output width of (1 + 160 - 512) / 160 = -2. That
 * negative dim is what BytesRequired() choked on:
 *
 *     Internal error: Unexpected failure when preparing tensor allocations:
 *     tensorflow/lite/util.cc BytesRequired number of elements overflowed.
 *
 * resizeInput() cannot rescue this — the constructor has already thrown, and TF Lite
 * exposes no option to defer allocation. So we materialise the dynamic dimension in
 * the FlatBuffer bytes *before* the Interpreter ever sees them. interpreter_builder.cc
 * takes a tensor's real dims from the `shape` field (shape_signature is only carried
 * alongside), so this is exactly Python's resize_tensor_input, just applied earlier.
 * Rank is unchanged, so it's a pure in-place int32 rewrite: no FlatBuffer offsets
 * move, and the asset on disk is untouched — we only edit our own copy in RAM.
 *
 * Because we can patch to any length, we build the mel model at the *exact* sample
 * count we're about to feed it. The graph is then fully static, so resizeInput() and
 * allocateTensors() never run at all and XNNPACK keeps delegating the convolutions.
 * If the buffer length ever changes, [ensureMelInput] rebuilds once and caches.
 */
class OwwVerifier private constructor(
    private var mel: Interpreter,
    private val melModel: ByteArray,
    private val emb: Interpreter,
    private val wake: Interpreter,
    val name: String,
) {
    /** Sample count the current [mel] interpreter was built for. */
    private var melSamples = MEL_DEFAULT_SAMPLES

    companion object {
        private const val TAG = "PortalHA"
        private const val MEL_BINS = 32
        private const val EMB_FRAMES = 76      // mel frames per embedding window
        private const val EMB_STRIDE = 8       // 8 mel frames = 80 ms hop
        private const val EMB_FEATURES = 96
        private const val WAKE_EMBEDDINGS = 16 // embeddings per wake-model input

        // The mel model's STFT: 512-wide kernel, stride 160, VALID padding. These two
        // give us the frame arithmetic without having to run the model.
        private const val MEL_FFT = 512
        private const val MEL_HOP = 160

        // Sample count the mel interpreter is built for at load time. Matches the 2.4 s
        // window WakeWordDetector captures (VERIFY_BUFFER_SAMPLES = 16000 * 24 / 10), so
        // the common path never rebuilds. Any other length just costs one rebuild.
        private const val MEL_DEFAULT_SAMPLES = 38400

        /** Mel frames produced for [samples] input samples (CONV_2D, VALID, k=512, s=160). */
        private fun melFrames(samples: Int): Int =
            if (samples < MEL_FFT) 0 else (samples - MEL_FFT) / MEL_HOP + 1

        /** Fewest input samples that yield [frames] mel frames — the inverse of [melFrames]. */
        private fun samplesForFrames(frames: Int): Int =
            if (frames <= 0) 0 else (frames - 1) * MEL_HOP + MEL_FFT

        // Shortest buffer that can produce even ONE wake score: 16 embedding windows need
        // 76 + 15*8 = 196 mel frames, which needs 31712 samples (1.982 s @ 16 kHz).
        private val MIN_SAMPLES =
            samplesForFrames(EMB_FRAMES + (WAKE_EMBEDDINGS - 1) * EMB_STRIDE)

        // Pretrained models we ship. Key = the wake phrase (normalized, spaces kept)
        // it verifies; a phrase with no entry simply isn't verified (Vosk-only).
        private val MODELS = mapOf(
            "alexa" to "alexa_v0.1.tflite",
            "hey jarvis" to "hey_jarvis_v0.1.tflite",
        )

        fun modelFor(phrase: String): String? = MODELS[phrase.trim().lowercase()]

        /** Build a verifier for [phrase], or null if we ship no model for it / load fails. */
        fun create(context: Context, phrase: String): OwwVerifier? {
            val asset = modelFor(phrase) ?: return null
            var mel: Interpreter? = null
            var emb: Interpreter? = null
            var wake: Interpreter? = null
            return runCatching {
                val melModel = readAsset(context, "oww/melspectrogram.tflite")
                mel = buildMel(melModel, MEL_DEFAULT_SAMPLES)
                emb = buildInterpreter(readAsset(context, "oww/embedding_model.tflite"))
                wake = buildInterpreter(readAsset(context, "oww/$asset"))
                OwwVerifier(mel!!, melModel, emb!!, wake!!, phrase.trim().lowercase()).also {
                    // Logging the mel output shape is the cheap proof that AllocateTensors
                    // succeeded: expect [1, 1, 237, 32] for the default 2.4 s window.
                    Log.i(
                        TAG,
                        "wake: openWakeWord verifier loaded for '$phrase' ($asset): " +
                            "mel in=[1, $MEL_DEFAULT_SAMPLES] " +
                            "out=${mel!!.getOutputTensor(0).shape().contentToString()}",
                    )
                }
            }.onFailure {
                Log.w(TAG, "wake: openWakeWord load failed for '$phrase': ${it.message}")
                runCatching { mel?.close() }
                runCatching { emb?.close() }
                runCatching { wake?.close() }
            }.getOrNull()
        }

        /** Mel interpreter with its dynamic input dimension pinned to [samples]. */
        private fun buildMel(melModel: ByteArray, samples: Int): Interpreter {
            patchInputShape(melModel, intArrayOf(1, samples))
            return buildInterpreter(melModel)
        }

        /**
         * Construct an interpreter over [model], retrying once without XNNPACK. The
         * delegate is applied inside the constructor, before allocation, and older ARM
         * builds have been known to reject graphs it accepts elsewhere — dropping it
         * costs speed on a conv-heavy model but never correctness.
         */
        private fun buildInterpreter(model: ByteArray): Interpreter {
            fun options(xnnpack: Boolean) = Interpreter.Options().apply {
                setNumThreads(1)              // tiny models; 1 thread avoids fighting the mic loop
                setUseXNNPACK(xnnpack)
            }
            // A fresh direct buffer per attempt: the Interpreter keeps a reference to the
            // one it succeeds with, so buffers must never be shared between interpreters.
            return runCatching { Interpreter(directBuffer(model), options(true)) }
                .getOrElse { failure ->
                    Log.w(TAG, "wake: oww interpreter failed with XNNPACK (${failure.message}) — retrying without")
                    Interpreter(directBuffer(model), options(false))
                }
        }

        private fun readAsset(context: Context, path: String): ByteArray =
            context.assets.open(path).use { it.readBytes() }

        // Assets may be stored compressed, so copy into a direct buffer rather than
        // memory-mapping the APK entry (~4.5 MB total for all three models).
        private fun directBuffer(bytes: ByteArray): ByteBuffer =
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes); rewind()
            }

        /**
         * Rewrite the stored input shape of subgraph 0's first input tensor, in place, in
         * a raw .tflite FlatBuffer. See the class doc for why this has to happen before
         * the Interpreter is constructed.
         *
         * Minimal walk: Model.subgraphs[0] -> .tensors[.inputs[0]] -> .shape. [dims] must
         * have the tensor's existing rank, so nothing is re-serialised and no offset
         * moves; we only overwrite the int32s already in the shape vector. Throws
         * (IllegalArgumentException) if the model doesn't look the way we expect, which
         * the callers' runCatching turns into a clean load failure / -1f score.
         */
        private fun patchInputShape(model: ByteArray, dims: IntArray) {
            // FlatBuffers are always little-endian, regardless of the platform.
            val b = ByteBuffer.wrap(model).order(ByteOrder.LITTLE_ENDIAN)

            // uoffset_t: stored relative to its own position, and added.
            fun deref(p: Int): Int = p + b.getInt(p)

            /** Absolute position of a table field, or -1 when absent (FlatBuffers omit defaults). */
            fun field(table: Int, id: Int): Int {
                val vtable = table - b.getInt(table)          // soffset_t: subtracted
                val vtableBytes = b.getShort(vtable).toInt() and 0xFFFF
                val slot = 4 + 2 * id
                if (slot + 2 > vtableBytes) return -1         // field beyond this vtable
                val voffset = b.getShort(vtable + slot).toInt() and 0xFFFF
                return if (voffset == 0) -1 else table + voffset
            }

            /** Start of a vector field: points at [int32 length][elements...]. -1 when absent. */
            fun vector(table: Int, id: Int): Int {
                val f = field(table, id)
                return if (f < 0) -1 else deref(f)
            }

            val root = deref(0)
            val subgraphs = vector(root, 2)                   // Model.subgraphs
            require(subgraphs >= 0 && b.getInt(subgraphs) >= 1) { "model has no subgraphs" }
            val subgraph = deref(subgraphs + 4)               // subgraphs[0], a vector of tables

            val tensors = vector(subgraph, 0)                 // SubGraph.tensors
            val inputs = vector(subgraph, 1)                  // SubGraph.inputs
            require(tensors >= 0 && inputs >= 0 && b.getInt(inputs) >= 1) { "subgraph has no input" }
            val tensor = deref(tensors + 4 + 4 * b.getInt(inputs + 4))

            val shape = vector(tensor, 0)                     // Tensor.shape
            require(shape >= 0) { "input tensor has no shape vector" }
            val rank = b.getInt(shape)
            require(rank == dims.size) { "input rank is $rank, expected ${dims.size}" }

            for (i in dims.indices) b.putInt(shape + 4 + 4 * i, dims[i])
        }
    }

    /**
     * Point [mel] at a graph built for exactly [samples] input samples. Normally a no-op:
     * the interpreter is already built for the caller's fixed verification window. When it
     * isn't, rebuilding beats resizeInput() — a static graph can't hit the dynamic-shape
     * re-planning paths, and the callers only ever change length once (if at all).
     */
    private fun ensureMelInput(samples: Int) {
        if (samples == melSamples) return
        val rebuilt = buildMel(melModel, samples)   // throws -> caught by score()
        val previous = mel
        mel = rebuilt
        melSamples = samples
        runCatching { previous.close() }
        Log.i(TAG, "wake: oww mel rebuilt for $samples samples (${melFrames(samples)} mel frames)")
    }

    /**
     * Score [audio] (16 kHz mono int16) for this wake word. Returns the best window
     * score in 0..1, or -1 if the buffer is too short / inference fails. Runs on the
     * caller's thread (the wake worker) — typically a few hundred ms for a ~2.4 s buffer.
     */
    @Synchronized
    fun score(audio: ShortArray): Float = runCatching {
        // 16 embedding windows need 196 mel frames, so anything shorter can't produce a
        // single score. Bail before touching the interpreters.
        if (audio.size < MIN_SAMPLES) return -1f

        // ── 1. mel spectrogram over the whole buffer ─────────────────────────
        ensureMelInput(audio.size)
        val input = ByteBuffer.allocateDirect(audio.size * 4).order(ByteOrder.nativeOrder())
        for (s in audio) input.putFloat(s.toFloat())   // raw int16 magnitudes, as openWakeWord does
        input.rewind()
        val melShape = mel.getOutputTensor(0).shape()          // [1, 1, frames, 32]
        val frames = melShape[melShape.size - 2]
        if (frames < EMB_FRAMES + EMB_STRIDE) return -1f
        val melOut = Array(1) { Array(1) { Array(frames) { FloatArray(MEL_BINS) } } }
        mel.run(input, melOut)
        val m = melOut[0][0]
        for (f in 0 until frames) for (b in 0 until MEL_BINS) m[f][b] = m[f][b] / 10f + 2f

        // ── 2. embeddings over sliding 76-frame windows ──────────────────────
        val windows = (frames - EMB_FRAMES) / EMB_STRIDE + 1
        if (windows < WAKE_EMBEDDINGS) return -1f
        val embs = Array(windows) { FloatArray(EMB_FEATURES) }
        val embIn = Array(1) { Array(EMB_FRAMES) { Array(MEL_BINS) { FloatArray(1) } } }
        val embOut = Array(1) { Array(1) { Array(1) { FloatArray(EMB_FEATURES) } } }
        for (w in 0 until windows) {
            val start = w * EMB_STRIDE
            for (f in 0 until EMB_FRAMES) for (b in 0 until MEL_BINS) embIn[0][f][b][0] = m[start + f][b]
            emb.run(embIn, embOut)
            System.arraycopy(embOut[0][0][0], 0, embs[w], 0, EMB_FEATURES)
        }

        // ── 3. wake model over each run of 16 consecutive embeddings ─────────
        val wakeIn = Array(1) { Array(WAKE_EMBEDDINGS) { FloatArray(EMB_FEATURES) } }
        val wakeOut = Array(1) { FloatArray(1) }
        var best = 0f
        for (start in 0..(windows - WAKE_EMBEDDINGS)) {
            for (i in 0 until WAKE_EMBEDDINGS) System.arraycopy(embs[start + i], 0, wakeIn[0][i], 0, EMB_FEATURES)
            wake.run(wakeIn, wakeOut)
            if (wakeOut[0][0] > best) best = wakeOut[0][0]
        }
        best
    }.getOrElse { Log.w(TAG, "wake: oww score failed: ${it.message}"); -1f }

    // Synchronized to match score(), which can swap [mel] out from under a concurrent close.
    @Synchronized
    fun close() {
        runCatching { mel.close() }; runCatching { emb.close() }; runCatching { wake.close() }
    }
}
