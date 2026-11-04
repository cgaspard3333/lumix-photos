package fr.cgaspard.lumixphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiQrTest {

    @Test
    fun `format affiché par les Lumix`() {
        val text = "MDL: DSC-1\n\nSSID: FZ1000-A1B2C3\nPW: secret123\n"
        assertEquals(WifiCredentials("FZ1000-A1B2C3", "secret123"), WifiQr.parse(text))
    }

    @Test
    fun `format Wi-Fi standard, avec caractères échappés`() {
        assertEquals(WifiCredentials("Ma;Box", "p:a\\ss"), WifiQr.parse("""WIFI:T:WPA;S:Ma\;Box;P:p\:a\\ss;;"""))
    }

    @Test
    fun `réseau ouvert`() {
        assertEquals(WifiCredentials("Ouvert", ""), WifiQr.parse("WIFI:S:Ouvert;;"))
    }

    @Test
    fun `QR code sans identifiants Wi-Fi`() {
        assertNull(WifiQr.parse("https://example.com"))
        assertNull(WifiQr.parse("MDL: DSC-1\nPW: x"))
    }
}
