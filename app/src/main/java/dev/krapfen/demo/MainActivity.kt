package dev.krapfen.demo

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import dev.krapfen.Krapfen

/**
 * Demo for the Krapfen library, using only its public API: edit Strudel
 * code, play/stop it, load bundled examples, adjust volume, and watch the
 * playback status and Strudel's log.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var codeInput: EditText
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var meter: TextView
    private lateinit var log: TextView
    private var lastLoggedStatusAt = 0L

    private val updater = object : Runnable {
        override fun run() {
            val peak = Krapfen.outputPeak
            meter.text = "peak %.3f  %s".format(peak, "█".repeat((peak * 40).toInt().coerceIn(0, 40)))
            Krapfen.status { result ->
                val st = (result as? Krapfen.Result.Success)?.value ?: return@status
                if (!st.playing) return@status
                val text = "cycle %.2f · %.3f cps · events %d · late %d · samples %d"
                    .format(st.cycle, st.cps, st.scheduledEvents, st.lateEvents, st.samples)
                status.text = text
                if (SystemClock.elapsedRealtime() - lastLoggedStatusAt > 5000) {
                    Log.i(TAG, "status: $text")
                    lastLoggedStatusAt = SystemClock.elapsedRealtime()
                }
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = "Krapfen 🍩 ${Krapfen.VERSION}"
            textSize = 28f
            gravity = Gravity.CENTER
        })

        codeInput = EditText(this).apply {
            setText(DEFAULT_CODE)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            maxLines = 10
            setHorizontallyScrolling(true)
        }
        root.addView(codeInput)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "▶ Play"
            setOnClickListener { play() }
        })
        row.addView(Button(this).apply {
            text = "■ Stop"
            setOnClickListener {
                Krapfen.stop()
                status.text = "stopped"
            }
        })
        root.addView(row)

        // Recording, for comparing Krapfen's output with strudel.cc.
        val recRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recRow.addView(Button(this).apply {
            text = "● Record & play"
            setOnClickListener {
                Krapfen.stop()
                // Give stop() a moment so the recording starts with the pattern.
                handler.postDelayed({
                    if (Krapfen.startRecording()) {
                        Log.i(TAG, "recording started")
                        play()
                    }
                }, 300)
            }
        })
        recRow.addView(Button(this).apply {
            text = "Save recording"
            setOnClickListener {
                val file = java.io.File(getExternalFilesDir(null), "krapfen-${System.currentTimeMillis()}.wav")
                Krapfen.stopRecording(file) { result ->
                    val text = when (result) {
                        is Krapfen.Result.Success -> "saved %.1f s to ${file.path}".format(result.value)
                        is Krapfen.Result.Failure -> "recording failed: ${result.message}"
                    }
                    output.text = text
                    Log.i(TAG, text)
                }
            }
        })
        root.addView(recRow)

        // One button per bundled example (assets/krapfen/examples/*.strudel).
        for (name in assets.list(EXAMPLES).orEmpty().filter { it.endsWith(".strudel") }.sorted()) {
            root.addView(Button(this).apply {
                text = "Load example: ${name.removeSuffix(".strudel")}"
                setOnClickListener {
                    codeInput.setText(assets.open("$EXAMPLES/$name").bufferedReader().use { it.readText() })
                }
            })
        }

        root.addView(TextView(this).apply { text = "Volume" })
        root.addView(SeekBar(this).apply {
            max = 150
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    Krapfen.volume = value / 100f
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })

        output = monospace(12f).apply { text = "starting…" }
        status = monospace(12f)
        meter = monospace(12f)
        log = monospace(11f)
        listOf(output, status, meter, log).forEach(root::addView)

        setContentView(ScrollView(this).apply { addView(root) })

        Krapfen.logListener = Krapfen.LogListener { level, message ->
            if (level >= Krapfen.LogLevel.WARN) {
                log.text = (log.text.toString() + "\n$level: $message").takeLast(2000)
            }
        }
        val t0 = SystemClock.elapsedRealtime()
        Krapfen.initialize(this) { result ->
            val ms = SystemClock.elapsedRealtime() - t0
            output.text = when (result) {
                is Krapfen.Result.Success -> "ready ($ms ms)"
                is Krapfen.Result.Failure -> "failed to start: ${result.message}"
            }
            Log.i(TAG, "initialize: $result in $ms ms")
        }
    }

    private fun play() {
        val t0 = SystemClock.elapsedRealtime()
        Krapfen.play(codeInput.text.toString()) { result ->
            val ms = SystemClock.elapsedRealtime() - t0
            output.text = when (result) {
                is Krapfen.Result.Success -> "playing · evaluated in $ms ms · %.1f s/cycle".format(1 / result.value)
                is Krapfen.Result.Failure -> "error: ${result.message}"
            }
            Log.i(TAG, "play: $result in $ms ms")
        }
    }

    private fun monospace(size: Float) = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = size
        setPadding(0, 16, 0, 0)
    }

    override fun onStart() {
        super.onStart()
        Krapfen.resume { Log.i(TAG, "resume: $it") }
        handler.post(updater)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(updater)
        Krapfen.pause()
    }

    companion object {
        private const val TAG = "KrapfenDemo"
        private const val EXAMPLES = "krapfen/examples"
        private const val DEFAULT_CODE = "note(\"c e g\").s(\"sine\")"
    }
}
