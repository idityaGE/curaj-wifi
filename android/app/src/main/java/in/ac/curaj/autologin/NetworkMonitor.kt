package `in`.ac.curaj.autologin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/**
 * One authoritative Wi-Fi network for the whole app.
 *
 * The old code fell back to ConnectivityManager.allNetworks whenever mobile
 * data was the active network. That list can contain a stale, non-default
 * network; binding a socket to it throws EPERM on some Android builds. A
 * registered NetworkCallback always gives us a fresh, usable Network.
 */
object NetworkMonitor {

    data class WifiDetails(
        val ssid: String?,
        val ipAddress: String?,
        val rssi: Int?,
        val level: Int?,
        val signalPercent: Int?,
        val frequencyMhz: Int?,
        val linkSpeedMbps: Int?,
        val validated: Boolean,
        val captive: Boolean
    )

    data class Wifi(
        val network: Network,
        val details: WifiDetails?
    )

    private val _wifi = MutableStateFlow<Wifi?>(null)
    val wifi: StateFlow<Wifi?> = _wifi.asStateFlow()

    private var manager: ConnectivityManager? = null
    private var appContext: Context? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var clients = 0

    @Synchronized
    fun start(context: Context) {
        clients += 1
        if (callback != null) return

        val app = context.applicationContext
        appContext = app
        val cm = app.getSystemService(ConnectivityManager::class.java)
        manager = cm
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = accept(network)
            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities
            ) = accept(network)

            override fun onLost(network: Network) {
                if (_wifi.value?.network == network) _wifi.value = null
            }
        }
        callback = cb
        runCatching { cm.registerNetworkCallback(request, cb) }

        // This helps before the first callback arrives. It never consults
        // allNetworks, so it cannot select a stale network.
        cm.activeNetwork?.let { if (isWifi(it)) accept(it) }
    }

    @Synchronized
    fun stop(context: Context) {
        clients = (clients - 1).coerceAtLeast(0)
        if (clients > 0) return
        callback?.let { cb ->
            runCatching { manager?.unregisterNetworkCallback(cb) }
        }
        callback = null
        manager = null
        appContext = null
        _wifi.value = null
    }

    /** Fresh network to use for portal requests, or null while Wi-Fi is absent. */
    fun current(): Network? {
        val cm = manager ?: return null
        val saved = _wifi.value
        if (saved != null && cm.getNetworkCapabilities(saved.network) != null) {
            return saved.network
        }
        val active = cm.activeNetwork
        if (active != null && isWifi(active)) {
            accept(active)
            return active
        }
        return null
    }

    private fun isWifi(network: Network): Boolean {
        val cm = manager ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private fun accept(network: Network) {
        if (!isWifi(network)) return
        _wifi.value = Wifi(network, readDetails(network))
    }

    private fun readDetails(network: Network): WifiDetails? {
        val cm = manager ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        val link = cm.getLinkProperties(network)

        val capsInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                caps.transportInfo as? WifiInfo
            } else {
                null
            }
        }.getOrNull()
        val legacyInfo = runCatching {
            val wm = appContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.connectionInfo
        }.getOrNull()
        val candidates = listOfNotNull(capsInfo, legacyInfo)

        val ssid = candidates.firstNotNullOfOrNull { info ->
            runCatching { cleanSsid(info.ssid) }.getOrNull()
        }

        val rssi = candidates.firstNotNullOfOrNull { info ->
            runCatching { info.rssi.takeIf { it in -126..-1 } }.getOrNull()
        }

        val level = rssi?.let {
            runCatching { WifiManager.calculateSignalLevel(it, 5) }.getOrNull()
        }
        val signalPercent = rssi?.let {
            runCatching {
                WifiManager.calculateSignalLevel(it, 101).coerceIn(0, 100)
            }.getOrNull()
        }

        val frequency = candidates.firstNotNullOfOrNull { info ->
            runCatching { info.frequency.takeIf { it > 0 } }.getOrNull()
        }

        val linkSpeed = candidates.firstNotNullOfOrNull { info ->
            runCatching { info.linkSpeed.takeIf { it > 0 } }.getOrNull()
        }

        return WifiDetails(
            ssid = ssid,
            ipAddress = ipv4(link),
            rssi = rssi,
            level = level,
            signalPercent = signalPercent,
            frequencyMhz = frequency,
            linkSpeedMbps = linkSpeed,
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        )
    }

    private fun cleanSsid(value: String?): String? =
        value
            ?.removeSurrounding("\"")
            ?.takeIf {
                it.isNotBlank() &&
                    !it.equals("<unknown ssid>", ignoreCase = true)
            }

    private fun ipv4(link: LinkProperties?): String? =
        link?.linkAddresses?.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress
        }?.address?.hostAddress

    fun detailsPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    fun hasDetailsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, detailsPermission()
        ) == PackageManager.PERMISSION_GRANTED
}
