package com.example.videocaptions

import android.app.Activity
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
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

    private val modelNames = listOf("Whisper Base (~100 MB, faster)", "Whisper Small (~350 MB, most accurate)")
    private val modelIds = listOf("base", "small")
    private val modelBaseUrl = "https://github.com/mounsokdara/offline-video-captions/releases/download/models/"

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
    private lateinit var pickBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var langSpinner: Spinner
    private lateinit var modelSpinner: Spinner
    private var srt = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        modelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modelNames)
        }
        langSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, langNames)
        }
        pickBtn = Button(this).apply { text = "Choose video" }
        saveBtn = Button(this).apply { text = "Save captions (.srt)"; isEnabled = false }
        status = TextView(this).apply { text = "Whisper AI. The model downloads once (needs internet), then everything runs offline. Pick model and spoken language, then a video." }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        output = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(output) }

        root.addView(header("Model"))
        root.addView(modelSpinner)
        root.addView(header("Spoken language"))
        root.addView(langSpinner)
        root.addView(header("Video"))
        root.addView(pickBtn)
        root.addView(saveBtn)
        root.addView(status)
        root.addView(progress)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

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
        val lang = langCodes[langSpinner.selectedItemPosition]
        val modelName = modelIds[modelSpinner.selectedItemPosition]
        pickBtn.isEnabled = false
        saveBtn.isEnabled = false
        langSpinner.isEnabled = false
        modelSpinner.isEnabled = false
        output.text = ""
        progress.progress = 0
        status.text = "Preparing AI model..."
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            try {
                val dir = ensureModel(modelName)
                val cues = transcribe(uri, lang, dir)
                srt = buildSrt(cues)
                runOnUiThread {
                    if (cues.isEmpty()) output.text = "(no speech detected)"
                    status.text = "Done. ${cues.size} captions."
                    progress.progress = 1000
                    saveBtn.isEnabled = cues.isNotEmpty()
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Error: ${e.message}" }
            } finally {
                runOnUiThread {
                    pickBtn.isEnabled = true
                    langSpinner.isEnabled = true
                    modelSpinner.isEnabled = true
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }.start()
    }

    private fun transcribe(uri: Uri, lang: String, modelDir: File): List<Cue> {
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
            assets,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
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
        val window = FloatArray(512)
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
                        window[wn++] = if (cnt > 0) acc.toFloat() / cnt / 32768f else 0f
                        if (wn == 512) {
                            vad.acceptWaveform(window.copyOf())
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

    // Classic Holo section header: small blue caps label with a thin blue rule.
    private fun header(title: String): LinearLayout {
        val d = resources.displayMetrics.density
        val holoBlue = Color.parseColor("#33B5E5")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (12 * d).toInt(), 0, (4 * d).toInt())
            addView(TextView(this@MainActivity).apply {
                text = title.uppercase(Locale.getDefault())
                textSize = 13f
                setTextColor(holoBlue)
                setPadding((4 * d).toInt(), 0, 0, (2 * d).toInt())
            })
            addView(View(this@MainActivity).apply { setBackgroundColor(holoBlue) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (2 * d).toInt()))
        }
    }

    private fun ensureModel(name: String): File {
        val dir = File(filesDir, "whisper-$name")
        dir.mkdirs()
        val files = listOf(
            "encoder.int8.onnx" to "whisper-$name-encoder.int8.onnx",
            "decoder.int8.onnx" to "whisper-$name-decoder.int8.onnx",
            "tokens.txt" to "whisper-$name-tokens.txt",
        )
        for ((local, remote) in files) download(modelBaseUrl + remote, File(dir, local), local)
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
        if (code != 200 && code != 206) throw IOException("Model download failed (HTTP $code). Check your internet connection.")
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
        if (total > 0 && part.length() != total) throw IOException("Download interrupted. Tap Choose video again to resume.")
        if (!part.renameTo(dest)) throw IOException("Could not save model file")
        runOnUiThread { progress.progress = 0 }
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
            val d = dur * p.length / total
            out.add(Cue(t, t + d, p))
            t += d
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
