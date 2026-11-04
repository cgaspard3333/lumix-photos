package fr.cgaspard.lumixphotos

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

data class WifiCredentials(val ssid: String, val password: String)

object WifiQr {

    /** Ouvre le scanner de Google Play Services ; [onResult] reçoit null si le code n'est pas reconnu. */
    fun scan(context: Context, onResult: (WifiCredentials?) -> Unit, onError: (String) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode -> onResult(barcode.rawValue?.let(::parse)) }
            .addOnFailureListener { onError(it.message ?: "Scanner indisponible") }
    }

    /**
     * Les Lumix affichent « MDL: …\nSSID: FZ1000-XXXXXX\nPW: … » ; on accepte aussi le format
     * Wi-Fi standard « WIFI:S:…;P:…;; ».
     */
    fun parse(text: String): WifiCredentials? {
        if (text.startsWith("WIFI:")) {
            val fields = Regex("""([SPT]):((?:\\.|[^;])*);""").findAll(text.removePrefix("WIFI:"))
                .associate { it.groupValues[1] to it.groupValues[2].replace(Regex("""\\(.)"""), "$1") }
            val ssid = fields["S"] ?: return null
            return WifiCredentials(ssid, fields["P"].orEmpty())
        }
        val lines = text.lines().associate { it.substringBefore(':').trim().uppercase() to it.substringAfter(':', "").trim() }
        val ssid = lines["SSID"]?.takeIf { it.isNotEmpty() } ?: return null
        return WifiCredentials(ssid, lines["PW"].orEmpty())
    }
}
