package io.github.mangome.camftp

import android.content.Context

/**
 * 配置项的唯一出处：服务、UI、入库都用这里，避免 key 两边写岔。
 * 默认值来自 [Profiles.NIKON_Z50II]（相机侧填的必须跟它一致）。
 */
object Config {

    const val PREFS = "camftp"

    private const val KEY_PORT = "port"
    private const val KEY_PASSIVE_PORTS = "passivePorts"
    private const val KEY_USER = "user"
    private const val KEY_PASSWORD = "password"
    private const val KEY_FOLDER = "folder"

    const val DEFAULT_USER = "camftp"
    const val DEFAULT_PASSWORD = "123456"
    const val DEFAULT_FOLDER = "CamFtp"

    /** 普通 App 绑不了 <1024 的端口（prim-ftpd 也把下限设在 1024） */
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    var port: Int = Profiles.NIKON_Z50II.controlPort
    var passivePorts: String = Profiles.NIKON_Z50II.passivePorts.orEmpty()
    var user: String = DEFAULT_USER
    var password: String = DEFAULT_PASSWORD
    var folder: String = DEFAULT_FOLDER

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        port = p.getInt(KEY_PORT, Profiles.NIKON_Z50II.controlPort)
        passivePorts = p.getString(KEY_PASSIVE_PORTS, Profiles.NIKON_Z50II.passivePorts) ?: ""
        user = p.getString(KEY_USER, DEFAULT_USER) ?: DEFAULT_USER
        password = p.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
        folder = p.getString(KEY_FOLDER, DEFAULT_FOLDER) ?: DEFAULT_FOLDER
    }

    fun save(context: Context, newPort: Int, newPassivePorts: String, newUser: String, newPassword: String, newFolder: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_PORT, newPort)
            .putString(KEY_PASSIVE_PORTS, newPassivePorts)
            .putString(KEY_USER, newUser)
            .putString(KEY_PASSWORD, newPassword)
            .putString(KEY_FOLDER, newFolder)
            .apply()
        load(context)
    }

    /** 被动端口范围：空 = 不限制（不推荐）；否则必须是 "a-b" 且 a<b */
    fun passivePortsError(value: String): String? {
        if (value.isBlank()) return null
        val parts = value.trim().split('-')
        if (parts.size != 2) return "格式：32768-61000"
        val from = parts[0].trim().toIntOrNull() ?: return "格式：32768-61000"
        val to = parts[1].trim().toIntOrNull() ?: return "格式：32768-61000"
        if (from !in MIN_PORT..MAX_PORT || to !in MIN_PORT..MAX_PORT || from >= to) return "端口需在 $MIN_PORT-$MAX_PORT 且 起<止"
        return null
    }

    fun portError(value: String): String? {
        val n = value.trim().toIntOrNull() ?: return "必须是数字"
        return if (n in MIN_PORT..MAX_PORT) null else "需在 $MIN_PORT-$MAX_PORT（<1024 需要特权）"
    }
}
