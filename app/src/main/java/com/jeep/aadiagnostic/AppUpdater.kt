package com.jeep.aadiagnostic

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Explicit, user-approved update from a GitHub Release APK.
 * Never downloads GitHub Actions artifacts (those require authentication).
 */
object AppUpdater {
    private const val REPO = "Cheburgenovich/Jeep-AA-Diagnostic"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private data class Release(val versionCode: Int, val version: String, val notes: String, val apk: String, val checksum: String)

    private fun get(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Jeep-AA-Diagnostic-Updater")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            return connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }

    private fun latest(): Release {
        val json = JSONObject(get(API).toString(Charsets.UTF_8))
        val tag = json.getString("tag_name")
        // Release tags must be v<integer> (e.g. v3), matching Android versionCode.
        val code = Regex("^v([0-9]+)$").matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull()
            ?: error("Неверный формат тега релиза: $tag")
        val assets = json.getJSONArray("assets")
        var apk: String? = null
        var sha: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            when (a.getString("name")) {
                "Jeep-AA-Diagnostic.apk" -> apk = a.getString("browser_download_url")
                "Jeep-AA-Diagnostic.apk.sha256" -> sha = a.getString("browser_download_url")
            }
        }
        return Release(code, tag, json.optString("body").take(3000), apk ?: error("В релизе отсутствует APK"), sha ?: error("В релизе отсутствует SHA256"))
    }

    fun check(activity: Activity) {
        Thread {
            try {
                val release = latest()
                val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
                activity.runOnUiThread {
                    if (release.versionCode <= installed) {
                        AlertDialog.Builder(activity).setTitle("Обновлений нет")
                            .setMessage("Установлена версия $installed, опубликована ${release.version}.")
                            .setPositiveButton("OK", null).show()
                    } else {
                        AlertDialog.Builder(activity).setTitle("Доступна версия ${release.version}")
                            .setMessage("Текущая версия: $installed\n\nИзменения:\n${release.notes.ifBlank { "Описание не указано" }}\n\nСкачать APK и открыть установщик Android?")
                            .setPositiveButton("Скачать") { _, _ -> download(activity, release) }
                            .setNegativeButton("Отмена", null).show()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    AlertDialog.Builder(activity).setTitle("Не удалось проверить обновления")
                        .setMessage("${e.message}\n\nПроверь интернет и наличие опубликованного GitHub Release.")
                        .setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    private fun download(activity: Activity, release: Release) {
        Toast.makeText(activity, "Загрузка APK началась", Toast.LENGTH_LONG).show()
        Thread {
            try {
                val expected = get(release.checksum).toString(Charsets.UTF_8).trim().split(Regex("\\s+")).first().lowercase()
                require(expected.matches(Regex("[a-f0-9]{64}"))) { "Некорректный SHA256 релиза" }
                val data = get(release.apk)
                require(data.size in 100_000..80_000_000) { "Некорректный размер APK" }
                val actual = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
                require(actual == expected) { "Контрольная сумма APK не совпадает. Установка отменена." }
                val file = File(File(activity.cacheDir, "updates").apply { mkdirs() }, "Jeep-AA-Diagnostic.apk")
                file.writeBytes(data)
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", file)
                activity.runOnUiThread {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        AlertDialog.Builder(activity).setTitle("Установщик недоступен")
                            .setMessage("Разреши установку приложений из Jeep AA Diagnostic в настройках Android. ${e.message}")
                            .setPositiveButton("OK", null).show()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    AlertDialog.Builder(activity).setTitle("Загрузка отменена")
                        .setMessage(e.message ?: e.toString()).setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }
}
