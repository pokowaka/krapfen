package dev.krapfen

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
import android.widget.TextView

/**
 * Proof-of-concept screen.
 *
 * Top: Strudel code evaluated in the embedded QuickJS engine, showing the
 * resulting events. Bottom: buttons that send raw dough messages, and a live
 * peak meter showing that the native synth is producing audio.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var engine: StrudelEngine
    private lateinit var codeInput: EditText
    private lateinit var output: TextView
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
            setPadding(48, 64, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "Krapfen 🍩"
            textSize = 28f
            gravity = Gravity.CENTER
        })

        // --- Strudel ---
        root.addView(sectionTitle("Strudel (QuickJS)"))
        codeInput = EditText(this).apply {
            setText(DEFAULT_CODE)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            maxLines = 8
            setHorizontallyScrolling(true)
        }
        root.addView(codeInput)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Evaluate"
            setOnClickListener { evaluate(codeInput.text.toString()) }
        })
        row.addView(Button(this).apply {
            text = "Load “A Conversation”"
            setOnClickListener {
                val code = assets.open("krapfen/examples/conversation.strudel")
                    .bufferedReader().use { it.readText() }
                codeInput.setText(code)
                evaluate(code)
            }
        })
        root.addView(row)

        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            text = "loading Strudel…"
        }
        root.addView(output)

        // --- dough ---
        root.addView(sectionTitle("dough (native synth)"))
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
            typeface = Typeface.MONOSPACE
            setPadding(0, 32, 0, 0)
        }
        root.addView(meter)

        setContentView(ScrollView(this).apply { addView(root) })

        engine = StrudelEngine(applicationContext)
        val t0 = SystemClock.elapsedRealtime()
        engine.init { result ->
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "Strudel init: $result in $ms ms")
            handler.post {
                output.text = when (result) {
                    is StrudelEngine.Result.Ok -> "Strudel ready ($ms ms)"
                    is StrudelEngine.Result.Error -> "Strudel failed to load:\n${result.message}"
                }
            }
        }
    }

    private fun evaluate(code: String) {
        output.text = "evaluating…"
        val t0 = SystemClock.elapsedRealtime()
        engine.evaluate(code) { result ->
            val evalMs = SystemClock.elapsedRealtime() - t0
            when (result) {
                is StrudelEngine.Result.Error -> {
                    Log.w(TAG, "evaluate error: ${result.message}")
                    handler.post { output.text = "error: ${result.message}" }
                }
                is StrudelEngine.Result.Ok -> {
                    val cps = result.value
                    val t1 = SystemClock.elapsedRealtime()
                    engine.query(0.0, 1.0) { events ->
                        val queryMs = SystemClock.elapsedRealtime() - t1
                        val text = formatEvents(events, cps, evalMs, queryMs)
                        Log.i(TAG, text)
                        handler.post { output.text = text }
                    }
                }
            }
        }
    }

    private fun formatEvents(
        result: StrudelEngine.Result<List<StrudelEngine.Event>>,
        cps: Double,
        evalMs: Long,
        queryMs: Long,
    ): String = when (result) {
        is StrudelEngine.Result.Error -> "query error: ${result.message}"
        is StrudelEngine.Result.Ok -> buildString {
            val events = result.value
            append("evaluated in $evalMs ms · cps %.4f (%.1f s/cycle)\n".format(cps, 1 / cps))
            append("cycle 0: ${events.size} events, queried in $queryMs ms\n")
            for (e in events.sortedBy { it.begin }.take(MAX_SHOWN)) {
                val v = e.value
                val note = if (v.has("note")) "note %.1f".format(v.getDouble("note")) else ""
                append("  %.3f +%.3f  %-9s %s\n".format(e.begin, e.duration, v.optString("s", "?"), note))
            }
            if (events.size > MAX_SHOWN) append("  … ${events.size - MAX_SHOWN} more\n")
        }
    }

    private fun sectionTitle(label: String) = TextView(this).apply {
        text = label
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 40, 0, 8)
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
        private const val MAX_SHOWN = 12
        private const val DEFAULT_CODE = "note(\"c e g\").s(\"sine\")"
    }
}
