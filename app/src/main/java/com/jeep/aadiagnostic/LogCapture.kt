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
    private val usbSignal = Regex("UsbDeviceManager|UsbPortManager|UsbAccessory|UsbHostManager|usb.*(attach|detach|connect|disconnect)|accessory.*(attach|detach)", RegexOption.IGNORE_CASE)
    private val projectionSignal = Regex("gearhead|android.?auto|projection|carservice", RegexOption.IGNORE_CASE)
    private val failureSignal = Regex("authenticat.*(fail|error|reject)|certificat.*(fail|invalid|expir)|handshake.*(fail|error)|securityexception|clock.?skew|communication error|error.?8|projection.*(fail|disconnect|error)", RegexOption.IGNORE_CASE)
    // A generic "connected" log message is NOT proof of successful projection.
    private val wirelessSignal = Regex("""isWireless=true|SmartBox_[A-Za-z0-9_-]+|wireless.?projection""", RegexOption.IGNORE_CASE)
    private val wiredSignal = Regex("""isWireless=false|VERSION_NEGOTIATION_SUCCESS_USB""", RegexOption.IGNORE_CASE)
    private var lastTransport = "UNKNOWN"
    private val lastSignal = mutableMapOf<String, Long>()
    private val authFailure = Regex("""PROTOCOL_AUTH_FAILED_BY_CAR\(8\)|AUTH_FAILED_BY_CAR\(44\)|Remote auth failure""", RegexOption.IGNORE_CASE)
    private val ioFailure = Regex("""PROTOCOL_IO_ERROR\(3\)""", RegexOption.IGNORE_CASE)
    private val versionOk = Regex("VERSION_NEGOTIATION_SUCCESS_USB", RegexOption.IGNORE_CASE)
    private val sslFailure = Regex("SSL_FAILED", RegexOption.IGNORE_CASE)
    private fun infer(context: Context, line: String) {
        if (wirelessSignal.containsMatchIn(line) || wiredSignal.containsMatchIn(line)) {
            val transport = when {
                line.contains("isWireless=true", true) -> "WIRELESS_CONFIRMED"
                line.contains("isWireless=false", true) -> "USB_CONFIRMED"
                line.contains("VERSION_NEGOTIATION_SUCCESS_USB", true) -> "USB_INDICATED"
                else -> "WIRELESS_INDICATED"
            }
            if (transport != lastTransport) {
                lastTransport = transport
                EventStore.log(context, "AA_TRANSPORT", "evidence=$transport;source=" + line.take(400))
            }
        }
        val type = when {
            authFailure.containsMatchIn(line) -> "AUTO_AUTH_FAILED_BY_CAR"
            ioFailure.containsMatchIn(line) -> "AUTO_PROTOCOL_IO_ERROR"
            versionOk.containsMatchIn(line) -> "AUTO_USB_VERSION_NEGOTIATED"
            sslFailure.containsMatchIn(line) && projectionSignal.containsMatchIn(line) -> "AUTO_SSL_FAILED"
            projectionSignal.containsMatchIn(line) && failureSignal.containsMatchIn(line) -> "AUTO_POSSIBLE_AA_FAILURE"
            usbSignal.containsMatchIn(line) -> "AUTO_USB_SIGNAL"
            else -> null
        } ?: return
        val now = SystemClock.elapsedRealtime()
        // Limit duplicated vendor log spam while keeping raw logcat unchanged.
        val allowed = synchronized(lastSignal) {
            val last = lastSignal[type] ?: 0L
            if (now - last < 2000L) false else { lastSignal[type] = now; true }
        }
        if (allowed) {
            EventStore.log(context, type, "transport=$lastTransport; " + line.take(900))
            if (type == "AUTO_AUTH_FAILED_BY_CAR" || type == "AUTO_PROTOCOL_IO_ERROR") {
                Diagnostics.capture(context, type)
            }
        }
    }
    // Bounded rotating capture: 8 x 8 MiB, oldest segments are replaced.
    private const val SEGMENT_BYTES = 8L * 1024 * 1024
    private const val SEGMENT_COUNT = 8
    private const val MATCHED_BYTES = 8L * 1024 * 1024
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
            val matched = File(dir, "logcat-matches.txt")
            try {
                lastTransport = "UNKNOWN"
                EventStore.log(app, "LOGCAT_START", "continuous rotating 8 x 8 MiB; matched 8 MiB")
                val p = ProcessBuilder("logcat", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "events", "-T", "1")
                    .redirectErrorStream(true).start()
                process = p
                var segment = 0
                var segmentBytes = 0L
                var count = 0
                var writer = File(dir, "logcat-part-%02d.txt".format(segment)).bufferedWriter()
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                        while (running.get()) {
                            val line = reader.readLine() ?: break
                            val record = line + "\n"
                            val bytes = record.toByteArray(Charsets.UTF_8).size.toLong()
                            if (segmentBytes + bytes > SEGMENT_BYTES) {
                                writer.flush()
                                writer.close()
                                segment = (segment + 1) % SEGMENT_COUNT
                                writer = File(dir, "logcat-part-%02d.txt".format(segment)).bufferedWriter()
                                segmentBytes = 0L
                                EventStore.log(app, "LOGCAT_ROTATE", "segment=$segment; maxSegments=$SEGMENT_COUNT")
                            }
                            writer.write(record)
                            segmentBytes += bytes
                            infer(app, line)
                            if (keywords.containsMatchIn(line)) {
                                synchronized(this) {
                                    if (matched.exists() && matched.length() > MATCHED_BYTES) {
                                        val previous = File(dir, "logcat-matches-previous.txt")
                                        if (previous.exists()) previous.delete()
                                        matched.renameTo(previous)
                                    }
                                    matched.appendText(line.take(4000) + "\n")
                                }
                            }
                            count++
                            if (count % 100 == 0) writer.flush()
                        }
                    }
                } finally {
                    writer.flush()
                    writer.close()
                }
                EventStore.log(app, "LOGCAT_EXIT", "exit_or_end;lines=$count;segment=$segment")
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
