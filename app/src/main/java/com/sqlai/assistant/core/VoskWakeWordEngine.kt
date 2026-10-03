package com.sqlai.assistant.core

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * v7 M3 - pure wake-word matching rules: case-insensitive + word-boundary
 * (so "mysql"/"sqlserver" never trigger, "SQL stop" does). Kept free of any
 * Android type so unit tests can exercise it directly.
 */
object WakeWordMatcher {

    /** Index of [wakeWord] in [text] at a word boundary, or -1. */
    fun find(text: String, wakeWord: String): Int {
        val t = text.lowercase()
        val w = wakeWord.lowercase().trim()
        if (w.isEmpty() || t.isEmpty()) return -1
        var from = 0
        while (true) {
            val idx = t.indexOf(w, from)
            if (idx < 0) return -1
            val beforeOk = idx == 0 || !t[idx - 1].isLetterOrDigit()
            val end = idx + w.length
            val afterOk = end >= t.length || !t[end].isLetterOrDigit()
            if (beforeOk && afterOk) return idx
            from = idx + 1
        }
    }

    fun containsWakeWord(text: String, wakeWord: String): Boolean = find(text, wakeWord) >= 0

    /** Text after the wake word ("sql open whatsapp" -> "open whatsapp"). */
    fun remainder(text: String, wakeWord: String): String {
        val idx = find(text, wakeWord)
        if (idx < 0) return text.trim()
        return text.substring(idx + wakeWord.length)
            .trim { it.isWhitespace() || it == ',' || it == '.' || it == '!' || it == '?' }
    }
}

/**
 * v7 M3 - always-on, fully offline wake-word engine (Kaldi models via Vosk).
 *
 * Lifecycle:
 *  - [start] spawns one daemon thread: ensure model (download once, resumable,
 *    CRC-verified) -> Recognizer -> acquire mic ([AudioManagerController.MicOwner.VOSK],
 *    retry while the interim STT loop still holds it) -> AudioRecord 16 kHz
 *    mono PCM -> [onReady] -> feed frames forever.
 *  - Every Kaldi partial result is checked against the wake word; on match
 *    [onWake] fires once with the buffered transcript (finals + current
 *    partial) and the service takes the mic for the command burst.
 *  - Any failure (download, model, mic, AudioRecord, missing RECORD_AUDIO)
 *    cleans up and calls [onFailed] exactly once -> service falls back to the
 *    legacy 24/7 STT wake loop, never a regression.
 *  - [stop] is safe to call from any state, releases the mic synchronously.
 */
class VoskWakeWordEngine(
    private val context: Context,
    private val wakeWord: String,
    private val modelLang: String,
    private val onReady: () -> Unit,
    private val onWake: (transcript: String) -> Unit,
    private val onFailed: (reason: String) -> Unit
) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val MODEL_EN = "vosk-model-small-en-in-0.4"
        private const val MODEL_HI = "vosk-model-small-hi-0.22"
        private const val MODEL_BASE = "https://alphacephei.com/vosk/models/"
        private const val MIN_ZIP_BYTES = 500_000L
        private const val MIN_MODEL_BYTES = 1_000_000L

        private val modelLock = Any()

        /** Loaded once per language and shared across engine restarts. */
        @Volatile private var sharedModel: Model? = null
        @Volatile private var sharedLang: String = ""

        private fun modelName(lang: String): String =
            if (lang.equals("hi", ignoreCase = true)) MODEL_HI else MODEL_EN
    }

    @Volatile private var running = false
    @Volatile private var thread: Thread? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var micHeld = false
    @Volatile private var wakeFired = false

    /** Spawn the engine thread (idempotent). Callbacks land on that thread. */
    fun start() {
        if (running) return
        running = true
        wakeFired = false
        thread = Thread({ runLoop() }, "vosk-wake").apply {
            isDaemon = true
            start()
        }
    }

    /** Stop the loop, drop the mic and silence all callbacks. Idempotent. */
    fun stop() {
        running = false
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // Already stopped / never started.
        }
        releaseMic()
        thread = null
    }

    /** True while the recognition loop is alive (watchdog health check). */
    fun isAlive(): Boolean = running && thread?.isAlive == true

    private fun runLoop() {
        var rec: Recognizer? = null
        try {
            val model = ensureModel() ?: return fail("model unavailable")
            rec = Recognizer(model, SAMPLE_RATE.toFloat())
            // Mic handoff: the interim STT loop may still hold it for a few
            // hundred ms - poll until we win (or the engine is stopped).
            while (running && !AudioManagerController.acquireMic(
                    context, AudioManagerController.MicOwner.VOSK
                )
            ) {
                Thread.sleep(300)
            }
            if (!running) return
            micHeld = true

            // Explicit RECORD_AUDIO check (lint MissingPermission + graceful
            // fallback to the STT loop when the user has not granted it yet).
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return fail("RECORD_AUDIO permission missing")
            }
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufBytes = maxOf(minBuf, SAMPLE_RATE * 2) // >= 1 s of PCM16
            val ar = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufBytes
                )
            } catch (e: SecurityException) {
                return fail("mic permission denied")
            }
            recorder = ar
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                return fail("mic init failed")
            }
            ar.startRecording()
            onReady()

            val buf = ShortArray(bufBytes / 2)
            var pending = ""
            while (running) {
                // Call/live paths can preempt VOSK - exit cleanly; the
                // service watchdog restarts us once the mic comes back.
                if (!AudioManagerController.ownsMic(
                        AudioManagerController.MicOwner.VOSK
                    )
                ) break
                val n = ar.read(buf, 0, buf.size)
                if (n < 0) break // record stream stolen / invalidated
                if (n == 0) continue
                val isFinal = rec.acceptWaveForm(buf, n)
                val text = extractText(if (isFinal) rec.result else rec.partialResult)
                val combined = "$pending $text".trim()
                if (!wakeFired && WakeWordMatcher.containsWakeWord(combined, wakeWord)) {
                    wakeFired = true
                    pending = ""
                    if (running) onWake(combined)
                } else if (isFinal && text.isNotBlank()) {
                    // Keep finals so the burst can fall back to this buffer.
                    pending = combined
                }
            }
        } catch (t: Throwable) {
            fail(t.message ?: t.javaClass.simpleName)
        } finally {
            try {
                recorder?.release()
            } catch (e: Exception) {
                // Ignore.
            }
            recorder = null
            releaseMic()
            try {
                rec?.close()
            } catch (e: Exception) {
                // Ignore.
            }
        }
    }

    private fun extractText(json: String): String = try {
        val obj = JSONObject(json)
        val partial = obj.optString("partial")
        if (partial.isNotBlank()) partial else obj.optString("text")
    } catch (e: Exception) {
        ""
    }

    private fun releaseMic() {
        if (!micHeld) return
        micHeld = false
        AudioManagerController.releaseMic(AudioManagerController.MicOwner.VOSK)
    }

    private fun fail(reason: String) {
        val shouldNotify = running
        running = false
        if (shouldNotify) onFailed(reason)
    }

    // ------------------------------------------------------------- models

    private fun ensureModel(): Model? {
        synchronized(modelLock) {
            sharedModel?.let { existing ->
                if (sharedLang == modelLang) return existing
                try {
                    existing.close()
                } catch (e: Exception) {
                    // Old model dropping.
                }
                sharedModel = null
            }
            val dir = File(File(context.filesDir, "models"), modelName(modelLang))
            if (!isValidModelDir(dir)) {
                if (!downloadAndExtract(dir)) return null
            }
            try {
                val model = Model(dir.absolutePath)
                sharedModel = model
                sharedLang = modelLang
                return model
            } catch (t: Throwable) {
                // Corrupt model - wipe and retry from a clean download once.
                dir.deleteRecursively()
                if (!downloadAndExtract(dir)) return null
                return try {
                    val model = Model(dir.absolutePath)
                    sharedModel = model
                    sharedLang = modelLang
                    model
                } catch (t2: Throwable) {
                    null
                }
            }
        }
    }

    private fun isValidModelDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        if (!File(dir, "am").isDirectory || !File(dir, "graph").isDirectory) return false
        var size = 0L
        dir.walkTopDown().forEach { if (it.isFile) size += it.length() }
        return size >= MIN_MODEL_BYTES
    }

    /**
     * Download the model zip with Range-resume into `<name>.zip.part`, then
     * extract it. ZipInputStream verifies every entry CRC (corrupt zip ->
     * delete + return false); path traversal is blocked. Network failures
     * KEEP the .part file so the next attempt resumes.
     */
    private fun downloadAndExtract(target: File): Boolean {
        val root = target.parentFile ?: return false
        root.mkdirs()
        val part = File(root, "${target.name}.zip.part")
        try {
            var existing = if (part.exists()) part.length() else 0L
            if (existing in 1 until MIN_ZIP_BYTES) existing = 0L
            val conn = (URL(MODEL_BASE + target.name + ".zip").openConnection() as HttpURLConnection)
                .apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                }
            val code = conn.responseCode
            val append = existing > 0 && code == 206
            if (code !in 200..299) {
                conn.disconnect()
                return false
            }
            conn.inputStream.use { ins ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(32_768)
                    while (running) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                    }
                    if (!running) {
                        conn.disconnect()
                        return false // keep .part for resume
                    }
                }
            }
            conn.disconnect()
            if (part.length() < MIN_ZIP_BYTES) {
                part.delete()
                return false
            }
        } catch (t: Throwable) {
            // Network/HTTP error - keep .part (resume later), fail this boot.
            return false
        }

        // Extraction errors mean the zip itself is bad - drop it.
        try {
            target.deleteRecursively()
            ZipInputStream(part.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val out = File(root, entry.name)
                    val canonical = out.canonicalPath
                    if (!canonical.startsWith(root.canonicalPath + File.separator)) {
                        throw IOException("zip-slip blocked: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos -> zin.copyTo(fos) }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            part.delete()
        } catch (t: Throwable) {
            part.delete()
            return false
        }
        return isValidModelDir(target)
    }
}
