package ph.ojt.attendance

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.abs

enum class TimeStatus { OK, NEEDS_SYNC, AUTO_TIME_OFF, CLOCK_MISMATCH }

/**
 * Trusted Philippine time that ignores the phone's clock.
 * 1) Syncs with NTP servers when online and stores an anchor (NTP time + elapsedRealtime).
 * 2) Offline, time = anchor + (elapsedRealtime now - elapsedRealtime at sync).
 *    elapsedRealtime cannot be changed from Settings.
 * 3) If the phone clock disagrees with trusted time, or "Automatic date & time" is off, the app locks.
 * 4) A reboot invalidates the anchor (elapsedRealtime resets), so a new sync is required.
 */
object TimeAuthority {
    private const val PREFS = "time_anchor"
    private const val MAX_DRIFT_MS = 120_000L
    private val HOSTS = listOf("time.google.com", "time.cloudflare.com", "ph.pool.ntp.org", "pool.ntp.org")

    private fun bootCount(ctx: Context): Int =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /** Blocking. Call from a background thread. */
    fun sync(ctx: Context): Boolean {
        for (h in HOSTS) {
            try {
                val r = query(h) ?: continue
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong("ntp", r.first)
                    .putLong("elapsed", r.second)
                    .putInt("boot", bootCount(ctx))
                    .apply()
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun query(host: String): Pair<Long, Long>? {
        DatagramSocket().use { s ->
            s.soTimeout = 4000
            val addr = InetAddress.getByName(host)
            val buf = ByteArray(48)
            buf[0] = 0x1B
            val t0 = SystemClock.elapsedRealtime()
            s.send(DatagramPacket(buf, buf.size, addr, 123))
            val resp = DatagramPacket(ByteArray(48), 48)
            s.receive(resp)
            val t1 = SystemClock.elapsedRealtime()
            val rtt = t1 - t0
            if (rtt > 3000) return null
            val d = resp.data
            fun u32(o: Int): Long {
                var v = 0L
                for (i in 0..3) v = (v shl 8) or (d[o + i].toLong() and 0xFF)
                return v
            }
            val sec = u32(40)
            val frac = u32(44)
            if (sec == 0L) return null
            val ms = (sec - 2208988800L) * 1000L + ((frac * 1000L) shr 32)
            return Pair(ms + rtt / 2, t1)
        }
    }

    fun trustedNowMs(ctx: Context): Long? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("ntp")) return null
        if (p.getInt("boot", -2) != bootCount(ctx)) return null
        val el = SystemClock.elapsedRealtime() - p.getLong("elapsed", 0L)
        if (el < 0) return null
        return p.getLong("ntp", 0L) + el
    }

    fun status(ctx: Context): TimeStatus {
        val now = trustedNowMs(ctx) ?: return TimeStatus.NEEDS_SYNC
        if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME, 1) == 0)
            return TimeStatus.AUTO_TIME_OFF
        if (abs(System.currentTimeMillis() - now) > MAX_DRIFT_MS) return TimeStatus.CLOCK_MISMATCH
        return TimeStatus.OK
    }
}
