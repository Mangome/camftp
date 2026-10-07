package io.github.mangome.camftp

import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.FtpException
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.usermanager.AnonymousAuthentication
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.WritePermission

/**
 * 单用户、明文密码。不用 PropertiesUserManagerFactory：它要写文件，还可能碰到 Android 缺的类。
 *
 * 坑：构造参数**不能**叫 `name` / `password`。BaseUser 有 getName/setName/getPassword/setPassword，
 * 在 `apply { setName(name) }` 里那个 `name` 会解析成接收者 BaseUser 自己的属性（初始为 null），
 * 于是用户名密码被写成 null，登录必失败。这里改成 userName/userPassword 绕开遮蔽。
 * 坑 2：只给 WritePermission 是登不进去的 —— USER 命令会做并发登录检查
 * （`configUser.authorize(ConcurrentLoginRequest)`），返回 null 就直接回 421。必须给 ConcurrentLoginPermission。
 * 坑 3：WritePermission 的入参是**相对家目录的虚拟路径**，传物理路径会让每个 STOR 都 550 Permission denied。
 * 这里传 "/" 即整个家目录。
 * 坑 5（同坑 1 的变体）：局部变量名不能叫 `authorities` —— BaseUser 有 getAuthorities/setAuthorities，
 * `apply { setAuthorities(authorities) }` 里的 `authorities` 会解析成接收者自己的（此时为空列表），
 * 结果用户一个权限都没有，USER 阶段直接 421。
 * 坑 4：匿名登录（相机侧开「匿名登录」）走的是 [AnonymousAuthentication]，密码不被校验；
 * 返回的用户名必须叫 "anonymous"，否则 DefaultFtpStatistics 不把它算作匿名会话。
 */
class SimpleUserManager(
    private val userName: String,
    private val userPassword: String,
    homeDir: String,
    private val anonymousEnabled: Boolean = false,
) : UserManager {

    private val permissions = listOf(
        WritePermission("/"),   // 虚拟路径；"/" = 家目录整棵树（传物理路径会全部 550）
        ConcurrentLoginPermission(Int.MAX_VALUE, Int.MAX_VALUE),   // 相机断线重连是常态，不限制
    )

    private val user = BaseUser().apply {
        setName(userName)
        setPassword(userPassword)
        setHomeDirectory(homeDir)
        setAuthorities(permissions)
    }

    private val anonymousUser = BaseUser().apply {
        setName(UserManager.ANONYMOUS)
        setHomeDirectory(homeDir)
        setAuthorities(permissions)
    }

    override fun getUserByName(name: String?): User? = when (name) {
        userName -> user
        UserManager.ANONYMOUS -> anonymousUser.takeIf { anonymousEnabled }
        else -> null
    }

    override fun getAllUserNames(): Array<String> =
        if (anonymousEnabled) arrayOf(userName, UserManager.ANONYMOUS) else arrayOf(userName)

    override fun doesExist(name: String?): Boolean = getUserByName(name) != null

    override fun authenticate(authentication: Authentication?): User? = when (authentication) {
        is AnonymousAuthentication -> anonymousUser.takeIf { anonymousEnabled }
        // 匿名开关关着时，"anonymous" 这个用户名也不当普通账号放行
        is UsernamePasswordAuthentication ->
            user.takeIf { it.name == authentication.username && authentication.password == userPassword }
        else -> null
    }

    override fun getAdminName(): String = userName

    override fun isAdmin(login: String?): Boolean = login == userName

    override fun delete(name: String?) = throw FtpException("not supported")

    override fun save(user: User?) = throw FtpException("not supported")
}
