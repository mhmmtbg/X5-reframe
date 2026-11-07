package com.mhmmtbg.x5reframe.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher

/**
 * Kameranın Wi-Fi'sine bağlantı. İki yol:
 *  1) Uygulama içinden: Android'in "cihaza bağlan" penceresi kameranın ağını (adı "X5 …" ile başlar) listeler,
 *     kullanıcı seçer. Bu ağ yalnızca uygulamaya açılır; telefonun interneti mobil veriden sürer.
 *  2) Telefon ayarlarından zaten bağlanılmışsa: Wi-Fi ağlarından kamerayı yanıtlayan bulunur.
 * Her iki durumda da istekler bu ağa bağlanır (internetsiz Wi-Fi'de Android trafiği mobil veriye yönlendirebilir).
 */
object CameraLink {
    @Volatile var network: Network? = null
        private set
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun client(): OscClient = OscClient(network = network)

    /** Telefon zaten kameranın ağındaysa onu bul. */
    fun findExisting(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val wifis = cm.allNetworks.filter { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        for (n in wifis) {
            if (runCatching { OscClient(network = n).info() }.isSuccess) { network = n; return n }
        }
        return null
    }

    /**
     * Sistem penceresiyle kameranın ağına bağlan.
     * @param prefix ağ adı başlangıcı ("X5"), [password] kameranın Wi-Fi parolası (varsayılan 88888888)
     */
    fun connect(context: Context, prefix: String, password: String, onResult: (Network?, String?) -> Unit) {
        release(context)
        val spec = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(prefix.trim(), PatternMatcher.PATTERN_PREFIX))
            .apply { if (password.isNotBlank()) setWpa2Passphrase(password) }
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        var answered = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                if (!answered) { answered = true; onResult(n, null) }
            }
            override fun onUnavailable() {
                if (!answered) { answered = true; onResult(null, "Kamera ağı bulunamadı ya da bağlantı reddedildi") }
            }
            override fun onLost(n: Network) { if (network == n) network = null }
        }
        callback = cb
        cm.requestNetwork(req, cb, 60_000)
    }

    fun release(context: Context) {
        callback?.let { runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        callback = null
        network = null
    }
}
