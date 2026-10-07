package io.github.mangome.camftp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        start()
        return START_STICKY   // 被系统杀掉后自己回来（不做开机自启）
    }

    private fun start() {
        if (engine != null) return

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val port = prefs.getInt(KEY_PORT, Profiles.NIKON_Z50II.controlPort)
        val user = prefs.getString(KEY_USER, DEFAULT_USER) ?: DEFAULT_USER
        val password = prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD

        // 先挂上前台，避免 startForegroundService 的 5 秒限制；起不来再降级退出
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.state_starting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        val newEngine = FtpEngine(
            profile = Profiles.NIKON_Z50II.copy(controlPort = port),
            homeDir = File(filesDir, HOME_DIR),
            user = user,
            password = password,
            sink = MediaStoreSink(this),
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
        FtpState.addEvent(FtpState.Event(result.displayName, result.ok, result.detail))
        updateNotification()
    }

    private fun updateNotification() {
        val s = FtpState.snapshot.value
        val text = getString(R.string.state_running, s.port, s.received)
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

        private const val CHANNEL_ID = "camftp"
        private const val NOTIFICATION_ID = 1
        private const val PREFS = "camftp"
        private const val HOME_DIR = "ftp"

        // M3 的 UI 会写这些 key；先给默认值，服务不依赖 UI
        const val KEY_PORT = "port"
        const val KEY_USER = "user"
        const val KEY_PASSWORD = "password"
        const val DEFAULT_USER = "camftp"
        const val DEFAULT_PASSWORD = "123456"
    }
}
