package io.github.mangome.camftp

/**
 * 机型差异全部收敛成数据：以后加机型 = 加一条数据，不动引擎。
 */
data class CameraProfile(
    val id: String,
    val displayName: String,
    val controlPort: Int,
    val passivePorts: String?,
    val notes: String,
)

object Profiles {
    val NIKON_Z50II = CameraProfile(
        id = "nikon-z50ii",
        displayName = "Nikon Z50II",
        controlPort = 2121,
        passivePorts = "32768-61000",
        notes = "相机：网络菜单 → 连接到FTP服务器 → 配置手动；PASV 开或关都行；目标文件夹选「主文件夹」。",
    )

    val GENERIC_NIKON = NIKON_Z50II.copy(id = "nikon-generic", displayName = "Nikon（其他机型）")
}
