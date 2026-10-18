package dev.krapfen

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Proof-of-concept screen: a few buttons that send dough messages and a
 * live peak meter showing that the native engine is producing audio.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var meter: TextView
    private var lastLoggedPeakAt = 0L

    private val meterUpdater = object : Runnable {
        override fun run() {
            val peak = Native.peak()
            val bars = (peak * 40).toInt().coerceIn(0, 40)
            meter.text = "peak %.3f  %s".format(peak, "█".repeat(bars))

            // Log audible output at most once a second so it can be verified
            // from logcat without flooding it.
            val now = System.currentTimeMillis()
            if (peak > 0.001f && now - lastLoggedPeakAt > 1000) {
                Log.i(TAG, "output peak=%.3f".format(peak))
                lastLoggedPeakAt = now
            }
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "Krapfen 🍩"
            textSize = 32f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "dough synth engine, native on Android"
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        })

        fun button(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            })
        }

        button("Sine 440 Hz (2 s)") {
            send("dough/play/sound/sine/freq/440/duration/2/gain/0.5")
        }
        button("A minor chord (saw, lpf)") {
            for (freq in listOf(220.0, 261.63, 329.63)) {
                send("dough/play/sound/sawtooth/freq/$freq/duration/2/gain/0.25/lpf/900/release/1")
            }
        }
        button("Triangle + delay + reverb") {
            send("dough/play/sound/triangle/freq/659.25/duration/0.3/gain/0.4/delay/0.5/delaytime/0.375/delayfeedback/0.55/room/0.6")
        }
        button("Hush") {
            send("dough/hush")
        }

        meter = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 48, 0, 0)
        }
        root.addView(meter)

        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        val result = Native.start()
        Log.i(TAG, "Native.start() = $result")
        handler.post(meterUpdater)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(meterUpdater)
        Native.stop()
    }

    private fun send(message: String) {
        Log.i(TAG, "send: $message")
        Native.evaluate(message)
    }

    companion object {
        private const val TAG = "Krapfen"
    }
}
