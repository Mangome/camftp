package io.github.mangome.camftp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 方向修正的表：表错了竖拍的照片在网格里就是躺倒的，而这条链路上别的部分都没法 JVM 单测
 * （`BitmapFactory` / `Matrix` 在单测里都是桩）。
 */
class ExifTransformTest {

    @Test
    fun `八种方向跟 androidx 的 rotate-then-flip 定义一致`() {
        assertEquals("1 = 不动", ExifTransform(0, false), exifTransform(1))
        assertEquals("2 = 只左右翻", ExifTransform(0, true), exifTransform(2))
        assertEquals("3 = 转 180", ExifTransform(180, false), exifTransform(3))
        assertEquals("4 = 转 180 再左右翻（= 上下翻）", ExifTransform(180, true), exifTransform(4))
        assertEquals("5 = 转 270 再左右翻", ExifTransform(270, true), exifTransform(5))
        assertEquals("6 = 顺时针 90（竖拍最常写这个）", ExifTransform(90, false), exifTransform(6))
        assertEquals("7 = 转 90 再左右翻", ExifTransform(90, true), exifTransform(7))
        assertEquals("8 = 转 270", ExifTransform(270, false), exifTransform(8))
    }

    @Test
    fun `只有 90 的倍数，且翻转只出现在该翻的那四种上`() {
        (0..9).forEach { orientation ->
            assertEquals("方向 $orientation 的旋转角只能是 0/90/180/270", 0, exifTransform(orientation).degree % 90)
        }
        // 2/4/5/7 之外一个都不该翻（1/3/6/8 翻了就成镜像了）
        assertEquals(listOf(2, 4, 5, 7), (1..8).filter { exifTransform(it).flip })
    }

    @Test
    fun `没定义的方向当正常处理`() {
        assertEquals(ExifTransform.NONE, exifTransform(0))
        assertEquals(ExifTransform.NONE, exifTransform(9))
        assertEquals(ExifTransform.NONE, exifTransform(-1))
    }
}
