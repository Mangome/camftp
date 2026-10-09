package io.github.mangome.camftp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import org.slf4j.LoggerFactory

/** 该拿接收服务怎么办（纯逻辑，[HotspotWatchTest] 守着） */
enum class ServiceAction { NONE, START, STOP }

/** 热点 = 总开关：能收就该在跑，不能收就该停着 */
fun serviceAction(canReceive: Boolean, running: Boolean): ServiceAction = when {
    canReceive && !running -> ServiceAction.START
    !canReceive && running -> ServiceAction.STOP
    else -> ServiceAction.NONE
}

/**
 * 界面里已经没有「开始 / 停止接收」按钮了：热点开着就接收，关热点就是停止。
 *
 * 什么时候复查一次：
 *  - 系统广播（[HotspotReceiver]）：[attach] 注册的运行时接收器是**真正干活**的那份；清单里也声明了一份，
 *    但实测 Android 17 / ColorOS 每次都被隐式广播策略拦掉（handoff §4），只当给别的 ROM 兜底
 *  - 回到前台（MainActivity.onResume）、服务起来时（FtpService.start 的兜底检查）
 * 每次判断都重新扫网卡：广播里带的数据只当「该看一眼了」的闹钟，不信它的内容。
 */
object HotspotWatch {

    private val log = LoggerFactory.getLogger("HotspotWatch")

    /** AP 开关广播（= WifiManager.WIFI_AP_STATE_CHANGED_ACTION）+ 网络共享接口变化 */
    private const val ACTION_AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val ACTION_TETHER_STATE = "android.net.conn.TETHER_STATE_CHANGED"

    // WIFI_AP_STATE_* 是 @SystemApi（普通 App 用不了），值由 WifiService 钉死，照搬
    private const val AP_DISABLING = 10
    private const val AP_DISABLED = 11
    private const val AP_FAILED = 14

    private var attached = false

    /** 幂等。application context 上注册，进程活着就一直有效（Activity 销毁不影响）；进程被杀后切热点不会再自动起，兜底是打开 App */
    fun attach(context: Context) {
        if (attached) return
        attached = true
        val filter = IntentFilter(ACTION_AP_STATE).apply { addAction(ACTION_TETHER_STATE) }
        // 系统广播必须用 EXPORTED 才收得到（官方文档明说）；不带权限，反正里面的东西一律不信
        ContextCompat.registerReceiver(
            context.applicationContext,
            HotspotReceiver(),
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /** 现在能不能收：只认热点网卡（相机的唯一入口，没热点接收毫无意义） */
    fun canReceive(context: Context): Boolean = NetworkInfo.ipv4(context).any { it.isHotspot }

    /** 把服务对齐到当前热点状态 */
    fun sync(context: Context) = when (serviceAction(canReceive(context), FtpState.snapshot.value.running)) {
        ServiceAction.START -> start(context)
        ServiceAction.STOP -> stop(context)
        ServiceAction.NONE -> Unit
    }

    /** 广播入口：明确的「关掉了」直接停（这时网卡可能还挂着几百毫秒，等扫描会漏） */
    fun onBroadcast(context: Context, intent: Intent) {
        val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)
        log.info("收到 {} state={}", intent.action, state)
        if (state == AP_DISABLING || state == AP_DISABLED || state == AP_FAILED) {
            if (FtpState.snapshot.value.running) stop(context)
        } else {
            sync(context)
        }
    }

    private fun start(context: Context) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, FtpService::class.java))
        } catch (t: Throwable) {
            // Android 12+ 后台起前台服务有限制：起不来就算了，打开 App（onResume 时再 sync）会自动补上
            log.warn("后台起服务被系统拦下：{}", t.toString())
        }
    }

    private fun stop(context: Context) {
        context.startService(Intent(context, FtpService::class.java).setAction(FtpService.ACTION_STOP))
    }
}

/** 清单里声明的那个（App 没进程时也能被拉起来看热点开没开） */
class HotspotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = HotspotWatch.onBroadcast(context, intent)
}
