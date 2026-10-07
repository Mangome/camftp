package io.github.mangome.camftp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

/**
 * NEF 出图的唯一通路：`BitmapFactory` 解不了 TIFF，抽内嵌 JPEG 是唯一办法。
 *
 * 夹具是一张真实的最小 1x1 JPEG（160 字节，SOI 在 0、EOI 在末尾）。
 * 不用 `javax.imageio` 造图：`java.desktop` 不在单测的编译类路径上（AGP 拿 android.jar 当 bootclasspath）。
 */
class EmbeddedJpegTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `从原始数据里挑出最大的那张内嵌 JPEG`() {
        val small = MINIMAL_JPEG
        val big = jpegWithComment(4096)
        val file = tmp.newFile("DSC_0001.NEF")
        file.outputStream().use { out ->
            out.write(ByteArray(4096) { 0x1A })   // 伪 RAW 数据
            out.write(small)                      // 相机常见布局：先一张小缩略图
            out.write(ByteArray(1024))
            out.write(big)                        // 真正的预览在后面、更大
            out.write(ByteArray(2048))
        }

        val found = EmbeddedJpeg.largest(file)!!
        assertArrayEquals("要挑大的那张，而且一个字节都不能多、不能少", big, found)
    }

    @Test
    fun `没有内嵌 JPEG 的原始数据返回 null`() {
        val file = tmp.newFile("DSC_0002.NEF")
        file.writeBytes(ByteArray(8192) { 0x00 })
        assertNull(EmbeddedJpeg.largest(file))
    }

    @Test
    fun `只有 SOI 没有 EOI 的假命中不算图`() {
        val file = tmp.newFile("DSC_0003.NEF")
        file.outputStream().use { out ->
            out.write(ByteArray(1024) { 0x7F })
            out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
            out.write(ByteArray(1024) { 0x7F })   // 永远等不到结尾
        }
        assertNull(EmbeddedJpeg.largest(file))
    }

    /** 命中点会被 chunk 边界切开：跨 chunk 的状态（前两个字节）必须保住 */
    @Test
    fun `内嵌 JPEG 跨越读取缓冲区边界也能取出来`() {
        val jpeg = jpegWithComment(64)
        // 让 SOI 的 FF D8 正好落在第一个 64KB chunk 的最后两个字节
        val file = tmp.newFile("DSC_0004.NEF")
        file.outputStream().use { out ->
            out.write(ByteArray(64 * 1024 - 2) { 0x11 })
            out.write(jpeg)
        }
        assertEquals(jpeg.size, EmbeddedJpeg.largest(file)!!.size)
    }

    /**
     * 在 SOI 后面插一个 COM 段（`FF FE <长度> <内容>`）——JPEG 允许 marker 流里带注释，
     * 这样既有不同大小的候选，又不用 JPEG 编码器。
     */
    private fun jpegWithComment(size: Int): ByteArray = ByteArrayOutputStream().also { out ->
        out.write(MINIMAL_JPEG, 0, 2)                          // SOI
        out.write(0xFF)
        out.write(0xFE)
        out.write((size + 2) shr 8)
        out.write((size + 2) and 0xFF)
        out.write(ByteArray(size) { 0x41 })                    // 不含 0xFF，不会造出假的 EOI
        out.write(MINIMAL_JPEG, 2, MINIMAL_JPEG.size - 2)      // 其余原样
    }.toByteArray()

    private companion object {
        /** 最小 1x1 JPEG，真实编码器的产物（SOI@0 / EOI@158，各只出现一次） */
        val MINIMAL_JPEG: ByteArray = java.util.Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcp" +
                "LDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAA" +
                "AAD/2gAIAQEAAD8AKp//2Q==",
        )
    }
}
