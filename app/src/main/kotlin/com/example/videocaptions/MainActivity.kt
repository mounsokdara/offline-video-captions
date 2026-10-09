package com.example.videocaptions

import android.app.Activity
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.util.Locale

class MainActivity : Activity() {

    private class Word(val text: String, val start: Double, val end: Double)

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pickBtn: Button
    private lateinit var saveBtn: Button

    @Volatile private var model: Model? = null
    private var srt = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        pickBtn = Button(this).apply { text = "Choose video"; isEnabled = false }
        saveBtn = Button(this).apply { text = "Save captions (.srt)"; isEnabled = false }
        status = TextView(this).apply { text = "Loading offline speech model..." }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        output = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(output) }

        root.addView(pickBtn)
        root.addView(saveBtn)
        root.addView(status)
        root.addView(progress)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        pickBtn.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }
            startActivityForResult(i, REQ_PICK)
        }
        saveBtn.setOnClickListener {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/x-subrip"
                putExtra(Intent.EXTRA_TITLE, "captions.srt")
            }
            startActivityForResult(i, REQ_SAVE)
        }

        Thread {
            try {
                val dir = File(filesDir, "model")
                val marker = File(filesDir, "model.ok")
                if (!marker.exists()) {
                    dir.deleteRecursively()
                    copyAsset("model", dir)
                    marker.writeText("ok")
                }
                model = Model(dir.absolutePath)
                runOnUiThread { status.text = "Ready. Works fully offline (English)."; pickBtn.isEnabled = true }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Model load failed: ${e.message}" }
            }
        }.start()
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
        pickBtn.isEnabled = false
        saveBtn.isEnabled = false
        output.text = ""
        progress.progress = 0
        status.text = "Transcribing..."
        Thread {
            try {
                val words = transcribe(uri)
                srt = buildSrt(words)
                runOnUiThread {
                    output.text = if (srt.isBlank()) "(no speech detected)" else srt
                    status.text = "Done. ${words.size} words."
                    progress.progress = 1000
                    pickBtn.isEnabled = true
                    saveBtn.isEnabled = srt.isNotBlank()
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Error: ${e.message}"; pickBtn.isEnabled = true }
            }
        }.start()
    }

    private fun transcribe(uri: Uri): List<Word> {
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

        val rec = Recognizer(model, 16000f)
        rec.setWords(true)
        val words = ArrayList<Word>()
        val info = MediaCodec.BufferInfo()
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
                    val out = ShortArray((frames / step).toInt() + 2)
                    var count = 0
                    while (pos + step <= frames) {
                        val s = pos.toInt()
                        val e = maxOf((pos + step).toInt(), s + 1)
                        var acc = 0L
                        var cnt = 0
                        for (f in s until minOf(e, frames)) {
                            for (c in 0 until channels) { acc += shorts[f * channels + c]; cnt++ }
                        }
                        out[count++] = if (cnt > 0) (acc / cnt).toShort() else 0
                        pos += step
                    }
                    pos -= frames
                    if (pos < 0) pos = 0.0
                    if (count > 0 && rec.acceptWaveForm(out, count)) collect(rec.result, words)

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
        collect(rec.finalResult, words)
        rec.close()
        codec.stop(); codec.release(); extractor.release()
        return words
    }

    private fun collect(json: String, into: MutableList<Word>) {
        val arr = JSONObject(json).optJSONArray("result") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            into.add(Word(o.getString("word"), o.getDouble("start"), o.getDouble("end")))
        }
    }

    private fun buildSrt(words: List<Word>): String {
        val sb = StringBuilder()
        var idx = 1
        var i = 0
        while (i < words.size) {
            val first = words[i]
            var j = i
            while (j + 1 < words.size && (j - i) < 7 &&
                words[j + 1].start - words[j].end < 0.8 &&
                words[j + 1].end - first.start < 4.5) j++
            val text = words.subList(i, j + 1).joinToString(" ") { it.text }
            sb.append(idx++).append('\n')
                .append(ts(first.start)).append(" --> ").append(ts(words[j].end)).append('\n')
                .append(text).append("\n\n")
            i = j + 1
        }
        return sb.toString()
    }

    private fun ts(sec: Double): String {
        val ms = (sec * 1000).toLong()
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }

    private fun copyAsset(path: String, dest: File) {
        val list = assets.list(path) ?: emptyArray()
        if (list.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(path).use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } }
        } else {
            dest.mkdirs()
            for (name in list) copyAsset("$path/$name", File(dest, name))
        }
    }

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_SAVE = 2
    }
}
