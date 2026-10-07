package io.github.mangome.camftp

import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.filesystem.nativefs.NativeFileSystemFactory
import org.apache.ftpserver.ftplet.Ftplet
import org.apache.ftpserver.listener.ListenerFactory
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Apache FtpServer 的配置与生命周期。纯 JVM，不 import 任何 android.*，所以能跑单测。
 *
 * [onResult] 在后台线程回调；[awaitIdle] 供测试等待入库完成。
 */
class FtpEngine(
    private val profile: CameraProfile,
    val homeDir: File,
    private val user: String,
    private val password: String,
    private val sink: Sink,
    /** 允许匿名登录（相机侧开「匿名登录」）：用户名密码不校验 */
    private val anonymous: Boolean = false,
    private val onResult: (StoreResult) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger("FtpEngine")

    private var server: FtpServer? = null

    // 单线程：入库串行且保序；ftplet 回调（MINA IO 线程）永不阻塞
    private var workers: ExecutorService? = null

    val isRunning: Boolean get() = server != null

    fun start() {
        check(server == null) { "FtpEngine 已在运行" }
        homeDir.mkdirs()
        workers = Executors.newSingleThreadExecutor { r -> Thread(r, "camftp-sink").apply { isDaemon = true } }

        val listenerFactory = ListenerFactory().apply {
            setPort(profile.controlPort)
            setIdleTimeout(IDLE_SECONDS)
            setDataConnectionConfiguration(
                DataConnectionConfigurationFactory().apply {
                    profile.passivePorts?.let { setPassivePorts(it) }
                    setIdleTime(IDLE_SECONDS)
                    setActiveEnabled(true)   // 相机可以把 PASV 关掉走主动模式
                    // 1.2.1 里没有 setPassiveEnabled：被动模式没有开关，永远可用
                    // 不设 passiveAddress：FtpServer 会回落到控制连接的本地地址，正是热点 IP
                }.createDataConnectionConfiguration()
            )
        }

        val factory = FtpServerFactory().apply {
            addListener("default", listenerFactory.createListener())
            setUserManager(SimpleUserManager(user, password, homeDir.absolutePath, anonymous))
            setFileSystem(NativeFileSystemFactory().apply { setCreateHome(true) })
            setConnectionConfig(
                ConnectionConfigFactory().apply {
                    setMaxLogins(4)          // 相机断线重连是常态，别设 1
                    setAnonymousLoginEnabled(anonymous)
                    setMaxThreads(8)
                }.createConnectionConfig()
            )
            // FtpServerFactory 没有 addFtplet()，只能整表设置。
            // 必须是可变 Map：DefaultFtpServerContext.dispose() 会 clear() 它（mapOf 是只读的，会崩）
            setFtplets(mutableMapOf<String, Ftplet>("sink" to SinkFtplet(homeDir) { enqueue(it) }))
        }

        server = factory.createServer().also { it.start() }
        log.info(
            "FTP 已启动：0.0.0.0:{}，PASV {}，家目录 {}",
            profile.controlPort, profile.passivePorts ?: "不限", homeDir.absolutePath,
        )
    }

    fun stop() {
        server?.stop()
        server = null
        workers?.shutdown()
        workers = null
    }

    /** 服务启动时把上次没入库成功的残留文件重试一遍。 */
    fun retryPending() {
        homeDir.listFiles()?.filter { it.isFile }?.forEach { enqueue(it) }
    }

    /** 等待已排队的入库任务跑完（测试用；FIFO 保证前面的都已完成）。 */    fun awaitIdle(timeoutMs: Long = 5_000) {
        val executor = workers ?: return
        val latch = CountDownLatch(1)
        executor.execute { latch.countDown() }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun enqueue(file: File) {
        val executor = workers ?: return
        executor.execute {
            val result = try {
                sink.onStored(file)
            } catch (t: Throwable) {
                log.warn("入库失败：{}", file.name, t)
                // 保底：出错绝不删源文件，留在 homeDir 里，下次启动重试
                StoreResult(file.name, false, "入库失败：${t.message ?: t::class.simpleName}")
            }
            runCatching { onResult(result) }
        }
    }

    private companion object {
        /** 相机两次操作之间可能停很久，超时给宽一点 */
        const val IDLE_SECONDS = 300
    }
}
