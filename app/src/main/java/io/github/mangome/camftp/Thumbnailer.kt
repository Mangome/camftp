package io.github.mangome.camftp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.util.Size
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 入库时生成一张小图（长边 384px / JPEG q=80），跟着「最近收到」一起落盘。
 *
 * 不用 `ContentResolver.loadThumbnail` 有两条原因：
 *  1. NEF 这类 RAW 系统解码器解不了，只有自己抽内嵌 JPEG 才有图（[EmbeddedJpeg]）；
 *  2. 网格要在原图被相册删掉之后照样显示 —— 那说明这一份必须由我们留下。
 *
 * **方向必须自己修**：`BitmapFactory` 不应用 EXIF orientation（系统相册会转，所以这个坑只在这一层看得见），
 * 不修的话竖拍的照片在网格里是躺倒的 —— 方形裁剪下连构图都是错的。
 *
 * 任何一步失败都返回 null：缩略图是装饰，绝不能让入库背它的锅。调用方在入库线程上调（不占 MINA IO 线程）。
 */
object Thumbnailer {

    private const val MAX_EDGE = 384
    private const val QUALITY = 80

    /**
     * MimeTypeMap 认不出后缀时的兜底：这些后缀按视频试一把。
     * 主要是别把一个几 GB 的 `.mts` 当图片整个读一遍找内嵌 JPEG（入库是单线程，会挡住后面的图）。
     */
    private val VIDEO = setOf("mp4", "m4v", "mov", "3gp", "3g2", "avi", "mts", "m2ts", "mkv", "wmv", "mpg", "mpeg")

    /** 找内嵌预览前的体积上限：超过这个就不值得为一个缩略图整读一遍（消费级相机的 RAW 都远小于它） */
    private const val MAX_SCAN = 256L * 1024 * 1024

    /** [mime] 来自 [MediaStoreSink.mimeOf] */
    fun of(file: File, mime: String): ByteArray? = runCatching {
        val decoded = when {
            mime.startsWith("video/") || file.extension.lowercase() in VIDEO -> video(file)?.let(::Decoded)
            else -> image(file)   // 图片 / RAW / 后缀不认识的，全按内容试
        } ?: return null
        encode(decoded)
    }.getOrNull()

    private class Decoded(val bitmap: Bitmap, val transform: ExifTransform = ExifTransform.NONE)

    /**
     * 先按普通图片解（JPG/PNG/HEIC/WebP…）：`BitmapFactory` 按内容识别，跟厂商、后缀都无关。
     * 解不出来（TIFF 系 RAW：NEF/CR2/ARW/DNG/ORF/RW2/PEF，以及 `.tif` 之类）就去文件里找内嵌 JPEG 预览 ——
     * 这条路**不认后缀白名单**，谁嵌了 JPEG 谁就有图，换品牌不用改代码。
     */
    private fun image(file: File): Decoded? {
        decodeFile(file)?.let { return Decoded(it, orientationOf(file.absolutePath)) }
        if (file.length() > MAX_SCAN) return null
        val preview = EmbeddedJpeg.largest(file) ?: return null
        return decodeBytes(preview)?.let { Decoded(it, orientationOf(preview)) }
    }

    /** 视频：帧的旋转由 `ThumbnailUtils` 自己带（走 MediaMetadataRetriever） */
    private fun video(file: File): Bitmap? =
        ThumbnailUtils.createVideoThumbnail(file, Size(MAX_EDGE, MAX_EDGE), null)

    /** 读方向失败（格式不支持 / 压根没有 EXIF）就当正常：这块是装饰，不能因为它把图丢了 */
    private fun orientationOf(path: String): ExifTransform = runCatching {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.map(::exifTransform).getOrDefault(ExifTransform.NONE)

    /** RAW 走这条：预览 JPEG 自带 EXIF（相机一般把主图的 EXIF 抄了一份进去） */
    private fun orientationOf(jpeg: ByteArray): ExifTransform = runCatching {
        ExifInterface(ByteArrayInputStream(jpeg))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.map(::exifTransform).getOrDefault(ExifTransform.NONE)

    /**
     * 先缩后转：转整张 8000×6000 的原图要多占一份几十 MB 的位图，而这一步只是为了 384px 的缩略图。
     * 等比缩放下「先缩再转」和「先转再缩」结果一样（照片的方向修正只有 90 的倍数和镜像）。
     */
    private fun encode(decoded: Decoded): ByteArray {
        val scaled = scale(decoded.bitmap)
        val upright = if (decoded.transform == ExifTransform.NONE) scaled else rotate(scaled, decoded.transform)
        try {
            val out = ByteArrayOutputStream()
            upright.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            return out.toByteArray()
        } finally {
            upright.recycle()
        }
    }

    /** @return 长边 ≤ [MAX_EDGE] 的位图；新建了就回收 [src]（**调用方不能再碰 src**） */
    private fun scale(src: Bitmap): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= MAX_EDGE) return src
        val factor = MAX_EDGE.toFloat() / long
        val width = (src.width * factor).toInt().coerceAtLeast(1)
        val height = (src.height * factor).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, width, height, true).also { src.recycle() }
    }

    private fun rotate(src: Bitmap, transform: ExifTransform): Bitmap {
        val matrix = Matrix()
        if (transform.degree != 0) matrix.postRotate(transform.degree.toFloat())
        if (transform.flip) matrix.postScale(-1f, 1f)   // 「先转、再左右翻」，见 [ExifTransform]
        // createBitmap 会把内容平移到新位图里，不会切掉边缘
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true).also { src.recycle() }
    }

    private fun decodeFile(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null   // 不认识的格式（含 RAW）
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** 只按 2 的幂降采样（解码器支持的唯一值），剩下的零头交给 [scale] */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= MAX_EDGE) sample *= 2
        return sample
    }
}
