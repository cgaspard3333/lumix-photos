package fr.cgaspard.lumixphotos

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
     * Copie [firstStream] dans la galerie. Si la connexion coupe en cours de route, [reopen] est
     * appelé avec le nombre d'octets déjà reçus pour reprendre là où on en était.
     */
    suspend fun save(
        context: Context,
        item: CameraItem,
        fileName: String,
        takenAt: LocalDateTime?,
        /** Orientation EXIF annoncée par l'appareil (les aperçus réduits n'ont pas d'EXIF). */
        rotation: Int,
        totalBytes: Long,
        firstStream: InputStream,
        reopen: suspend (offset: Long) -> InputStream,
        onProgress: (bytesCopied: Long) -> Unit,
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
            resolver.openOutputStream(uri)!!.use { out ->
                var input = firstStream
                try {
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    var reported = 0L
                    while (true) {
                        val n = try {
                            input.read(buf)
                        } catch (e: IOException) {
                            if (totalBytes <= 0) throw e
                            -1
                        }
                        if (n < 0) {
                            // Fin prématurée (coupure Wi-Fi…) : on reprend à partir du dernier octet reçu
                            if (totalBytes > 0 && copied < totalBytes) {
                                currentCoroutineContext().ensureActive()
                                runCatching { input.close() }
                                input = reopen(copied)
                                continue
                            }
                            break
                        }
                        out.write(buf, 0, n)
                        copied += n
                        // Une mise à jour par Mo suffit, même pour une vidéo 4K de plusieurs centaines de Mo
                        if (copied - reported >= 1_000_000) {
                            reported = copied
                            // La lecture réseau est bloquante : l'annulation se vérifie ici
                            currentCoroutineContext().ensureActive()
                            onProgress(copied)
                        }
                    }
                    onProgress(copied)
                    if (totalBytes > 0 && copied != totalBytes) {
                        throw IOException("Fichier incomplet : $copied / $totalBytes octets")
                    }
                } finally {
                    runCatching { input.close() }
                }
            }
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

    /**
     * Noms (côté appareil) des fichiers réellement présents dans la galerie. Une photo effacée
     * du téléphone redevient ainsi « à télécharger ». Sans permission de lecture, Android ne
     * montre que les fichiers créés par l'appli : c'est justement ce qu'on veut.
     */
    fun downloadedNames(context: Context): Set<String> {
        val names = HashSet<String>()
        val dirs = listOf(IMAGE_DIR, VIDEO_DIR, OTHER_DIR) + LEGACY_DIRS
        val selection = dirs.joinToString(" OR ") { "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" }
        val args = dirs.map { "$it/%" }.toTypedArray()
        for (collection in listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        )) {
            runCatching {
                context.contentResolver.query(
                    collection, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), selection, args, null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) names += cursor.getString(0).replace("$REDUCED_SUFFIX.", ".")
                }
            }
        }
        return names
    }
}
