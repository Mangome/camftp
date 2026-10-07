package io.github.mangome.camftp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 服务 → UI 的唯一状态通道。
 * （文档里写的 LocalBroadcastManager 已经废弃，用 StateFlow）
 *
 * 「最近收到」那点展示信息（条目 / 已收到张数 / 链路已证明）落盘：冷启动 [attach] 读回来，
 * 否则重启 App 列表就空了。会话数、上次连接时间是「这一次运行」的实况，不落盘。
 */
object FtpState {

    /** [counts] = false 的事件（自检图）不进「已收到 N 张」的计数；[at] = 入库时刻 */
    data class Event(
        val name: String,
        val ok: Boolean,
        val detail: String = "",
        val counts: Boolean = true,
        /** 入库后的 MediaStore 地址（失败为 null）：UI 点这一格直接打开图片 */
        val uri: String? = null,
        val at: Long = System.currentTimeMillis(),
        /** 入库时生成的小图（见 [Thumbnailer]）：「最近收到」的网格靠它显示，没有就画占位格子 */
        val thumb: ByteArray? = null,
    )

    data class Snapshot(
        val running: Boolean = false,
        val port: Int = 0,
        val received: Int = 0,
        val events: List<Event> = emptyList(),
        /** 成功入库过（真图或自检图）→ 入库链路已被证明，自检按钮可以收了。跟着列表一起落盘 */
        val anyStored: Boolean = false,
        /** 当前连着的相机会话数（FTP 控制连接）——「相机连上了没」的唯一实证 */
        val clients: Int = 0,
        /** 最近一次相机连上来的时刻，0 = 这次启动还没见过相机 */
        val lastConnectAt: Long = 0,
        /** 正在接收的文件名（STOR 开始到结束之间），null = 没在传。实况，不落盘 */
        val transferring: String? = null,
    )

    /** 上限 12 = 网格 3 列的整 4 行（10 会排出 3+3+3+1 的独苗行） */
    private const val MAX_EVENTS = 12

    /**
     * 落盘格式版本：读不认识就当没存过（**不兼容旧版本**，升级后历史条目丢一次、
     * 「已收到 N 张」和 [Snapshot.anyStored] 都归零 —— 用户明确要的，别为它加兼容分支）。
     */
    private const val MAGIC = 2
    private const val STORE_NAME = "recent.bin"

    /** 单条缩略图的上限：384px / q80 实测 ~30KB，留足余量；超出的只会是文件写坏了 */
    private const val MAX_THUMB = 4 * 1024 * 1024

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    /** null = 不落盘（JVM 单测走这条），落盘失败也只丢历史，绝不能影响接收 */
    private var store: File? = null

    /** 进程启动时调一次（UI 与服务都调，后来的是 no-op） */
    fun attach(dir: File) {
        if (store == null) open(dir)
    }

    /** 换落盘目录并重读（[attach] 的实体；测试用它模拟「重启 App」） */
    internal fun open(dir: File) {
        store = File(dir, STORE_NAME)
        _snapshot.update { it.copy(events = emptyList(), received = 0, anyStored = false) }
        read()
    }

    fun running(port: Int) =
        _snapshot.update { it.copy(running = true, port = port, clients = 0, transferring = null) }

    fun stopped() = _snapshot.update { it.copy(running = false, clients = 0, transferring = null) }

    /** [delta] = +1 连上 / -1 断开，来自 FtpEngine 的 ftplet 回调（控制连接，不含数据连接） */
    fun clientDelta(delta: Int) = _snapshot.update {
        it.copy(
            clients = (it.clients + delta).coerceAtLeast(0),
            lastConnectAt = if (delta > 0) System.currentTimeMillis() else it.lastConnectAt,
        )
    }

    /** [name] = 正在接收的文件名（STORE 开始），null = 传完了 / 连接断了 */
    fun transfer(name: String?) = _snapshot.update { it.copy(transferring = name) }

    fun addEvent(event: Event) {
        _snapshot.update {
            it.copy(
                received = it.received + if (event.ok && event.counts) 1 else 0,
                anyStored = it.anyStored || event.ok,
                events = (listOf(event) + it.events).take(MAX_EVENTS),
            )
        }
        persist()
    }

    /**
     * 每条一个定长字段 [DataOutputStream]（`writeUTF` 自带长度前缀，文件名 / 详情里有制表符换行也不怕）。
     * 不加 fsync：几百字节，写丢了大不了少一条历史。
     *
     * ponytail: 12 条小图（~300KB）跟着整块重写，够用且永不留孤儿文件。真到几千条再改成缩略图目录 + 清理。
     */
    @Synchronized
    private fun persist() {
        val file = store ?: return
        val s = _snapshot.value
        runCatching {
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(s.received)
                out.writeBoolean(s.anyStored)
                out.writeInt(s.events.size)
                s.events.forEach {
                    out.writeUTF(it.name)
                    out.writeBoolean(it.ok)
                    out.writeUTF(it.detail)
                    out.writeBoolean(it.counts)
                    out.writeUTF(it.uri.orEmpty())
                    out.writeLong(it.at)
                    it.thumb?.let { b -> out.writeInt(b.size); out.write(b) } ?: out.writeInt(0)
                }
            }
        }
    }

    @Synchronized
    private fun read() {
        val file = store?.takeIf { it.isFile } ?: return
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return@use
                val received = input.readInt()
                val anyStored = input.readBoolean()
                val count = input.readInt()
                // 文件写坏时别照着垃圾长度去分配列表
                if (count !in 0..MAX_EVENTS) return@use
                val events = List(count) {
                    val name = input.readUTF()
                    val ok = input.readBoolean()
                    val detail = input.readUTF()
                    val counts = input.readBoolean()
                    val uri = input.readUTF().ifEmpty { null }
                    val at = input.readLong()
                    val size = input.readInt()
                    // 长度写坏时别照着垃圾值分配数组
                    val thumb = if (size in 1..MAX_THUMB) ByteArray(size).also { input.readFully(it) } else null
                    Event(name, ok, detail, counts, uri, at, thumb)
                }
                _snapshot.update { it.copy(events = events, received = received, anyStored = anyStored) }
            }
        }
    }
}
