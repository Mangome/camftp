package io.github.mangome.camftp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File

/**
 * 自检按钮的显隐全押在 [FtpState.Snapshot.anyStored] 上，而自检图又不该混进「已收到 N 张」——
 * 这两条一起守着（FtpState 是单例，一个用例里按顺序走完，免得跨用例相互污染）。
 */
class FtpStateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `失败不入账，自检成功收起按钮但不计数，真图才计数`() {
        val before = FtpState.snapshot.value.received

        FtpState.addEvent(FtpState.Event("坏文件.JPG", ok = false, detail = "入库失败"))
        assertEquals(before, FtpState.snapshot.value.received)
        assertFalse(FtpState.snapshot.value.anyStored)   // 失败不算「链路已证明」，按钮得留着

        FtpState.addEvent(FtpState.Event("测试图", ok = true, detail = "DCIM/CamFtp", counts = false))
        assertEquals(before, FtpState.snapshot.value.received)
        assertTrue(FtpState.snapshot.value.anyStored)

        FtpState.addEvent(FtpState.Event("DSC_0001.JPG", ok = true, detail = "DCIM/CamFtp"))
        assertEquals(before + 1, FtpState.snapshot.value.received)
        // UI 每行都要显示时间，缺了就是列表里一列 1970
        assertTrue(FtpState.snapshot.value.events.first().at > 0)
    }

    @Test
    fun `会话数只认增量，多减一次不会变负，断开不清「上次连接」`() {
        FtpState.running(2121)
        assertEquals(0, FtpState.snapshot.value.clients)
        assertEquals(0L, FtpState.snapshot.value.lastConnectAt)

        FtpState.clientDelta(1)
        assertEquals(1, FtpState.snapshot.value.clients)
        assertTrue("连上要记下时间，断开后 UI 还要显示它", FtpState.snapshot.value.lastConnectAt > 0)

        FtpState.clientDelta(1)    // 相机可能同时开几个会话
        FtpState.clientDelta(-1)
        assertEquals(1, FtpState.snapshot.value.clients)

        FtpState.stopped()
        assertEquals(0, FtpState.snapshot.value.clients)
        assertTrue(FtpState.snapshot.value.lastConnectAt > 0)
    }

    /**
     * 「最近收到」落盘：重启 App 列表 / 张数 / 「已成功入库过」都得回得来，否则用户以为图丢了。
     * 注意：FtpState 是单例，这条会掀翻共享状态，finally 里归零，别污染别的用例。
     */
    @Test
    fun `最近条目和已入库标识都落盘，重开 App 还在`() {
        val dir = tmp.newFolder("state")
        try {
            FtpState.open(dir)   // 空目录 = 冷启动
            val thumb = ByteArray(32) { it.toByte() }
            FtpState.addEvent(
                FtpState.Event(
                    "DSC_0001.JPG", ok = true, detail = "DCIM/CamFtp",
                    uri = "content://media/external/images/media/1", at = 1_700_000_000_000,
                    thumb = thumb,
                )
            )
            FtpState.addEvent(FtpState.Event("坏文件.JPG", ok = false, detail = "入库失败", at = 1_700_000_001_000))

            FtpState.open(dir)   // 再读一遍 = 重启 App
            val s = FtpState.snapshot.value
            assertEquals(listOf("坏文件.JPG", "DSC_0001.JPG"), s.events.map { it.name })   // 新的在前
            assertEquals(1, s.received)
            assertTrue("重启后自检按钮不该又冒出来", s.anyStored)
            // 每行都要显示时间，点行还要能打开图片：时间戳、uri、缩略图都得原样回来
            assertEquals(1_700_000_000_000L, s.events.last().at)
            assertEquals("content://media/external/images/media/1", s.events.last().uri)
            assertArrayEquals("缩略图要跟着落盘，否则重启后网格就空了", thumb, s.events.last().thumb)
            assertNull("失败条目没有缩略图，别写出一条空数组", s.events.first().thumb)
        } finally {
            FtpState.open(tmp.newFolder("reset"))   // 单例归零
            assertEquals(0, FtpState.snapshot.value.received)
        }
    }

    /**
     * 用户明确要求不兼容旧数据：读到不认识的 MAGIC 就当没存过（丢的是历史条目，不影响接收）。
     * 这条守着「别被好心人加回兼容分支」——加回去就等于把升级后的历史格式永久钉死。
     */
    @Test
    fun `旧格式的落盘文件直接当成没存过`() {
        val dir = tmp.newFolder("legacy")
        // 名字跟 FtpState.STORE_NAME 必须一致，typo 会让这条用例永远绿
        File(dir, "recent.bin").outputStream().buffered().use { out ->
            DataOutputStream(out).use { legacy ->
                legacy.writeInt(1)          // 老 MAGIC
                legacy.writeInt(7)          // 老 received
                legacy.writeBoolean(true)   // 老 anyStored
                legacy.writeInt(0)          // 0 条
            }
        }
        try {
            FtpState.open(dir)
            val s = FtpState.snapshot.value
            assertEquals(0, s.received)
            assertTrue(s.events.isEmpty())
            assertFalse(s.anyStored)
        } finally {
            FtpState.open(tmp.newFolder("reset-legacy"))
        }
    }
}
