package fr.cgaspard.lumixphotos

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Les Lumix écrivent dans l'en-tête des MP4 (boîte « mvhd ») l'heure locale comme si c'était
 * l'heure UTC. Android et Google Photos lisent cette valeur : les vidéos apparaissent décalées
 * de 1 ou 2 heures. On corrige la copie (8 octets) avant qu'Android ne l'analyse.
 */
object Mp4Dates {

    /** Secondes entre l'origine des dates MP4 (1904-01-01 UTC) et l'époque Unix. */
    private const val MP4_EPOCH_OFFSET = 2_082_844_800L

    /**
     * Corrige l'heure de création/modification si elle vaut [takenAt] (heure locale de
     * l'appareil) lue comme de l'UTC. Renvoie true si le fichier a été modifié.
     */
    fun fixCreationTime(channel: FileChannel, writer: FileChannel, takenAt: LocalDateTime): Boolean {
        val mvhd = findMvhd(channel) ?: return false
        val header = ByteBuffer.allocate(4)
        channel.read(header, mvhd)
        val version = header.get(0).toInt()
        val fieldSize = if (version == 1) 8 else 4
        val fields = ByteBuffer.allocate(fieldSize * 2)
        channel.read(fields, mvhd + 4)
        fields.flip()
        val creation = if (version == 1) fields.long else fields.int.toLong() and 0xFFFFFFFFL

        val asIfUtc = takenAt.toEpochSecond(ZoneOffset.UTC) + MP4_EPOCH_OFFSET
        // Garde-fou : on ne touche qu'à un fichier qui présente exactement ce défaut
        if (abs(creation - asIfUtc) > 120) return false
        val correct = takenAt.atZone(ZoneId.systemDefault()).toEpochSecond() + MP4_EPOCH_OFFSET
        if (correct == creation) return false

        val out = ByteBuffer.allocate(fieldSize * 2)
        repeat(2) { if (version == 1) out.putLong(correct) else out.putInt(correct.toInt()) }
        out.flip()
        writer.write(out, mvhd + 4)
        return true
    }

    /** Position du contenu (après type) de moov/mvhd, ou null. */
    private fun findMvhd(channel: FileChannel): Long? {
        val moov = findBox(channel, 0, channel.size(), "moov") ?: return null
        return findBox(channel, moov.first, moov.second, "mvhd")?.first
    }

    /** Cherche une boîte [type] entre [start] et [end] ; renvoie (début du contenu, fin). */
    private fun findBox(channel: FileChannel, start: Long, end: Long, type: String): Pair<Long, Long>? {
        var pos = start
        val header = ByteBuffer.allocate(16)
        while (pos + 8 <= end) {
            header.clear()
            channel.read(header, pos)
            header.flip()
            if (header.remaining() < 8) return null
            var size = header.int.toLong() and 0xFFFFFFFFL
            val boxType = ByteArray(4).also { header.get(it) }.decodeToString()
            var headerSize = 8L
            when (size) {
                1L -> {
                    if (header.remaining() < 8) return null
                    size = header.long
                    headerSize = 16
                }
                0L -> size = end - pos
            }
            if (size < headerSize) return null
            if (boxType == type) return (pos + headerSize) to (pos + size)
            pos += size
        }
        return null
    }
}
