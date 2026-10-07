package io.github.mangome.camftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自检按钮的显隐全押在 [FtpState.Snapshot.anyStored] 上，而自检图又不该混进「已收到 N 张」——
 * 这两条一起守着（FtpState 是单例，一个用例里按顺序走完，免得跨用例相互污染）。
 */
class FtpStateTest {

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
    }
}
