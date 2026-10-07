package io.github.mangome.camftp

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 找出"该念给相机听"的本机地址。
 *
 * 实测（一台 OPPO ColorOS 机 / ColorOS 17）开热点后同时存在的网卡：
 *   ap0     10.129.14.x/24   ← 热点，就是它
 *   wlan0   192.168.x.x/24  ← 家里 Wi-Fi
 *   vgate0  172.30.x.x/32 ← 系统的虚拟网关
 *   还有 ccmni*（移动数据）和 tun/gre/ifb/dummy 一堆虚拟网卡
 * 所以不能"枚举所有 IPv4"，必须把虚拟的踢掉，再把热点网卡排到最前。
 */
object NetworkInfo {

    /** 已知的热点接口名（ap0 是这台 ColorOS 实测的；其余是各家 ROM 的常见叫法） */
    private val HOTSPOT_NAMES = setOf("ap0", "swlan0", "wlan1", "softap0", "wlan-ap", "ap1")

    /** 虚拟/隧道/蜂窝网卡，永远不可能给相机用 */
    private val VIRTUAL = Regex(
        "^(lo|dummy\\d*|ifb\\d*|tunl?\\d*|tap\\d*|gre\\d*|gretap\\d*|erspan\\d*|ip6?tnl\\d*|ip6?gre\\d*|sit\\d*|" +
            "ccmni\\d*|rmnet\\w*|vgate\\d*|p2p\\w*|clat\\d*|bt-pan|ncm\\d*)$"
    )

    data class Iface(val name: String, val ip: String, val isHotspot: Boolean)

    fun ipv4(): List<Iface> {
        val result = mutableListOf<Iface>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
        for (nif in interfaces) {
            val name = nif.name ?: continue
            if (VIRTUAL.matches(name) || nif.isLoopback || !nif.isUp) continue
            for (ia in nif.interfaceAddresses) {
                val addr = ia.address as? Inet4Address ?: continue
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                if (ia.networkPrefixLength.toInt() == 32) continue   // 点对点/虚拟
                result += Iface(name, addr.hostAddress ?: continue, name in HOTSPOT_NAMES)
            }
        }
        return result.distinct().sortedWith(compareByDescending<Iface> { it.isHotspot }.thenBy { it.name })
    }

    /** 优先热点网卡，其次第一个可用地址；没有就 null */
    fun preferred(): Iface? = ipv4().firstOrNull()
}
