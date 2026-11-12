package com.jarves.mh

import android.content.Context
import com.jarves.mh.runtime.LinuxEnv
import com.jarves.mh.runtime.NativeSpawnProcess
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Process-wide owner of the Linux environment and the bb-app process. BbService keeps the process alive. */
object BbRunner {
    enum class State { IDLE, SETUP, STARTING, RUNNING, FAILED, STOPPED }

    @Volatile var state = State.IDLE
        private set
    @Volatile var detail = "Not started"
        private set

    private var logFile: File? = null
    private val lock = Any()
    private var worker: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var bbProcess: Process? = null
    private var env: LinuxEnv? = null

    /** The log for copying: all of it up to [maxChars], otherwise the newest part (the clipboard has a size limit). */
    fun logForCopy(maxChars: Int = 400_000): String {
        val f = logFile ?: return ""
        val text = runCatching { f.readText() }.getOrDefault("")
        return if (text.length <= maxChars) text
        else "[log cut to the last $maxChars characters; the full file is ${f.absolutePath}]\n" + text.takeLast(maxChars)
    }

    fun logTail(maxBytes: Int = 24_000): String {
        val f = logFile ?: return ""
        return runCatching {
            java.io.RandomAccessFile(f, "r").use { r ->
                val n = minOf(r.length(), maxBytes.toLong()).toInt()
                r.seek(r.length() - n)
                ByteArray(n).also(r::readFully).toString(Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    fun log(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        runCatching { logFile?.appendText("$stamp $line\n") }
        android.util.Log.i("BbHost", line)
    }

    /** Starts setup and bb in the background; does nothing if it is already running. */
    fun start(context: Context) {
        synchronized(lock) {
            if (worker?.isAlive == true) return
            val app = context.applicationContext
            env = LinuxEnv(app)
            logFile = File(app.filesDir, "bbhost.log").also {
                // Keep the whole history across restarts; roll over to one backup file at 20 MB.
                if (it.length() > 20_000_000) it.renameTo(File(app.filesDir, "bbhost.log.1"))
            }
            log("---- session start ${java.util.Date()} | BB Host ${BuildConfig.VERSION_NAME} | Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) | ${android.os.Build.MODEL} | ABIs ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            stopRequested = false
            state = State.SETUP
            worker = Thread({ runAll() }, "bbhost-runner").also { it.start() }
        }
    }

    /** Stops bb and waits for the worker. Call it off the main thread. */
    fun stop() {
        stopRequested = true
        val w = worker
        if (w?.isAlive == true) {
            runCatching { env?.stopBbGracefully(::log) }
            bbProcess?.destroy()
            w.join(15_000)
        }
        if (state != State.FAILED) set(State.STOPPED, "Stopped")
    }

    private fun set(s: State, d: String) {
        state = s
        detail = d
        log("== $d")
    }

    private fun runAll() {
        val e = env ?: return
        try {
            set(State.SETUP, "Preparing the Linux environment")
            val cancelled = { stopRequested }
            e.ensureBase(::log)
            e.ensureTools(::log, cancelled)
            e.ensureBb(::log, cancelled)
            if (stopRequested) return
            set(State.STARTING, "Starting bb")
            val process = e.startBb()
            bbProcess = process
            val tail = LinuxEnv.LogTail((process as NativeSpawnProcess).outputFile, ::log)
            var healthy = false
            var lastCheck = 0L
            while (process.isAlive) {
                if (!tail.pump()) Thread.sleep(150)
                if (!healthy && System.currentTimeMillis() - lastCheck > 2_000) {
                    lastCheck = System.currentTimeMillis()
                    if (healthCheck()) {
                        healthy = true
                        set(State.RUNNING, "bb is running at ${LinuxEnv.BB_URL}")
                    }
                }
            }
            while (tail.pump()) Unit
            tail.flush()
            val exit = process.waitFor()
            if (stopRequested) set(State.STOPPED, "Stopped")
            else set(State.FAILED, "bb exited with code $exit. See the log; use Restart to try again.")
        } catch (t: Throwable) {
            if (stopRequested) set(State.STOPPED, "Stopped")
            else set(State.FAILED, "Failed: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            bbProcess = null
        }
    }

    private fun healthCheck(): Boolean = runCatching {
        val c = URL("${LinuxEnv.BB_URL}/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1_500
        c.readTimeout = 1_500
        try {
            c.responseCode == 200 && c.inputStream.bufferedReader().use { it.readText() }.contains("\"ok\":true")
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)
}
