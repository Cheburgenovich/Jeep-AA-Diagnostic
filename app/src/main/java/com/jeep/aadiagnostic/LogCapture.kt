package com.jeep.aadiagnostic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures accessible Android logcat while the monitor service is running.
 * READ_LOGS normally requires adb pm grant. Never silently enables capture.
 */
object LogCapture {
    private val running = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    @Volatile private var worker: Thread? = null
    private const val MAX_BYTES = 24L * 1024 * 1024
    private val keywords = Regex("android.?auto|gearhead|projection|carservice|car.?error|authenticat|certificat|ssl|tls|securityexception|usb|uconnect|smartbox|time.?sync|clock.?skew|handshake", RegexOption.IGNORE_CASE)

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    fun isRunning(): Boolean = running.get()

    fun start(context: Context) {
        if (!hasPermission(context)) {
            EventStore.log(context, "LOGCAT_UNAVAILABLE", "READ_LOGS not granted; grant via ADB")
            return
        }
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        worker = Thread({
            val dir = File(app.filesDir, "sessions").apply { mkdirs() }
            val full = File(dir, "logcat-current.txt")
            val matched = File(dir, "logcat-matches.txt")
            try {
                EventStore.log(app, "LOGCAT_START", "continuous; accessible buffers; max 24 MiB")
                val p = ProcessBuilder("logcat", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "events", "-T", "1")
                    .redirectErrorStream(true).start()
                process = p
                BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                    full.bufferedWriter().use { writer ->
                        var count = 0
                        while (running.get()) {
                            val line = reader.readLine() ?: break
                            writer.appendLine(line)
                            if (keywords.containsMatchIn(line)) {
                                synchronized(this) { matched.appendText(line.take(4000) + "\n") }
                            }
                            count++
                            if (count % 100 == 0) writer.flush()
                            if (full.length() > MAX_BYTES) {
                                EventStore.log(app, "LOGCAT_LIMIT", "24 MiB reached; capture stopped to protect storage")
                                break
                            }
                        }
                    }
                }
                EventStore.log(app, "LOGCAT_EXIT", "exit_or_end; bytes=${full.length()}")
            } catch (e: Exception) {
                EventStore.log(app, "LOGCAT_ERROR", e.toString())
            } finally {
                process?.destroy()
                process = null
                running.set(false)
            }
        }, "JeepLogcatCapture").apply { isDaemon = true; start() }
    }

    fun stop(context: Context) {
        if (running.getAndSet(false)) {
            process?.destroy()
            EventStore.log(context, "LOGCAT_STOP_REQUEST")
        }
    }

    fun snapshot(context: Context, label: String) {
        if (!hasPermission(context)) {
            EventStore.log(context, "LOGCAT_SNAPSHOT_UNAVAILABLE", "READ_LOGS missing")
            return
        }
        Thread {
            val target = File(File(context.filesDir, "sessions").apply { mkdirs() }, "logcat-snapshot-${System.currentTimeMillis()}-${label}.txt")
            try {
                val p = ProcessBuilder("logcat", "-d", "-t", "5000", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "events")
                    .redirectErrorStream(true).start()
                p.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                val code = p.waitFor()
                EventStore.log(context, "LOGCAT_SNAPSHOT", "exit=$code;bytes=${target.length()};label=$label")
            } catch(e: Exception) { EventStore.log(context, "LOGCAT_SNAPSHOT_ERROR", e.toString()) }
        }.start()
    }
}
