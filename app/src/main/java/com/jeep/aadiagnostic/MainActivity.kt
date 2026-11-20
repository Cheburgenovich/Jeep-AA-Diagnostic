package com.jeep.aadiagnostic

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(TextView(this).apply { text = "Jeep AA Diagnostic"; textSize = 25f })
        root.addView(TextView(this).apply { text = "Android Auto Error 8 · Uconnect VP2 7\nДанные сохраняются на телефоне"; textSize = 14f })
        status = TextView(this).apply { textSize = 15f; setPadding(0, 16, 0, 16) }
        root.addView(status)
        fun button(label: String, action: () -> Unit) {
            root.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, -2))
        }
        button("1. Разрешить GPS и уведомления") { permissions() }
        button("2. Начать мониторинг") { start() }
        button("Остановить мониторинг") { stopService(Intent(this, MonitorService::class.java)); EventStore.log(this, "USER_STOP"); refresh() }
        button("✓ Android Auto подключился") { mark("AA_SUCCESS") }
        button("✕ Ошибка Android Auto 8") { mark("AA_ERROR_8") }
        button("Другая ошибка") { mark("AA_OTHER_ERROR") }
        button("Время магнитолы изменилось") { mark("CAR_CLOCK_CHANGED") }
        button("Добавить комментарий") {
            val input = EditText(this).apply { hint = "Что происходило?" }
            AlertDialog.Builder(this).setTitle("Комментарий").setView(input)
                .setPositiveButton("Сохранить") { _, _ -> EventStore.log(this, "NOTE", input.text.toString()); refresh() }
                .setNegativeButton("Отмена", null).show()
        }
        button("Экспортировать ZIP") { exportZip() }
        button("Снять Logcat (требуется разрешение ADB)") { captureLogcat() }
        button("Обновить журнал") { refresh() }
        root.addView(TextView(this).apply { text = "Важно: внутреннее время Uconnect недоступно. Координаты и системные логи в ZIP могут содержать личные данные."; textSize = 13f })
        log = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(log)
        refresh()
    }
    private fun mark(type: String) { EventStore.log(this, type, "user_marked"); Toast.makeText(this, "Отметка сохранена", Toast.LENGTH_SHORT).show(); refresh() }
    private fun permissions() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION); missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 101)
        else Toast.makeText(this, "Разрешения уже выданы", Toast.LENGTH_SHORT).show()
    }
    private fun start() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions(); return
        }
        try { startForegroundService(Intent(this, MonitorService::class.java)); EventStore.log(this, "USER_START"); refresh() }
        catch (e: Exception) { EventStore.log(this, "START_ERROR", e.toString()); Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show() }
    }
    private fun refresh() {
        status.text = "Устройство: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid API: ${Build.VERSION.SDK_INT}\nUTC: ${Instant.now()}\nЧасовой пояс: ${java.util.TimeZone.getDefault().id}\nGPS: ${checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED}\nREAD_LOGS: ${checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED}"
        if (::log.isInitialized) log.text = EventStore.tail(this)
    }
    private fun exportZip() {
        try {
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val zip = File(dir, "jeep-aa-${System.currentTimeMillis()}.zip")
            ZipOutputStream(FileOutputStream(zip)).use { output ->
                for ((name, file) in listOf("events.jsonl" to EventStore.events(this), "logcat.txt" to File(filesDir, "logcat.txt"))) {
                    if (file.exists()) { output.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(output) }; output.closeEntry() }
                }
                output.putNextEntry(ZipEntry("README.txt"))
                output.write("Jeep AA Diagnostic 1.0\nExported ${Instant.now()}\nLogs may contain GPS coordinates and personal information.\nConnection markers are user supplied.\n".toByteArray())
                output.closeEntry()
            }
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.provider", zip)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Сохранить или отправить ZIP"))
        } catch (e: Exception) { Toast.makeText(this, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show() }
    }
    private fun captureLogcat() {
        if (checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this).setTitle("Нужно разрешение ADB")
                .setMessage("Подключи телефон к компьютеру и выполни:\nadb shell pm grant com.jeep.aadiagnostic android.permission.READ_LOGS\n\nНекоторые защищённые журналы могут оставаться недоступны.")
                .setPositiveButton("OK", null).show()
            return
        }
        Thread {
            try {
                val process = ProcessBuilder("logcat", "-d", "-t", "5000", "-v", "threadtime").redirectErrorStream(true).start()
                val output = File(filesDir, "logcat.txt")
                process.inputStream.use { input -> output.outputStream().use { input.copyTo(it) } }
                val exit = process.waitFor()
                EventStore.log(this, "LOGCAT_CAPTURE", "exit=$exit;bytes=${output.length()}")
                runOnUiThread { Toast.makeText(this, "Logcat сохранён", Toast.LENGTH_LONG).show(); refresh() }
            } catch (e: Exception) {
                EventStore.log(this, "LOGCAT_ERROR", e.toString())
                runOnUiThread { Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}