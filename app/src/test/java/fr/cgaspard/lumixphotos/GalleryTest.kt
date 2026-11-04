package fr.cgaspard.lumixphotos

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class GalleryTest {

    private fun item(n: Int) = CameraItem(
        id = "$n", title = "100-$n.JPG", mimeType = "image/jpeg",
        originalUrl = "http://x/DO$n.JPG", thumbnailUrl = null, previewUrl = null,
    )

    private fun meta(day: Int) = FileMeta(LocalDateTime.of(2026, 7, day, 12, 0), 1000, 1)

    @Test
    fun `regroupe par jour`() {
        val items = listOf(item(3), item(2), item(1))
        val gallery = Gallery.build(items, mapOf("100-3.JPG" to meta(26), "100-2.JPG" to meta(26), "100-1.JPG" to meta(25)))
        val headers = gallery.entries.filterIsInstance<DayHeader>()
        assertEquals(listOf(LocalDate.of(2026, 7, 26), LocalDate.of(2026, 7, 25)), headers.map { it.day })
        assertEquals(listOf(2, 1), headers.map { it.count })
        assertEquals(5, gallery.entries.size)
    }

    @Test
    fun `un fichier sans date reste dans la section précédente`() {
        val items = listOf(item(3), item(2), item(1))
        val gallery = Gallery.build(items, mapOf("100-3.JPG" to meta(26), "100-1.JPG" to meta(26)))
        val headers = gallery.entries.filterIsInstance<DayHeader>()
        assertEquals(1, headers.size)
        assertEquals(3, headers.single().count)
    }

    @Test
    fun `dates encore inconnues en tête de liste`() {
        val gallery = Gallery.build(listOf(item(2), item(1)), mapOf("100-1.JPG" to meta(25)))
        val headers = gallery.entries.filterIsInstance<DayHeader>()
        assertEquals(listOf(null, LocalDate.of(2026, 7, 25)), headers.map { it.day })
        // La bulle du défilement rapide renvoie à la section de chaque ligne
        assertEquals(headers[1], gallery.headerAt(gallery.entries.lastIndex))
    }
}
