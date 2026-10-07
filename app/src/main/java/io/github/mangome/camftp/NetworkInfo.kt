package io.github.mangome.camftp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 找出"该念给相机听"的本机地址。
 *
 * 实测开热点后同时存在的网卡：
 *   OPPO ColorOS 17：  ap0    10.129.14.x/24      ← 热点，就是它；wlan0 192.168.x.x/24 ← 家里 Wi-Fi
 *   Xiaomi MIX Flip 2 / HyperOS 3 / Android 16：
 *                      wlan2  10.130.223.120/24   ← 热点（`dumpsys tethering`：wlan2 - TetheredState）
 *                      wlan0  192.168.1.103/24    ← 家里 Wi-Fi
 * 另外还有 vgate0（系统虚拟网关）、ccmni 与 rmnet 系列（移动数据）和 tun/gre/ifb/dummy 一堆虚拟网卡。
 * 所以不能"枚举所有 IPv4"，必须把虚拟的踢掉，再把热点网卡排到最前；
 * **热点网卡名也不能写死名单** —— ColorOS 叫 ap0、小米叫 wlan2（[isHotspot]）。
 */
object NetworkInfo {

    /**
     * 热点网卡名的形状。系统自己的定义是 `wlan\d|softap\d|ap_br_wlan\d|ap_br_softap\d`
     * （`dumpsys tethering` 的 tetherableWifiRegexs），再加上 ap0 / swlan0 / wlan-ap 这些各家叫法。
     *
     * ⚠️ `wlan\d+` 既可能是热点、也可能是正在连的 STA（大多机器上 STA 就是 wlan0），
     * 名字本身分不开，靠 [isHotspot] 排除 STA —— 别在这里加"wlan0 不是热点"这种前提。
     */
    private val HOTSPOT_NAME = Regex("^(ap|softap|swlan|wlan-ap|ap_br_wlan|ap_br_softap)\\d*$|^wlan\\d+$")

    /** 虚拟/隧道/蜂窝网卡，永远不可能给相机用 */
    private val VIRTUAL = Regex(
        "^(lo|dummy\\d*|ifb\\d*|tunl?\\d*|tap\\d*|gre\\d*|gretap\\d*|erspan\\d*|ip6?tnl\\d*|ip6?gre\\d*|sit\\d*|" +
            "ccmni\\d*|rmnet\\w*|vgate\\d*|p2p\\w*|clat\\d*|bt-pan|ncm\\d*)$"
    )

    data class Iface(val name: String, val ip: String, val isHotspot: Boolean)

    /** 名字像热点网卡吗（wlanN 也像 —— 那还可能是 STA，要用 [isHotspot] 判） */
    internal fun hotspotName(name: String) = HOTSPOT_NAME.matches(name)

    /** 这台是不是热点网卡：名字像 AP，且不是当前正连着 Wi-Fi 的那张（[staWifi]） */
    internal fun isHotspot(name: String, staWifi: Set<String>) = name !in staWifi && hotspotName(name)

    fun ipv4(context: Context): List<Iface> {
        val staWifi = wifiSta(context)
        val result = mutableListOf<Iface>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
        for (nif in interfaces) {
            val name = nif.name ?: continue
            if (VIRTUAL.matches(name) || nif.isLoopback || !nif.isUp) continue
            for (ia in nif.interfaceAddresses) {
                val addr = ia.address as? Inet4Address ?: continue
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                if (ia.networkPrefixLength.toInt() == 32) continue   // 点对点/虚拟
                result += Iface(name, addr.hostAddress ?: continue, isHotspot(name, staWifi))
            }
        }
        return result.distinct().sortedWith(compareByDescending<Iface> { it.isHotspot }.thenBy { it.name })
    }

    /** 优先热点网卡，其次第一个可用地址；没有就 null */
    fun preferred(context: Context): Iface? = ipv4(context).firstOrNull()

    /**
     * 当前正连着 Wi-Fi 的那张网卡（STA）。
     * 小米上热点网卡叫 wlan2、STA 叫 wlan0 —— 只有系统知道哪张是 STA，网卡名本身分不出来。
     */
    private fun wifiSta(context: Context): Set<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptySet()
        return cm.allNetworks.mapNotNull { n ->
            val isWifi = cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            cm.getLinkProperties(n)?.interfaceName?.takeIf { isWifi }
        }.toSet()
    }
}
