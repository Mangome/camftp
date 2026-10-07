package io.github.mangome.camftp

/**
 * EXIF 方向 → 「顺时针转多少度 + 转完再左右翻转」。
 *
 * 这张表跟 androidx `ExifInterface.getRotationDegrees()` / `isFlipped()` 的定义一致（`Matrix` 在
 * JVM 单测里是桩，所以把能测的部分单独拎出来：**表错了竖拍的照片就会躺倒**）。
 * 取值就是 EXIF Orientation 的原始数值（1..8），不引 `android.media.ExifInterface` 的常量，保持纯 JVM。
 */
internal data class ExifTransform(val degree: Int, val flip: Boolean) {
    companion object {
        /** 1（正常）、0、以及读不出来的取值 */
        val NONE = ExifTransform(0, false)
    }
}

internal fun exifTransform(orientation: Int): ExifTransform = when (orientation) {
    2 -> ExifTransform(0, true)       // FLIP_HORIZONTAL：只左右翻
    3 -> ExifTransform(180, false)    // ROTATE_180
    4 -> ExifTransform(180, true)     // FLIP_VERTICAL = 转 180 再左右翻
    5 -> ExifTransform(270, true)     // TRANSPOSE
    6 -> ExifTransform(90, false)     // ROTATE_90：竖着拍最常写的就是这个
    7 -> ExifTransform(90, true)      // TRANSVERSE
    8 -> ExifTransform(270, false)    // ROTATE_270
    else -> ExifTransform.NONE
}
