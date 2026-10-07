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

    /** [counts] = false 的事件（自检图）不进「已收到 N 张」的计数 */
    data class Event(val name: String, val ok: Boolean, val detail: String = "", val counts: Boolean = true)

    data class Snapshot(
        val running: Boolean = false,
        val port: Int = 0,
        val received: Int = 0,
        val events: List<Event> = emptyList(),
        /** 本进程内成功入库过（真图或自检图）→ 入库链路已被证明，自检按钮可以收了 */
        val anyStored: Boolean = false,
    )

    private const val MAX_EVENTS = 10

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun running(port: Int) = _snapshot.update { it.copy(running = true, port = port) }

    fun stopped() = _snapshot.update { it.copy(running = false) }

    fun addEvent(event: Event) = _snapshot.update {
        it.copy(
            received = it.received + if (event.ok && event.counts) 1 else 0,
            anyStored = it.anyStored || event.ok,
            events = (listOf(event) + it.events).take(MAX_EVENTS),
        )
    }
}
