package io.github.mangome.camftp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 服务 → UI 的唯一状态通道。
 * （文档里写的 LocalBroadcastManager 已经废弃，用 StateFlow；进程没跑时快照回到初始值）
 */
object FtpState {

    /** [counts] = false 的事件（自检图）不进「已收到 N 张」的计数；[at] = 入库时刻，UI 每行显示 */
    data class Event(
        val name: String,
        val ok: Boolean,
        val detail: String = "",
        val counts: Boolean = true,
        /** 入库后的 MediaStore 地址（失败为 null）：UI 点这一行直接打开图片 */
        val uri: String? = null,
        val at: Long = System.currentTimeMillis(),
    )

    data class Snapshot(
        val running: Boolean = false,
        val port: Int = 0,
        val received: Int = 0,
        val events: List<Event> = emptyList(),
        /** 本进程内成功入库过（真图或自检图）→ 入库链路已被证明，自检按钮可以收了 */
        val anyStored: Boolean = false,
        /** 当前连着的相机会话数（FTP 控制连接）——「相机连上了没」的唯一实证 */
        val clients: Int = 0,
        /** 最近一次相机连上来的时刻，0 = 这次启动还没见过相机 */
        val lastConnectAt: Long = 0,
    )

    private const val MAX_EVENTS = 10

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun running(port: Int) = _snapshot.update { it.copy(running = true, port = port, clients = 0) }

    fun stopped() = _snapshot.update { it.copy(running = false, clients = 0) }

    /** [delta] = +1 连上 / -1 断开，来自 FtpEngine 的 ftplet 回调（控制连接，不含数据连接） */
    fun clientDelta(delta: Int) = _snapshot.update {
        it.copy(
            clients = (it.clients + delta).coerceAtLeast(0),
            lastConnectAt = if (delta > 0) System.currentTimeMillis() else it.lastConnectAt,
        )
    }

    fun addEvent(event: Event) = _snapshot.update {
        it.copy(
            received = it.received + if (event.ok && event.counts) 1 else 0,
            anyStored = it.anyStored || event.ok,
            events = (listOf(event) + it.events).take(MAX_EVENTS),
        )
    }
}
