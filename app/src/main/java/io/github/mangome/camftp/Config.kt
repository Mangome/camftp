package io.github.mangome.camftp

import android.content.Context

/**
 * 配置项的唯一出处：服务、UI、入库都用这里，避免 key 两边写岔。
 * 默认值来自 [Profiles.NIKON_Z50II]（相机侧填的必须跟它一致）。
 */
object Config {

    const val PREFS = "camftp"

    private const val KEY_PORT = "port"
    private const val KEY_USER = "user"
    private const val KEY_PASSWORD = "password"
    private const val KEY_FOLDER = "folder"
    private const val KEY_ANONYMOUS = "anonymous"

    const val DEFAULT_USER = "camftp"
    const val DEFAULT_PASSWORD = "123456"
    const val DEFAULT_FOLDER = "CamFtp"

    /** 普通 App 绑不了 <1024 的端口（prim-ftpd 也把下限设在 1024） */
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    var port: Int = Profiles.NIKON_Z50II.controlPort

    /** 被动端口范围跟着机型走，不给用户改（相机侧不会填这个，改坏了只会白断连） */
    val passivePorts: String = Profiles.NIKON_Z50II.passivePorts.orEmpty()
    var user: String = DEFAULT_USER
    var password: String = DEFAULT_PASSWORD
    var folder: String = DEFAULT_FOLDER

    /** 允许匿名登录：相机侧开「匿名登录」时用，不校验用户名密码（默认开，相机上少填两项） */
    var anonymous: Boolean = true

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        port = p.getInt(KEY_PORT, Profiles.NIKON_Z50II.controlPort)
        user = p.getString(KEY_USER, DEFAULT_USER) ?: DEFAULT_USER
        password = p.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
        folder = p.getString(KEY_FOLDER, DEFAULT_FOLDER) ?: DEFAULT_FOLDER
        anonymous = p.getBoolean(KEY_ANONYMOUS, true)
    }

    fun save(
        context: Context,
        newPort: Int,
        newUser: String,
        newPassword: String,
        newFolder: String,
        newAnonymous: Boolean,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_PORT, newPort)
            .putString(KEY_USER, newUser)
            .putString(KEY_PASSWORD, newPassword)
            .putString(KEY_FOLDER, newFolder)
            .putBoolean(KEY_ANONYMOUS, newAnonymous)
            .apply()
        load(context)
    }

    fun portError(value: String): String? {
        val n = value.trim().toIntOrNull() ?: return "必须是数字"
        return if (n in MIN_PORT..MAX_PORT) null else "端口要在 $MIN_PORT-$MAX_PORT 之间"
    }
}
