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
        engine.awaitIdle()
        return data
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
}
