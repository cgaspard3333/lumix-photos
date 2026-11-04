package fr.cgaspard.lumixphotos

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TimeZone

class Mp4DatesTest {

    private val mp4Epoch = 2_082_844_800L
    private val takenAt = LocalDateTime.of(2026, 7, 25, 11, 50, 25)
    private lateinit var savedZone: TimeZone
    private lateinit var file: File

    @Before
    fun setUp() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        file = File.createTempFile("test", ".mp4")
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(savedZone)
        file.delete()
    }

    private fun box(type: String, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(8 + content.size)
            writeBytes(type)
            write(content)
        }
        return out.toByteArray()
    }

    /** ftyp + mdat + moov(mvhd v0), comme les MP4 des Lumix (moov en fin de fichier). */
    private fun writeMp4(creation: Long) {
        val mvhd = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeInt(0) // version 0, flags
                writeInt(creation.toInt())
                writeInt(creation.toInt())
                write(ByteArray(88))
            }
        }.toByteArray()
        file.writeBytes(box("ftyp", "mp42".toByteArray()) + box("mdat", ByteArray(1000)) + box("moov", box("mvhd", mvhd)))
    }

    private fun readCreation(): Long {
        val bytes = file.readBytes()
        val mvhd = String(bytes, Charsets.ISO_8859_1).indexOf("mvhd") + 4
        return RandomAccessFile(file, "r").use { it.seek(mvhd + 4L); it.readInt().toLong() and 0xFFFFFFFFL }
    }

    private fun fix(): Boolean = RandomAccessFile(file, "rw").use { raf ->
        Mp4Dates.fixCreationTime(raf.channel, raf.channel, takenAt)
    }

    @Test
    fun `corrige l'heure locale notée comme de l'UTC`() {
        writeMp4(takenAt.toEpochSecond(ZoneOffset.UTC) + mp4Epoch)
        assertTrue(fix())
        // 11:50:25 à Paris en été = 09:50:25 UTC
        assertEquals(LocalDateTime.of(2026, 7, 25, 9, 50, 25).toEpochSecond(ZoneOffset.UTC) + mp4Epoch, readCreation())
    }

    @Test
    fun `ne touche pas une date déjà correcte`() {
        val correct = LocalDateTime.of(2026, 7, 25, 9, 50, 25).toEpochSecond(ZoneOffset.UTC) + mp4Epoch
        writeMp4(correct)
        assertFalse(fix())
        assertEquals(correct, readCreation())
    }

    @Test
    fun `fichier sans moov`() {
        file.writeBytes(box("ftyp", "mp42".toByteArray()))
        assertFalse(fix())
    }
}
