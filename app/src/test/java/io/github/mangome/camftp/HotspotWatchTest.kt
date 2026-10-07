package io.github.mangome.camftp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 服务跟着热点走：能收就起、不能收了就停，状态已经对就不动它（别把服务重启一遍）。
 * 这是「关热点 = 停止接收」的唯一规则来源。
 */
class HotspotWatchTest {

    @Test
    fun `热点开了没在跑就起，热点关了就停，其余不动`() {
        assertEquals(ServiceAction.START, serviceAction(canReceive = true, running = false))
        assertEquals(ServiceAction.NONE, serviceAction(canReceive = true, running = true))
        assertEquals(ServiceAction.STOP, serviceAction(canReceive = false, running = true))
        assertEquals(ServiceAction.NONE, serviceAction(canReceive = false, running = false))
    }
}
