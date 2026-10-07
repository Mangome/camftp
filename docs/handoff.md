# CamFtp 开发笔记

**仓库** <https://github.com/Mangome/camftp> ｜ **状态**：功能完成，`v0.1.0` 已发布，只剩可选的热点自动化（§7）
**面向**：要改这个仓库的人 / 新的 agent session。原 v1 设计文档 `app-development.md` 已并入本文（连同它被实测证伪的部分，见 §5），不再单独维护。

---

## 0. 是什么 / 怎么分层

- 前台服务里跑 **Apache FtpServer 1.2.1**：控制端口 `2121`（可配），被动端口 `32768-61000`（尼康手册写死的范围）
- 相机（实测 **Nikon Z50II**）经**手机热点**连上来，FTP `STOR` 推图 → 写入 MediaStore（图片 / 视频 → `DCIM/<目录>`，其它类型 → `Download/<目录>`）→ 通知栏显示已收张数
- **不申请存储权限**（走 MediaStore，Android 10+ 免权限）、**不自动化热点**（用户手动开，App 只给一个跳设置的按钮）、只有一屏 UI

```
MainActivity (XML + ViewBinding)          状态 / 开关 / 配置 / 相机地址块 / 事件
  └─ FtpService      前台服务(connectedDevice) + 静音常驻通知 + PARTIAL_WAKE_LOCK
       └─ FtpEngine   纯 JVM，不 import android.* → 可单测
            ├─ CameraProfile  机型差异=数据（NIKON_Z50II / GENERIC_NIKON）
            ├─ Sink → MediaStoreSink（真机）/ FakeSink（单测）
            └─ FtpServerFactory + SinkFtplet + SimpleUserManager
```

**别做的**：自己写 FTP 协议栈、引第三方 FTP 库、上 Compose（单屏，只换来编译器/BOM 版本对齐风险）、`MANAGE_EXTERNAL_STORAGE`、开机自启。机型差异只做成数据，**不要**为它抽 interface / factory（只有一种实现）；真出现第二种传输协议（如 PTP/IP）再抽 `Transport`。
`FtpEngine` 只依赖 `java.io` + ftpserver，是全部可测性的来源。

---

## 1. 环境（Windows 开发机）

> 每个新的 PowerShell 会话都要先设一遍（用户级已写进注册表，但已启动的进程读不到）

```powershell
$env:JAVA_HOME='<JDK 21 路径>'
$env:ANDROID_HOME='<Android SDK 路径>'
$env:Path="$env:JAVA_HOME\bin;<Gradle 8.14.5 的 bin>;<Android SDK 路径>\platform-tools;$env:Path"
Set-Location <仓库根>
```

| 项 | 值 |
| --- | --- |
| JDK / Gradle | Temurin 21 · Gradle 8.14.5（wrapper dist 已缓存，`./gradlew` 可直接用，也可用系统 `gradle`） |
| Android SDK | `<Android SDK 路径>`（platform-tools 37.0.1、`platforms;android-36`、`build-tools;36.0.0`，licenses 已接受） |
| 测试机 | **一台 OPPO ColorOS 机**：Android 17 / API 37、ColorOS、arm64，无线调试在线（serial 用 `adb devices` 取） |
| 网络 | `dl.google.com` / `repo1.maven.org` **可直连**；`developer.android.com` 不通，走代理 `<HTTP 代理主机>:7890`（只在单次命令里显式指定） |
| origin | **SSH** `git@github.com:Mangome/camftp.git`。git 全局 `http.proxy` 指向没在跑的代理，https remote 必失败 —— 别改回 https |

**adb 操作坑**（都踩过）：

- `adb shell pm grant` 会被拒（Android 17 + ColorOS 收紧）→ 一律用 `adb install -g` 一次授予
- 截图全黑 = 手机息屏 → 先 `adb shell input keyevent KEYCODE_WAKEUP`；**用户正在用手机时别抢屏幕**
- `uiautomator dump` 遇到窗口动画偶发失败 → 重试几次
- 区分「App 崩了」和「用户划掉了」：看 `adb shell logcat -d -b crash`，别只看 `dumpsys window` 的焦点

---

## 2. 代码结构

```
app/src/main/java/io/github/mangome/camftp/
├── Config.kt              配置唯一出处（端口/被动端口/账号/目录名/匿名开关）+ 校验；默认 camftp / 123456
├── CameraProfile.kt       机型差异=数据（NIKON_Z50II / GENERIC_NIKON）
├── Sink.kt                interface Sink + StoreResult
├── MediaStoreSink.kt      入库：图片视频→DCIM/<dir>，其它→Download/<dir>；失败保留源文件
├── FtpEngine.kt           纯 JVM：FtpServer 配置/生命周期 + 单线程入库 executor + retryPending()
├── SimpleUserManager.kt   单用户明文认证 + 可选的匿名账号（坑最密集的地方，见 §5）
├── SinkFtplet.kt          上传回调：CWD/STOR 自动建目录，把文件丢给 executor
├── FtpService.kt          前台服务 connectedDevice、静音常驻通知、wakelock、START_STICKY
├── FtpState.kt            StateFlow 单例状态通道（服务→UI）
├── NetworkInfo.kt         枚举 IPv4、过滤虚拟网卡、优先选热点网卡（猜错了 App 就把热点标记漏掉，见 §5）
└── MainActivity.kt        单屏 UI
app/src/test/java/.../FtpEngineTest.kt   9 个 JVM 用例（FTP 引擎全流程，含匿名登录开关两种状态）
```

`compileSdk`/`targetSdk` 36、`minSdk` 29、ViewBinding、AGP 8.13.2 / Kotlin 2.2.21（见 `gradle/libs.versions.toml`）。

---

## 3. 关键设计决策（改代码前先读）

1. **`FtpEngine` 不 import 任何 `android.*`** → 能跑 JVM 单测。这条破了就没法测。
2. **入库 executor 在 `FtpEngine` 里**（单线程、保序）。`Ftplet` 回调跑在 MINA IO 线程上，绝不能阻塞；放在引擎里，任何 `Sink` 实现都不用操心线程。
3. **入库失败绝不删源文件**：留在 `filesDir/ftp`，服务启动时 `retryPending()` 重试一次，UI 事件列表标红。宁可留垃圾不丢图。
4. **`SinkFtplet.beforeCommand` 自动建目录**（`CWD` 与 `STOR/APPE/STOU` 两条路径）。v1 文档说"MVP 不做"，翻案了 —— curl 等客户端是「先 CWD 再 STOR」，不处理直接 550。
5. **状态通道用 `FtpState`（StateFlow 单例）**，不用已废弃的 `LocalBroadcastManager`。
6. **配置唯一出处 `Config`**；保存时若服务在跑 → `FtpService.ACTION_RESTART` 热重启（比"提示用户手动重启"少一步无意义操作）。
7. **入库走 MediaStore 两段式**：`insert(IS_PENDING=1)` → 拷字节 → `update(IS_PENDING=0) → delete 源文件`。`RELATIVE_PATH` 图片视频用 `DCIM/<dir>`、Downloads 用 `Download/<dir>`。`.nef` 手动映射成 `image/x-nikon-nef`，推不出来就落 `Download/`，不崩。
8. **同名冲突交给 MediaStore 自动改名**（`DSC_0001 (1).JPG`），不自己处理。
9. **账号只允许 ASCII 简单字符**：相机上用遥控器打字很痛苦，别逼用户输复杂密码。
10. `idleTimeout` 300s、`maxLogins` 4：相机两次操作之间可能停很久，且会反复登录登出，**别把 maxLogins 设成 1**。
11. **不设 `passiveAddress`**：Apache FtpServer 回落到控制连接的本地地址（= 当前热点 IP），正好是相机要的。
12. 通知：`IMPORTANCE_LOW`、`setOnlyAlertOnce(true)`、ongoing、全静音 —— 拍摄现场手机不该响。
13. 监听 `0.0.0.0`（热点 / 家里 Wi-Fi / USB 网都通）；**IP 一律运行时枚举，不要写死**（网段随 ROM 变，ColorOS 每次开热点都可能是新网段 —— 这正是 UI 第一屏「念给相机听」那块大字存在的意义）。
14. **匿名登录 = 两个开关一起开**：`ConnectionConfigFactory.setAnonymousLoginEnabled(true)` + `SimpleUserManager` 处理 `AnonymousAuthentication`。两个坑：① 相机（及 curl / 资源管理器）发的用户名是字面量 `anonymous`，`USER` 命令里是**大小写敏感**的 `equals`，别自作主张做归一化；② 返回的 `User` 名字必须叫 `anonymous`，否则 `DefaultFtpStatistics` 不把它算作匿名会话。匿名与具名**共存**（不互斥）—— 少一个分支，「关了就只认具名」由开关本身搞定。`maxAnonymousLogins` 别传 0：源码里 `currAnonLogin >= maxAnonymousLogins` 永远成立，会把匿名登录全拒掉（“0 = 不限”只活在日志文案里）。

比 v1 设计多做的：自检按钮、CWD 自动建目录、残留文件重试。

---

## 4. 协议与平台事实（有出处，改配置时别推翻）

| 事实 | 出处 |
| --- | --- |
| Z50II 支持 FTP 推送，**服务器地址 / 端口 / 目标文件夹都能在相机上手动填**；`[PASV mode]`、`[Anonymous login]` 可开关 | Z50II 手册 25-04 / 09-09-07 |
| 尼康写明的端口：**FTP 用 TCP 21 和 32768–61000**（21 是文档默认值，我们改 2121 因为 <1024 需特权） | Z50II 手册 25-04 |
| 尼康官方只保证 IIS 类服务器，**不保证第三方 FTP 服务器** —— 所以我们不能改协议细节，只能照标准 FTP 实现 | 同上 |
| Apache FtpServer 1.2.1 不引用 `javax.management` / `java.beans`，Android 上可跑 | 解包扫过 212 个 class |
| `Ftplet` 回调签名是 `onUploadEnd(FtpSession, FtpRequest)`，**没有 File 参数** | ftplet-api 1.2.1 源码 |
| FGS 类型 `connectedDevice` 的运行时前提之一是持有 `CHANGE_WIFI_STATE` | Android FGS 文档 |
| 普通 App 绑不了 <1024 端口（prim-ftpd 自身限定 1024–64000，佐证） | prim-ftpd 源码 |

---

## 5. 🐞 踩过的坑（**照 v1 文档写代码会崩**，全部实测）

| 问题 | 现象 / 修法 |
| --- | --- |
| `setPassiveEnabled(true)` **在 FtpServer 1.2.1 里不存在** | 编译不过。被动模式没有开关（只有 active 有），删掉 |
| `setFtplets(mapOf(...))`：Kotlin `mapOf` 不可变，而 `DefaultFtpServerContext.dispose()` 会 `clear()` 它 | `stop()` 抛 `UnsupportedOperationException`。用 `mutableMapOf<String, Ftplet>` |
| `BaseUser().apply { setName(name) }` 里 `name` 解析成**接收者自己的属性**（此时 null） | 用户名/密码被写成 null，登录必失败。构造参数改名 `userName/userPassword` |
| 同一种遮蔽，但换了个名字：`private val authorities = listOf(...)` 再 `apply { setAuthorities(authorities) }` | **所有账号都登不上**：USER 阶段直接 `421 Maximum login limit has been reached.`（看着像并发登录数超了，实际是 `configUser.authorize(ConcurrentLoginRequest)` 返回 null 那条分支）。因为 `authorities` 解析成了 BaseUser 自己的 `getAuthorities()`（此时是空列表），用户一个权限都没有。改叫 `permissions`。**教训**：自定义属性名不能和 `apply` 接收者上任何 getter 同名 —— 断点看不出来，只能通过回复码定位 |
| 只给 `WritePermission`：`USER` 命令还会 `authorize(ConcurrentLoginRequest)` | 直接 `421 Maximum login limit has been reached.`。必须加 `ConcurrentLoginPermission(MAX, MAX)` |
| `WritePermission(homeDir.absolutePath)` 传的是**物理路径** | 每个 `STOR` 都 `550 Permission denied`。它按**相对家目录的虚拟路径**前缀匹配，应传 `"/"` |
| `org.apache.commons:commons-net:3.11.1` 坐标不存在 | 正确坐标 `commons-net:commons-net`（单测客户端） |
| 依赖自带 `META-INF/DEPENDENCIES`、`LICENSE`、`NOTICE` 三份撞车 | `mergeDebugJavaResource` 失败。要 `packaging { resources { excludes += ... } }` |
| FTP 会话的 `TYPE I` **必须在 `login()` 之后**发 | 登录前发会被 530 拒，会话停在 ASCII，字节被 LF→CRLF 改写（65536→66060，单测数据对不上） |
| FtpServer 的 `onUploadEnd` 回调**可能晚于 226 响应** | 单测里 `storeFile()` 返回 ≠ 已派发入库，"排空队列"不够，要轮询等结果（`awaitResults()`） |
| README 说 ColorOS 热点网段是 `192.168.43.1` | 实际是 **`ap0` / `10.129.14.x`**；同时还有 `wlan0`(家里 Wi-Fi)、`vgate0`、`ccmni*`(数据)、tun/gre/ifb/dummy 一堆虚拟网卡 —— 枚举 IPv4 必须黑名单过滤 + 热点名置顶 |
| `getString(R.string.x, "2121")` 配 `%d` 占位符 | **一启动就闪退** `IllegalFormatConversionException: d != java.lang.String`。资源占位符类型必须和实参一致 |
| 跳「热点设置」的 action 名**记错一个前缀就静默跑偏** | 实测这台 ColorOS 17：`com.android.settings.TETHER_SETTINGS` **无 App 注册**（启动必失败）；`android.settings.TETHER_SETTINGS` / `android.settings.OPLUS_TETHER_SETTINGS` 落到「网络共享」页；只有 `com.android.settings.WIFI_TETHER_SETTINGS` 直接是「个人热点」页；`Settings.Panel.ACTION_INTERNET_CONNECTIVITY` 这台机上 SystemUI 没注册（只提供音量面板）。旧代码三个候选全落空 → 用户点按钮看到的是 WiFi / 网络页。**验证方法**：`adb shell dumpsys package <pkg> \| grep -i tether` 看谁真的注册了 action，再用 `am start -a <action>` + `uiautomator dump` 看落点页面标题 |
| `adb shell pm grant` 被拒（Android 17 + ColorOS） | 用 `adb install -g` |
| `targetSdk 36` 起系统**强制 edge-to-edge**（`windowOptOutEdgeToEdgeEnforcement` 在 Android 16+ 失效），内容画到状态栏底下被时钟压住；且 Material 1.12 的 M3 主题不设 `android:windowLightStatusBar`，浅色主题下白图标落在浅色背景上基本看不见 | ① `activity_main.xml` 的 ScrollView 加 `android:fitsSystemWindows="true"`，20dp 内边距**必须挪到内层 LinearLayout**（`computeSystemWindowInsets` 只在「该边 padding==0」时才补 inset，padding 留在 root 上会静默失效）；② 新增 `res/values/themes.xml` 的 `Theme.CamFtp`，`windowLightStatusBar` / `windowLightNavigationBar` = `?attr/isLightTheme`，Manifest 改用它 |

**顺带的事实**：这台机器开着热点时 `wlan0` 仍连着家里 Wi-Fi（驱动支持 AP+STA），所以无线调试不会因为开热点而断。

相机侧（不是代码问题，是使用问题）的坑见 [`camera-setup.md`](./camera-setup.md)。

---

## 6. 验收矩阵

### 已验证（真机 一台 OPPO ColorOS 机 + Nikon Z50II）

- 电脑侧：被动 / 主动模式上传 → `226`，落 `/sdcard/DCIM/CamFtp/`，文件权限 `media_rw`（真进 MediaStore）；`.NEF` → `DCIM`（mime `image/x-nikon-nef`）、未知后缀 `.txt` → `Download`；子目录上传（路径带目录与 `CWD` 两种）自动建目录；私有目录 `filesDir/ftp` 无残留
- 匿名登录：App 勾上 + 保存（服务热重启）后，匿名 PASV / 主动模式上传都 `226` 入库；取消勾选后匿名登录回 `530`、具名账号照常 `226`（真机 2026-10-07，另有 2 个 JVM 用例守这两条）
- 服务：通知 `importance=LOW` / 静音 / ongoing / 点击回 App；前台服务类型 `0x10`（connectedDevice）；改端口 2121→2122 自动热重启（新端口通、旧端口拒连）
- UI：热点识别（地址行显示 `10.129.14.x（热点）`，虚拟网卡被过滤）、配置校验、自检图入库、事件列表
- 「打开热点设置」按钮 → ColorOS「个人热点」页（`com.android.settings.WIFI_TETHER_SETTINGS`；即使用该设置应用已停在「网络共享」页，再点也能切过去）
- **相机真人验收**：Z50II 经手机热点连 `10.129.14.x:2121`，回放上传成功，相册看到原文件名；连续多张 + 息屏不断线

### 未验证 / 已知风险（接手时先知道）

1. 相机侧传 **RAW / NEF** 没试过（只测过电脑伪造的 NEF）
2. 相机**重传同名文件** → `DSC_0001 (1).JPG` 改名，未实测
3. `START_STICKY` 被杀后自恢复没实测
4. **只在 ColorOS / Android 17 一台机上验过**，其它 ROM / Android 10–16 未验（「打开热点设置」的 action 落点尤其可能不同，换 ROM 复验时走上面 §5 的「验证方法」）
5. 配置的失败路径（如被动端口填成被占用范围）没测
6. 批量几百张 / 超大文件没测（8MB 连拍过了）

---

## 7. 待办

1. **升 `targetSdk` 到 37 时第一件事：加本地网络权限**。Android 17 起对 `targetSdk ≥ 37` 强制；接受入站 TCP 连接算「本地网络访问」，必须声明 `android.permission.ACCESS_LOCAL_NETWORK` + 运行时申请（权限组 `NEARBY_DEVICES`，用户看到「附近的设备」弹窗），否则**相机的连接会被静默丢弃**（表现为 TCP 超时，无任何报错）。落点：`MainActivity.ensureNotificationPermission()` 旁加同款方法 + README 写清"第一次启动要允许附近的设备"。<br>⚠️ `targetSdk ≤ 36` 时**不要**提前声明该权限（`INTERNET` 会隐式授予）。来源：`developer.android.com/privacy-and-security/local-network-permission`
2. **M5（可选）热点自动化**：`WifiManager.startLocalOnlyHotspotWithConfiguration()`（API 36）+ `SoftApConfiguration.Builder.setWifiSsid/setPassphrase`（36.1+），测试机 Android 17 已满足门槛。但 LOH 和系统「个人热点」是两套东西，行为差异大、要额外授权、相机侧配置得重填。落地要求：单独文件、`@RequiresApi(36)`、运行时版本判断兜住，**不许影响现有构建**；风险是 ColorOS 可能改行为。
3. 换机器开发时：把 keystore `<你的 release keystore 路径>` 和仓库根 `keystore.properties`（密码是随机生成的）**复制过去并备份**，丢了就没法给已装用户升级。

---

## 8. 复现 / 回归命令

```powershell
gradle test                     # 9 个 JVM 用例
gradle :app:testDebugUnitTest --rerun    # 强制重跑（复现竞态用）
gradle assembleDebug --console=plain
adb install -r -g app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n io.github.mangome.camftp/.MainActivity
```

真机：电脑当 FTP 客户端（不用热点，家里 Wi-Fi 直连也行）：

```powershell
$ip = (adb shell ip -4 -o addr show wlan0) -replace '.*inet ([0-9.]+)/.*','$1'
curl.exe -sS --user camftp:123456 -T .\x.jpg "ftp://${ip}:2121/"                      # 被动
curl.exe -sS --user camftp:123456 --ftp-port - -T .\x.jpg "ftp://${ip}:2121/x.jpg"   # 主动
curl.exe -sS -T .\x.jpg "ftp://${ip}:2121/anon.jpg"                                 # 匿名（App 里先勾上「允许匿名登录」）
adb shell ls -l /sdcard/DCIM/CamFtp/
```

清理测试图要用 MediaStore 删（`content delete --uri content://media/external/images/media/<id>`，id 先 `content query` 拿），直接 `rm` 会留残留行。

相机侧验收步骤交给用户跑 → [`camera-setup.md`](./camera-setup.md)。

---

## 9. 备忘

- **临时文件一律放 `temp/`**（截图、dumpsys 转储），已在 `.gitignore` 里 —— 仓库根目录不落临时文件。
- `.gitattributes` 强制 `gradlew` LF、`*.bat` CRLF；`git add` 时 "LF will be replaced by CRLF" 是无害警告。
- `openspec/` 是误提交，已从仓库移除（本地文件还留着，也在 `.gitignore` 里）。
- 应用图标是手写矢量（`ic_launcher_foreground.xml` 相机+上传箭头 / 深蓝底），当时生图工具不可用。想换 AI 图标先配好 provider。
- 相机里存着旧配置（服务器地址 `10.129.14.x`），**手机热点一关或网段变了就得重填**。
- 已装过旧版本（默认账号 `旧默认账号`）的机器，SharedPreferences 里**仍是旧值**，除非在 UI 里改。

---

## 10. 参考资料

- 尼康 Z50II 手册 FTP 章节：<https://onlinemanual.nikonimglib.com/z50II/en/25-04.html>、<https://onlinemanual.nikonimglib.com/z50II/en/09-09-07.html>
- Android 前台服务类型与前提条件：<https://developer.android.com/develop/background-work/services/fgs/service-types>
- 同类开源实现（可读，别 fork）：[wolpi/prim-ftpd](https://github.com/wolpi/prim-ftpd)、[ppareit/swiftp](https://github.com/ppareit/swiftp)
- 生产环境配置参考（端口 1234 / PASV OFF）：<https://faq.alltuu.com/a538/5a74/3f78>
