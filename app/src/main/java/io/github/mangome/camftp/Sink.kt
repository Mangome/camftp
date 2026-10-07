package io.github.mangome.camftp

import java.io.File

/** 收完一个文件后调用；实现方负责搬走或删除 [file]。 */
interface Sink {
    fun onStored(file: File): StoreResult
}

data class StoreResult(val displayName: String, val ok: Boolean, val detail: String = "")
