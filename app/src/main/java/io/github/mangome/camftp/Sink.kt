package io.github.mangome.camftp

import java.io.File

/** 收完一个文件后调用；实现方负责搬走或删除 [file]。 */
interface Sink {
    fun onStored(file: File): StoreResult
}

/**
 * [uri] = 入库后的 MediaStore 地址（String 而非 android.net.Uri：这层要留给纯 JVM）；失败时 null。
 * [thumb] = 自己生成的小图（见 [Thumbnailer]），UI 的网格靠它显示；没有就是占位格子
 */
data class StoreResult(
    val displayName: String,
    val ok: Boolean,
    val detail: String = "",
    val uri: String? = null,
    val thumb: ByteArray? = null,
)
