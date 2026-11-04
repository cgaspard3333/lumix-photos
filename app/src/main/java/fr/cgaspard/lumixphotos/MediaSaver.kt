package fr.cgaspard.lumixphotos

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Enregistre les fichiers dans la galerie, sans permission de stockage (Android 10+). */
object MediaSaver {

    /** Suffixe des copies en taille réduite. */
    const val REDUCED_SUFFIX = "_reduite"

    // Android interdit les dossiers à la racine du stockage : DCIM accepte photos et vidéos
    private val IMAGE_DIR = "${Environment.DIRECTORY_DCIM}/Lumix/Image"
    private val VIDEO_DIR = "${Environment.DIRECTORY_DCIM}/Lumix/Video"
    private val OTHER_DIR = "${Environment.DIRECTORY_DOWNLOADS}/Lumix"
    // Emplacements des premières versions de l'appli
    private val LEGACY_DIRS = listOf("${Environment.DIRECTORY_PICTURES}/Lumix", "${Environment.DIRECTORY_MOVIES}/Lumix")

    /**
     * Crée le fichier dans la galerie et y fait écrire le contenu par [write] (le réseau est géré
     * par l'appelant), puis corrige les dates avant de le rendre visible.
     */
    suspend fun save(
        context: Context,
        item: CameraItem,
        fileName: String,
        takenAt: LocalDateTime?,
        /** Orientation EXIF annoncée par l'appareil (les aperçus réduits n'ont pas d'EXIF). */
        rotation: Int,
        write: suspend (OutputStream) -> Unit,
    ): Uri {
        val (collection, path) = when {
            item.mimeType == "image/jpeg" ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to IMAGE_DIR
            item.isVideo ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to VIDEO_DIR
            // RAW (RW2) et autres : MediaStore.Images les refuse, on les met dans Téléchargements
            else ->
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to OTHER_DIR
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: error("Impossible de créer $fileName")
        try {
            resolver.openOutputStream(uri)!!.use { write(it) }
            if (takenAt != null && item.mimeType == "video/mp4") {
                runCatching {
                    resolver.openFileDescriptor(uri, "rw")?.use { fd ->
                        FileInputStream(fd.fileDescriptor).channel.use { reader ->
                            FileOutputStream(fd.fileDescriptor).channel.use { writer ->
                                Mp4Dates.fixCreationTime(reader, writer, takenAt)
                            }
                        }
                    }
                }
            }
            if (takenAt != null && item.mimeType == "image/jpeg") {
                runCatching { resolver.openFileDescriptor(uri, "rw")?.use { completeExif(it.fileDescriptor, takenAt, rotation) } }
            }
            // La galerie n'analyse le fichier qu'à sa publication : les corrections ci-dessus seront prises en compte
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /**
     * Android ne date une photo que si l'EXIF précise le fuseau horaire, ce que les Lumix ne font
     * pas : sans cela, la galerie la classe à la date du téléchargement. On ajoute uniquement les
     * balises absentes (fuseau ; date et orientation pour les copies réduites, qui n'ont pas d'EXIF).
     */
    private fun completeExif(fd: FileDescriptor, takenAt: LocalDateTime, rotation: Int) {
        val exif = ExifInterface(fd)
        var changed = false
        fun setIfMissing(tag: String, value: String) {
            if (exif.getAttribute(tag).isNullOrBlank()) {
                exif.setAttribute(tag, value)
                changed = true
            }
        }
        val stamp = takenAt.format(EXIF_DATE)
        setIfMissing(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
        setIfMissing(ExifInterface.TAG_DATETIME, stamp)
        val offset = ZoneId.systemDefault().rules.getOffset(takenAt).id.let { if (it == "Z") "+00:00" else it }
        setIfMissing(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
        setIfMissing(ExifInterface.TAG_OFFSET_TIME, offset)
        if (rotation != ExifInterface.ORIENTATION_NORMAL && exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) == 0) {
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, rotation.toString())
            changed = true
        }
        if (changed) exif.saveAttributes()
    }

    private val COLLECTIONS = listOf(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
    )

    /** Un fichier de la galerie copié par l'appli. */
    data class LocalCopy(val uri: Uri, val size: Long, val reduced: Boolean)

    /**
     * Copies présentes dans la galerie, par nom de fichier côté appareil. Une photo effacée du
     * téléphone redevient ainsi « à télécharger ». Sans permission de lecture, Android ne montre
     * que les fichiers créés par l'appli : c'est justement ce qu'on veut.
     */
    fun localCopies(context: Context): Map<String, List<LocalCopy>> {
        val copies = HashMap<String, MutableList<LocalCopy>>()
        val dirs = listOf(IMAGE_DIR, VIDEO_DIR, OTHER_DIR) + LEGACY_DIRS
        val selection = dirs.joinToString(" OR ") { "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" }
        val args = dirs.map { "$it/%" }.toTypedArray()
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
        for (collection in COLLECTIONS) {
            runCatching {
                context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1)
                        val reduced = name.contains("$REDUCED_SUFFIX.")
                        copies.getOrPut(name.replace("$REDUCED_SUFFIX.", ".")) { mutableListOf() } +=
                            LocalCopy(ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getLong(2), reduced)
                    }
                }
            }
        }
        return copies
    }

    /**
     * La copie correspond-elle bien à ce fichier de l'appareil ? Avec plusieurs cartes SD, deux
     * fichiers différents peuvent porter le même nom : on compare aussi la taille (à quelques Ko
     * près, l'appli complétant l'EXIF des photos). Une copie réduite compte comme téléchargée.
     */
    fun matches(copy: LocalCopy, originalSize: Long): Boolean =
        copy.reduced || originalSize <= 0 || abs(copy.size - originalSize) <= 16 * 1024
}
