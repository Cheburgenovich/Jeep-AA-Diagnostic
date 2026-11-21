package com.jeep.aadiagnostic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.location.LocationManager
import android.hardware.usb.UsbManager
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.TimeZone

/** Explicit diagnostic snapshot. Does not claim to read the head unit's internal clock. */
object Diagnostics {
    fun capture(context: Context, reason: String) {
        val data = JSONObject()
        fun put(key: String, value: Any?) { data.put(key, value ?: JSONObject.NULL) }
        put("reason", reason)
        put("utc", Instant.now().toString())
        put("wall_ms", System.currentTimeMillis())
        put("elapsed_ms", SystemClock.elapsedRealtime())
        put("zone", TimeZone.getDefault().id)
        put("zone_offset_ms", TimeZone.getDefault().getOffset(System.currentTimeMillis()))
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("sdk", Build.VERSION.SDK_INT)
        put("build_fingerprint", Build.FINGERPRINT)
        put("auto_time", runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, -1) }.getOrDefault(-1))
        put("auto_time_zone", runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME_ZONE, -1) }.getOrDefault(-1))
        put("airplane_mode", runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1) }.getOrDefault(-1))
        put("battery_optimization_ignored", runCatching { (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(context.packageName) }.getOrNull())
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = runCatching { cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
        put("network_wifi", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        put("network_cellular", caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        put("network_vpn", caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        put("network_validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        put("gps_enabled", runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull())
        put("location_permission", context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        put("usb_host_devices", runCatching { usb.deviceList.size }.getOrNull())
        put("android_auto_package", runCatching { context.packageManager.getPackageInfo("com.google.android.projection.gearhead", 0).longVersionCode.toString() }.getOrNull())
        put("play_services_package", runCatching { context.packageManager.getPackageInfo("com.google.android.gms", 0).longVersionCode.toString() }.getOrNull())
        val dir = File(context.filesDir, "sessions").apply { mkdirs() }
        synchronized(this) { File(dir, "snapshots.jsonl").appendText(data.toString() + "\n") }
        EventStore.log(context, "SNAPSHOT", reason)
    }
}
