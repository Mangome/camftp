package io.github.mangome.camftp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.slf4j.LoggerFactory
import java.io.File

/**
 * 前台服务（类型 connectedDevice）：持有 FtpEngine、常驻通知、PARTIAL_WAKE_LOCK。
 * 状态往 [FtpState] 里丢，UI 自己订阅。
 */
class FtpService : Service() {

    private val log = LoggerFactory.getLogger("FtpService")
    private var engine: FtpEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 服务可能先于 UI 起来（热点广播拉起）：落盘的「最近收到」先读回来，别被新条目顶掉
        FtpState.attach(filesDir)
        HotspotWatch.attach(this)   // 服务常驻时也得盯着热点：用户关热点 = 停止接收
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,   // 全静音：现场拍照时手机不该响
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                // 改配置后热重启：端口/凭据换掉，服务不中断重进前台
                engine?.stop()
                engine = null
            }
        }
        start()
        return START_STICKY   // 被系统杀掉后自己回来（不做开机自启）
    }

    private fun start() {
        if (engine != null) return

        // 热点关着就不该有服务（被 START_STICKY 拉回来时可能已经是这个状态）
        if (!HotspotWatch.canReceive(this)) {
            log.info("没热点，不启动")
            stopSelf()
            return
        }

        Config.load(this)
        val port = Config.port
        val user = Config.user
        val password = Config.password

        // 先挂上前台，避免 startForegroundService 的 5 秒限制；起不来再降级退出
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_starting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        val newEngine = FtpEngine(
            profile = Profiles.NIKON_Z50II.copy(
                controlPort = port,
                passivePorts = Config.passivePorts.ifBlank { null },
            ),
            homeDir = File(filesDir, HOME_DIR),
            user = user,
            password = password,
            anonymous = Config.anonymous,
            onClients = FtpState::clientDelta,
            sink = MediaStoreSink(this, Config.folder),
            onResult = ::onResult,
        )
        try {
            newEngine.start()
        } catch (t: Throwable) {
            log.error("FTP 启动失败（端口 {} 可能被占用）", port, t)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        engine = newEngine
        acquireWakeLock()
        FtpState.running(port)
        updateNotification()
        newEngine.retryPending()   // 上次没入库成功的残留，再试一次
    }

    private fun onResult(result: StoreResult) {
        if (result.ok) log.info("已入库 {}/{}", result.detail, result.displayName)
        else log.warn("入库失败 {}：{}", result.displayName, result.detail)
        FtpState.addEvent(FtpState.Event(result.displayName, result.ok, result.detail, uri = result.uri))
        updateNotification()
    }

    private fun updateNotification() {
        val s = FtpState.snapshot.value
        // IP 每次重新枚举（只在启动/收到图时调用，频率很低），热点换网段也能跟上
        val ip = NetworkInfo.preferred(this)?.ip ?: getString(R.string.ip_unknown)
        val text = getString(R.string.notification_received, ip, s.port, s.received)
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "camftp").apply { acquire() }
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        FtpState.stopped()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "io.github.mangome.camftp.STOP"
        const val ACTION_RESTART = "io.github.mangome.camftp.RESTART"

        private const val CHANNEL_ID = "camftp"
        private const val NOTIFICATION_ID = 1
        private const val HOME_DIR = "ftp"
    }
}
