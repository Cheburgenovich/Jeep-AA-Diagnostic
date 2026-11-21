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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(TextView(this).apply { text="Jeep AA Diagnostic 2.0"; textSize=25f })
        root.addView(TextView(this).apply { text="Android Auto Error 8 · Uconnect VP2 7\nЛокальная диагностика, без отправки на сервер"; textSize=14f })
        status = TextView(this).apply { textSize=14f; setPadding(0,16,0,16) }
        root.addView(status)
        fun button(label:String, action:()->Unit) {
            root.addView(Button(this).apply { text=label; isAllCaps=false; setOnClickListener { action() } }, LinearLayout.LayoutParams(-1,-2))
        }
        button("1. Разрешить GPS и уведомления") { permissions() }
        button("2. Начать мониторинг и доступный Logcat") { start() }
        button("Остановить мониторинг") { stopService(Intent(this,MonitorService::class.java)); EventStore.log(this,"USER_STOP"); refresh() }
        button("▶ Начинаю попытку подключения") { mark("AA_ATTEMPT_START") }
        button("✓ Android Auto подключился") { mark("AA_SUCCESS") }
        button("✕ Ошибка Android Auto 8") { mark("AA_ERROR_8") }
        button("Другая ошибка") { mark("AA_OTHER_ERROR") }
        button("Время магнитолы изменилось") { mark("CAR_CLOCK_CHANGED") }
        button("Снимок диагностики сейчас") { Diagnostics.capture(this,"MANUAL"); LogCapture.snapshot(this,"manual"); refresh() }
        button("Добавить комментарий") {
            val input = EditText(this).apply { hint="Время магнитолы, тип подключения, точный текст ошибки" }
            AlertDialog.Builder(this).setTitle("Комментарий").setView(input)
                .setPositiveButton("Сохранить") { _, _ -> EventStore.log(this,"NOTE",input.text.toString()); refresh() }
                .setNegativeButton("Отмена",null).show()
        }
        button("Экспортировать полный ZIP") { exportZip() }
        button("Проверить доступ к системным журналам") { logcatHelp() }
        button("Обновить журнал") { refresh() }
        root.addView(TextView(this).apply {
            text="Важно: системный Logcat требует READ_LOGS через ADB. Даже с разрешением часть сообщений Android Auto может быть скрыта. Внутренние часы Uconnect недоступны. ZIP может содержать точные координаты, имена сетей и другие личные данные."
            textSize=13f
        })
        log = TextView(this).apply { textSize=12f; setTextIsSelectable(true) }
        root.addView(log)
        refresh()
    }
    private fun mark(type:String) {
        EventStore.log(this,type,"user_marked")
        Diagnostics.capture(this,type)
        LogCapture.snapshot(this,type.lowercase())
        Toast.makeText(this,"Отметка и снимок сохранены",Toast.LENGTH_SHORT).show()
        refresh()
    }
    private fun permissions() {
        val missing=mutableListOf<String>()
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION); missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if(missing.isNotEmpty()) requestPermissions(missing.toTypedArray(),101) else Toast.makeText(this,"Разрешения уже выданы",Toast.LENGTH_SHORT).show()
    }
    private fun start() {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) { permissions();return }
        try {
            startForegroundService(Intent(this,MonitorService::class.java))
            EventStore.log(this,"USER_START")
            Diagnostics.capture(this,"USER_START")
            refresh()
        } catch(e:Exception) { EventStore.log(this,"START_ERROR",e.toString());Toast.makeText(this,e.toString(),Toast.LENGTH_LONG).show() }
    }
    private fun refresh() {
        status.text="Устройство: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid API: ${Build.VERSION.SDK_INT}\nUTC: ${Instant.now()}\nЧасовой пояс: ${java.util.TimeZone.getDefault().id}\nGPS: ${checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED}\nREAD_LOGS: ${LogCapture.hasPermission(this)}\nLogcat активен: ${LogCapture.isRunning()}"
        if(::log.isInitialized) log.text=EventStore.tail(this,40)
    }
    private fun logcatHelp() {
        AlertDialog.Builder(this).setTitle("Расширенные системные журналы")
            .setMessage("READ_LOGS: ${LogCapture.hasPermission(this)}\n\nДля разрешения на компьютере:\nadb shell pm grant com.jeep.aadiagnostic android.permission.READ_LOGS\n\nПосле этого останови и снова запусти мониторинг. Захват начинается автоматически и ограничен 24 МиБ на сессию. Без разрешения сохраняются события, GNSS и системные снимки. Android может скрывать часть защищённых логов.")
            .setPositiveButton("OK",null).show()
    }
    private fun exportZip() {
        Thread {
            try {
                val dir=File(cacheDir,"exports").apply { mkdirs() }
                val zip=File(dir,"jeep-aa-v2-${System.currentTimeMillis()}.zip")
                ZipOutputStream(FileOutputStream(zip)).use { output ->
                    val session=File(filesDir,"sessions")
                    session.listFiles()?.filter { it.isFile && it.length()<=30L*1024*1024 }?.forEach { file ->
                        output.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(output) }
                        output.closeEntry()
                    }
                    output.putNextEntry(ZipEntry("README.txt"))
                    output.write("Jeep AA Diagnostic 2.0\nExported ${Instant.now()}\nUser markers are not automatically detected errors. GNSS location timestamps are not an independent clock. Contains sensitive device logs and possibly coordinates.\n".toByteArray())
                    output.closeEntry()
                }
                val uri:Uri=FileProvider.getUriForFile(this,"$packageName.provider",zip)
                runOnUiThread {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type="application/zip";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },"Сохранить или отправить ZIP"))
                }
            } catch(e:Exception) { runOnUiThread { Toast.makeText(this,"Ошибка экспорта: ${e.message}",Toast.LENGTH_LONG).show() } }
        }.start()
    }
}
