package io.github.mangome.camftp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import org.slf4j.LoggerFactory
import java.io.File

/**
 * 收到文件 → 写进系统相册 → 删源文件。
 * Android 10+ 走 MediaStore，本 App **不需要任何存储权限**。
 */
class MediaStoreSink(
    private val context: Context,
    private val folder: String = Config.DEFAULT_FOLDER,
) : Sink {

    private val log = LoggerFactory.getLogger("MediaStoreSink")

    override fun onStored(file: File): StoreResult {
        val name = file.name
        val mime = mimeOf(name)
        val (collection, relativePath) = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "DCIM/$folder"
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "DCIM/$folder"
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to "Download/$folder"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(collection, values)
            ?: return StoreResult(name, false, "保存失败")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("打不开相册")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            log.warn("入库失败，回滚半成品 {}", name, t)
            runCatching { resolver.delete(uri, null, null) }
            return StoreResult(name, false, "保存失败：${t.message ?: t::class.simpleName}")
        }

        // 只有入库成功才删源文件：失败就留在私有目录，下次启动重试（宁留垃圾不丢图）
        if (!file.delete()) log.warn("源文件删不掉：{}", file.absolutePath)
        return StoreResult(name, true, relativePath)
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "nef") return "image/x-nikon-nef"   // MimeTypeMap 不认 NEF
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
