package io.github.mangome.camftp

import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.FtpletResult
import java.io.File

/**
 * 上传回调。注意：回调跑在 MINA IO 线程上，只做两件快事 —— 建目录、把文件丢给 [onFile]。
 */
class SinkFtplet(
    private val homeDir: File,
    private val onFile: (File) -> Unit,
) : DefaultFtplet() {

    override fun onUploadStart(session: FtpSession, request: FtpRequest): FtpletResult {
        // 相机若往子目录传，目录不存在会直接 550。3 行消掉整类"传不上来"的故障。
        request.argument?.let { File(homeDir, it.trimStart('/')).parentFile?.mkdirs() }
        return FtpletResult.DEFAULT
    }

    override fun onUploadEnd(session: FtpSession, request: FtpRequest): FtpletResult {
        val arg = request.argument
        val physical = session.fileSystemView.getFile(arg).physicalFile as? File
        val target = physical?.takeIf { it.isFile } ?: File(homeDir, arg.trimStart('/'))
        if (target.isFile) onFile(target)
        return FtpletResult.DEFAULT
    }
}
