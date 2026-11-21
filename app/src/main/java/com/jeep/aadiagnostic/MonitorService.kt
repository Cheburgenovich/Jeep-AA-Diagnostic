package com.jeep.aadiagnostic

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.*
import android.net.*
import android.os.*
import android.hardware.usb.UsbManager
import java.util.TimeZone

class MonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val lm by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val cm by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
    private var wall = System.currentTimeMillis()
    private var elapsed = SystemClock.elapsedRealtime()
    private var zone = TimeZone.getDefault().id
    private var network = ""
    private var gnssLast = 0L
    private var lastTimeSnapshot = 0L
    private val tick = object : Runnable {
        override fun run() {
            val w = System.currentTimeMillis()
            val e = SystemClock.elapsedRealtime()
            val jump = (w - wall) - (e - elapsed)
            if (kotlin.math.abs(jump) > 1500) EventStore.log(this@MonitorService, "CLOCK_JUMP", "delta_ms=$jump")
            wall = w; elapsed = e
            if (e - lastTimeSnapshot >= 5000L) {
                lastTimeSnapshot = e
                Diagnostics.capture(this@MonitorService, "PERIODIC_5S")
            }
            val z = TimeZone.getDefault().id
            if (z != zone) { EventStore.log(this@MonitorService, "TIMEZONE", "$zone -> $z"); zone = z }
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val n = "wifi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)};cellular=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)};vpn=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)};validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
            if (n != network) { EventStore.log(this@MonitorService, "NETWORK", n); network = n }
            handler.postDelayed(this, 2000L)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            EventStore.log(context, "SYSTEM_EVENT", intent.action ?: "unknown")
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED || intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED ||
                intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED || intent.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                EventStore.log(context, "AUTO_USB_BROADCAST", "signal_only;action=${intent.action}")
                Diagnostics.capture(context, "USB_CHANGE")
            }
            if (intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED || intent.action == Intent.ACTION_DATE_CHANGED) Diagnostics.capture(context, intent.action ?: "TIME_EVENT")
        }
    }
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val data = "provider=${location.provider};lat=${location.latitude};lon=${location.longitude};accuracy=${location.accuracy};fix_time=${location.time};mock=${location.isMock}"
            EventStore.log(this@MonitorService, "LOCATION", data)
            if (location.provider == LocationManager.GPS_PROVIDER) {
                val offset = location.time - System.currentTimeMillis()
                if (kotlin.math.abs(offset) > 10000) EventStore.log(this@MonitorService, "GPS_TIME_OFFSET", "offset_ms=$offset;may_be_stale")
            }
        }
    }
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onFirstFix(ttffMillis: Int) { EventStore.log(this@MonitorService, "GNSS_FIX", "ttff_ms=$ttffMillis") }
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val now = SystemClock.elapsedRealtime()
            if (now - gnssLast >= 15000) {
                gnssLast = now
                val used = (0 until status.satelliteCount).count { status.usedInFix(it) }
                EventStore.log(this@MonitorService, "GNSS_SATELLITES", "visible=${status.satelliteCount};used=$used")
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("monitoring", true).apply()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("monitor", "AA Diagnostics", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(11, Notification.Builder(this, "monitor").setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("Jeep AA Diagnostic").setContentText("Мониторинг активен").setContentIntent(pending).setOngoing(true).build())
        EventStore.log(this, "SESSION_START", "device=${Build.MANUFACTURER} ${Build.MODEL};sdk=${Build.VERSION.SDK_INT}")
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 0f, locationListener, Looper.getMainLooper()) }
                .onFailure { EventStore.log(this, "GPS_ERROR", it.toString()) }
            runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15000L, 0f, locationListener, Looper.getMainLooper()) }
            runCatching { lm.registerGnssStatusCallback(gnssCallback, handler) }
        } else EventStore.log(this, "LOCATION_PERMISSION_MISSING")
        Diagnostics.capture(this, "SESSION_START")
        LogCapture.start(this)
        handler.post(tick)
    }
    override fun onDestroy() {
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(receiver) }
        runCatching { lm.removeUpdates(locationListener) }
        runCatching { lm.unregisterGnssStatusCallback(gnssCallback) }
        LogCapture.stop(this)
        Diagnostics.capture(this, "SESSION_STOP")
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("monitoring", false).apply()
        EventStore.log(this, "SESSION_STOP")
        super.onDestroy()
    }
}