package io.github.mangome.camftp

import java.io.File

/** 收完一个文件后调用；实现方负责搬走或删除 [file]。 */
interface Sink {
    fun onStored(file: File): StoreResult
}

/** [uri] = 入库后的 MediaStore 地址（String 而非 android.net.Uri：这层要留给纯 JVM）；失败时 null */
data class StoreResult(
    val displayName: String,
    val ok: Boolean,
    val detail: String = "",
    val uri: String? = null,
)
