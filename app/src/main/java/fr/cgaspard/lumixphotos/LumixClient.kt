package fr.cgaspard.lumixphotos

import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.time.LocalDateTime

/** Un fichier présent sur la carte SD de l'appareil. */
data class CameraItem(
    val id: String,
    val title: String,
    val mimeType: String,
    val originalUrl: String,
    val thumbnailUrl: String?,
    /** JPEG réduit (1620×1080, ~1 Mo) : aperçu plein écran, et copie rapide des photos. */
    val previewUrl: String?,
    /** « 4K », « 1080p »… pour les vidéos, d'après l'info Panasonic de la ressource. */
    val videoFormat: String? = null,
) {
    val isVideo get() = mimeType.startsWith("video/")
    /** Pour une vidéo, l'aperçu n'est qu'une image extraite : pas de copie « réduite ». */
    val canReduce get() = !isVideo && previewUrl != null
}

/** Infos absentes de la liste DLNA, que l'appareil donne dans les en-têtes HTTP de chaque fichier. */
data class FileMeta(
    val date: LocalDateTime?,
    val size: Long,
    /** Valeur d'orientation EXIF : 1 = normale, 3 = 180°, 6 = 90° horaire, 8 = 90° anti-horaire. */
    val rotation: Int,
) {
    companion object {
        fun fromHeaders(header: (String) -> String?) = FileMeta(
            date = header("X-REC_DATE_TIME")?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
            size = header("X-FILE_SIZE")?.toLongOrNull() ?: -1L,
            rotation = header("X-ROTATE_INFO")?.toIntOrNull() ?: 1,
        )
    }
}

class CameraResponse(val stream: InputStream, val meta: FileMeta)

/**
 * Client pour les Lumix Wi-Fi (FZ1000, GH4, GX8, G80…) en mode
 * « Prise de vue à distance et affichage ».
 *
 * - Port 80 : API cam.cgi (passage en mode lecture, keep-alive)
 * - Port 60606 : serveur UPnP/DLNA ContentDirectory (liste des fichiers)
 * - Port 50001 : serveur de fichiers (DO = original, DL = grand, DT = vignette)
 */
class LumixClient(val host: String) {

    private val camUrl = "http://$host/cam.cgi"
    private val cdsUrl = "http://$host:60606/Server0/CDS_control"

    /** Enregistre l'application auprès de l'appareil, passe en mode lecture et renvoie son état. */
    fun connect(deviceName: String, deviceId: String): String {
        // Les firmwares récents exigent cette demande d'accès ; les anciens l'ignorent.
        runCatching {
            camCommand("mode=accctrl&type=req_acc&value=$deviceId&value2=${deviceName.urlEncode()}")
        }
        val state = camCommand("mode=getstate")
        if (!state.contains("<result>ok</result>")) {
            throw IOException("Réponse inattendue de l'appareil : $state")
        }
        camCommand("mode=camcmd&value=playmode")
        return state
    }

    /** Évite que l'appareil coupe la session en l'absence d'activité ; renvoie son état (batterie…). */
    fun keepAlive(): String = camCommand("mode=getstate")

    /**
     * Nom du point d'accès Wi-Fi de l'appareil (ex. « FZ1000-A1B2C3 ») : c'est aussi son nom UPnP,
     * qu'on obtient en l'interrogeant directement en SSDP puis en lisant sa description.
     */
    fun friendlyName(): String? = runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = 2_000
            val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n").toByteArray()
            socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName(host), 1900))
            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            val location = Regex("(?im)^LOCATION:\\s*(\\S+)").find(String(buf, 0, packet.length))!!.groupValues[1]
            val conn = open(location, timeoutMs = 5_000)
            try {
                val xml = conn.inputStream.use { it.readBytes().decodeToString() }
                Regex("<friendlyName>([^<]+)</friendlyName>").find(xml)?.groupValues?.get(1)
            } finally {
                conn.disconnect()
            }
        }
    }.getOrNull()

    fun listItems(): List<CameraItem> {
        val result = mutableListOf<CameraItem>()
        browseRecursive("0", result, depth = 0)
        // Plus récents en premier : la numérotation dossier+fichier de l'appareil suit l'ordre de prise de vue
        return result.sortedByDescending { it.id }
    }

    private fun browseRecursive(objectId: String, out: MutableList<CameraItem>, depth: Int) {
        var start = 0
        while (true) {
            val page = browse(objectId, start, PAGE_SIZE)
            for (container in page.containers) {
                if (depth < 4) browseRecursive(container, out, depth + 1)
            }
            out += page.items
            start += page.returned
            if (page.returned == 0 || start >= page.total) break
        }
    }

    private class BrowsePage(
        val items: List<CameraItem>,
        val containers: List<String>,
        val returned: Int,
        val total: Int,
    )

    private fun browse(objectId: String, start: Int, count: Int): BrowsePage {
        val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1" xmlns:pana="urn:schemas-panasonic-com:pana">
<ObjectID>$objectId</ObjectID>
<BrowseFlag>BrowseDirectChildren</BrowseFlag>
<Filter>*</Filter>
<StartingIndex>$start</StartingIndex>
<RequestedCount>$count</RequestedCount>
<SortCriteria></SortCriteria>
<pana:X_FromCP>LumixLink2.0</pana:X_FromCP>
</u:Browse>
</s:Body>
</s:Envelope>"""
        val conn = open(cdsUrl, timeoutMs = 20_000)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) {
                throw IOException("Browse : HTTP ${conn.responseCode}")
            }
            val envelope = conn.inputStream.use { it.readBytes().decodeToString() }
            Log.d(TAG, "<- Browse $start+$count : ${envelope.length} octets")
            val didl = extractTag(envelope, "Result") ?: throw IOException("Réponse Browse invalide")
            val total = extractTag(envelope, "TotalMatches")?.toIntOrNull() ?: 0
            val returned = extractTag(envelope, "NumberReturned")?.toIntOrNull() ?: 0
            val (items, containers) = parseDidl(didl)
            return BrowsePage(items, containers, returned, total)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Ouvre un flux sur un fichier de l'appareil (original, aperçu ou vignette).
     *
     * [reuseConnection] : l'appareil met ~140 ms à accepter une connexion, contre ~36 ms par
     * requête sur une connexion gardée ouverte. Utile pour les petits fichiers enchaînés.
     */
    fun openStream(
        url: String,
        timeoutMs: Int = 30_000,
        reuseConnection: Boolean = false,
        /** Début de la plage voulue : l'appareil gère les requêtes Range. */
        offset: Long = 0,
        /** Taille maximale de la plage (téléchargement par morceaux), ou null jusqu'à la fin. */
        length: Long? = null,
    ): CameraResponse {
        val conn = open(url, timeoutMs)
        if (!reuseConnection) conn.setRequestProperty("Connection", "close")
        val ranged = offset > 0 || length != null
        if (ranged) conn.setRequestProperty("Range", "bytes=$offset-${length?.let { offset + it - 1 } ?: ""}")
        val ok = if (ranged) {
            // Sans 206 + bon Content-Range, on recopierait le début du fichier à la suite
            conn.responseCode == 206 && conn.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true
        } else {
            conn.responseCode == 200
        }
        if (!ok) {
            conn.disconnect()
            throw IOException("HTTP ${conn.responseCode} pour $url")
        }
        // Le serveur répond en « chunked » sans Content-Length, mais donne la taille dans X-FILE_SIZE
        return CameraResponse(conn.inputStream, FileMeta.fromHeaders(conn::getHeaderField))
    }

    /** Date, taille et orientation d'un fichier, sans le télécharger (~36 ms, connexion réutilisée). */
    fun fetchMeta(url: String): FileMeta {
        val conn = open(url, timeoutMs = 5_000)
        conn.requestMethod = "HEAD"
        // Pas de disconnect() : la connexion retourne au pool pour la requête suivante
        if (conn.responseCode != 200) {
            conn.disconnect()
            throw IOException("HTTP ${conn.responseCode} pour $url")
        }
        return FileMeta.fromHeaders(conn::getHeaderField)
    }

    private fun camCommand(query: String): String {
        val conn = open("$camUrl?$query", timeoutMs = 8_000)
        try {
            return conn.inputStream.use { it.readBytes().decodeToString() }
                .also { Log.d(TAG, "<- ${conn.responseCode} ${it.take(200)}") }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        if (BuildConfig.DEBUG) Log.d(TAG, "-> $url")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = timeoutMs
            useCaches = false
        }
    }

    companion object {
        private const val TAG = "LumixClient"
        // L'appareil plafonne de toute façon à 50 éléments par page
        private const val PAGE_SIZE = 50

        private fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8")

        /** Contenu texte (déséchappé) du premier élément `tag`, quel que soit son préfixe. */
        private fun extractTag(xml: String, tag: String): String? {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name.substringAfter(':') == tag) {
                    return parser.nextText()
                }
            }
            return null
        }

        private fun parseDidl(didl: String): Pair<List<CameraItem>, List<String>> {
            val items = mutableListOf<CameraItem>()
            val containers = mutableListOf<String>()
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(StringReader(didl))

            var id: String? = null
            var title: String? = null
            val resources = mutableListOf<Pair<String, String>>() // protocolInfo -> url

            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "container" -> parser.getAttributeValue(null, "id")?.let { containers += it }
                        "item" -> {
                            id = parser.getAttributeValue(null, "id")
                            title = null; resources.clear()
                        }
                        "title" -> if (id != null) title = parser.nextText().trim()
                        "res" -> if (id != null) {
                            val info = parser.getAttributeValue(null, "protocolInfo") ?: ""
                            resources += info to parser.nextText().trim()
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "item" && id != null) {
                        toItem(id, title, resources)?.let { items += it }
                        id = null
                    }
                }
            }
            return items to containers
        }

        private fun toItem(
            id: String,
            title: String?,
            resources: List<Pair<String, String>>,
        ): CameraItem? {
            if (resources.isEmpty()) return null
            fun fileName(url: String) = url.substringAfterLast('/')
            // Sur les Lumix, le préfixe du nom de fichier indique la taille : DO/DL/DT.
            val original = resources.firstOrNull { fileName(it.second).startsWith("DO") }
                ?: resources.firstOrNull { !it.first.contains("JPEG_TN") && !it.first.contains("JPEG_SM") }
                ?: resources.first()
            val thumb = resources.firstOrNull { fileName(it.second).startsWith("DT") }
                ?: resources.firstOrNull { it.first.contains("JPEG_TN") }
                ?: resources.firstOrNull { fileName(it.second).startsWith("DL") }
            // L'appareil annonce ses vidéos en application/octet-stream : on se fie alors à l'extension
            val mime = original.first.split(':').getOrNull(2)
                ?.takeIf { it.contains('/') && it != "application/octet-stream" }
                ?: guessMime(fileName(original.second))
            // Ex. PANASONIC.COM_PN=CAM_AVC_MP4_HP_2160_24P_AAC
            val height = Regex("""_(\d{3,4})_\d+[PI]_""").find(original.first)?.groupValues?.get(1)?.toInt()
            val videoFormat = when {
                !mime.startsWith("video/") || height == null -> null
                height >= 2160 -> "4K"
                else -> "${height}p"
            }
            val preview = resources.firstOrNull {
                fileName(it.second).startsWith("DL") || fileName(it.second).startsWith("DS")
            }
            val ext = fileName(original.second).substringAfterLast('.', "")
            val name = when {
                title.isNullOrBlank() -> fileName(original.second)
                ext.isNotEmpty() && !title.contains('.') -> "$title.$ext"
                else -> title
            }
            return CameraItem(id, name, mime, original.second, thumb?.second, preview?.second, videoFormat)
        }

        private fun guessMime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "rw2" -> "image/x-panasonic-rw2"
            "mp4" -> "video/mp4"
            "mts" -> "video/mp2t"
            "mov" -> "video/quicktime"
            else -> "application/octet-stream"
        }
    }
}
