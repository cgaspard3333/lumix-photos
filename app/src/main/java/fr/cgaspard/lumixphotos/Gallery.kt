package fr.cgaspard.lumixphotos

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
private val MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH)

/** Une ligne de la galerie : un en-tête de jour ou un fichier. */
sealed interface GalleryEntry {
    val key: String
}

data class DayHeader(val day: LocalDate?, val count: Int) : GalleryEntry {
    override val key get() = "day:$day"
    val label: String
        get() = day?.format(DAY_FORMAT)?.replaceFirstChar { it.uppercase() } ?: "Date en cours de lecture…"
    /** Libellé court affiché dans la bulle du défilement rapide. */
    val monthLabel: String
        get() = day?.format(MONTH_FORMAT)?.replaceFirstChar { it.uppercase() } ?: "…"
}

data class FileEntry(val item: CameraItem) : GalleryEntry {
    override val key get() = item.id
}

class Gallery(val entries: List<GalleryEntry>, private val headerOf: IntArray) {
    /** En-tête de la section qui contient la ligne [index]. */
    fun headerAt(index: Int): DayHeader? =
        headerOf.getOrNull(index.coerceIn(0, (headerOf.size - 1).coerceAtLeast(0)))
            ?.let { entries[it] as DayHeader }

    companion object {
        val EMPTY = Gallery(emptyList(), IntArray(0))

        /**
         * Regroupe les fichiers par jour de prise de vue. Les fichiers dont la date n'est pas
         * encore connue restent dans la section précédente, pour éviter que tout bouge
         * pendant le chargement des dates.
         */
        fun build(items: List<CameraItem>, meta: Map<String, FileMeta>): Gallery {
            val entries = ArrayList<GalleryEntry>(items.size + 64)
            val headerOf = ArrayList<Int>(items.size + 64)
            var headerIndex = -1
            var currentDay: LocalDate? = null
            var count = 0

            fun closeSection() {
                if (headerIndex >= 0) entries[headerIndex] = DayHeader(currentDay, count)
            }

            for (item in items) {
                val day = meta[item.title]?.date?.toLocalDate()
                if (headerIndex < 0 || (day != null && day != currentDay)) {
                    closeSection()
                    currentDay = day
                    headerIndex = entries.size
                    entries += DayHeader(currentDay, 0)
                    headerOf += headerIndex
                    count = 0
                }
                entries += FileEntry(item)
                headerOf += headerIndex
                count++
            }
            closeSection()
            return Gallery(entries, headerOf.toIntArray())
        }
    }
}

/**
 * Mémorise date/taille/orientation des fichiers pour ne pas les redemander à l'appareil.
 *
 * Une table par carte SD : plusieurs cartes utilisées dans le même appareil peuvent contenir
 * des fichiers de même nom. À la connexion, on reconnaît la carte en vérifiant auprès de
 * l'appareil quelques fichiers de chaque table connue.
 */
class MetaCache(context: Context) {
    private val root = File(context.filesDir, "meta")
    // Cache unique des premières versions, avant la gestion de plusieurs cartes
    private val legacy = File(context.filesDir, "meta.tsv")
    private var current: File? = null

    suspend fun selectCard(
        camera: String,
        items: List<CameraItem>,
        check: suspend (CameraItem) -> FileMeta?,
    ): Map<String, FileMeta> {
        val dir = File(root, camera.replace(Regex("[^A-Za-z0-9_-]"), "_")).apply { mkdirs() }
        if (legacy.exists()) legacy.renameTo(File(dir, "${UUID.randomUUID()}.tsv"))
        val byTitle = items.associateBy { it.title }
        val tables = dir.listFiles { f -> f.name.endsWith(".tsv") }.orEmpty().sortedByDescending { it.lastModified() }
        for (file in tables) {
            val table = read(file)
            val common = table.keys.filter { it in byTitle }.sorted()
            if (common.isEmpty()) continue
            val count = minOf(SAMPLE_SIZE, common.size)
            val sample = (0 until count).map { common[it * common.size / count] }
            val same = sample.all { title ->
                val cached = table.getValue(title)
                check(byTitle.getValue(title))?.let { it.size == cached.size && it.date == cached.date } == true
            }
            if (same) {
                current = file
                return table
            }
        }
        current = File(dir, "${UUID.randomUUID()}.tsv")
        return emptyMap()
    }

    fun save(meta: Map<String, FileMeta>) {
        val file = current ?: return
        val tmp = File(file.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            for ((name, m) in meta) {
                w.append(name).append('\t')
                    .append(m.date?.toEpochSecond(ZoneOffset.UTC)?.toString() ?: "").append('\t')
                    .append(m.size.toString()).append('\t')
                    .append(m.rotation.toString()).append('\n')
            }
        }
        tmp.renameTo(file)
    }

    private fun read(file: File): Map<String, FileMeta> = runCatching {
        file.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 4) return@mapNotNull null
            p[0] to FileMeta(
                date = p[1].toLongOrNull()?.let { LocalDateTime.ofEpochSecond(it, 0, ZoneOffset.UTC) },
                size = p[2].toLongOrNull() ?: -1L,
                rotation = p[3].toIntOrNull() ?: 1,
            )
        }.toMap()
    }.getOrDefault(emptyMap())

    private companion object {
        /** Fichiers vérifiés par table : quelques requêtes HEAD, moins d'une seconde. */
        const val SAMPLE_SIZE = 4
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes >= 1_000_000_000 -> String.format(Locale.FRENCH, "%.2f Go", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.FRENCH, "%.1f Mo", bytes / 1e6)
    else -> String.format(Locale.FRENCH, "%d Ko", bytes / 1000)
}

fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds.coerceAtLeast(1)} s"
    seconds < 3600 -> "${seconds / 60} min ${(seconds % 60).toString().padStart(2, '0')}"
    else -> "${seconds / 3600} h ${((seconds % 3600) / 60).toString().padStart(2, '0')}"
}
