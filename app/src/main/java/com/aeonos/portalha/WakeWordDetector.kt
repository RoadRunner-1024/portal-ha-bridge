package com.aeonos.portalha

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * On-device wake-word listener. Runs a Vosk recognizer with a grammar limited to the
 * wake phrase (keyword = the last word), fed the SAME warm 16 kHz mono mic that
 * [SoundMonitor] already holds in the foreground — so it works on Android 10 Portals,
 * where a headless background mic gets silenced. On a match it calls [onWake]; the
 * service then fires portal-wake's public handoff broadcast to the assistant.
 *
 * The wake word is a config string ([phrase]) — changing it needs no new model, the
 * whole point of using Vosk. The ~40 MB small model is downloaded to filesDir on first
 * start (not bundled) so the APK stays small; it's loaded from there on subsequent runs.
 *
 * Vosk inference runs on a dedicated thread draining [queue]; [feed] (called on
 * SoundMonitor's capture thread) only copies + enqueues, so heavy decoding never stalls
 * the mic loop.
 */
class WakeWordDetector(
    private val context: Context,
    private val onWake: () -> Unit,
    // "<wake phrase> announce" spoken as one utterance → hands-free intercom announce
    // instead of the assistant handoff. Deliberately gated harder than the plain wake
    // (exact phrase, higher floors) — a false announcement interrupts the whole house.
    private val onAnnounce: () -> Unit = {},
    // Fired when the SEPARATE Alexa wake word ([alexaPhrase]) is matched — routes to the
    // revived Amazon Alexa (falcon) client. Independent of [onWake]/Jarvis.
    private val onAlexaWake: () -> Unit = {},
    // "<alexa phrase> stop" spoken as one breath. A LISTEN round-trip can't help here —
    // by the time her mic opens the words are gone (she then listens to the room for ~8s
    // and errors out, killing the music session with it). The service acts on it locally.
    private val onAlexaStop: () -> Unit = {},
) {
    companion object {
        private const val TAG = "PortalHA"
        private const val SAMPLE_RATE = 16000.0f
        private const val FRAME_SAMPLES = 640                 // 40 ms @ 16 kHz
        private const val FIRE_COOLDOWN_MS = 3_000L
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val MODEL_DIR = "vosk-model"            // unpacked model under filesDir
        private const val QUEUE_FRAMES = 50                   // ~2 s of 40 ms frames

        // Accuracy gates, ported from rudysev/portal-wake's WakeMatcher (its on-device-tuned
        // policy). A genuine close-mic "hey jarvis" decodes as a bare "hey jarvis" — no "[unk]",
        // both words ~1.0 confidence. Background audio (TV, a nearby phone call) that assembles a
        // wake shows up contaminated ("[unk] hey jarvis") or with a weak lead — these gates reject it.
        private const val UNK_TOKEN = "[unk]"                 // grammar escape + contamination signal
        private const val KEYWORD_MIN_CONF = 0.60             // "jarvis" must clear this
        private const val LEAD_MIN_CONF = 0.80                // "hey" must clear this
        private const val CLEAN_PHRASE_MAX_WORDS = 3          // a real wake is a short, clean phrase
        private const val ANNOUNCE_WORD = "announce"          // "<phrase> announce" → voice announce
        private const val ANNOUNCE_MIN_CONF = 0.90            // every word must clear this for announce
        private const val STOP_WORD = "stop"                  // "<alexa phrase> stop" → local stop
        // Deliberately laxer than announce: a false "alexa stop" merely pauses playback,
        // and it usually has to decode over loud story/music audio.
        private const val STOP_MIN_CONF = 0.60
        private const val WARMUP_SILENCE_FRAMES = 25          // ~1 s of silence settles the decoder
        private val LEAD_ALIASES = mapOf("hey" to setOf("hey", "hay"))   // Vosk mishears "hey" as "hay"

        // openWakeWord second stage: the last ~2.4 s of mic audio is kept so a Vosk
        // match can be re-scored by a network trained on the actual phrase. 2.4 s
        // covers the phrase plus decode lag (Vosk finalizes an utterance a beat
        // after it ends) and yields several scoring windows.
        private const val VERIFY_BUFFER_SAMPLES = 16000 * 24 / 10

    }

    /** One recognized word + its per-word confidence (−1 when the model gave no score). */
    private data class RecWord(val word: String, val conf: Double)

    // Jarvis wake phrase (empty = Jarvis route disabled). The recognizer is constrained to
    // — and a match requires — the WHOLE phrase ("hey jarvis"): matching only the last word
    // let ordinary speech false-trigger (a one-word grammar maps almost any utterance onto
    // the single keyword, so the assistant's own reply kept re-firing the wake).
    @Volatile var phrase: String = "hey jarvis"
    // Alexa wake phrase (empty = Alexa route disabled) — a SECOND, independent wake word.
    @Volatile var alexaPhrase: String = ""

    private val jarvisTarget get() = phrase.trim().lowercase()
    private val alexaTarget get() = alexaPhrase.trim().lowercase()

    // keyword = the salient last word ("jarvis"); lead = the word before it ("hey"). Precision
    // comes from requiring the lead in front of the keyword, not from the keyword alone.
    private fun wordsOf(p: String) = p.split(' ').filter { it.isNotEmpty() }
    private fun keywordOf(p: String) = wordsOf(p).lastOrNull() ?: ""
    private fun leadOf(p: String) = wordsOf(p).let { if (it.size >= 2) it[it.size - 2] else null }

    private val running = AtomicBoolean(false)
    @Volatile private var ready = false
    private val queue = ArrayBlockingQueue<ShortArray>(QUEUE_FRAMES)
    private var worker: Thread? = null
    private var lastFireMs = 0L

    // ── openWakeWord verification ────────────────────────────────────────────
    // Enabled by the service (Prefs.wakeVerifyEnabled); [verifyThreshold] is the
    // score a Vosk match must reach to fire. Only phrases we ship a model for are
    // verified — any other phrase keeps the Vosk-only behaviour.
    @Volatile var verifyEnabled = true
    @Volatile var verifyThreshold = 0.5f
    private var jarvisVerifier: OwwVerifier? = null
    private var alexaVerifier: OwwVerifier? = null
    // Recent mic audio for the verifier, written on the capture thread and read on
    // the wake worker. Coarse locking is fine — copies happen only on a Vosk match.
    private val verifyBuf = ShortArray(VERIFY_BUFFER_SAMPLES)
    private var verifyPos = 0
    private var verifyFilled = 0
    private val verifyLock = Any()

    /** Whether a wake phrase can be neurally verified (used for logging/settings). */
    fun canVerify(phrase: String) = OwwVerifier.modelFor(phrase) != null

    /**
     * Score the last ~2.4 s of mic audio with every loaded verifier and log the result,
     * without any Vosk match. Purely a diagnostic (DEBUG_OWW_SCORE): it's how you find a
     * sensible threshold for a room — run it while the TV is on to see what the noise
     * floor scores, and right after saying the phrase to see what a real one scores.
     */
    /**
     * Score a 16 kHz mono 16-bit PCM WAV instead of the mic buffer — the offline test
     * harness for the verifier (push known utterances, compare scores). Padded/centred
     * to the standard verification window so it takes the identical code path.
     */
    fun debugScoreFile(path: String) {
        val pcm = runCatching {
            val bytes = java.io.File(path).readBytes()
            // Minimal WAV: find the "data" chunk rather than assuming a 44-byte header.
            var i = 12
            var off = 44; var len = bytes.size - 44
            while (i + 8 <= bytes.size) {
                val id = String(bytes, i, 4, Charsets.US_ASCII)
                val sz = (bytes[i + 4].toInt() and 0xFF) or ((bytes[i + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[i + 6].toInt() and 0xFF) shl 16) or ((bytes[i + 7].toInt() and 0xFF) shl 24)
                if (id == "data") { off = i + 8; len = minOf(sz, bytes.size - off); break }
                i += 8 + sz + (sz and 1)
            }
            val n = len / 2
            ShortArray(n) { k ->
                ((bytes[off + 2 * k].toInt() and 0xFF) or (bytes[off + 2 * k + 1].toInt() shl 8)).toShort()
            }
        }.getOrElse { Log.w(TAG, "wake: oww file '$path' unreadable: ${it.message}"); return }

        // Centre it in a full-length window (silence padded) so short clips still score.
        val buf = ShortArray(VERIFY_BUFFER_SAMPLES)
        val copy = minOf(pcm.size, VERIFY_BUFFER_SAMPLES)
        val at = (VERIFY_BUFFER_SAMPLES - copy) / 2
        System.arraycopy(pcm, 0, buf, at, copy)
        listOfNotNull(jarvisVerifier, alexaVerifier).ifEmpty {
            Log.i(TAG, "wake: oww file — no verifier loaded"); return
        }.forEach { v ->
            val s = v.score(buf)
            Log.i(TAG, "wake: oww FILE '${path.substringAfterLast('/')}' vs '${v.name}' " +
                "score=${"%.4f".format(s)} thr=$verifyThreshold (${pcm.size} samples of audio)")
        }
    }

    fun debugScore() {
        val audio = verifySnapshot()
        if (audio == null) { Log.i(TAG, "wake: oww debug — no audio buffered yet (mic running?)"); return }
        listOfNotNull(jarvisVerifier, alexaVerifier).ifEmpty {
            Log.i(TAG, "wake: oww debug — no verifier loaded (enabled=$verifyEnabled)"); return
        }.forEach { v ->
            val t0 = System.currentTimeMillis()
            val s = v.score(audio)
            Log.i(TAG, "wake: oww debug '${v.name}' score=${"%.4f".format(s)} thr=$verifyThreshold " +
                "(${System.currentTimeMillis() - t0}ms over ${audio.size} samples)")
        }
    }

    // Matches are ignored until this time. The service sets it briefly after a handoff
    // so the assistant's reply (echoed back through the mic) can't immediately re-fire.
    @Volatile private var ignoreUntilMs = 0L
    fun pauseMatching(ms: Long) { ignoreUntilMs = System.currentTimeMillis() + ms }

    fun isRunning() = running.get()

    /** Start (idempotent). Loads/downloads the model off-thread, then decodes from [queue]. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        queue.clear()
        worker = Thread({ run() }, "portal-ha-wake").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        ready = false
        queue.clear()
        worker = null   // the loop sees running=false and exits, closing Vosk
    }

    /** Called on SoundMonitor's capture thread — copy + enqueue only, never blocks. */
    fun feed(buf: ShortArray, n: Int) {
        if (!running.get() || n <= 0) return
        val frame = buf.copyOf(n)
        if (!queue.offer(frame)) { queue.poll(); queue.offer(frame) }   // drop oldest if behind
        if (verifyEnabled) synchronized(verifyLock) {
            for (i in 0 until n) {
                verifyBuf[verifyPos] = buf[i]
                verifyPos = (verifyPos + 1) % VERIFY_BUFFER_SAMPLES
            }
            verifyFilled = minOf(VERIFY_BUFFER_SAMPLES, verifyFilled + n)
        }
    }

    /** Snapshot of the recent audio in chronological order (oldest → newest). */
    private fun verifySnapshot(): ShortArray? = synchronized(verifyLock) {
        if (verifyFilled < VERIFY_BUFFER_SAMPLES) return null   // not enough history yet
        val out = ShortArray(VERIFY_BUFFER_SAMPLES)
        val tail = VERIFY_BUFFER_SAMPLES - verifyPos
        System.arraycopy(verifyBuf, verifyPos, out, 0, tail)
        System.arraycopy(verifyBuf, 0, out, tail, verifyPos)
        out
    }

    // Second-stage check: does a network trained on this exact phrase agree with
    // Vosk? Unverifiable phrases (no shipped model) and a disabled/failed verifier
    // pass through, so behaviour degrades to the previous Vosk-only policy.
    private fun verified(phrase: String, what: String): Boolean {
        if (!verifyEnabled) return true
        val v = when (phrase) {
            jarvisTarget -> jarvisVerifier
            alexaTarget -> alexaVerifier
            else -> null
        } ?: return true
        val audio = verifySnapshot() ?: return true
        val started = System.currentTimeMillis()
        val score = v.score(audio)
        val took = System.currentTimeMillis() - started
        if (score < 0f) return true                       // inference problem — don't block the wake
        val ok = score >= verifyThreshold
        Log.i(TAG, "wake: oww '${v.name}' score=${"%.3f".format(score)} thr=$verifyThreshold ${if (ok) "PASS" else "REJECT"} ($what, ${took}ms)")
        return ok
    }

    private fun run() {
        val model = loadModel() ?: run {
            Log.w(TAG, "wake: model unavailable — wake word disabled")
            running.set(false); return
        }
        // Grammar biases the decoder toward the wake phrase/keyword/lead; "[unk]" absorbs
        // non-matching speech (so look-alikes aren't forced onto the wake) AND is the
        // contamination signal the gate below rejects. setWords gives per-word confidence.
        val entries = LinkedHashSet<String>().apply {
            if (jarvisTarget.isNotEmpty()) {
                add(jarvisTarget); add(keywordOf(jarvisTarget)); leadOf(jarvisTarget)?.let { add(it) }
                add("$jarvisTarget $ANNOUNCE_WORD"); add(ANNOUNCE_WORD)   // bias the announce trigger
            }
            if (alexaTarget.isNotEmpty()) {
                add(alexaTarget); add(keywordOf(alexaTarget)); leadOf(alexaTarget)?.let { add(it) }
                add("$alexaTarget $STOP_WORD"); add(STOP_WORD)   // bias the one-breath stop
            }
            add(UNK_TOKEN)
        }
        val grammar = JSONArray(entries.toList()).toString()
        val recognizer = runCatching { Recognizer(model, SAMPLE_RATE, grammar) }
            .getOrElse { runCatching { Recognizer(model, SAMPLE_RATE) }.getOrNull() }
        if (recognizer == null) {
            Log.w(TAG, "wake: could not create recognizer")
            runCatching { model.close() }; running.set(false); return
        }
        runCatching { recognizer.setWords(true) }
        warmUp(recognizer)          // settle the decoder so the first "hey" isn't dropped
        if (verifyEnabled) {
            if (jarvisTarget.isNotEmpty()) jarvisVerifier = OwwVerifier.create(context, jarvisTarget)
            if (alexaTarget.isNotEmpty()) alexaVerifier = OwwVerifier.create(context, alexaTarget)
        }
        ready = true
        Log.i(TAG, "wake: recognizer ready (jarvis='$jarvisTarget'${if (jarvisVerifier != null) "+oww" else ""}, " +
            "alexa='$alexaTarget'${if (alexaVerifier != null) "+oww" else ""})")
        try {
            while (running.get()) {
                val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                // Only evaluate FINALIZED decodes — partial results are unstable and were a
                // major false-positive source. acceptWaveForm returns true at end-of-utterance.
                if (!recognizer.acceptWaveForm(frame, frame.size)) continue
                val rec = parseResult(recognizer.result)
                if (rec.isEmpty()) continue
                // An utterance carrying "announce" is ONLY ever an announce candidate — if it
                // fails the strict gate it's a near-miss, never a fallback assistant wake
                // (the intent was clearly announce; waking Jarvis instead would be worse).
                val hasAnnounce = rec.any { it.word == ANNOUNCE_WORD }
                val hasStop = rec.any { it.word == STOP_WORD }
                val jw = jarvisTarget; val aw = alexaTarget
                if (jw.isNotEmpty() && hasAnnounce && isAnnounce(rec, jw) && verified(jw, "announce")) {
                    fire(recognizer, rec, "voice announce") { onAnnounce() }
                } else if (aw.isNotEmpty() && hasStop && !hasAnnounce &&
                        isCompound(rec, aw, STOP_WORD, STOP_MIN_CONF) && verified(aw, "alexa stop")) {
                    fire(recognizer, rec, "alexa stop") { onAlexaStop() }
                } else if (aw.isNotEmpty() && !hasAnnounce && isWakeFor(rec, aw) && verified(aw, "alexa wake")) {
                    // NB "[alexa] [stop]" that fails the compound gate still lands here as a
                    // plain wake — in a held turn that's the barge-in, which cuts her speech
                    // anyway (most of what "stop" wanted).
                    fire(recognizer, rec, "alexa wake") { onAlexaWake() }
                } else if (jw.isNotEmpty() && !hasAnnounce && isWakeFor(rec, jw) && verified(jw, "wake")) {
                    fire(recognizer, rec, "wake") { onWake() }
                } else if (rec.any { w -> (jw.isNotEmpty() && w.word == keywordOf(jw)) ||
                        (aw.isNotEmpty() && w.word == keywordOf(aw)) || w.word == ANNOUNCE_WORD }) {
                    // A keyword decoded but a gate rejected it — log the near-miss for tuning.
                    Log.i(TAG, "wake: near-miss [${render(rec)}] (rejected)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake: loop error: ${e.message}")
        } finally {
            ready = false
            runCatching { recognizer.close() }
            runCatching { model.close() }
            jarvisVerifier?.close(); jarvisVerifier = null
            alexaVerifier?.close(); alexaVerifier = null
            Log.i(TAG, "wake: stopped")
        }
    }

    // Parse a Vosk final-result JSON into (word, confidence). Prefers the per-word "result"
    // array (from setWords); falls back to the plain transcript with unknown confidence (-1).
    private fun parseResult(json: String): List<RecWord> {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arr = obj.optJSONArray("result")
        if (arr != null && arr.length() > 0) {
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                RecWord(o.optString("word").lowercase(), o.optDouble("conf", -1.0))
            }
        }
        val text = obj.optString("text").trim()
        if (text.isEmpty()) return emptyList()
        return text.lowercase().split(Regex("\\s+")).map { RecWord(it, -1.0) }
    }

    // Shared trigger path: cooldown + ignore-window gate, then reset the decoder and fire.
    private inline fun fire(recognizer: Recognizer, rec: List<RecWord>, what: String, action: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now < ignoreUntilMs || now - lastFireMs < FIRE_COOLDOWN_MS) return
        lastFireMs = now
        Log.i(TAG, "wake: matched [${render(rec)}] -> firing $what")
        recognizer.reset()
        warmUp(recognizer)
        queue.clear()
        runCatching { action() }
    }

    // The announce decision — deliberately the strictest gate in the app: the decode must be
    // EXACTLY "<lead> <keyword> announce" (nothing before/after, so any [unk] fails by length),
    // and EVERY word must clear ANNOUNCE_MIN_CONF (real close-mic decodes score ~1.00; every
    // live false positive we've caught had at least one weak word). Bias: a missed announce
    // costs a repeat; a false one interrupts the whole house.
    private fun isAnnounce(rec: List<RecWord>, phrase: String): Boolean =
        isCompound(rec, phrase, ANNOUNCE_WORD, ANNOUNCE_MIN_CONF)

    // Exact "<phrase> <suffix>" — nothing before/after (any [unk] fails by length) and every
    // word over [minConf]. Shared by announce and the one-breath alexa stop.
    private fun isCompound(rec: List<RecWord>, phrase: String, suffix: String, minConf: Double): Boolean {
        val expected = wordsOf(phrase) + suffix                    // e.g. [hey, jarvis, announce]
        if (rec.size != expected.size) return false
        val leadIdx = expected.size - 3                            // lead position (when phrase has one)
        for (i in expected.indices) {
            val ok = rec[i].word == expected[i] ||
                (i == leadIdx && rec[i].word in (LEAD_ALIASES[expected[i]] ?: emptySet()))
            if (!ok) return false
            if (rec[i].conf < minConf) return false                // unknown conf (-1) fails too
        }
        return true
    }

    // The wake decision (port of portal-wake's strict route), for a GIVEN phrase: the keyword
    // must be present, preceded by a confident lead (if the phrase has one), in a short phrase
    // with NO "[unk]" contamination. Single-word phrases (e.g. "alexa") have no lead check.
    private fun isWakeFor(rec: List<RecWord>, phrase: String): Boolean {
        val kw = keywordOf(phrase)
        if (kw.isEmpty()) return false
        val i = rec.indexOfLast { it.word == kw }
        if (i < 0) return false
        if (rec.any { it.word == UNK_TOKEN }) return false                     // contamination → reject
        if (rec.size > CLEAN_PHRASE_MAX_WORDS) return false                    // must be a clean phrase
        val leads = leadOf(phrase)?.let { LEAD_ALIASES[it] ?: setOf(it) }
        if (leads != null) {                                                   // confident lead in front
            val before = rec.subList(0, i)
            if (before.none { it.word in leads && (it.conf < 0 || it.conf >= LEAD_MIN_CONF) }) return false
        }
        if (rec[i].conf >= 0.0 && rec[i].conf < KEYWORD_MIN_CONF) return false  // keyword over its floor
        return true
    }

    /** Render a decode as `word(conf%)` tokens for the fire / near-miss logs. */
    private fun render(rec: List<RecWord>): String =
        rec.joinToString(" ") { if (it.conf < 0) it.word else "${it.word}(${(it.conf * 100).toInt()})" }

    /** Feed ~1 s of silence so Kaldi's online decoder settles — otherwise the first "hey" after a
     *  (re)start or a reset is dropped, causing "no 'hey'" near-misses. Does NOT reset afterward
     *  (that would undo the settling); the trailing silence is harmless in the next utterance. */
    private fun warmUp(rec: Recognizer) {
        runCatching {
            val silence = ShortArray(FRAME_SAMPLES)
            repeat(WARMUP_SILENCE_FRAMES) { rec.acceptWaveForm(silence, silence.size) }
        }
    }

    private fun loadModel(): Model? {
        val dir = File(context.filesDir, MODEL_DIR)
        if (!File(dir, "am").exists()) {
            Log.i(TAG, "wake: model not present — downloading (~40 MB, one time)")
            if (!downloadAndUnzip(dir)) return null
        }
        return runCatching { Model(dir.absolutePath) }
            .onFailure { Log.w(TAG, "wake: model load failed: ${it.message}") }
            .getOrNull()
    }

    // Download the model zip and unpack it into [targetDir], stripping the archive's
    // top-level folder. Unpacks to a .tmp dir first, then renames, so a half-finished
    // download is never treated as a valid model.
    private fun downloadAndUnzip(targetDir: File): Boolean {
        val tmp = File(targetDir.parentFile, "$MODEL_DIR.tmp")
        runCatching { tmp.deleteRecursively() }
        tmp.mkdirs()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "wake: model download HTTP ${conn.responseCode}"); return false
            }
            ZipInputStream(conn.inputStream.buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val rel = entry.name.substringAfter('/', "")   // strip top-level folder
                    if (rel.isNotEmpty()) {
                        val out = File(tmp, rel)
                        if (entry.isDirectory) out.mkdirs()
                        else { out.parentFile?.mkdirs(); out.outputStream().use { zin.copyTo(it) } }
                    }
                    zin.closeEntry(); entry = zin.nextEntry
                }
            }
            if (!File(tmp, "am").exists()) { Log.w(TAG, "wake: unpacked model missing 'am/'"); return false }
            runCatching { targetDir.deleteRecursively() }
            val ok = tmp.renameTo(targetDir)
            Log.i(TAG, "wake: model ready ($ok) at ${targetDir.absolutePath}")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "wake: model download failed: ${e.message}"); false
        } finally {
            conn?.disconnect()
            runCatching { if (tmp.exists()) tmp.deleteRecursively() }
        }
    }
}
