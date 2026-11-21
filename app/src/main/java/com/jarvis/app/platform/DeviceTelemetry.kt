package com.jarvis.app.platform

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import com.jarvis.app.domain.model.NetworkSnapshot
import com.jarvis.app.domain.model.TelemetrySnapshot
import java.net.Inet4Address
import java.net.NetworkInterface

class DeviceTelemetry(private val context: Context) {

    fun snapshot(): TelemetrySnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val ramTotalMb = (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(1L)
        val ramAvailMb = memInfo.availMem / (1024L * 1024L)
        val ramUsedMb = (ramTotalMb - ramAvailMb).coerceAtLeast(0L)
        val ramPercent = ((ramUsedMb * 100L) / ramTotalMb).toInt().coerceIn(0, 100)

        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.blockCountLong * stat.blockSizeLong
        val availBytes = stat.availableBlocksLong * stat.blockSizeLong
        val usedBytes = (totalBytes - availBytes).coerceAtLeast(0L)
        val storageTotalGb = (totalBytes / (1024f * 1024f * 1024f)).coerceAtLeast(0.1f)
        val storageUsedGb = usedBytes / (1024f * 1024f * 1024f)
        val storagePercent = if (totalBytes > 0) ((usedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0 && scale > 0) ((level * 100) / scale).coerceIn(0, 100) else 0
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC = tempTenths / 10f
        val healthCode = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD)
        val health = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Degraded"
            else -> "Good"
        }

        return TelemetrySnapshot(
            ramUsedMb = ramUsedMb,
            ramTotalMb = ramTotalMb,
            ramPercent = ramPercent,
            storageUsedGb = storageUsedGb,
            storageTotalGb = storageTotalGb,
            storagePercent = storagePercent,
            batteryPercent = batteryPct,
            isCharging = isCharging,
            batteryTempC = tempC,
            batteryHealth = health,
            network = readNetworkSnapshot()
        )
    }

    fun readNetworkSnapshot(publicIp: String = "—", cityRegion: String = "—"): NetworkSnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = if (active != null) cm.getNetworkCapabilities(active) else null
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val transport = when {
            caps == null || !connected -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Online"
        }
        return NetworkSnapshot(
            connected = connected,
            transport = transport,
            localIp = localIpv4Address(),
            publicIp = publicIp,
            cityRegion = cityRegion
        )
    }

    private fun localIpv4Address(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "—"
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "—"
                    }
                }
            }
            "—"
        } catch (_: Exception) {
            "—"
        }
    }

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun lastKnownDeviceLocation(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var best: Location? = null
            for (p in providers) {
                if (lm.isProviderEnabled(p)) {
                    @Suppress("MissingPermission")
                    val loc = lm.getLastKnownLocation(p)
                    if (loc != null && (best == null || loc.time > best.time)) {
                        best = loc
                    }
                }
            }
            best?.let { it.latitude to it.longitude }
        } catch (_: Exception) {
            null
        }
    }
}
