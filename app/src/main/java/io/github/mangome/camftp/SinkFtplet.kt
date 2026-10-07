package io.github.mangome.camftp

import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.FtpletResult
import java.io.File

/**
 * 上传回调。注意：回调跑在 MINA IO 线程上，只做快事 —— 建目录、把文件丢给 [onFile]，绝不阻塞。
 *
 * 建目录分两处（相机和 curl 的路径不一样）：
 *  - `CWD 不存在的目录` 要先建，否则客户端直接吃 550（curl 就是先 CWD 再 STOR）
 *  - `STOR 子目录/文件` 要建父目录
 */
class SinkFtplet(
    private val homeDir: File,
    private val onFile: (File) -> Unit,
) : DefaultFtplet() {

    override fun beforeCommand(session: FtpSession, request: FtpRequest): FtpletResult {
        val arg = request.argument ?: return FtpletResult.DEFAULT
        when (request.command) {
            "CWD" -> {
                val dir = session.fileSystemView.getFile(arg)
                if (!dir.doesExist()) (dir.physicalFile as? File)?.mkdirs()
            }
            "STOR", "APPE", "STOU" ->
                File(homeDir, arg.trimStart('/')).parentFile?.mkdirs()
        }
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
