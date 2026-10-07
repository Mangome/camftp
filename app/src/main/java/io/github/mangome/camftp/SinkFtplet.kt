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
 *
 * 另外兼职数连接数：`onConnect/onDisconnect` 只对控制连接成对触发（实测 DefaultFtpHandler 里
 * 只在 sessionOpened/sessionClosed 调，数据连接不触发），所以它就是「相机连上了没」
 */
class SinkFtplet(
    private val homeDir: File,
    /** 控制连接数变化：+1 连上 / -1 断开 */
    private val onClients: (Int) -> Unit = {},
    /** 正在接收的文件名（null = 传完/断开）：顶部面板的「正在接收」靠它 */
    private val onTransfer: (String?) -> Unit = {},
    private val onFile: (File) -> Unit,
) : DefaultFtplet() {

    override fun onConnect(session: FtpSession): FtpletResult {
        onClients(1)
        return FtpletResult.DEFAULT
    }

    override fun onDisconnect(session: FtpSession): FtpletResult {
        onClients(-1)
        onTransfer(null)   // 传到一半断线：onUploadEnd 不会来，别让面板永远钉在「正在接收」
        return FtpletResult.DEFAULT
    }

    override fun beforeCommand(session: FtpSession, request: FtpRequest): FtpletResult {
        val arg = request.argument ?: return FtpletResult.DEFAULT
        when (request.command) {
            "CWD" -> {
                val dir = session.fileSystemView.getFile(arg)
                if (!dir.doesExist()) (dir.physicalFile as? File)?.mkdirs()
            }
            "STOR", "APPE", "STOU" -> {
                File(homeDir, arg.trimStart('/')).parentFile?.mkdirs()
                onTransfer(arg)   // 数据连接还没开就能说「正在接收」，名字此刻已知
            }
        }
        return FtpletResult.DEFAULT
    }

    override fun onUploadEnd(session: FtpSession, request: FtpRequest): FtpletResult {
        val arg = request.argument
        val physical = session.fileSystemView.getFile(arg).physicalFile as? File
        val target = physical?.takeIf { it.isFile } ?: File(homeDir, arg.trimStart('/'))
        onTransfer(null)
        if (target.isFile) onFile(target)
        return FtpletResult.DEFAULT
    }
}
