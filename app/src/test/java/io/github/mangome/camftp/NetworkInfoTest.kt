package io.github.mangome.camftp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 热点网卡识别。
 *
 * 实测两台机器：ColorOS 17 热点叫 `ap0`、STA 叫 `wlan0`；Xiaomi MIX Flip 2 / HyperOS 3
 * 热点叫 `wlan2`、STA 叫 `wlan0`（`dumpsys tethering`：`wlan2 - TetheredState`）。
 * 写死名单就会漏掉小米 → 用户看到「开了热点却显示未开」，这组用例守着这条。
 */
class NetworkInfoTest {

    @Test
    fun `小米 wlan2 是热点，正连着的 wlan0 不是`() {
        assertTrue(NetworkInfo.isHotspot(name = "wlan2", staWifi = setOf("wlan0")))
        assertFalse(NetworkInfo.isHotspot(name = "wlan0", staWifi = setOf("wlan0")))
    }

    @Test
    fun `STA 是 wlan1 的机器，wlan0 当热点也认`() {
        assertTrue(NetworkInfo.isHotspot(name = "wlan0", staWifi = setOf("wlan1")))
        assertFalse(NetworkInfo.isHotspot(name = "wlan1", staWifi = setOf("wlan1")))
    }

    @Test
    fun `ColorOS 把自己热点也报进 Wi-Fi 网络里，ap0 照样认（这里误杀过）`() {
        assertTrue(NetworkInfo.isHotspot(name = "ap0", staWifi = setOf("wlan0", "ap0")))
    }

    @Test
    fun `各家 ROM 的热点名都认`() {
        listOf("ap0", "ap1", "softap0", "swlan0", "wlan-ap0", "wlan1").forEach {
            assertTrue(it, NetworkInfo.isHotspot(name = it, staWifi = emptySet()))
        }
    }

    @Test
    fun `蜂窝、p2p、虚拟网卡都不是热点`() {
        listOf("rmnet_data2", "ccmni0", "p2p0", "vgate0", "tun0", "dummy0").forEach {
            assertFalse(it, NetworkInfo.isHotspot(name = it, staWifi = emptySet()))
        }
    }
}
