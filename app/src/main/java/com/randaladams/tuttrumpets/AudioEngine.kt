package com.randaladams.tuttrumpets

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/** One recorded note: mono PCM plus the loop region used while a note is held. */
class Sample(val data: FloatArray, val rate: Int, val loopStart: Int, val loopEnd: Int)

object SampleLoader {
    /** Reads assets/meta.json and the matching "<id>_sustain.wav" files. */
    fun load(context: Context): Map<String, Sample> {
        val json = context.assets.open("meta.json").bufferedReader().use { it.readText() }
        val meta = org.json.JSONObject(json)
        val out = HashMap<String, Sample>()
        val keys = meta.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val m = meta.getJSONObject(id)
            val bytes = context.assets.open("${id}_sustain.wav").use { it.readBytes() }
            val (data, rate) = readWav(bytes)
            var loopEnd = (m.getDouble("loop_end_s") * rate).toInt()
            loopEnd = min(loopEnd, data.size)
            var loopStart = (m.getDouble("loop_start_s") * rate).toInt()
            loopStart = max(0, min(loopStart, loopEnd - 2))
            out[id] = Sample(data, rate, loopStart, loopEnd)
        }
        return out
    }

    private fun readWav(bytes: ByteArray): Pair<FloatArray, Int> {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 22050
        var channels = 1
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (id == "fmt ") {
                channels = bb.getShort(pos + 10).toInt()
                rate = bb.getInt(pos + 12)
            } else if (id == "data") {
                val avail = min(size, bytes.size - pos - 8)
                val frames = avail / 2 / channels
                val out = FloatArray(frames)
                for (i in 0 until frames) {
                    out[i] = bb.getShort(pos + 8 + i * 2 * channels).toFloat() / 32768f
                }
                return Pair(out, rate)
            }
            pos += 8 + size + (size and 1)
        }
        throw IllegalArgumentException("WAV file has no data chunk")
    }
}

/**
 * Small software mixer on a low-latency AudioTrack.
 * Each trumpet is monophonic, like the real instrument: a new note on the
 * same trumpet quickly fades out the previous one.
 */
class AudioEngine(private val context: Context) {

    private class Voice(val sample: Sample, val group: Int, val id: String) {
        var pos = 0.0
        var gain = 0f
        var attackStep = 1f
        var releaseMul = 1f
        var releasing = false
        var framesPlayed = 0
        var pendingRelease = false
    }

    private val lock = Any()
    private val voices = ArrayList<Voice>()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var outRate = 48000

    fun start() {
        if (running) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        outRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
        val minBytes = AudioTrack.getMinBufferSize(
            outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(outRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBytes, burst * 4 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = t
        running = true
        t.play()
        val th = Thread(Runnable { renderLoop(t, burst) }, "trumpet-audio")
        thread = th
        th.start()
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        track?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                // already stopped
            }
            it.release()
        }
        track = null
        synchronized(lock) { voices.clear() }
    }

    fun noteOn(id: String, group: Int, sample: Sample) {
        synchronized(lock) {
            for (v in voices) {
                if (v.group == group && !v.releasing) release(v, 0.06f)
            }
            val v = Voice(sample, group, id)
            v.attackStep = 1f / (0.003f * outRate)
            voices.add(v)
            while (voices.size > 12) voices.removeAt(0)
        }
    }

    fun noteOff(id: String) {
        synchronized(lock) {
            // A quick tap still plays a short, full-volume note: the release
            // waits until the note has sounded for at least MIN_NOTE_SECONDS.
            for (v in voices) {
                if (v.id == id && !v.releasing) v.pendingRelease = true
            }
        }
    }

    private fun release(v: Voice, seconds: Float) {
        v.releasing = true
        v.releaseMul = exp(ln(SILENCE) / (seconds * outRate))
    }

    private fun renderLoop(t: AudioTrack, burst: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = FloatArray(burst)
        while (running) {
            Arrays.fill(buf, 0f)
            synchronized(lock) { mix(buf) }
            for (i in buf.indices) buf[i] = tanh(buf[i])
            t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
        }
    }

    private fun mix(out: FloatArray) {
        val iter = voices.iterator()
        while (iter.hasNext()) {
            val v = iter.next()
            val s = v.sample
            val d = s.data
            val step = s.rate.toDouble() / outRate
            val loopLen = s.loopEnd - s.loopStart
            var dead = false
            val minFrames = (MIN_NOTE_SECONDS * outRate).toInt()
            for (i in out.indices) {
                if (v.pendingRelease && !v.releasing && v.framesPlayed >= minFrames) {
                    release(v, 0.25f)
                }
                v.framesPlayed++
                if (v.releasing) {
                    v.gain *= v.releaseMul
                    if (v.gain < SILENCE) {
                        dead = true
                        break
                    }
                } else if (v.gain < 1f) {
                    v.gain = min(1f, v.gain + v.attackStep)
                }
                val i0 = v.pos.toInt()
                val frac = (v.pos - i0).toFloat()
                var i1 = i0 + 1
                if (i1 >= s.loopEnd) i1 -= loopLen
                val a = d[i0]
                val b = d[i1]
                out[i] += (a + (b - a) * frac) * v.gain * MASTER
                v.pos += step
                if (v.pos >= s.loopEnd) v.pos -= loopLen
            }
            if (dead) iter.remove()
        }
    }

    companion object {
        private const val MASTER = 0.8f
        private const val SILENCE = 0.0005f
        private const val MIN_NOTE_SECONDS = 0.20f
    }
}
