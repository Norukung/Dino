package com.group16.dinoblog.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import android.net.Uri
import android.os.Build
import android.os.StatFs
import com.group16.dinoblog.dtn.DtnConfig
import com.group16.dinoblog.model.MediaManifest
import com.group16.dinoblog.model.MediaPolicy
import com.group16.dinoblog.model.MessageContentType
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Only generated UUIDs select files; remote filenames are never paths. */
class MediaFileStore(private val context: Context) {
    val directory = File(context.filesDir, "dinoblog_media").also { require(it.isDirectory || it.mkdirs()) }
    private fun safe(id: String): String {
        require(runCatching { UUID.fromString(id).toString().equals(id, ignoreCase = true) }.getOrDefault(false))
        return id.lowercase()
    }
    fun finalFile(mediaId: String) = File(directory, "${safe(mediaId)}.media")
    fun partFile(mediaId: String) = File(directory, "${safe(mediaId)}.part")
    fun hasSpace(bytes: Long): Boolean = StatFs(directory.absolutePath).availableBytes > bytes + 1_048_576
    fun delete(mediaId: String) { finalFile(mediaId).delete(); partFile(mediaId).delete() }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verified(media: MediaManifest): Boolean {
        val file = finalFile(media.mediaId)
        return file.isFile && file.length() == media.fileSize && sha256(file) == media.sha256
    }

    fun readChunk(media: MediaManifest, index: Int): ByteArray? {
        if (index !in 0 until media.totalChunks || !verifiedFileSize(media)) return null
        val offset = index.toLong() * media.chunkSize
        val length = minOf(media.chunkSize.toLong(), media.fileSize - offset).toInt()
        return RandomAccessFile(finalFile(media.mediaId), "r").use { file ->
            file.seek(offset)
            ByteArray(length).also { file.readFully(it) }
        }
    }

    private fun verifiedFileSize(media: MediaManifest) = finalFile(media.mediaId).let { it.isFile && it.length() == media.fileSize }

    fun writeChunk(media: MediaManifest, index: Int, bytes: ByteArray) {
        require(index in 0 until media.totalChunks)
        val offset = index.toLong() * media.chunkSize
        require(bytes.size == minOf(media.chunkSize.toLong(), media.fileSize - offset).toInt())
        RandomAccessFile(partFile(media.mediaId), "rw").use { file ->
            file.seek(offset)
            file.write(bytes)
            file.fd.sync()
        }
    }

    fun finish(media: MediaManifest): Boolean {
        val partial = partFile(media.mediaId)
        if (!partial.isFile || partial.length() != media.fileSize || sha256(partial) != media.sha256) return false
        if (media.mediaType == MessageContentType.IMAGE) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(partial.absolutePath, bounds)
            if (bounds.outWidth !in 1..20_000 || bounds.outHeight !in 1..20_000 ||
                bounds.outWidth.toLong() * bounds.outHeight > 100_000_000L) return false
        } else if (!canReadMedia(partial.absolutePath, media.mediaType)) return false
        val final = finalFile(media.mediaId)
        if (final.exists()) final.delete()
        return partial.renameTo(final)
    }

    private fun canReadMedia(path: String, type: MessageContentType): Boolean = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            duration != null && duration > 0 && (type != MessageContentType.VIDEO ||
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.let { it > 0 } == true)
        } finally { retriever.release() }
    }.getOrDefault(false)

    fun prepare(uri: Uri, type: MessageContentType): PreparedImage {
        require(type in setOf(MessageContentType.AUDIO, MessageContentType.VIDEO))
        val resolver = context.contentResolver
        val mime = requireNotNull(resolver.getType(uri)?.lowercase()) { "Unsupported media type" }
        require(mime in MediaPolicy.mimes(type)) { "Unsupported ${type.name.lowercase()} format" }
        val limit = MediaPolicy.onlineMax(type)
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0))
                require(cursor.getLong(0) in 1..limit) { "${type.name.lowercase().replaceFirstChar { it.uppercase() }} is too large to send" }
        }
        val mediaId = UUID.randomUUID().toString()
        val temp = partFile(mediaId)
        val final = finalFile(mediaId)
        try {
            var copied = 0L
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        copied += n
                        require(copied <= limit) { "${type.name.lowercase()} is too large to send" }
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
            require(copied > 0 && canReadMedia(temp.absolutePath, type)) { "Cannot play selected media" }
            val retriever = MediaMetadataRetriever()
            val metadata = try {
                retriever.setDataSource(temp.absolutePath)
                Triple(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull())
            } finally { retriever.release() }
            require(temp.renameTo(final)) { "Could not save media" }
            val manifest = MediaManifest("", mediaId, mime, when (type) {
                MessageContentType.AUDIO -> "audio"
                else -> "video"
            }, copied, sha256(final), MediaPolicy.CHUNK_BYTES,
                ((copied + MediaPolicy.CHUNK_BYTES - 1) / MediaPolicy.CHUNK_BYTES).toInt(),
                type, metadata.first, metadata.second, metadata.third)
            return PreparedImage(manifest, final.absolutePath)
        } catch (error: Exception) { temp.delete(); final.delete(); throw error }
    }

    fun prepare(uri: Uri): PreparedImage {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)
        require(mime in setOf("image/jpeg", "image/png", "image/webp")) { "Choose a JPEG, PNG or WebP image" }
        val bitmap = decodeSampled(uri)
        try {
            val scale = minOf(1f, DtnConfig.MAX_IMAGE_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
            val width = maxOf(1, (bitmap.width * scale).toInt())
            val height = maxOf(1, (bitmap.height * scale).toInt())
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            val opaque = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
            if (scaled !== bitmap) scaled.recycle()
            val mediaId = UUID.randomUUID().toString()
            val final = finalFile(mediaId)
            val temp = partFile(mediaId)
            try {
                for (quality in listOf(85, 75, 65, 55, 45)) {
                    FileOutputStream(temp).use { output -> require(opaque.compress(Bitmap.CompressFormat.JPEG, quality, output)) }
                    if (temp.length() in 1..DtnConfig.MAX_IMAGE_BYTES.toLong()) break
                }
                require(temp.length() in 1..DtnConfig.MAX_IMAGE_BYTES.toLong()) { "Image is too large to send" }
                require(temp.renameTo(final)) { "Could not save image" }
                val size = final.length()
                val manifest = MediaManifest("", mediaId, "image/jpeg", "image.jpg", size,
                    sha256(final), DtnConfig.IMAGE_CHUNK_BYTES,
                    ((size + DtnConfig.IMAGE_CHUNK_BYTES - 1) / DtnConfig.IMAGE_CHUNK_BYTES).toInt(),
                    MessageContentType.IMAGE, width = width, height = height)
                return PreparedImage(manifest, final.absolutePath)
            } catch (e: Exception) { temp.delete(); final.delete(); throw e }
            finally { opaque.recycle() }
        } finally { bitmap.recycle() }
    }

    private fun decodeSampled(uri: Uri): Bitmap {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val longEdge = maxOf(info.size.width, info.size.height)
                require(longEdge in 1..20_000 && info.size.width.toLong() * info.size.height <= 100_000_000L) { "Image dimensions are too large" }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(maxOf(1, (longEdge + DtnConfig.MAX_IMAGE_EDGE - 1) / DtnConfig.MAX_IMAGE_EDGE))
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val edge = maxOf(bounds.outWidth, bounds.outHeight)
        require(edge in 1..20_000 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L) { "Invalid image dimensions" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = maxOf(1, (edge + DtnConfig.MAX_IMAGE_EDGE - 1) / DtnConfig.MAX_IMAGE_EDGE)
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: error("Could not read image")
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            }
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { if (it !== decoded) decoded.recycle() }
    }
}
