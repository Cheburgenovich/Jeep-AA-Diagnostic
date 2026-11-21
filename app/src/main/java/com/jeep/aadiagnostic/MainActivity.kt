package com.jeep.aadiagnostic

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var monitorButton: Button
    private lateinit var details: LinearLayout
    private lateinit var mode: Spinner
    private lateinit var manualPanel: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }
    private val navy = Color.rgb(17, 32, 49)
    private val blue = Color.rgb(28, 94, 167)
    private val green = Color.rgb(28, 122, 82)
    private val red = Color.rgb(174, 48, 48)
    private val muted = Color.rgb(87, 103, 117)
    private val refreshLoop = object: Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 3000) }
    }

    private fun background(color: Int, radius: Float = 16f): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius * resources.displayMetrics.density }

    private fun label(text: String, size: Float = 15f, color: Int = navy, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun section(root: LinearLayout, title: String): LinearLayout {
        root.addView(label(title, 17f, navy, true).apply { setPadding(0, 22, 0, 10) })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            background = background(Color.WHITE)
            elevation = 2f
        }
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
        return card
    }

    private fun button(root: LinearLayout, title: String, color: Int = blue, action: () -> Unit): Button =
        Button(this).apply {
            text = title; isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE); background = background(color, 12f)
            setOnClickListener { action() }
            root.addView(this, LinearLayout.LayoutParams(-1, 52.dp()).apply { bottomMargin = 9 })
        }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp(), 18.dp(), 18.dp(), 24.dp())
            setBackgroundColor(Color.rgb(244, 247, 250))
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        root.addView(label("Jeep AA Diagnostic", 26f, navy, true))
        root.addView(label("Версия ${BuildConfig.VERSION_NAME} · Android Auto / Uconnect", 13f, muted))
        val stateCard = section(root, "Состояние диагностики")
        status = label("", 14f).apply { setPadding(0, 0, 0, 12) }
        stateCard.addView(status)
        monitorButton = button(stateCard, "Начать мониторинг", green) { toggleMonitoring() }
        button(stateCard, "Разрешения GPS и уведомлений") { permissions() }

        val test = section(root, "Автоматическая диагностика")
        test.addView(label("Мониторинг автоматически сохраняет доступные системные журналы, изменения USB, сети и времени. Обнаруженные признаки сбоев не означают подтверждённую ошибку 8.", 14f, muted))
        manualPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        button(test, "Показать / скрыть ручные отметки", muted) {
            manualPanel.visibility = if (manualPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        test.addView(manualPanel)
        manualPanel.addView(label("Ручные отметки необязательны.", 13f, muted).apply { setPadding(0, 0, 0, 8) })
        mode = Spinner(this)
        val modes = arrayOf("USB-кабель", "Беспроводной SmartBox", "Другой способ")
        mode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        mode.setSelection(prefs.getInt("mode", 0).coerceIn(0, 2))
        manualPanel.addView(mode)
        button(manualPanel, "▶ Начать попытку подключения") { mark("AA_ATTEMPT_START") }
        button(manualPanel, "✓ Android Auto подключился", green) { mark("AA_SUCCESS") }
        button(manualPanel, "✕ Ошибка Android Auto 8", red) { mark("AA_ERROR_8") }
        button(manualPanel, "Другая ошибка", muted) { mark("AA_OTHER_ERROR") }
        button(manualPanel, "Часы Uconnect изменились", muted) { clockNote() }
        button(manualPanel, "Добавить заметку", muted) { note() }

        val files = section(root, "Данные и обновления")
        button(files, "Экспортировать диагностику ZIP") { exportZip() }
        button(files, "Проверить обновления GitHub") { AppUpdater.check(this) }
        button(files, "Расширенная диагностика (ADB)", muted) { logcatHelp() }

        val history = section(root, "Последние события")
        button(history, "Показать / скрыть журнал", muted) {
            details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        log = label("", 11f, muted).apply { setTextIsSelectable(true) }
        details.addView(log)
        history.addView(details)
        root.addView(label("Журналы хранятся на телефоне. ZIP может содержать координаты и личные данные. Время Uconnect отмечается вручную.", 12f, muted).apply { setPadding(0, 12, 0, 0) })
        refresh()
    }

    override fun onResume() { super.onResume(); handler.post(refreshLoop) }
    override fun onPause() { handler.removeCallbacks(refreshLoop); super.onPause() }

    private fun isMonitoring(): Boolean = prefs.getBoolean("monitoring", false)

    private fun refresh() {
        if (!::status.isInitialized) return
        val running = isMonitoring()
        val gps = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val logcat = LogCapture.hasPermission(this)
        val stats = EventStore.counts(this)
        status.text = "${if (running) "● Мониторинг запущен" else "○ Мониторинг остановлен"}\n" +
            "GPS: ${if (gps) "разрешён" else "нет разрешения"}  ·  Системные логи: ${if (logcat) "доступны" else "нужен ADB"}\n" +
            "Logcat: ${if (LogCapture.isRunning()) "записывается" else "не записывается"}\n" +
            "Попыток: ${stats.first}  ·  Ошибок 8: ${stats.second}  ·  Успешных: ${stats.third}"
        monitorButton.text = if (running) "Остановить мониторинг" else "Начать мониторинг"
        if (::log.isInitialized && details.visibility == View.VISIBLE) log.text = EventStore.tail(this, 25)
    }

    private fun toggleMonitoring() {
        if (isMonitoring()) {
            stopService(Intent(this, MonitorService::class.java))
            prefs.edit().putBoolean("monitoring", false).apply()
            EventStore.log(this, "USER_STOP")
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions()
                Toast.makeText(this, "Разреши точное местоположение, затем нажми «Начать мониторинг»", Toast.LENGTH_LONG).show()
                return
            }
            try {
                startForegroundService(Intent(this, MonitorService::class.java))
                prefs.edit().putBoolean("monitoring", true).apply()
                EventStore.log(this, "USER_START")
            } catch(e: Exception) {
                EventStore.log(this, "START_ERROR", e.toString())
                Toast.makeText(this, "Не удалось запустить: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        refresh()
    }

    private fun permissions() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isEmpty()) Toast.makeText(this, "Все обычные разрешения выданы", Toast.LENGTH_SHORT).show()
        else requestPermissions(missing.toTypedArray(), 101)
    }

    private fun mark(type: String, note: String = "") {
        val selected = mode.selectedItem?.toString() ?: "Неизвестно"
        prefs.edit().putInt("mode", mode.selectedItemPosition).apply()
        EventStore.log(this, type, "method=$selected;${note.ifBlank { "user_marked" }}")
        // Snapshot and optional logcat capture happen without further user action.
        Diagnostics.capture(this, type)
        LogCapture.snapshot(this, type.lowercase())
        Toast.makeText(this, "Событие сохранено", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun clockNote() {
        val input = EditText(this).apply { hint = "Например: было 11:20, стало 14:20" }
        AlertDialog.Builder(this).setTitle("Как изменилось время Uconnect?")
            .setView(input).setPositiveButton("Сохранить") { _, _ -> mark("CAR_CLOCK_CHANGED", input.text.toString()) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun note() {
        val input = EditText(this).apply { hint = "Что происходило с Android Auto?" }
        AlertDialog.Builder(this).setTitle("Заметка")
            .setView(input).setPositiveButton("Сохранить") { _, _ -> EventStore.log(this, "NOTE", input.text.toString()); refresh() }
            .setNegativeButton("Отмена", null).show()
    }

    private fun logcatHelp() {
        AlertDialog.Builder(this).setTitle("Расширенные журналы")
            .setMessage("READ_LOGS: ${LogCapture.hasPermission(this)}\n\nПозже выдадим разрешение через ADB:\nadb shell pm grant com.jeep.aadiagnostic android.permission.READ_LOGS\n\nБез ADB приложение продолжает записывать события, GPS и системные снимки.")
            .setPositiveButton("Понятно", null).show()
    }

    private fun exportZip() {
        Toast.makeText(this, "Подготавливаю архив…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val dir = File(cacheDir, "exports").apply { mkdirs() }
                val zip = File(dir, "jeep-aa-v${BuildConfig.VERSION_NAME}-${System.currentTimeMillis()}.zip")
                ZipOutputStream(FileOutputStream(zip)).use { output ->
                    File(filesDir, "sessions").listFiles()?.filter { it.isFile && it.length() <= 100L * 1024 * 1024 }?.forEach { file ->
                        output.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(output) }
                        output.closeEntry()
                    }
                    output.putNextEntry(ZipEntry("README.txt"))
                    output.write("Jeep AA Diagnostic ${BuildConfig.VERSION_NAME}\nExported ${Instant.now()}\nError codes are extracted from phone Logcat; transport evidence can be inferred and may be uncertain. Internal Uconnect clock is NOT readable. Rotating logs retain recent data only. Contains potentially sensitive device data, locations and identifiers.\n".toByteArray())
                    output.closeEntry()
                }
                val uri: Uri = FileProvider.getUriForFile(this, "$packageName.provider", zip)
                runOnUiThread {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Сохранить или отправить ZIP"))
                }
            } catch(e: Exception) {
                runOnUiThread { Toast.makeText(this, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}
