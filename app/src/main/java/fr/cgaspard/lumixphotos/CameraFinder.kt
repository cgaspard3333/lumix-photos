package fr.cgaspard.lumixphotos

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.resume

/** IP de l'appareil quand on est connecté directement à son point d'accès Wi-Fi. */
const val DIRECT_MODE_IP = "192.168.54.1"

object CameraFinder {

    /**
     * Force le trafic de l'appli à passer par le Wi-Fi. Indispensable en connexion
     * directe : le point d'accès de l'appareil n'a pas d'accès Internet, Android
     * enverrait sinon les requêtes sur les données mobiles.
     */
    fun bindToWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifi: Network? = cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        return cm.bindProcessToNetwork(wifi)
    }

    /**
     * Demande à Android de rejoindre le Wi-Fi de l'appareil. La première fois, Android affiche
     * une fenêtre de confirmation. Le Wi-Fi domestique n'est pas coupé : l'appareil devient un
     * réseau local supplémentaire, réservé à l'appli, et tout son trafic y est dirigé.
     *
     * Renvoie le callback à désenregistrer pour libérer le réseau, ou null en cas d'échec.
     */
    suspend fun joinCameraWifi(
        context: Context,
        ssid: String,
        passphrase: String,
        onLost: () -> Unit,
    ): ConnectivityManager.NetworkCallback? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .apply { if (passphrase.isNotEmpty()) setWpa2Passphrase(passphrase) }
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        var registered: ConnectivityManager.NetworkCallback? = null
        val result = withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine { cont ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    // Aussi rappelé après une coupure suivie d'une reconnexion : on se relie au nouveau réseau
                    override fun onAvailable(network: Network) {
                        cm.bindProcessToNetwork(network)
                        if (cont.isActive) cont.resume(this)
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onLost(network: Network) = onLost()
                }
                registered = callback
                cm.requestNetwork(request, callback)
            }
        }
        if (result == null) registered?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        return result
    }

    fun releaseCameraWifi(context: Context, callback: ConnectivityManager.NetworkCallback) {
        runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
    }

    /** Cherche un appareil Lumix sur le réseau Wi-Fi courant via SSDP. */
    fun discover(context: Context, timeoutMs: Int = 3_000): String? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("lumix-ssdp").apply { setReferenceCounted(false); acquire() }
        try {
            MulticastSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0))
                socket.soTimeout = 500
                val group = InetAddress.getByName("239.255.255.250")
                for (st in listOf(
                    "urn:schemas-upnp-org:device:MediaServer:1",
                    "urn:schemas-upnp-org:service:ContentDirectory:1",
                )) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                    val bytes = msg.toByteArray()
                    socket.send(DatagramPacket(bytes, bytes.size, group, 1900))
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length)
                    // Le serveur DLNA des Lumix écoute sur le port 60606
                    if (text.contains(":60606/") || text.contains("Panasonic", ignoreCase = true)) {
                        return packet.address.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
            // SSDP indisponible : on se rabat sur les IP connues
        } finally {
            lock.release()
        }
        return null
    }

    /** Vérifie rapidement que l'API de pilotage répond sur cette adresse. */
    fun isReachable(host: String, timeoutMs: Int = 1_500): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, 80), timeoutMs) }
        true
    } catch (_: Exception) {
        false
    }
}
