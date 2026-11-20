package com.jeep.aadiagnostic

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant

object EventStore {
    private val lock = Any()
    fun log(context: Context, type: String, details: String = "") {
        val obj = JSONObject().put("utc", Instant.now().toString())
            .put("wall_time_ms", System.currentTimeMillis())
            .put("elapsed_ms", android.os.SystemClock.elapsedRealtime())
            .put("type", type).put("details", details.take(3000))
        synchronized(lock) {
            val dir = File(context.filesDir, "sessions").apply { mkdirs() }
            File(dir, "events.jsonl").appendText(obj.toString() + "\n")
        }
    }
    fun events(context: Context): File = File(File(context.filesDir, "sessions"), "events.jsonl")
    fun tail(context: Context, limit: Int = 30): String = synchronized(lock) {
        val f = events(context)
        if (!f.exists()) "Нет событий" else f.readLines().takeLast(limit).joinToString("\n") { line ->
            runCatching {
                val j = JSONObject(line)
                "${j.optString("utc")}  ${j.optString("type")}: ${j.optString("details")}"
            }.getOrDefault(line)
        }
    }
}