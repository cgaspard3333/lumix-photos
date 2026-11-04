package fr.cgaspard.lumixphotos

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPlanTest {

    private val photo = CameraItem("1", "100-0001.JPG", "image/jpeg", "u", null, "p")
    private val video = CameraItem("2", "100-0002.MP4", "video/mp4", "u", null, "p", "4K")
    private val unknown = CameraItem("3", "100-0003.JPG", "image/jpeg", "u", null, "p")

    private val plan = DownloadPlan(
        items = listOf(photo, video, unknown),
        sizes = mapOf("1" to 10_000_000L, "2" to 400_000_000L, "3" to -1L),
        alreadyDownloaded = setOf("2"),
        bytesPerSecond = 2_000_000,
        freeBytes = 1_000_000_000,
    )

    @Test
    fun `ignore les fichiers déjà présents`() {
        assertEquals(listOf(photo, unknown), plan.items(skipExisting = true))
        assertEquals(10_000_000L, plan.bytes(skipExisting = true, reduced = false))
        assertEquals(410_000_000L, plan.bytes(skipExisting = false, reduced = false))
    }

    @Test
    fun `taille réduite pour les photos seulement`() {
        val reduced = 2 * DownloadPlan.REDUCED_PHOTO_BYTES
        assertEquals(reduced, plan.bytes(skipExisting = true, reduced = true))
        assertEquals(reduced + 400_000_000L, plan.bytes(skipExisting = false, reduced = true))
    }

    @Test
    fun `tailles inconnues`() {
        assertEquals(1, plan.unknownSizes(skipExisting = true))
    }
}
