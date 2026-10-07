package io.github.mangome.camftp

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 没有相机也能证明 FTP 层是活的：真起一个 FtpServer，用 commons-net 当客户端。
 */
class FtpEngineTest {

    private class RecordingSink : Sink {
        val results = CopyOnWriteArrayList<StoreResult>()
        val bytes = CopyOnWriteArrayList<ByteArray>()

        override fun onStored(file: File): StoreResult {
            bytes += file.readBytes()
            file.delete()
            return StoreResult(file.name, true).also { results += it }
        }
    }

    private lateinit var home: File
    private lateinit var engine: FtpEngine
    private lateinit var sink: RecordingSink
    private var port = 0

    /** 会话数由 +1/-1 增量累出来，正是 UI 那边 FtpState.clientDelta 的用法 */
    private val clientSum = AtomicInteger()
    private val clientCounts = CopyOnWriteArrayList<Int>()

    @Before
    fun setUp() {
        home = Files.createTempDirectory("camftp-home").toFile()
        sink = RecordingSink()
        port = ServerSocket(0).use { it.localPort }   // 高位随机端口，别用 0
        engine = FtpEngine(
            profile = Profiles.NIKON_Z50II.copy(controlPort = port),
            homeDir = home,
            user = "camftp",
            password = "123456",
            sink = sink,
            onClients = { delta -> clientCounts += clientSum.addAndGet(delta) },
        )
        engine.start()
    }

    @After
    fun tearDown() {
        engine.stop()
        home.deleteRecursively()
    }

    private fun connect(): FTPClient = FTPClient().apply {
        connect("127.0.0.1", port)
    }

    private fun payload(size: Int = 64 * 1024) = ByteArray(size) { (it % 251).toByte() }

    private fun store(passive: Boolean, name: String = "DSC_0001.JPG"): ByteArray {
        val data = payload()
        val client = connect()
        try {
            val loggedIn = client.login("camftp", "123456")
            assertTrue("登录失败，服务端回复：${client.replyString}", loggedIn)
            // TYPE 必须在登录后发：登录前会被 530 拒掉，会话留在 ASCII，字节会被 LF→CRLF 改写
            client.setFileType(FTP.BINARY_FILE_TYPE)
            if (passive) client.enterLocalPassiveMode() else client.enterLocalActiveMode()
            val stored = client.storeFile(name, ByteArrayInputStream(data))
            assertTrue("STOR 失败：${client.replyString}", stored)
        } finally {
            client.disconnect()
        }
        awaitResults(1)
        return data
    }

    /**
     * 等入库真发生。
     * 注意：服务端的 Ftplet.onUploadEnd 回调可能在 226 响应之后才触发，
     * 所以 storeFile() 返回不代表已经排队入库了，光 awaitIdle() 会扑空。
     */
    private fun awaitResults(count: Int, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (sink.results.size < count && System.currentTimeMillis() < deadline) Thread.sleep(20)
        engine.awaitIdle()
    }

    @Test
    fun `相机连上来会话数变 1，断开归 0（UI 的「相机已连接」就靠它）`() {
        val client = connect()
        awaitClientCount(1, "TCP 连上后会话数应该是 1")

        client.disconnect()
        awaitClientCount(0, "断开后应该归 0")
    }

    private fun awaitClientCount(expected: Int, message: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (clientCounts.lastOrNull() != expected && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("$message，实际变动序列：$clientCounts", expected, clientCounts.lastOrNull())
    }

    @Test
    fun `被动模式下 STOR 落盘、字节一致、源文件被搬走`() {
        val data = store(passive = true)

        assertEquals(1, sink.results.size)
        assertTrue(sink.results[0].ok)
        assertEquals("DSC_0001.JPG", sink.results[0].displayName)
        assertArrayEquals(data, sink.bytes[0])
        assertFalse("sink 应已搬走源文件", File(home, "DSC_0001.JPG").exists())
    }

    @Test
    fun `主动模式下 STOR 也能收（相机可以把 PASV 关掉）`() {
        val data = store(passive = false)

        assertEquals(1, sink.results.size)
        assertArrayEquals(data, sink.bytes[0])
    }

    @Test
    fun `往不存在的子目录上传会自动建目录`() {
        val data = store(passive = true, name = "100NIKON/DSC_0002.JPG")

        assertEquals(1, sink.results.size)
        assertArrayEquals(data, sink.bytes[0])
        assertTrue("子目录应已被自动创建", File(home, "100NIKON").isDirectory)
    }

    @Test
    fun `SimpleUserManager 能直接认证（隔离 FtpServer）`() {
        val um = SimpleUserManager("camftp", "123456", home.absolutePath)
        assertNotNull("用户名密码都对，怎么认证失败？", um.authenticate(UsernamePasswordAuthentication("camftp", "123456")))
        assertNull("错误密码应该被拒", um.authenticate(UsernamePasswordAuthentication("camftp", "bad")))
        assertNotNull("getUserByName 拿不到用户", um.getUserByName("camftp"))
    }

    @Test
    fun `往不存在的子目录 CWD 再上传也能成（curl 就是这条路）`() {
        val data = payload()
        val client = connect()
        try {
            assertTrue("登录失败", client.login("camftp", "123456"))
            client.setFileType(FTP.BINARY_FILE_TYPE)
            client.enterLocalPassiveMode()
            val changed = client.changeWorkingDirectory("200NIKON")
            assertTrue("CWD 到不存在的目录应该被自动创建：${client.replyString}", changed)
            assertTrue("STOR 失败：${client.replyString}", client.storeFile("DSC_0005.JPG", ByteArrayInputStream(data)))
        } finally {
            client.disconnect()
        }
        awaitResults(1)
        assertArrayEquals(data, sink.bytes[0])
    }

    @Test
    fun `启动时会把残留文件重试入库`() {
        val leftover = File(home, "DSC_0009.JPG").apply { writeBytes(payload(1024)) }

        engine.retryPending()
        awaitResults(1)

        assertEquals("DSC_0009.JPG", sink.results[0].displayName)
        assertFalse("重试成功后源文件应被搬走", leftover.exists())
    }

    @Test
    fun `密码错误被拒`() {
        val client = connect()
        try {
            assertFalse(client.login("camftp", "wrong"))
        } finally {
            client.disconnect()
        }
        engine.awaitIdle()
        assertEquals(0, sink.results.size)
    }

    @Test
    fun `匿名登录默认被拒（开关关着时 anonymous 不能当普通账号）`() {
        val client = connect()
        try {
            assertFalse("匿名登录默认是关的，不该放行", client.login("anonymous", "x@y.com"))
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `开了匿名就能不输入用户名密码上传`() {
        val anonHome = Files.createTempDirectory("camftp-anon").toFile()
        val anonSink = RecordingSink()
        val anonPort = ServerSocket(0).use { it.localPort }
        val anonEngine = FtpEngine(
            profile = Profiles.NIKON_Z50II.copy(controlPort = anonPort),
            homeDir = anonHome,
            user = "camftp",
            password = "123456",
            sink = anonSink,
            anonymous = true,
        )
        anonEngine.start()
        try {
            val data = payload()
            val client = FTPClient().apply { connect("127.0.0.1", anonPort) }
            try {
                // 相机/curl 的匿名登录：用户名 anonymous，密码随便填（常在填邮箱）
                assertTrue("匿名登录失败：${client.replyString}", client.login("anonymous", "camftp@example.com"))
                client.setFileType(FTP.BINARY_FILE_TYPE)
                client.enterLocalPassiveMode()
                assertTrue("匿名 STOR 失败：${client.replyString}", client.storeFile("DSC_0100.JPG", ByteArrayInputStream(data)))
            } finally {
                client.disconnect()
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (anonSink.results.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertArrayEquals(data, anonSink.bytes[0])
        } finally {
            anonEngine.stop()
            anonHome.deleteRecursively()
        }
    }
}