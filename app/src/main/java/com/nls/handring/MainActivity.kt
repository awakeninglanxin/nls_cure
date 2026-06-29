package com.nls.handring

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var engine: TherapyEngine
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var connectBtn: Button
    private lateinit var progContainer: LinearLayout
    private lateinit var powerContainer: LinearLayout
    private lateinit var playBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var loopBtn: Button
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var intervalSeek: SeekBar
    private lateinit var intervalLabel: TextView

    private var selProg: String? = null
    private var selPower: Int? = null
    private var looping = true
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        engine = TherapyEngine(this)
        bindViews()
        buildProgramList()
        setupListeners()

        engine.onStatus = { msg -> handler.post { statusSub.text = msg } }
        engine.onProgress = { cur, total ->
            handler.post {
                val pct = (cur.toFloat() / total * 100).toInt()
                progressBar.progress = pct
            }
        }
    }

    private fun bindViews() {
        statusDot = findViewById(R.id.status_dot)
        statusText = findViewById(R.id.status_text)
        statusSub = findViewById(R.id.status_sub)
        connectBtn = findViewById(R.id.connect_btn)
        progContainer = findViewById(R.id.program_list)
        powerContainer = findViewById(R.id.power_chips)
        playBtn = findViewById(R.id.play_btn)
        stopBtn = findViewById(R.id.stop_btn)
        loopBtn = findViewById(R.id.loop_btn)
        progressBar = findViewById(R.id.progress_bar)
        intervalSeek = findViewById(R.id.interval_seek)
        intervalLabel = findViewById(R.id.interval_label)
    }

    private fun buildProgramList() {
        val keys = TherapyData.programs.keys.sortedBy { TherapyData.programs[it]!!.b9 }
        for (key in keys) {
            val prog = TherapyData.programs[key]!!
            val btn = Button(this).apply {
                text = "${prog.organ}  ${prog.freqMhz}MHz  (${prog.cmds.size})"
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, android.R.color.white))
                setBackgroundResource(android.R.color.transparent)
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                setPadding(32, 20, 32, 20)
                setOnClickListener { selectProgram(key) }
            }
            progContainer.addView(btn)
        }
    }

    private fun selectProgram(key: String) {
        selProg = key
        selPower = null
        buildPowerChips()
        updatePlayButton()
    }

    private fun buildPowerChips() {
        powerContainer.removeAllViews()
        val prog = selProg?.let { TherapyData.programs[it] } ?: return
        val done = mutableSetOf<Int>()
        for (cmd in prog.cmds) {
            if (cmd.b11 in done) continue
            done.add(cmd.b11)
            val chip = Button(this).apply {
                text = "${cmd.b11}"
                textSize = 11f
                setPadding(24, 8, 24, 8)
                setOnClickListener { selectPower(cmd.b11) }
            }
            powerContainer.addView(chip)
        }
    }

    private fun selectPower(v: Int) {
        selPower = if (selPower == v) null else v
        buildPowerChips()
    }

    private fun setupListeners() {
        connectBtn.setOnClickListener {
            if (engine.isConnected) {
                engine.disconnect()
                updateUI()
            } else {
                connectBtn.isEnabled = false
                connectBtn.text = "连接中…"
                engine.connect { ok, msg ->
                    handler.post {
                        connectBtn.isEnabled = true
                        connectBtn.text = if (ok) "断开手环" else "连接手环 (USB OTG)"
                        statusText.text = if (ok) "手环已连接" else "未连接"
                        statusSub.text = if (!ok) msg else ""
                        updateUI()
                    }
                }
            }
        }

        playBtn.setOnClickListener {
            if (engine.isPlaying) { engine.stop(); updateUI(); return@setOnClickListener }
            val key = selProg ?: return@setOnClickListener
            engine.setInterval(intervalSeek.progress.toLong())
            engine.play(key, selPower, looping)
            updateUI()
        }
        stopBtn.setOnClickListener { engine.stop(); updateUI() }
        loopBtn.setOnClickListener { looping = !looping; loopBtn.text = if (looping) "循环" else "单次" }

        intervalSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, b: Boolean) {
                intervalLabel.text = "${v}ms"
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun updateUI() {
        val conn = engine.isConnected
        val play = engine.isPlaying
        statusDot.setBackgroundResource(if (conn) android.R.color.holo_green_light else android.R.color.darker_gray)
        statusText.text = if (conn) "手环已连接" else "未连接"
        connectBtn.text = if (conn) "断开手环" else "连接手环 (USB OTG)"

        playBtn.visibility = if (play) View.GONE else View.VISIBLE
        stopBtn.visibility = if (play) View.VISIBLE else View.GONE
        playBtn.isEnabled = conn && selProg != null

        if (!play) progressBar.progress = 0
    }

    private fun updatePlayButton() {
        playBtn.isEnabled = engine.isConnected && selProg != null
    }

    override fun onDestroy() {
        engine.destroy()
        super.onDestroy()
    }
}
