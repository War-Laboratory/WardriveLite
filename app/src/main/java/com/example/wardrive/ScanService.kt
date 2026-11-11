package com.example.wardrive

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.bluetooth.le.ScanResult as BleResult

/** Foreground service that scans Wi-Fi, BLE and cell towers and tags them with GPS. */
class ScanService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.wardrive.STOP"
        private const val CH_STATUS = "status"
        private const val CH_ALERT = "alerts"
        private const val NOTIF_ID = 1
        private const val WIFI_INTERVAL_MS = 15_000L   // Android throttles to ~4 scans / 2 min unless disabled in Developer options
        private const val CELL_INTERVAL_MS = 10_000L
        private const val BLE_RESTART_MS = 10 * 60_000L // unfiltered BLE scans get downgraded after 30 min
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wifi: WifiManager
    private lateinit var tel: TelephonyManager
    private lateinit var lm: LocationManager
    private var bleScanner: BluetoothLeScanner? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Store.scanning) return START_STICKY

        Prefs.init(this)
        createChannels()
        startForeground(NOTIF_ID, buildStatus("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        tel = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        Store.scanning = true
        Store.openLog(this)
        Store.onNewAlert = { s -> handler.post { notifyAlert(s) } }
        ScanWidget.updateAll(this)

        startLocation()
        startWifi()
        startBle()
        handler.post(cellTick)
        handler.postDelayed(statusTick, 3000)
        handler.postDelayed(bleRestart, BLE_RESTART_MS)

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wardrive:scan")
            .apply { acquire(8 * 60 * 60 * 1000L) }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { lm.removeUpdates(locListener) } catch (_: Exception) {}
        if (wifiRegistered) try { unregisterReceiver(wifiReceiver) } catch (_: Exception) {}
        try { bleScanner?.stopScan(bleCallback) } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        Store.onNewAlert = null
        Store.scanning = false
        Store.closeLog()
        ScanWidget.updateAll(this)
        super.onDestroy()
    }

    // ---------------- Location ----------------

    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            // Prefer GPS; accept network fixes only if we have nothing better.
            if (l.provider != LocationManager.GPS_PROVIDER && Store.hasFix && Store.acc < 50f) return
            Store.lat = l.latitude
            Store.lon = l.longitude
            Store.alt = l.altitude
            Store.acc = l.accuracy
            Store.hasFix = true
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
        } catch (_: Exception) {}
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, locListener, Looper.getMainLooper())
        } catch (_: Exception) {}
    }

    // ---------------- Wi-Fi ----------------

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = handleWifi()
    }

    private val wifiTick = object : Runnable {
        @Suppress("DEPRECATION")
        override fun run() {
            try { wifi.startScan() } catch (_: Exception) {}
            handler.postDelayed(this, WIFI_INTERVAL_MS)
        }
    }

    private fun startWifi() {
        ContextCompat.registerReceiver(
            this, wifiReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        wifiRegistered = true
        handler.post(wifiTick)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun handleWifi() {
        val results = try { wifi.scanResults } catch (_: Exception) { return }
        for (r in results) {
            val bssid = r.BSSID ?: continue
            Store.observe(
                Obs(
                    key = bssid.uppercase(),
                    kind = Kind.WIFI,
                    name = r.SSID ?: "",
                    rssi = r.level,
                    channel = freqToChannel(r.frequency),
                    freq = r.frequency,
                    auth = r.capabilities ?: "",
                    type = "WIFI",
                )
            )
        }
    }

    private fun freqToChannel(f: Int): Int = when {
        f == 2484 -> 14
        f in 2412..2472 -> (f - 2407) / 5
        f in 5160..5885 -> (f - 5000) / 5
        f in 5955..7115 -> (f - 5950) / 5
        else -> 0
    }

    // ---------------- Bluetooth LE ----------------

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: BleResult) = handleBle(result)
        override fun onBatchScanResults(results: MutableList<BleResult>) { results.forEach { handleBle(it) } }
    }

    @SuppressLint("MissingPermission")
    private fun startBle() {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = bm.adapter ?: return
        if (!adapter.isEnabled) return
        bleScanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        try { bleScanner?.startScan(null, settings, bleCallback) } catch (_: Exception) {}
    }

    private val bleRestart = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            try { bleScanner?.stopScan(bleCallback) } catch (_: Exception) {}
            startBle()
            handler.postDelayed(this, BLE_RESTART_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBle(r: BleResult) {
        val rec = r.scanRecord
        val mfg = HashMap<Int, ByteArray>()
        rec?.manufacturerSpecificData?.let { sa ->
            for (i in 0 until sa.size()) mfg[sa.keyAt(i)] = sa.valueAt(i)
        }
        val services = rec?.serviceUuids?.map { it.uuid.toString().lowercase() }?.toSet() ?: emptySet()
        val name = rec?.deviceName
            ?: try { r.device.name } catch (_: SecurityException) { null }
            ?: ""
        Store.observe(
            Obs(
                key = r.device.address.uppercase(),
                kind = Kind.BLE,
                name = name,
                rssi = r.rssi,
                auth = "Misc [LE]",
                type = "BLE",
                mfgData = mfg,
                services = services,
            )
        )
    }

    // ---------------- Cell towers ----------------

    private val cellTick = object : Runnable {
        override fun run() {
            readCells()
            handler.postDelayed(this, CELL_INTERVAL_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readCells() {
        val cells: List<CellInfo> = try { tel.allCellInfo } catch (_: Exception) { null } ?: return
        val na = CellInfo.UNAVAILABLE
        for (ci in cells) {
            when (ci) {
                is CellInfoLte -> {
                    val id = ci.cellIdentity
                    val mcc = id.mccString ?: continue
                    val mnc = id.mncString ?: continue
                    if (id.ci == na) continue
                    cell("$mcc${mnc}_${id.tac}_${id.ci}", id.operatorAlphaShort, ci.cellSignalStrength.dbm, id.earfcn, "LTE", mcc + mnc)
                }
                is CellInfoNr -> {
                    val id = ci.cellIdentity as CellIdentityNr
                    val mcc = id.mccString ?: continue
                    val mnc = id.mncString ?: continue
                    if (id.nci == CellInfo.UNAVAILABLE_LONG) continue
                    cell("$mcc${mnc}_${id.tac}_${id.nci}", id.operatorAlphaShort, ci.cellSignalStrength.dbm, id.nrarfcn, "NR", mcc + mnc)
                }
                is CellInfoGsm -> {
                    val id = ci.cellIdentity
                    val mcc = id.mccString ?: continue
                    val mnc = id.mncString ?: continue
                    if (id.cid == na) continue
                    cell("$mcc${mnc}_${id.lac}_${id.cid}", id.operatorAlphaShort, ci.cellSignalStrength.dbm, id.arfcn, "GSM", mcc + mnc)
                }
                is CellInfoWcdma -> {
                    val id = ci.cellIdentity
                    val mcc = id.mccString ?: continue
                    val mnc = id.mncString ?: continue
                    if (id.cid == na) continue
                    cell("$mcc${mnc}_${id.lac}_${id.cid}", id.operatorAlphaShort, ci.cellSignalStrength.dbm, id.uarfcn, "WCDMA", mcc + mnc)
                }
            }
        }
    }

    private fun cell(key: String, op: CharSequence?, dbm: Int, ch: Int, type: String, plmn: String) {
        Store.observe(
            Obs(
                key = key, kind = Kind.CELL, name = op?.toString() ?: "", rssi = dbm,
                channel = if (ch == CellInfo.UNAVAILABLE) 0 else ch,
                auth = "$type;$plmn", type = type,
            )
        )
    }

    // ---------------- Notifications ----------------

    private val statusTick = object : Runnable {
        override fun run() {
            Store.flush()
            val all = Store.sightings.values
            val w = all.count { it.kind == Kind.WIFI }
            val b = all.count { it.kind == Kind.BLE }
            val c = all.count { it.kind == Kind.CELL }
            val a = all.count { it.alert != null }
            val gps = if (Store.hasFix) "GPS ±${Store.acc.toInt()}m" else "no GPS"
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildStatus("WiFi $w · BLE $b · Cell $c · Alerts $a · $gps"))
            ScanWidget.updateAll(this@ScanService)
            handler.postDelayed(this, 5000)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Scanning", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Detections", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(false) }
        )
    }

    private fun buildStatus(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScanService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Wardrive Lite scanning")
            .setColor(Themes.current.accent)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun notifyAlert(s: Sighting) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val a = s.alert ?: return
        if (!Prefs.notify || a.severity < (Prefs.minSeverity.toIntOrNull() ?: 1)) return
        if (Prefs.vibrate && a.severity >= 3) vibrate()
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(a.label)
            .setContentText("${s.name.ifEmpty { s.key }} · ${s.rssi} dBm" + if (s.following) " · FOLLOWING YOU" else "")
            .setColor(Themes.current.accent)
            .setContentIntent(PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).putExtra("tab", 1), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setPriority(if (a.severity >= 3) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(s.key.hashCode(), n)
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
            else getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120, 80, 260), -1))
        } catch (_: Exception) {}
    }
}
