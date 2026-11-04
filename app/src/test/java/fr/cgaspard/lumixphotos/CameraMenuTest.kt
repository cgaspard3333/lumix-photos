package fr.cgaspard.lumixphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraMenuTest {

    // Menu réel d'un DMC-FZ1000 (firmware 2.60), sans donnée personnelle
    private val allMenu = javaClass.getResource("/fz1000-allmenu.xml")!!.readText()

    @Test
    fun `réglages du FZ1000 avec libellés français`() {
        val settings = CameraMenu.parse(allMenu).associateBy { it.type }
        val wb = settings.getValue("whitebalance")
        assertEquals("Éq.blancs", wb.title)
        assertTrue(wb.options.any { it.value == "daylight" && it.label == "Jour" })
        // Libellé absent en français : repli sur l'anglais
        assertTrue(settings.getValue("exposure").options.any { it.value == "-5/3" && it.label == "-1 2/3" })
        for (type in listOf("iso", "afmode", "quality", "aspectratio", "flash", "videoquality", "colormode", "peaking")) {
            assertNotNull("réglage $type", settings[type])
        }
    }

    @Test
    fun `rafale et retardateur distinguent la seconde valeur`() {
        val drive = CameraMenu.parse(allMenu).filter { it.type == "drivemode" }
        assertTrue(drive.size >= 3)
        val selfTimer = drive.first { s -> s.options.any { it.value == "selftimer" } }
        assertEquals(setOf("10sec", "10sec3shot", "2sec"), selfTimer.options.mapNotNull { it.value2 }.toSet())
    }

    @Test
    fun `valeur actuelle`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><camrply><result>ok</result><settingvalue exposure="1/3"></settingvalue></camrply>"""
        assertEquals("1/3" to null, CameraMenu.currentValue(xml, "exposure"))
        // Nom d'attribut invalide en XML (commence par un chiffre), tel que l'envoie le FZ1000
        val photo4k = """<?xml version="1.0" encoding="UTF-8"?><camrply><result>ok</result><settingvalue 4k_photo="off"></settingvalue></camrply>"""
        assertEquals("off" to null, CameraMenu.currentValue(photo4k, "4k_photo"))
        val drive = """<camrply><result>ok</result><settingvalue drivemode="burst" drivemode2="h"></settingvalue></camrply>"""
        assertEquals("burst" to "h", CameraMenu.currentValue(drive, "drivemode"))
    }

    @Test
    fun `ouverture et vitesse depuis les bornes de l'objectif`() {
        val limits = CameraMenu.lensLimits("ok,1536/256,761/256,3584/256,-1536/256,0,on,400,25,on,128/1024,on")!!
        val aperture = CameraMenu.apertureSetting(limits).options.map { it.label }
        assertEquals("f/2,8", aperture.first())
        assertEquals("f/8", aperture.last())
        val shutter = CameraMenu.shutterSetting(limits).options.map { it.label }
        assertEquals("1/16000", shutter.first())
        assertEquals("60 s", shutter.last())
        assertTrue("1/1000" in shutter && "1/60" in shutter)
        // Mode M : vitesse lente renvoyée sous forme de valeur sentinelle
        val manual = CameraMenu.lensLimits("ok,1536/256,761/256,3584/256,16384/256,0,on,400,25,on,128/1024,on")!!
        assertEquals(shutter, CameraMenu.shutterSetting(manual).options.map { it.label })
    }
}
