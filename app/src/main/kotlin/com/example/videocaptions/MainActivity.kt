package com.example.videocaptions

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.Locale

class MainActivity : Activity() {

    private class Cue(val start: Double, val end: Double, val text: String)

    private val repoUrl = "https://github.com/mounsokdara/offline-video-captions/releases/download/"
    private val modelNames = listOf("Whisper Base (~160 MB, faster)", "Whisper Small (~375 MB, most accurate)")
    private val modelIds = listOf("base", "small")
    private val langNames = listOf(
        "Auto-detect", "English", "Khmer", "Chinese", "Japanese", "Korean", "French",
        "German", "Spanish", "Russian", "Arabic", "Hindi", "Thai", "Vietnamese", "Indonesian"
    )
    private val langCodes = listOf(
        "", "en", "km", "zh", "ja", "ko", "fr", "de", "es", "ru", "ar", "hi", "th", "vi", "id"
    )

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var progress: ProgressBar
    private lateinit var modelBtn: Button
    private lateinit var langBtn: Button
    private lateinit var pickBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var cleanBtn: Button
    private var modelIdx = 0
    private var langIdx = 0
    private var srt = ""
    private var nativeLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (14 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun button(t: String) = Button(this).apply { text = t; isAllCaps = false }

        val intro = TextView(this).apply {
            text = "Welcome! Now you need to:"
            textSize = 18f
            setPadding((4 * d).toInt(), 0, 0, (6 * d).toInt())
        }
        modelBtn = button("")
        langBtn = button("")
        pickBtn = button("3. Choose video (make captions)")
        saveBtn = button("Save captions (.srt)").apply { isEnabled = false }
        cleanBtn = button("Delete downloaded data")
        status = TextView(this).apply {
            text = "AI model downloads once (internet needed), then works offline."
            setPadding((4 * d).toInt(), (10 * d).toInt(), 0, (4 * d).toInt())
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        output = TextView(this).apply {
            hint = "Captions appear here."
            textSize = 17f
            setTextColor(Color.parseColor("#222222"))
            setHintTextColor(Color.parseColor("#777777"))
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            minHeight = (140 * d).toInt()
            setTextIsSelectable(true)
        }
        refreshLabels()

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (2 * d).toInt() }
        root.addView(intro)
        for (b in listOf(modelBtn, langBtn, pickBtn, saveBtn, cleanBtn)) root.addView(b, lp)
        root.addView(status)
        root.addView(progress)
        root.addView(output, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (8 * d).toInt() })
        setContentView(ScrollView(this).apply { addView(root) })

        modelBtn.setOnClickListener { choose("Model", modelNames, modelIdx) { modelIdx = it; refreshLabels() } }
        langBtn.setOnClickListener { choose("Spoken language", langNames, langIdx) { langIdx = it; refreshLabels() } }
        pickBtn.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }, REQ_PICK)
        }
        saveBtn.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/x-subrip"
                putExtra(Intent.EXTRA_TITLE, "captions.srt")
            }, REQ_SAVE)
        }
        cleanBtn.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Delete downloaded data?")
                .setMessage("Removes the downloaded AI model and runtime. They will be downloaded again when needed.")
                .setPositiveButton("Delete") { _, _ ->
                    filesDir.listFiles()?.forEach { it.deleteRecursively() }
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun refreshLabels() {
        modelBtn.text = "1. Model: ${modelNames[modelIdx].substringBefore(" (")}"
        langBtn.text = "2. Spoken language: ${langNames[langIdx]}"
    }

    private fun choose(title: String, items: List<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), current) { dlg, which -> onPick(which); dlg.dismiss() }
            .show()
    }

    private fun setBusy(busy: Boolean) {
        for (b in listOf(modelBtn, langBtn, pickBtn, cleanBtn)) b.isEnabled = !busy
        if (busy) saveBtn.isEnabled = false
        if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQ_PICK -> startTranscription(uri)
            REQ_SAVE -> try {
                contentResolver.openOutputStream(uri)?.use { it.write(srt.toByteArray()) }
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startTranscription(uri: Uri) {
        val lang = langCodes[langIdx]
        val modelName = modelIds[modelIdx]
        setBusy(true)
        output.text = ""
        progress.progress = 0
        status.text = "Preparing..."
        Thread {
            try {
                ensureRuntime()
                val dir = ensureModel(modelName)
                val cues = transcribe(uri, lang, dir)
                srt = buildSrt(cues)
                runOnUiThread {
                    if (cues.isEmpty()) output.text = "(no speech detected)"
                    status.text = "Done. ${cues.size} captions."
                    progress.progress = 1000
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Error: ${e.message}" }
            } finally {
                runOnUiThread {
                    setBusy(false)
                    saveBtn.isEnabled = srt.isNotBlank()
                }
            }
        }.start()
    }

    // ---- downloads -------------------------------------------------------------------------

    private fun ensureRuntime() {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64") }
            ?: throw IllegalStateException("Unsupported CPU: ${Build.SUPPORTED_ABIS.joinToString()}")
        val dir = File(filesDir, "runtime-$abi").apply { mkdirs() }
        val ort = File(dir, "libonnxruntime.so")
        val jni = File(dir, "libsherpa-onnx-jni.so")
        download(repoUrl + "runtime/$abi-libonnxruntime.so", ort, "AI runtime 1/3")
        download(repoUrl + "runtime/$abi-libsherpa-onnx-jni.so", jni, "AI runtime 2/3")
        download(repoUrl + "runtime/silero_vad.onnx", File(filesDir, "silero_vad.onnx"), "voice detector 3/3")
        if (!nativeLoaded) {
            System.load(ort.path)
            System.load(jni.path)
            nativeLoaded = true
        }
    }

    private fun ensureModel(name: String): File {
        val dir = File(filesDir, "whisper-$name").apply { mkdirs() }
        for (f in listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")) {
            download(repoUrl + "models/whisper-$name-" + f, File(dir, f), "$name $f")
        }
        return dir
    }

    private fun download(url: String, dest: File, label: String) {
        if (dest.exists()) return
        val part = File(dest.path + ".part")
        var existing = if (part.exists()) part.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
        conn.connect()
        val code = conn.responseCode
        if (code == 416) { part.renameTo(dest); return }
        if (code != 200 && code != 206) throw IOException("Download failed (HTTP $code). Check your internet connection.")
        val append = code == 206
        if (!append) existing = 0
        val remaining = conn.contentLengthLong
        val total = if (remaining > 0) existing + remaining else -1L
        var done = existing
        var lastUi = 0L
        FileOutputStream(part, append).use { out ->
            conn.inputStream.use { inp ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 300) {
                        lastUi = now
                        val mb = done / 1_048_576
                        val msg = if (total > 0) "Downloading $label: $mb / ${total / 1_048_576} MB" else "Downloading $label: $mb MB"
                        val p = if (total > 0) (done * 1000 / total).toInt().coerceIn(0, 1000) else 0
                        runOnUiThread { status.text = msg; progress.progress = p }
                    }
                }
            }
        }
        if (total > 0 && part.length() != total) throw IOException("Download interrupted. Tap again to resume.")
        if (!part.renameTo(dest)) throw IOException("Could not save file")
        if (dest.name.endsWith(".so")) dest.setReadOnly()
        runOnUiThread { progress.progress = 0 }
    }

    // ---- transcription ---------------------------------------------------------------------

    private fun transcribe(uri: Uri, lang: String, modelDir: File): List<Cue> {
        runOnUiThread { status.text = "Loading AI model..." }
        val rec = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = File(modelDir, "encoder.int8.onnx").path,
                        decoder = File(modelDir, "decoder.int8.onnx").path,
                        language = lang,
                        task = "transcribe",
                        tailPaddings = 1000,
                    ),
                    tokens = File(modelDir, "tokens.txt").path,
                    numThreads = 4,
                    provider = "cpu",
                    modelType = "whisper",
                ),
            )
        )
        val vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = File(filesDir, "silero_vad.onnx").path,
                    threshold = 0.5f,
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 25f,
                ),
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu",
            )
        )
        runOnUiThread { status.text = "Transcribing..." }

        val cues = ArrayList<Cue>()

        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front()
                vad.pop()
                val stream = rec.createStream()
                stream.acceptWaveform(seg.samples, 16000)
                rec.decode(stream)
                val text = rec.getResult(stream).text.trim()
                stream.release()
                if (text.isNotEmpty()) {
                    val before = cues.size
                    addCues(seg.start / 16000.0, seg.samples.size / 16000.0, text, cues)
                    val added = cues.subList(before, cues.size).joinToString("\n") { "[${ts(it.start)}] ${it.text}" }
                    runOnUiThread { output.append(added + "\n") }
                }
            }
        }

        val extractor = MediaExtractor()
        extractor.setDataSource(this, uri, null)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
        }
        if (track < 0 || fmt == null) throw IllegalStateException("No audio track in this video")
        extractor.selectTrack(track)

        val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        val win = FloatArray(512)
        var wn = 0
        var inputDone = false
        var outputDone = false
        var pos = 0.0
        var lastUi = 0L

        while (!outputDone) {
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            if (oi >= 0) {
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(oi)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val total = sb.remaining()
                    val shorts = ShortArray(total)
                    sb.get(shorts)
                    val frames = total / channels
                    val step = rate / 16000.0
                    while (pos + step <= frames) {
                        val s = pos.toInt()
                        val e = maxOf((pos + step).toInt(), s + 1)
                        var acc = 0L
                        var cnt = 0
                        for (f in s until minOf(e, frames)) {
                            for (c in 0 until channels) { acc += shorts[f * channels + c]; cnt++ }
                        }
                        win[wn++] = if (cnt > 0) acc.toFloat() / cnt / 32768f else 0f
                        if (wn == 512) {
                            vad.acceptWaveform(win.copyOf())
                            wn = 0
                            drain()
                        }
                        pos += step
                    }
                    pos -= frames
                    if (pos < 0) pos = 0.0

                    val now = System.currentTimeMillis()
                    if (durationUs > 0 && now - lastUi > 300) {
                        lastUi = now
                        val p = (info.presentationTimeUs * 1000L / durationUs).toInt().coerceIn(0, 999)
                        runOnUiThread { progress.progress = p }
                    }
                }
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val nf = codec.outputFormat
                rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                pos = 0.0
            }
        }
        vad.flush()
        drain()
        codec.stop(); codec.release(); extractor.release()
        vad.release()
        rec.release()
        return cues
    }

    private fun addCues(t0: Double, dur: Double, text: String, out: MutableList<Cue>) {
        val parts = ArrayList<String>()
        if (text.contains(' ')) {
            var cur = StringBuilder()
            var n = 0
            for (w in text.split(Regex("\\s+"))) {
                if (cur.isNotEmpty() && (n >= 9 || cur.length + w.length > 42)) {
                    parts.add(cur.toString()); cur = StringBuilder(); n = 0
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(w); n++
            }
            if (cur.isNotEmpty()) parts.add(cur.toString())
        } else {
            text.chunked(24).forEach { parts.add(it) }
        }
        val total = parts.sumOf { it.length }.toDouble().coerceAtLeast(1.0)
        var t = t0
        for (p in parts) {
            val dd = dur * p.length / total
            out.add(Cue(t, t + dd, p))
            t += dd
        }
    }

    private fun buildSrt(cues: List<Cue>): String {
        val sb = StringBuilder()
        cues.forEachIndexed { i, c ->
            sb.append(i + 1).append('\n')
                .append(ts(c.start, true)).append(" --> ").append(ts(c.end, true)).append('\n')
                .append(c.text).append("\n\n")
        }
        return sb.toString()
    }

    private fun ts(sec: Double, srtFormat: Boolean = false): String {
        val ms = (sec * 1000).toLong()
        return if (srtFormat)
            String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        else
            String.format(Locale.US, "%02d:%02d:%02d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60)
    }

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_SAVE = 2
    }
}
