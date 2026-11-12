package com.jarves.mh

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jarves.mh.runtime.LinuxEnv

/** One screen: progress and live log until bb answers /health, then bb's own web UI full screen. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var web: WebView
    private lateinit var stop: Button
    private var showLog = true
    private var webLoaded = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0E14"))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f }
        bar.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val toggleLog = Button(this).apply { text = "Log"; setOnClickListener { showLog = !showLog; refresh() } }
        val copy = Button(this).apply {
            text = "Copy log"
            setOnClickListener {
                val clip = getSystemService(android.content.ClipboardManager::class.java)
                clip.setPrimaryClip(android.content.ClipData.newPlainText("BB Host log", BbRunner.logForCopy()))
                android.widget.Toast.makeText(this@MainActivity, "Log copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        val restart = Button(this).apply { text = "Restart"; setOnClickListener { restartBb() } }
        stop = Button(this).apply { text = "Stop"; setOnClickListener { stopOrStart() } }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(toggleLog, copy, restart, stop).forEach { b ->
            b.textSize = 12f
            buttons.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        bar.addView(buttons)
        root.addView(bar)

        val body = FrameLayout(this)
        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#C9D1D9"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(16, 8, 16, 8)
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }
        body.addView(logScroll, FrameLayout.LayoutParams(-1, -1))
        body.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        startForegroundService(Intent(this, BbService::class.java))
        // Samsung and others kill battery-optimized background apps, which stops bb.
        if (!getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName")))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!showLog && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1_000)
        }
    }

    private fun refresh() {
        val state = BbRunner.state
        status.text = "BB Host: ${BbRunner.detail}"
        val running = state == BbRunner.State.RUNNING
        if (running && !webLoaded) {
            webLoaded = true
            showLog = false
            web.loadUrl(LinuxEnv.BB_URL)
        }
        if (!running) webLoaded = false
        val wantLog = showLog || !running
        web.visibility = if (wantLog) View.GONE else View.VISIBLE
        logScroll.visibility = if (wantLog) View.VISIBLE else View.GONE
        if (wantLog) {
            val text = BbRunner.logTail()
            if (text != logView.text.toString()) {
                logView.text = text
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        val active = state == BbRunner.State.SETUP || state == BbRunner.State.STARTING || running
        stop.text = if (active) "Stop" else "Start"
    }

    private fun stopOrStart() {
        val s = BbRunner.state
        if (s == BbRunner.State.SETUP || s == BbRunner.State.STARTING || s == BbRunner.State.RUNNING) {
            startService(Intent(this, BbService::class.java).setAction(BbService.ACTION_STOP))
        } else {
            startForegroundService(Intent(this, BbService::class.java))
        }
        showLog = true
    }

    private fun restartBb() {
        showLog = true
        Thread {
            BbRunner.stop()
            runOnUiThread { startForegroundService(Intent(this, BbService::class.java)) }
        }.start()
    }
}
