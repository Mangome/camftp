# CamFtp 交接文档

**写于**：2026-10-07 ｜ **给**：接手的新 session / 未来的自己
**仓库**：<https://github.com/Mangome/camftp>（public，计划 Apache-2.0）
**原始需求与设计**：`docs/app-development.md`（v1，本文档**不修改它**，它的错误统一记在 §5）

---

## 0. 现状一句话

**M1–M3 全部完成**：工程骨架 / FTP 引擎 / 前台服务 + MediaStore 入库 / 正式 UI，**电脑侧全流程 + 相机（Nikon Z50II）真人实测都过了**。剩下的只有 **M4**（开源文档、许可、签名发布）和可选的 **M5**（热点自动化）。

---

## 1. 里程碑状态

| 里程碑 | 状态 | 证据 |
| --- | --- | --- |
| M1 工程骨架 + FtpEngine + 单测 | ✅ | 提交 `7ceceab`；`gradle test` **7 个用例全绿** |
| M2 前台服务 + MediaStore 入库 + 通知 | ✅ | 提交 `df18f95`；真机：被动/主动模式上传 `226`、NEF→`DCIM`、txt→`Download`、子目录自动建、私有目录无残留 |
| M3 正式 UI + 配置持久化 + 网卡识别 + 自检 | ✅ | 提交 `07b5572`；真机：热点识别 `ap0`、自检图入库、改端口自动热重启、事件列表正常 |
| **相机真人验收** | ✅ | 用户 2026-10-07 报告通过（Z50II → 手机热点 → 相册） |
| M4 文档 / 许可 / 签名发布 | ⬜ **待做** | 见 §7 |
| M5 热点自动化 | ⬜ 可选 | 见 §8 |

---

## 2. 环境（已装好，直接用）

> ⚠️ **每个新的 PowerShell 会话都要自己先设一遍环境变量**（用户级已经写进注册表，但已启动的进程读不到）：

```powershell
$env:JAVA_HOME='<JDK 21 路径>'
$env:ANDROID_HOME='<Android SDK 路径>'
$env:Path="$env:JAVA_HOME\bin;<Gradle 8.14.5 的 bin>;<Android SDK 路径>\platform-tools;$env:Path"
Set-Location <仓库根>
```

| 东西 | 值 |
| --- | --- |
| JDK | Temurin **21.0.12.1** → `<JDK 21 路径>`（用户级 `JAVA_HOME` 已设） |
| Gradle | **8.14.5** → `<Gradle 8.14.5 路径>`；wrapper 已生成且**分发包已缓存在** `~/.gradle/wrapper/dists`，`./gradlew` 可直接用 |
| Android SDK | `<Android SDK 路径>`（platform-tools **37.0.1**、`platforms;android-36`、`build-tools;36.0.0`，licenses 已接受） |
| adb | 用 SDK 里那份即可（另有便携版 `D:\Portable\platform-tools`） |
| 测试机 | **一台 OPPO ColorOS 机**：Android **17 / API 37**、ColorOS CN01、arm64，**无线调试在线**，serial `<无线调试 serial>._adb-tls-connect._tcp` |
| 代理 | 见 `~/.pi/agent/AGENTS.md`：`<HTTP 代理主机>:7890`（=`192.168.x.x:7890`），**只在单次命令里显式指定**，禁止全局配置 |
| GitHub | `gh` 已登录（账号 **Mangome**）；origin 用 **SSH**（`git@github.com:Mangome/camftp.git`） |
| 生图工具 | ❌ 未配置：`~/.pi/agent/models.json` 里没有可用的 `sgra` provider |

**网络事实**（实测）：Gradle 依赖**可以直连**（`dl.google.com` / `repo1.maven.org` 都通）；`developer.android.com` 不通，走代理后可用，中文镜像 `developer.android.google.cn` 也可用；**git 全局配了 `http.proxy=127.0.0.1:7890`，但那个代理没在跑** → https remote 会连接失败，所以 origin 用 SSH（SSH 不受影响）。

### 常用命令

```powershell
gradle test assembleDebug --console=plain            # 构建 + 单测（或 ./gradlew，dist 已缓存）
adb install -r -g app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n io.github.mangome.camftp/.MainActivity
adb exec-out screencap -p > shot.png                 # 截图
adb shell uiautomator dump /sdcard/u.xml; adb shell cat /sdcard/u.xml   # 拿控件坐标
adb shell input tap X Y                              # 点控件
```

**操作设备时的坑**（都踩过）：
- `adb shell pm grant` 会失败（Android 17 + ColorOS 收紧了 shell 授权）→ 一律用 `adb install -g` 一次授予运行时权限
- `uiautomator dump` 偶发失败（有 Toast/窗口动画时）→ 要重试几次
- 截图全黑 = 手机息屏 → 先 `adb shell input keyevent KEYCODE_WAKEUP`；**如果用户正在用手机就别抢屏幕**（会打断他）
- 想区分"App 崩了"和"用户划掉了"：看 `adb shell logcat -d -b crash`（崩溃缓冲区），别只看 `dumpsys window` 的焦点

---

## 3. 代码结构

```
camftp/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml            AGP 8.13.2 / Kotlin 2.2.21 / 依赖清单
├── local.properties                     sdk.dir（已 gitignore）
├── .gitattributes / .gitignore
├── docs/
│   ├── app-development.md               原始设计与需求（v1）
│   ├── handoff.md                       ← 本文档
│   └── images/app-top.png, app-bottom.png   App 截图（M4 用；状态栏含运营商名，发布前建议裁剪）
└── app/
    ├── build.gradle.kts                 compileSdk/targetSdk 36、minSdk 29、ViewBinding、packaging excludes
    └── src/
        ├── main/AndroidManifest.xml     权限 + MainActivity + FtpService(connectedDevice)
        ├── main/java/io/github/mangome/camftp/
        │   ├── Config.kt                配置唯一出处（端口/被动端口/用户名/密码/目录名）+ 校验
        │   ├── CameraProfile.kt         机型差异=数据（NIKON_Z50II / GENERIC_NIKON）
        │   ├── Sink.kt                  interface Sink + StoreResult
        │   ├── MediaStoreSink.kt        入库：图片/视频→DCIM/<dir>，其它→Download/<dir>；失败回滚且保留源文件
        │   ├── FtpEngine.kt             纯 JVM：Apache FtpServer 配置/生命周期 + 单线程入库 executor + retryPending()
        │   ├── SimpleUserManager.kt     单用户明文认证（**三个坑都在这里，见 §5**）
        │   ├── SinkFtplet.kt            上传回调：CWD/STOR 自动建目录 + 把文件丢给 executor
        │   ├── FtpService.kt            前台服务(connectedDevice)、静音常驻通知、PARTIAL_WAKE_LOCK、START_STICKY
        │   ├── FtpState.kt              StateFlow 单例状态通道（服务→UI）
        │   ├── NetworkInfo.kt           枚举 IPv4、过滤虚拟网卡、热点网卡置顶高亮
        │   └── MainActivity.kt          单屏 UI：状态/开关/自检/相机地址块+复制/配置/网卡/热点按钮/事件
        ├── main/res/                    布局、strings、矢量自适应图标、通知图标
        └── test/java/.../FtpEngineTest.kt   7 个 JVM 用例
```

---

## 4. 关键设计决策（改代码前先读这段）

1. **`FtpEngine` 不 import 任何 `android.*`**（除日志），所以能跑 JVM 单测 —— 这条不能破，破了就没法测。
2. **入库 executor 放在 `FtpEngine` 里**（单线程、保序）。原因：`Ftplet` 回调跑在 MINA IO 线程上，绝不能阻塞；放在引擎里，任何 `Sink` 实现都不用操心线程。
3. **入库失败绝不删源文件**：留在 `filesDir/ftp`，服务启动时 `retryPending()` 重试一次，UI 事件列表标红。宁可留垃圾不丢图。
4. **`SinkFtplet.beforeCommand` 里自动建目录**（`CWD` 和 `STOR/APPE/STOU` 两条路径）—— 这是文档 §9.3 明确说"MVP 不做"但我们翻案的地方，因为 curl 等客户端是"先 CWD 再 STOR"，不处理就直接 550。
5. **状态通道用 `FtpState`（StateFlow 单例）**，不用文档写的 `LocalBroadcastManager`（已废弃）。
6. **配置唯一出处 `Config`**（服务/UI/入库都读它）；保存时若服务在跑 → `FtpService.ACTION_RESTART` 热重启（文档说"提示用户手动重启"，我们改成自动，少一步无意义操作）。
7. **不用 Compose、不用第三方 FTP 库、机型差异只做成数据**（`CameraProfile`，只有一种实现所以不抽 interface/factory）—— 与文档一致。
8. 通知：`IMPORTANCE_LOW`、`setOnlyAlertOnce(true)`、ongoing、点击回 App。**全静音**（拍摄现场手机不该响）。

**功能上比文档多做的 3 件事**：自检按钮、CWD 建目录、残留重试。**技术上改的 1 件事**：StateFlow 替代 LocalBroadcastManager。

---

## 5. 🐞 踩过的坑（**照抄文档会崩**，全部实测过）

| # | 出处 | 问题 | 现象 / 修法 |
| --- | --- | --- | --- |
| 1 | 文档 §6.4 | `setPassiveEnabled(true)` **在 FtpServer 1.2.1 里不存在** | 编译不过。被动模式没有开关（只有 active 有），删掉即可 |
| 2 | 文档 §6.4 | `setFtplets(mapOf(...))`：Kotlin `mapOf` 是**不可变** Map，而 `DefaultFtpServerContext.dispose()` 会 `clear()` 它 | `stop()` 抛 `UnsupportedOperationException`。用 `mutableMapOf<String, Ftplet>` |
| 3 | 文档 §6.4 | `BaseUser().apply { setName(name) }` 里 `name` 解析成**接收者 BaseUser 自己的属性**（此时是 null） | 用户名/密码被写成 null，登录必失败。构造参数改名 `userName/userPassword` |
| 4 | 文档 §6.4 | 只给 `WritePermission`：`USER` 命令还会 `authorize(ConcurrentLoginRequest)` | 直接 `421 Maximum login limit has been reached.`。必须加 `ConcurrentLoginPermission(MAX, MAX)` |
| 5 | 文档 §6.4 | `WritePermission(homeDir.absolutePath)` 传的是**物理路径** | 每个 `STOR` 都 `550 Permission denied`。它按**相对家目录的虚拟路径**做前缀匹配，应传 `"/"` |
| 6 | 文档 §5.3 | `org.apache.commons:commons-net:3.11.1` **坐标不存在** | 正确坐标是 `commons-net:commons-net`（3.11.1 存在） |
| 7 | 文档 §5.3 | 依赖自带 `META-INF/DEPENDENCIES`、`LICENSE`、`NOTICE`，三份撞车 | `mergeDebugJavaResource` 失败。需要 `packaging { resources { excludes += ... } }` |
| 8 | 平台 | FTP 会话的 `TYPE I` **必须在 `login()` 之后**发 | 登录前会被 530 拒掉，会话停在 ASCII，字节被 LF→CRLF 改写（65536→66060，测试数据对不上） |
| 9 | 平台 | FtpServer 的 `onUploadEnd` 回调**可能晚于 226 响应** | 单测里 `storeFile()` 返回 ≠ 已经派发入库。"排空队列"不够，要轮询等结果（`awaitResults()`） |
| 10 | 平台 | **ColorOS 热点网卡叫 `ap0`，网段 `10.129.14.x`**（不是文档假设的 `192.168.43.1`）；同一时刻还有 `wlan0`(家里 Wi-Fi)、`vgate0`(172.30.x)、`ccmni*`(移动数据)、tun/gre/ifb/dummy 一堆虚拟网卡 | "枚举所有 IPv4"会把垃圾地址念给相机。`NetworkInfo` 里黑名单过滤 + 热点名置顶 |
| 11 | UI | `getString(R.string.x, "2121")` 配 `%d` 占位符 | **一启动就闪退** `IllegalFormatConversionException: d != java.lang.String`。资源占位符类型必须和实参一致 |
| 12 | 平台 | `adb shell pm grant` 被拒（Android 17 + ColorOS） | 用 `adb install -g` 授予 |

补充事实：这台机器开着热点时 `wlan0` **仍连着家里 Wi-Fi**（驱动支持 AP+STA），所以无线调试不会因为开热点而断。

---

## 6. 验收矩阵

### 已验证（真机）

- 被动模式上传 → `226`、落 `/sdcard/DCIM/CamFtp/`、文件权限 `media_rw`（真的进了 MediaStore）
- **主动模式（相机 PASV 关）** 上传 → `226`
- `.NEF` → `DCIM/CamFtp`（mime `image/x-nikon-nef`）；未知后缀 `.txt` → `Download/CamFtp`
- 子目录上传（`100NIKON/x.jpg` 与 `CWD 200NIKON` 两条路径）→ 自动建目录、成功
- 私有目录 `filesDir/ftp` 上传后无残留
- 通知：`importance=LOW`、静音、ongoing、点击回 App；前台服务类型 `0x10`（connectedDevice）
- UI：热点识别 `ap0 → 10.129.14.x ← 热点`（虚拟网卡被过滤）、相机地址块、复制、配置校验、改端口 2121→2122 后自动热重启（新端口通、旧端口拒连）、自检图入库、事件列表
- **相机真人验收**（用户执行）：Z50II 通过手机热点连 `10.129.14.x:2121`，用户 旧默认账号，回放上传成功

### 未验证 / 风险清单（接手时要知道）

1. **「打开热点设置」按钮没实测**（三层 fallback：`TETHER_SETTINGS` → `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` → `ACTION_WIRELESS_SETTINGS`）
2. 相机侧传 **RAW/NEF** 没试过（只测过电脑伪造的 NEF）
3. 相机**重传同名文件** → MediaStore 自动改名 `DSC_0001 (1).JPG`（文档 §9.8 已写，未实测）
4. `START_STICKY` 被杀后自恢复没实测
5. **只在 ColorOS 17 一台机上验过**，其他 ROM / Android 10–16 未验
6. 改配置的失败路径（比如被动端口填成被占用范围）没测
7. 超长时/超大文件（相机 8MB 连拍过了，但没测批量几百张）

---

## 7. M4 待办（逐条可执行）

1. **`LICENSE`**：Apache-2.0 全文（与 Apache FtpServer / MINA / slf4j 一致，最省事）
2. **`NOTICE`**：把下面这些抄进去（`gradle :app:dependencies --configuration releaseRuntimeClasspath` 可重新导出）：
   - `org.apache.ftpserver:ftpserver-core:1.2.1`（Apache-2.0）
   - `org.apache.ftpserver:ftplet-api:1.2.1`（Apache-2.0）
   - `org.apache.mina:mina-core:2.2.4`（Apache-2.0）
   - `org.slf4j:slf4j-api:1.7.36`、`org.slf4j:slf4j-android:1.7.36`（MIT）
   - AndroidX 全家桶 / `com.google.android.material:material:1.12.0`（Apache-2.0）
   - `commons-net` 只是 `testImplementation`，**不用**写进 NOTICE
3. **`README.md`**：一句话说明 → 截图 → 相机设置图文步骤（Z50II + 通用尼康）→ **常见错误对照表** → 构建方法 → 隐私声明（只做局域网 FTP、不联网上传）→ **文档 §9 那 8 条坑必须都在**。应用名「CamFtp / 相机 FTP 接收」，**名字和图标里不许出现 Nikon/尼康商标**
4. **截图**：`docs/images/app-top.png`、`app-bottom.png` 已备（状态栏含运营商名和热点 SSID，发布前裁剪或重拍）
5. **版本号 / CHANGELOG**：现在是 `versionCode 1` / `versionName 0.1.0`
6. **签名 release APK**：`keytool -genkeypair` 生成 keystore（**存本机、绝不入库**）+ `keystore.properties`（进 `.gitignore`）+ `build.gradle.kts` 读它配 `signingConfigs.release`
7. **GitHub Release**：tag `v0.1.0` + 附带 APK
8. **`openspec/`**：`openspec/config.yaml` 等 3 个文件是被 `git add -A` 误提交的（不是本项目产物）。要么 `git rm -r --cached openspec`，要么保留 —— 问用户
9. **交付前自检**（文档 §11 逐条现状）：
   - `./gradlew test` 通过 ✅（7 个用例）
   - `./gradlew assembleDebug` 无错 ✅
   - 全工程 grep 不到 `MANAGE_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` ✅
   - 控制端口默认 2121、被动端口默认 `32768-61000`，都能在 UI 改且持久化 ✅
   - 前台服务类型 `connectedDevice` + Manifest 权限 ✅
   - `FtpEngine.kt` 无 `android.*` import ✅
   - README 有 §9 的 8 条坑 ⬜
   - LICENSE + NOTICE 在仓库根目录 ⬜

---

## 8. M5（可选）热点自动化

- 设备是 **Android 17**，文档假设的"要 Android 16.1+"门槛**已经满足**：`WifiManager.startLocalOnlyHotspotWithConfiguration()`（API 36）+ `SoftApConfiguration.Builder.setWifiSsid/setPassphrase`（36.1+）在这台机上可用
- 但别急着做：LOH（LocalOnlyHotspot）和系统"个人热点"是两套东西，行为差异、需要用户授权、相机侧的配置文件要重填
- 落地方式：单独一个文件、`@RequiresApi(36)`、运行时版本判断兜住，**不许影响 M1–M4 的构建**
- 风险：ColorOS 可能改行为；LOH 的 SSID/密码自定义在 36.1 才稳

---

## 9. ⚠️ 未来兼容：Android 17 / targetSdk 37 的本地网络权限（**升 targetSdk 时第一件事**）

事实来源：`developer.android.com/privacy-and-security/local-network-permission`（2026-10-02 更新，中文版 `developer.android.google.cn` 同内容）

- **接受入站 TCP 连接 = 本地网络访问**，属于该权限管辖范围
- **Android 17 起对 `targetSdk ≥ 37` 的应用强制**；`targetSdk ≤ 36` 时由 `INTERNET` **隐式授予**（保持可访问），所以**我们现在不用做任何事**
- 一旦把 `targetSdk` 升到 37，**必须**：Manifest 声明 `android.permission.ACCESS_LOCAL_NETWORK` + 运行时申请（权限组 `NEARBY_DEVICES`，用户会看到"附近的设备"弹窗），否则**相机的连接会被静默丢弃**（表现为 TCP 超时，没有任何报错）
- 建议落点：`MainActivity.ensureNotificationPermission()` 旁边加一个同款方法；README 里写清楚"第一次启动要允许附近的设备"
- 升级前不要提前声明该权限（文档明确写了：targetSdk ≤ 36 时**不要**在清单里加，也不要运行时申请）

---

## 10. 复现 / 回归命令

### 单测

```powershell
gradle :app:testDebugUnitTest --console=plain
gradle :app:testDebugUnitTest --rerun        # 强制重跑（复现竞态用）
```

### 真机：电脑当 FTP 客户端（不用热点，家里 Wi-Fi 直连也行）

```powershell
$ip = (adb shell ip -4 -o addr show wlan0) -replace '.*inet ([0-9.]+)/.*','$1'
curl.exe -sS --user camftp:123456 -T .\x.jpg "ftp://${ip}:2121/"              # 被动模式
curl.exe -sS --user camftp:123456 --ftp-port - -T .\x.jpg "ftp://${ip}:2121/x.jpg"  # 主动模式
adb shell ls -l /sdcard/DCIM/CamFtp/
adb shell content query --uri content://media/external/images/media --projection _display_name:relative_path
```

> 清理测试图要用 MediaStore 删（`content delete --uri content://media/external/images/media/<id>`，id 先 `content query` 拿），直接 `rm` 会留残留行。

### UI 自动化片段（adb 点控件）

```powershell
adb shell uiautomator dump /sdcard/u.xml; $x = adb shell cat /sdcard/u.xml
$m = [regex]::Match($x, 'text="启动"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
adb shell input tap <centerX> <centerY>
```

### 相机验收清单（原文，交给用户跑）

1. 手机开热点（SSID 纯英文）
2. App 点启动，记下显示的 `地址`（实测 `10.129.14.x`）
3. 相机：`网络` → `连接到FTP服务器` → `网络设定` → `创建配置文件` → `配置手动`
   - 无线：选手机热点；TCP/IP：自动
   - FTP：服务器类型 `FTP`、地址=App 显示的、端口 `2121`、目标文件夹「**主文件夹**」、**PASV `ON`**、匿名 `OFF`、用户 旧默认账号
4. 回放 → 选一张 → `i` 菜单 → `选择上传(FTP)`
5. 期望：通知变「已收 1 张」；App 事件列表出现 `✓ xxx.JPG → DCIM/CamFtp`；系统相册能看到原文件名
6. 回归 A：相机 PASV 改 `OFF` 再传一张，必须也成功
7. 回归 B：连传 5 张连拍、**全程息屏**，链接不许断
8. 前置：用 FTP 前把 SnapBridge 的 AP 模式断开（相机同时只能连一种设备）

---

## 11. 非代码问题 / 备忘

- **生图工具不可用**：`~/.pi/agent/models.json` 没有有效的 `sgra` provider，所以图标是手写矢量（`ic_launcher_foreground.xml` 相机+上传箭头 / `ic_launcher_background.xml` 深蓝底）。想换 AI 生成的图标先配 provider。
- **git 代理陷阱**：全局 `http.proxy=127.0.0.1:7890` 指向没在跑的 v2rayN → https remote 一定失败。origin 已改成 SSH，别再改回 https，除非你确定代理开着。
- **`openspec/` 是误提交**（见 §7.8）。
- `.gitattributes` 已加（`gradlew` 强制 LF、`*.bat` CRLF）。`git add` 时那句 "LF will be replaced by CRLF" 是无害警告。
- 手机相册里曾经有我塞的测试图（DSC_9001~9010、`camftp-selftest.jpg`），**已清干净**（含 MediaStore 行）。
- 相机配置仍留在相机里（服务器地址 `10.129.14.x`），**手机热点关掉或网段变了就得重新填**（ColorOS 每次开热点的网段可能不同，所以 App 必须显示当前 IP —— 这正是 UI 第一屏那块大字的意义）。
