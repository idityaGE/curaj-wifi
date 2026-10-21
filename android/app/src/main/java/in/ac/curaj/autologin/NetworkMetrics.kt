package `in`.ac.curaj.autologin

import android.net.Network
import android.os.Process
import android.os.SystemClock
import android.net.TrafficStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class NetworkMetrics(
    val latencyMs: Long? = null,
    val lossPercent: Int? = null,
    val downloadBps: Long? = null,
    val uploadBps: Long? = null,
    val updatedAt: Long = 0L
)

/**
 * Android apps cannot send raw ICMP, so latency and loss are measured with
 * five small HTTP requests to the campus portal. All requests are bound to
 * the supplied Wi-Fi network, never to mobile data.
 */
class NetworkMetricsSampler {

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L

    suspend fun sample(network: Network): NetworkMetrics = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 }
        val tx = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 }
        val dt = now - lastAt

        val download = if (lastAt > 0 && dt in 200..60_000 && lastRx >= 0 && rx != null) {
            ((rx - lastRx).coerceAtLeast(0) * 1000L / dt)
        } else {
            null
        }
        val upload = if (lastAt > 0 && dt in 200..60_000 && lastTx >= 0 && tx != null) {
            ((tx - lastTx).coerceAtLeast(0) * 1000L / dt)
        } else {
            null
        }

        lastRx = rx ?: lastRx
        lastTx = tx ?: lastTx
        lastAt = now

        val results = (1..PROBES).map { probe(network) }
        val successes = results.filterNotNull().sorted()
        val latency = if (successes.isEmpty()) null else successes[successes.size / 2]
        val loss = ((PROBES - successes.size) * 100) / PROBES

        NetworkMetrics(
            latencyMs = latency,
            lossPercent = loss,
            downloadBps = download,
            uploadBps = upload,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun probe(network: Network): Long? = try {
        val conn = network.openConnection(
            URL("http://122.252.242.93/userportal/")
        ) as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 1500
        conn.readTimeout = 1500
        conn.requestMethod = "GET"
        val start = SystemClock.elapsedRealtime()
        conn.responseCode
        val elapsed = SystemClock.elapsedRealtime() - start
        runCatching { conn.inputStream?.close() }
        conn.disconnect()
        elapsed
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val PROBES = 5
    }
}
