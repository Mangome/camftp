# CamFtp — 安卓相机 FTP 接收端 开发文档（MVP：Nikon Z50II）

**版本**：v1（2026-10-07）｜ **面向**：在开发机上执行本文档的编码 Agent
**前置事实**：手机开热点 + 相机 FTP 推送 → 手机收图的链路**已在真机验证通过**（用现成 FTP 服务端 App 走通）。本 App 要把这条链路做成一个能长期用的开源应用。
**口径**：标【已核实】的都有出处（尼康手册 / Android 文档 / Apache 源码，见附录 B）；标【待实测】的必须真机确认。

---

## 0. TL;DR（先读完这一段再动手）

做一个**单模块 Android 应用**：

- 前台服务里跑 **Apache FtpServer 1.2.1**，控制端口 2121（可配），被动端口 `32768-61000`。
- 相机（Nikon Z50II）通过**手机热点**连上来，把 JPEG 用 FTP `STOR` 推到手机。
- 收到文件 → 写入系统相册（MediaStore `DCIM/CamFtp`）→ 通知栏提示。
- **不申请任何存储权限**（走 MediaStore，Android 10+ 免权限）。
- **不控制热点**：MVP 里热点由用户手动开，App 只提供一个"打开热点设置"的按钮。热点自动化放 M3（Android 16+ 才有公开 API，见 §3.3）。
- 只有一个界面：状态 / 开关 / 配置 / "手机的热点 IP 和端口"（要念给相机听的那串字）。

**不要做的事**（省得返工）：不要自己写 FTP 协议栈；不要引入第三方 FTP 库（Apache FtpServer 就是 prim-ftpd 用的那个，已在 Android 上跑了十年）；不要用 Compose（见 §5.2）；不要申请 `MANAGE_EXTERNAL_STORAGE`；不要做开机自启。

---

## 1. 背景

尼康官方 SnapBridge 与第三方 Camera Connect & Control 在 Z50II 上连接体验差。已确认可行的替代路线：**相机自带的 FTP 推送能力 + 手机当 FTP 服务器**。国内图片直播平台（喔图/享像派）就是这套架构，本仓 `server.py` 是它的 Termux 原型（可读，作为行为参考）。

本 App = `server.py` 的安卓原生版 + 相册入库 + 通知 + 相机配置引导。

---

## 2. 已核实的协议事实（写代码前先看）

| 事实 | 出处 |
| --- | --- |
| Z50II 支持 FTP / SFTP / FTPS 推送，**服务器地址、目标文件夹、端口号都可在相机上手动填** | Z50II 手册 25-04 / 09-09-07 |
| 相机侧 `[PASV mode]` 可开关；`[Anonymous login]` 可开关 | 同上 |
| 尼康写明的端口：**FTP 用 TCP 21 和 32768–61000** | Z50II 手册 25-04（"Firewall settings"） |
| 尼康官方只保证 IIS 类服务器，"不支持第三方软件的 FTP 服务器" | 同上 |
| 生产环境实测配置（喔图教程）：**端口 1234、PASV 关、匿名关、用户名+密码** | faq.alltuu.com/a538/5a74/3f78 |
| 被动模式下，服务器不指定 `passiveAddress` 时，Apache FtpServer 用**控制连接的本地地址**作为 PASV 返回地址 | `IODataConnectionFactory.java:179-185`（已读源码） |
| Apache FtpServer 1.2.1 不引用 `javax.management` / `java.beans`（Android 上可跑，无缺失包） | 已解包扫过 212 个 class |
| Ftplet 回调签名是 `onUploadEnd(FtpSession, FtpRequest)`，**不再有 File 参数** | `ftplet-api-1.2.1` 源码 |
| FGS 类型 `connectedDevice` 的运行时前提之一是持有 `CHANGE_WIFI_STATE`（我们本来就要） | Android FGS 官方文档 |
| 普通 App 绑不了 <1024 端口（prim-ftpd 自身限定端口范围 1024–64000，佐证） | prim-ftpd 源码 strings |

---

## 3. 平台约束（决定架构长什么样）

### 3.1 端口
- 控制端口默认 **2121**（避开 21：<1024 需要特权）。
- 被动端口 **`32768-61000`**（尼康手册写死的数据端口范围）；用 `DataConnectionConfigurationFactory.setPassivePorts("32768-61000")`。
- 相机 Ftp 配置里填的端口必须与 App 里一致。

### 3.2 网络
- 手机热点网段因 ROM 而异（常见 `192.168.43.1`；也有 `192.168.1.1` / `172.20.10.1` 等）。**不要把 IP 写死**，启动时枚举网卡、把结果展示出来。
- 服务器监听 `0.0.0.0`（所有网卡）即可 —— 这样热点、家里 Wi-Fi、甚至 USB 网都通。

### 3.3 热点：MVP 不自动化
- Android 16（API 36）起才有公开的 `WifiManager.startLocalOnlyHotspotWithConfiguration()`；**`SoftApConfiguration.Builder.setWifiSsid/setPassphrase` 在文档里标的是 "Added in version 36.1"**，也就是说"自定义固定 SSID/密码"最稳妥的落点是 Android 16 QPR2+。【已核实，但你的机器未必满足】
- 结论：**MVP 让用户手动开热点**（已验证可行的路径），App 只放一个"打开热点设置"按钮（`Intent("com.android.settings.TETHER_SETTINGS")`，失败降级 `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`，再失败 `Settings.ACTION_WIRELESS_SETTINGS`）。
- 热点自动化放 M3，单独一个文件、`@RequiresApi(36)`，用运行时版本判断兜住，不许影响 MVP 构建。

### 3.4 后台存活
- 用**前台服务**（类型 `connectedDevice`），常驻通知。
- 持 `PARTIAL_WAKE_LOCK`（可开关，默认开），否则息屏长传可能被掐。
- 国产 ROM 仍需用户手动把 App 设为"无限制/允许自启动"，写进 README。

---

## 4. 架构

```
┌──────────────────────────────────────────────┐
│ MainActivity (XML + ViewBinding)             │  状态/开关/配置/IP 展示/日志
└───────────────┬──────────────────────────────┘
                │ startService / bind
┌───────────────▼──────────────────────────────┐
│ FtpService  (foreground, connectedDevice)    │  通知更新、wakelock、状态广播
└───────────────┬──────────────────────────────┘
                │
┌───────────────▼──────────────────────────────┐
│ FtpEngine        （纯 JVM，可单测）           │  Apache FtpServer 配置与生命周期
│  ├─ CameraProfile（数据，非接口）             │  MVP: NikonZ50II / GenericNikon
│  ├─ Sink (interface)                         │  可测性来源
│  │   ├─ MediaStoreSink   （真机）             │
│  │   └─ FileSink         （单测用 fake）      │
│  └─ FtpServerFactory + Listener + UserManager │
└──────────────────────────────────────────────┘
```

**扩展点（"预留多机型"怎么落）**：
- 机型差异**全部收敛成数据**：`CameraProfile(id, displayName, controlPort, passivePorts, setupSteps)`，放一个 `object Profiles { val NIKON_Z50II; val GENERIC_NIKON }`。**MVP 只有一个真实现，所以不引入 interface / factory**——等真的出现第二种传输协议（比如 PTP/IP）再抽 `Transport` 接口。
- 分层边界：`FtpEngine` 不 import 任何 `android.*`（除日志），`Sink` 负责入库。以后加"传完自动发微信/上传网盘"只需再加一个 `Sink` 实现。

---

## 5. 技术选型

### 5.1 版本
| 项 | 值 |
| --- | --- |
| 语言 | Kotlin |
| `compileSdk` / `targetSdk` | 36 |
| `minSdk` | **29**（Android 10；避开 legacy 存储的分支，MediaStore 全权限免申请） |
| AGP / Kotlin | 8.13.x / 2.2.x（以本机 Android Studio 模板为准，别硬凑） |
| 构建 | Gradle Kotlin DSL + version catalog |

### 5.2 UI：XML + ViewBinding，别用 Compose
单屏应用，Compose 只会带来 Compose 编译器插件/BOM 的版本对齐风险，换不来任何东西。**一个人写、一个布局文件。**

### 5.3 依赖（照抄，exclusions 必须有）
```kotlin
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // FTP 引擎：prim-ftpd 同款，Android 上已验证可用
    implementation("org.apache.ftpserver:ftpserver-core:1.2.1") {
        exclude(group = "org.springframework", module = "spring-context")
        exclude(group = "org.slf4j", module = "jcl-over-slf4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
        exclude(group = "log4j", module = "log4j")
    }
    // 与 ftpserver 1.2.1 锁定的 slf4j-api 1.7.36 对齐；输出走 logcat
    implementation("org.slf4j:slf4j-android:1.7.36")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.apache.commons:commons-net:3.11.1")  // 单测里的 FTP 客户端
}
```
`mina-core:2.2.4` 会由 ftpserver 传递进来，不用手写。

`android { buildFeatures { viewBinding = true } }`；MVP **关闭 `minifyEnabled`**；真要开，先加 `-keep class org.apache.ftpserver.** { *; }`（Apache FtpServer 有反射），跑通 §7.2 再提交。

---

## 6. 文件清单与规格

工程名 `camftp`，包名 `io.github.mango.camftp`（用户名不是 mango 就全局改掉，首次提交前改完）。

### 6.1 `CameraProfile.kt`
```kotlin
data class CameraProfile(
    val id: String,
    val displayName: String,
    val controlPort: Int,
    val passivePorts: String?,      // null = 不限制（不推荐）
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
```
（以后加机型 = 加一条数据，改 UI 下拉框即可，不动引擎。）

### 6.2 `Sink.kt`
```kotlin
interface Sink {
    /** 收完一个文件后调用；实现方负责搬走或删除 file。返回给 UI 展示的结果。 */
    fun onStored(file: java.io.File): StoreResult
}
data class StoreResult(val displayName: String, val ok: Boolean, val detail: String = "")
```

### 6.3 `MediaStoreSink.kt`
- `image/*` → `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`
- `video/*` → `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`
- 其它（NEF 之外、未知后缀）→ `MediaStore.Downloads.EXTERNAL_CONTENT_URI`
- `RELATIVE_PATH`：**图片和视频**用 `DCIM/CamFtp`；Downloads 用 `Download/CamFtp`
- 流程：`insert(values{ DISPLAY_NAME, MIME_TYPE, RELATIVE_PATH, IS_PENDING=1 })` → `openOutputStream` 拷贝 → `update(IS_PENDING=0)` → 删除源文件。
- mime 推断：`MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)`，`.nef` 手动映射成 `image/x-nikon-nef`（失败也不崩，走 Downloads）。
- 源文件在 app 私有目录，不占权限；峰值只多一个文件的大小。
- 同名冲突交给 MediaStore 自动改名（`DSC_0001 (1).JPG`），MVP 接受，写在 README 里。

### 6.4 `FtpEngine.kt`（核心，纯 JVM）
```kotlin
class FtpEngine(
    private val profile: CameraProfile,
    private val homeDir: File,          // context.filesDir/ftp
    private val user: String,
    private val password: String,
    private val sink: Sink,
) {
    private var server: FtpServer? = null

    fun start() {
        val listener = ListenerFactory().apply {
            port = profile.controlPort
            idleTimeout = 300          // 秒；相机两次操作之间可能停很久
        }
        val dataCfg = DataConnectionConfigurationFactory().apply {
            passivePorts = profile.passivePorts
            idleTime = 300
            isActiveEnabled = true     // 相机可以把 PASV 关掉走主动模式
            isPassiveEnabled = true
            // 不设 passiveAddress：源码里会回落到控制连接的本地地址，正是热点 IP
        }
        listener.dataConnectionConfiguration = dataCfg.createDataConnectionConfiguration()

        val users = SimpleUserManager(user, password, homeDir.absolutePath)

        val factory = FtpServerFactory().apply {
            addListener("default", listener.createListener())
            userManager = users
            // 家目录来自 User.homeDirectory，createHome 会在缺目录时建出来
            fileSystem = NativeFileSystemFactory().apply { setCreateHome(true) }
            connectionConfig = ConnectionConfigFactory().apply {
                maxLogins = 4
                isAnonymousLoginEnabled = false
                maxThreads = 8
            }.createConnectionConfig()
            // 注意：FtpServerFactory 没有 addFtplet()，只能整表设置（1.2.1 源码）
            setFtplets(mapOf("sink" to SinkFtplet(homeDir, sink)))
        }
        server = factory.createServer().also { it.start() }
    }

    fun stop() { server?.stop(); server = null }
}
```
要点：
- **`fileSystem`** 用 `org.apache.ftpserver.filesystem.nativefs.NativeFileSystemFactory`（`FileSystemFactory.createFileSystemView(User)`，家目录取自 `User.getHomeDirectory()`；`setCreateHome(true)` 负责建目录）。**别用 `PropertiesUserManagerFactory`**（要写文件、还可能碰到 Android 缺的类），自己实现下面的 `SimpleUserManager`。
- `SinkFtplet`：
```kotlin
class SinkFtplet(private val homeDir: File, private val sink: Sink) : DefaultFtplet() {
    override fun onUploadEnd(session: FtpSession, request: FtpRequest): FtpletResult {
        val f = session.fileSystemView.getFile(request.argument).physicalFile as? File
        val target = f?.takeIf { it.isFile } ?: File(homeDir, request.argument.trimStart('/'))
        if (target.isFile) sink.onStored(target)
        return FtpletResult.DEFAULT
    }
}
```
  `NativeFtpFile.getPhysicalFile()` 实测返回的就是 `java.io.File`；拿不到时按 homeDir 回落。注意回调**必须快速返回**：入库拷贝别在这个线程里做（`onUploadEnd` 在 MINA IO 线程上），丢给服务里的单线程 executor 或 `MediaStoreSink` 自带队列。
- `SimpleUserManager`：实现 `org.apache.ftpserver.ftplet.UserManager`（8 个方法），内部就一个 `BaseUser`（`setName/setPassword/setHomeDirectory/setAuthorities(listOf(WritePermission(home)))`），`authenticate()` 比对明文密码，`getAllUserNames()` 返回 1 个元素。约 30 行。

### 6.5 `FtpService.kt`
- `LifecycleService`（或普通 `Service`）+ `startForeground(id, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)`（三参重载 API 29+ 一直可用，minSdk 29 就够）。
- 通知：低优先级、`ongoing`，内容 `运行中 · 已收 N 张`；每收一张更新一次（`NotificationManagerCompat.notify`）。
- 持有 `PARTIAL_WAKE_LOCK`（tag `camftp`），`onDestroy` 释放。
- `onStartCommand` 返回 `START_STICKY`（被系统杀掉后能回来），但**不要**做开机自启（MVP 不需要，且 Android 15+ 对 `BOOT_COMPLETED` 拉起前台服务有限制）。
- 用户名/密码只允许 ASCII 简单字符（相机上用遥控器导航打字很痛苦），默认 旧默认账号。
- 通过 `LocalBroadcastManager`/`StateFlow` 单例把状态（运行中/计数/最后文件名/错误）给 UI。

### 6.6 `MainActivity.kt` + `activity_main.xml`
必须有的元素（别加更多）：
1. 状态行：`运行中 · 0.0.0.0:2121` 或 `已停止`。
2. 「启动 / 停止」大按钮。
3. **给相机念的地址块**（字号最大）：`地址 192.168.43.1 · 端口 2121 · 旧默认账号` + 「复制」按钮。
4. 配置：端口 / 用户名 / 密码 / 保存目录名（SharedPreferences 持久化，改配置时若服务在跑就提示重启服务）。
5. 网卡列表：枚举 `NetworkInterface.getNetworkInterfaces()` 的 IPv4，显示 `接口名 → IP`，热点接口高亮（名字命中 `ap0|swlan0|wlan1|softap0|wlan-ap` 视为热点；命中不了就只显示列表并提示"填相机里那个热点网段的地址"）。
6. 「打开热点设置」按钮。
7. 最近 10 条事件（"收到 DSC_0001.JPG  8.4 MB"）。
8. 权限申请：`POST_NOTIFICATIONS`（API 33+，启动服务前问）。

### 6.7 `AndroidManifest.xml`
```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE"/>          <!-- connectedDevice FGS 的前提 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>

<service android:name=".FtpService"
         android:exported="false"
         android:foregroundServiceType="connectedDevice"/>
```
**不要**申请存储类权限。

---

## 7. 测试

### 7.1 JVM 单测（必须留下，CI 里跑）
`app/src/test/java/.../FtpEngineTest.kt`：
1. 临时目录起 `FtpEngine`（**端口用 20000+ 的随机高位端口**，别用 0 —— `ListenerFactory.getPort()` 拿不到系统临时分配的端口，测试里就没法连了）。
2. 用 `commons-net` 的 `FTPClient` 登录 → `enterLocalPassiveMode()` → `storeFile("DSC_0001.JPG", stream)` → 断言：`FakeSink` 收到 1 个文件、字节数一致、文件已从 home 目录消失。
3. 加一个 `enterLocalActiveMode()` 的用例（相机可以把 PASV 关掉）。
4. 加一个错误密码被拒的用例。

> 这一条是"最小可运行的验证"：没有相机也能证明 FTP 层是活的。

### 7.2 真机验收清单（人来执行，Agent 只写脚本/说明）
1. 手机开热点，SSID 纯英文，记录密码。
2. 装 App，配置端口 2121，启动服务，记下 App 显示的热点 IP。
3. 相机：`网络` → `连接到FTP服务器` → `网络设定` → `创建配置文件` → `配置手动` → 服务器地址 = 手机 IP、端口 = 2121、PASV **先开着**、匿名关、填用户名密码 → 目标文件夹选**主文件夹**。
4. 相机连上后：回放里选一张 → `i` → `选择上传(FTP)`。
5. 验收：通知出现"收到 1 张"；系统相册能看到该图；原文件名保留。
6. 回归：把相机 PASV 改成 **OFF** 再传一张，也必须成功。
7. 回归：传 5 张连拍（每张 ~8MB），全程息屏，链接不许断。

---

## 8. 里程碑

| 里程碑 | 内容 | 验收 |
| --- | --- | --- |
| **M1** | 工程骨架 + `FtpEngine` + 单测 | `./gradlew test` 全绿 |
| **M2** | `FtpService` 前台服务 + 通知 + `MediaStoreSink` | 真机：用电脑的 FTP 客户端把一张图传上来，相册能看到 |
| **M3** | UI（含 IP 展示、复制配置、热点按钮）+ 权限 | 真机：§7.2 全流程通过（相机真人操作） |
| **M4** | README（含相机设置图文步骤、常见错误对照）+ LICENSE + NOTICE | 仓库能被陌生人照着跑通 |
| **M5（可选）** | 热点自动化（API 36.1+ LOH） | 不影响 M1–M4 的构建 |

---

## 9. 已知坑（照抄进 README）

1. 相机的"防火墙"文档写的是端口 21；**必须在相机手动配置里把端口改成 2121**，向导允许填（手册原文）。
2. 相机同一时刻只能连一种设备（FTP / 智能设备 / 电脑）。用 FTP 前把 SnapBridge 的 AP 模式断开。
3. 相机"目标文件夹"必须已存在——**选「主文件夹」最省事**；选了子文件夹就要保证 App 端存在同名目录（MVP 不做自动建目录，UI 里提示）。
4. 热点 SSID 必须英文/字母数字，否则相机扫不到；相机 `路由器频带` 要与热点频段一致。
5. 手机侧要关掉热点的"无连接时自动关闭"，国产 ROM 还要把 App 设成"无限制"后台 + 允许自启动。
6. 传 RAW/视频：相机 FTP 是否发 NEF 视机型而定；App 对任意后缀都要能存不崩（未知类型进 Downloads）。
7. 断线是正常现象（相机有 `Inactive connection timeout`）：服务器必须能承受反复登录登出，`maxLogins` 别设 1。
8. 相册里文件名可能变成 `DSC_0001 (1).JPG`（同名冲突时 MediaStore 自动改名），不是 bug。

---

## 10. 开源事项

- **LICENSE：Apache-2.0**（与 Apache FtpServer / MINA / slf4j 一致，最省事）。
- **必须带 `NOTICE`**：Apache-2.0 的组件要求保留声明。把 `ftpserver-core`、`mina-core`、`slf4j` 的版权行抄进 `NOTICE`。
- 商标：应用**名字里不要出现 "Nikon"**（"Nikon" 只能在描述"兼容机型"时作事实性提及），图标不要用尼康 Logo。App 名建议「CamFtp」/「相机 FTP 接收」。
- 隐私：App 只做局域网 FTP、不联网、不上传任何数据 —— 写进 README 和（如要上架）隐私声明。
- README 必须包含：一句话说明、截图位、相机设置步骤（Z50II + 通用尼康）、常见错误对照表、构建方法。

---

## 11. 交付前自检（Agent 逐条打勾）

- [ ] `./gradlew test` 通过（含 PASV / 主动模式 / 密码错误三个用例）
- [ ] `./gradlew assembleDebug` 无警告级错误
- [ ] 全工程 grep 不到 `MANAGE_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE`
- [ ] 控制端口默认 2121，被动端口默认 `32768-61000`，两者都能在 UI 改且持久化
- [ ] 前台服务类型是 `connectedDevice`，Manifest 里有 `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- [ ] `FtpEngine.kt` 里没有 `android.*` 的 import（保证可单测）
- [ ] README 里有 §9 的 8 条坑
- [ ] LICENSE + NOTICE 在仓库根目录

---

## 附录 A：尼康 Z50II 相机端设置步骤（原文核对自手册）

```
网络菜单 → 连接到FTP服务器 → 网络设定 → 创建配置文件 → 配置手动
  [常规] 配置文件名称：随意
  [无线] 选刚刚的手机热点（SSID 全英文）
  [TCP/IP] 一般「自动获取」即可（热点有 DHCP）
  [FTP]
     服务器类型：FTP
     地址：App 显示的 IP（如 192.168.43.1）
     端口：2121
     目标文件夹：主文件夹
     PASV模式：ON（或 OFF，两条都支持）
     匿名登录：OFF
     用户名 / 密码：与 App 一致
连上后：回放 → i 菜单 → 选择上传(FTP)
自动传：Options → 打开自动上传（视频除外）
```
来源：<https://onlinemanual.nikonimglib.com/z50II/en/25-04.html>、<https://onlinemanual.nikonimglib.com/z50II/en/09-09-07.html>

## 附录 B：参考资料

- 尼康 Z50II 手册 FTP 章节（端口、PASV、登录方式）：见附录 A 两条 URL
- Apache FtpServer 1.2.1 源码：`ListenerFactory` / `DataConnectionConfigurationFactory` / `FtpServerFactory` / `ConnectionConfigFactory` / `DefaultFtplet` / `UserManager` / `BaseUser`（本文档中所有 API 名均已从源码核对）
- `IODataConnectionFactory.java:179-185`：PASV 返回地址的取法
- 生产环境配置参考（端口 1234 / PASV OFF）：<https://faq.alltuu.com/a538/5a74/3f78>
- 开源同类实现（可直接读，别 fork）：[wolpi/prim-ftpd](https://github.com/wolpi/prim-ftpd)（Apache FtpServer + 前台服务 + 小部件的完整范例）、[ppareit/swiftp](https://github.com/ppareit/swiftp)
- Android 前台服务类型与前提条件：<https://developer.android.com/develop/background-work/services/fgs/service-types>
- 本仓原型：`<本机原型脚本路径>`（行为参照：被动端口范围、termux-media-scan 入库思路）
