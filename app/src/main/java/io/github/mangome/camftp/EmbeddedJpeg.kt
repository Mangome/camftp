package io.github.mangome.camftp

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 从任意文件里扫出「最大的内嵌 JPEG」。
 *
 * NEF / TIFF 系 RAW 的布局是「原始数据 + 旁边挂一张（或多张不同尺寸的）JPEG 预览」。
 * `BitmapFactory` 解不了 TIFF，所以抽内嵌预览是 RAW 出图的唯一办法
 * （相机实测 Nikon Z50II 传的就是 `.NEF`，见 handoff §6）。
 *
 * 扫描规则：`FF D8 FF`（SOI 后面必须紧跟一个 marker 字节）开始，到 `FF D9`（EOI）结束。
 * 熵编码段里的 `FF` 被 `FF 00` 转义，这两个序列不会在图像数据里假命中，单趟流式扫描就够。
 * 逐 chunk 处理，不把几十 MB 的 RAW 整个读进内存。纯 JVM：`./gradlew test` 守得住。
 */
object EmbeddedJpeg {

    /** 超过这个长度的候选不是「预览」，是误命中：边找 EOI 边涨到上限就丢掉重找 */
    private const val MAX_CANDIDATE = 16 * 1024 * 1024

    private const val CHUNK = 64 * 1024

    /** 找不到内嵌 JPEG 时返回 null（调用方给占位格子，不是错误） */
    fun largest(file: File): ByteArray? {
        var best: ByteArray? = null
        var candidate: ByteArrayOutputStream? = null
        var prev = -1        // 前一个字节
        var prevPrev = -1    // 前两个字节（都跨 chunk 保留，命中点会被切成两半）

        file.inputStream().buffered(CHUNK).use { input ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                for (i in 0 until n) {
                    val b = buf[i].toInt() and 0xFF
                    val cur = candidate
                    if (cur == null) {
                        if (prevPrev == 0xFF && prev == 0xD8 && b == 0xFF) {
                            // 已经流失的前两个字节补回去，候选从 SOI 起完整
                            candidate = ByteArrayOutputStream().apply {
                                write(0xFF)
                                write(0xD8)
                                write(0xFF)
                            }
                        }
                    } else {
                        cur.write(b)
                        when {
                            prev == 0xFF && b == 0xD9 -> {
                                val jpeg = cur.toByteArray()
                                if (jpeg.size > (best?.size ?: 0)) best = jpeg
                                candidate = null
                            }
                            cur.size() > MAX_CANDIDATE -> candidate = null
                        }
                    }
                    prevPrev = prev
                    prev = b
                }
            }
        }
        return best
    }
}
