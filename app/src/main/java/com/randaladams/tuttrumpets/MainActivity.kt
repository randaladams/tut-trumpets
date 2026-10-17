package com.randaladams.tuttrumpets

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var engine: AudioEngine
    private lateinit var view: TrumpetView
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        engine = AudioEngine(this)
        val samples = SampleLoader.load(this)
        view = TrumpetView(this, engine, samples) { showInfo() }
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        engine.start()
    }

    override fun onPause() {
        view.releaseAll()
        engine.stop()
        stopRecording()
        super.onPause()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showInfo() {
        val body = TextView(this).apply {
            text = INFO_TEXT
            textSize = 15f
            setLineSpacing(0f, 1.15f)
        }

        val playButton = Button(this).apply {
            text = PLAY_LABEL
            isAllCaps = false
        }
        playButton.setOnClickListener {
            if (player == null) {
                startRecording { playButton.text = PLAY_LABEL }
                if (player != null) playButton.text = STOP_LABEL
            } else {
                stopRecording()
                playButton.text = PLAY_LABEL
            }
        }

        val linksHeader = TextView(this).apply {
            text = "Read more"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(4))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(playButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) })
            addView(body)
            addView(linksHeader)
            addView(linkView("Tutankhamun's trumpets (Wikipedia)", WIKIPEDIA_URL))
            addView(linkView("Recreating the sound of Tutankhamun's trumpets (BBC News)", BBC_URL))
        }

        // A thin rule under the dialog title so scrolled text doesn't run into it.
        val divider = View(this).apply { setBackgroundColor(0x33FFFFFF) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
            })
            addView(ScrollView(this@MainActivity).apply { addView(layout) })
        }

        AlertDialog.Builder(this)
            .setTitle("Tutankhamun's Trumpets")
            .setView(container)
            .setPositiveButton("Close", null)
            .setOnDismissListener { stopRecording() }
            .show()
    }

    private fun linkView(label: String, url: String): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(0xFF7FA7FF.toInt())
        paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        gravity = Gravity.START
        setPadding(0, dp(8), 0, dp(8))
        setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, "No browser found to open $url", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startRecording(onFinished: () -> Unit) {
        val mp = MediaPlayer.create(this, R.raw.bbc_1939_broadcast)
        if (mp == null) {
            Toast.makeText(this, "Couldn't play the recording", Toast.LENGTH_SHORT).show()
            return
        }
        mp.setOnCompletionListener {
            stopRecording()
            onFinished()
        }
        mp.start()
        player = mp
    }

    private fun stopRecording() {
        player?.let {
            it.stop()
            it.release()
        }
        player = null
    }

    companion object {
        private const val PLAY_LABEL = "▶  Play the full 1939 broadcast (3:14)"
        private const val STOP_LABEL = "■  Stop the broadcast"
        private const val WIKIPEDIA_URL = "https://en.wikipedia.org/wiki/Tutankhamun%27s_trumpets"
        private const val BBC_URL = "https://www.bbc.co.uk/news/world-middle-east-13092827"

        private const val INFO_TEXT =
            "Two trumpets, one of silver and one of copper alloy, were found in " +
                "Tutankhamun's tomb by Howard Carter's team in the 1920s.\n\n" +
                "On 16 April 1939 the BBC broadcast them being played live from the " +
                "Cairo Museum by bandsman James Tappern, using a modern mouthpiece.\n\n" +
                "Every note in this app was cut from that broadcast recording. The " +
                "pitches are as recorded: the silver trumpet gives three notes of a " +
                "natural harmonic series, and the copper trumpet a different, less " +
                "regular set. The copper trumpet's rare low note was played only " +
                "once in the broadcast.\n\n" +
                "How to play: press and hold a note. Slide your finger to change " +
                "notes. Each trumpet plays one note at a time, like the real thing."
    }
}
