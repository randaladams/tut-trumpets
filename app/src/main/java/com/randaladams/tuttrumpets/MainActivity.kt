package com.randaladams.tuttrumpets

import android.app.Activity
import android.app.AlertDialog
import android.media.AudioManager
import android.os.Bundle
import android.view.WindowManager

class MainActivity : Activity() {

    private lateinit var engine: AudioEngine
    private lateinit var view: TrumpetView

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
        super.onPause()
    }

    private fun showInfo() {
        AlertDialog.Builder(this)
            .setTitle("Tutankhamun's Trumpets")
            .setMessage(INFO_TEXT)
            .setPositiveButton("Close", null)
            .show()
    }

    companion object {
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
