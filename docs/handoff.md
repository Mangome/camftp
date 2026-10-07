# CamFtp 开发笔记

**仓库** <https://github.com/Mangome/camftp> ｜ **状态**：功能完成，`v0.1.1` 已发布；未发版的两个改动：**热点自动化（接收跟着热点起停，§3.20）** + UI 精简
**面向**：要改这个仓库的人 / 新的 agent session。原 v1 设计文档 `app-development.md` 已并入本文（连同它被实测证伪的部分，见 §5），不再单独维护。

---

## 0. 是什么 / 怎么分层

- 前台服务里跑 **Apache FtpServer 1.2.1**：控制端口 `2121`（可配），被动端口 `32768-61000`（尼康手册写死的范围）
- 相机（实测 **Nikon Z50II**）经**手机热点**连上来，FTP `STOR` 推图 → 写入 MediaStore（图片 / 视频 → `DCIM/<目录>`，其它类型 → `Download/<目录>`）→ 通知栏显示已收张数
- **不申请存储权限**（走 MediaStore，Android 10+ 免权限）、**不替用户开热点**（只跳设置）、**热点就是总开关**：开着就在收、关掉就停，App 里没有开始 / 停止按钮（见 §3.20）、只有一屏 UI

```
MainActivity (XML + ViewBinding)          状态 / 热点设置入口 / 配置 / 相机地址块 / 事件
HotspotWatch (+ HotspotReceiver)          热点开/关 → 起/停 FtpService（回前台再对齐一次）
  └─ FtpService      前台服务(connectedDevice) + 静音常驻通知 + PARTIAL_WAKE_LOCK
       └─ FtpEngine   纯 JVM，不 import android.* → 可单测
            ├─ CameraProfile  机型差异=数据（NIKON_Z50II / GENERIC_NIKON）
            ├─ Sink → MediaStoreSink（真机）/ FakeSink（单测）
            └─ FtpServerFactory + SinkFtplet + SimpleUserManager
```

**别做的**：自己写 FTP 协议栈、引第三方 FTP 库、上 Compose（单屏，只换来编译器/BOM 版本对齐风险）、`MANAGE_EXTERNAL_STORAGE`、开机自启、**手动开始/停止按钮**（§3.20）。机型差异只做成数据，**不要**为它抽 interface / factory（只有一种实现）；真出现第二种传输协议（如 PTP/IP）再抽 `Transport`。
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
- **截图别写 `/sdcard`**：`screencap -p /sdcard/x.png` 会被 MediaStore 扫进系统相册（相册里冒出一堆 `sN.png`，清完要 `content delete` + `rm` 两头删）→ 写 `/data/local/tmp/`，那个目录不被扫描
- 区分「App 崩了」和「用户划掉了」：看 `adb shell logcat -d -b crash`，别只看 `dumpsys window` 的焦点

---

## 2. 代码结构

```
app/src/main/java/io/github/mangome/camftp/
├── Config.kt              配置唯一出处（端口/账号/目录名/匿名开关）+ 校验；具名账号默认 camftp / 123456、**匿名登录默认开**；被动端口固定 32768-61000，不可改（相机侧不填这个）
├── CameraProfile.kt       机型差异=数据（NIKON_Z50II / GENERIC_NIKON）
├── Sink.kt                interface Sink + StoreResult
├── MediaStoreSink.kt      入库：图片视频→DCIM/<dir>，其它→Download/<dir>；失败保留源文件
├── FtpEngine.kt           纯 JVM：FtpServer 配置/生命周期 + 单线程入库 executor + retryPending()
├── SimpleUserManager.kt   单用户明文认证 + 可选的匿名账号（坑最密集的地方，见 §5）
├── SinkFtplet.kt          上传回调：CWD/STOR 自动建目录，把文件丢给 executor；顺带数控制连接数（相机连没连）
├── HotspotWatch.kt        热点=开关：广播 + 回前台对齐 → 起/停 FtpService；HotspotReceiver 是清单里那份
├── FtpService.kt          前台服务 connectedDevice、静音常驻通知、wakelock、START_STICKY
├── FtpState.kt            StateFlow 单例状态通道（服务→UI；另存相机会话数 / 上次连接时间）
├── NetworkInfo.kt         枚举 IPv4、过滤虚拟网卡、识别热点网卡（名字形状 + 排除 STA，见 §3.20 / §5）
└── MainActivity.kt        单屏 UI
app/src/main/res/
├── values/colors.xml + values-night/colors.xml   全部颜色（cam_* 命名，深浅两套）
├── values/themes.xml      Theme.CamFtp：M3 槽位映射 + 状态栏/导航栏图标明暗
└── layout/activity_main.xml   状态 → （热点没开时的警示卡）→ 相机读数卡 → 折叠的高级设置 → 最近收到
app/src/test/java/.../FtpEngineTest.kt   10 个 JVM 用例（FTP 引擎全流程，含匿名登录开关两种状态、会话数回调）
app/src/test/java/.../FtpStateTest.kt     2 个：自检/真图计数的口径、会话数增量（不变负数、不断清「上次连接」）
app/src/test/java/.../HotspotWatchTest.kt 1 个：起/停/不动的规则（「热点=开关」的唯一规则来源）
app/src/test/java/.../NetworkInfoTest.kt 4 个：热点网卡识别（小米 wlan2 算热点、正在连 Wi-Fi 的 wlan0 不算）
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
14. **匿名登录 = 两个开关一起开**：`ConnectionConfigFactory.setAnonymousLoginEnabled(true)` + `SimpleUserManager` 处理 `AnonymousAuthentication`。两个坑：① 相机（及 curl / 资源管理器）发的用户名是字面量 `anonymous`，`USER` 命令里是**大小写敏感**的 `equals`，别自作主张做归一化；② 返回的 `User` 名字必须叫 `anonymous`，否则 `DefaultFtpStatistics` 不把它算作匿名会话。匿名与具名**共存**（不互斥）—— 少一个分支，「关了就只认具名」由开关本身搞定。`maxAnonymousLogins` 别传 0：源码里 `currAnonLogin >= maxAnonymousLogins` 永远成立，会把匿名登录全拒掉（“0 = 不限”只活在日志文案里）。**App 层默认开**（`Config.anonymous` 初值 true，prefs 缺省也 true）：相机侧少填两项，想回具名账号在高级设置里关；`FtpEngine` 的构造参数默认仍是 false（引擎级保守默认，运行时由 `FtpService` 传 `Config.anonymous`）。

比 v1 设计多做的：自检按钮、CWD 自动建目录、残留文件重试。

15. **UI 视觉约定**（界面重构后定的，改界面前先看这条）：颜色只写在 `values/colors.xml` 与 `values-night/colors.xml`（`cam_*` 命名），`Theme.CamFtp` 负责把它们映射到 M3 槽位，**别在 layout 里写死颜色**；屏幕从上到下 = 状态行 + 主按钮 → 相机读数卡 → 折叠的高级设置 → 最近收到，全程左对齐；整屏只留一个视觉高峰（地址读数 34sp 等宽，相机要照着输），状态只用颜色编码不用装饰；等宽字体只用于地址/端口/账号/事件这些要逐字符比对的地方；配置类低频操作一律进折叠区，校验失败时自动展开（否则错误提示在收起的区域里，用户看不见）。**大字读数必须显式写 `android:lineHeight`**（不写就是赌 ROM 的字体度量，见 §5）；**「热点」标记写在读数卡标题行右侧**：34sp 等宽下 `10.130.223.120` 已占满卡片（实测 953/964px），tag 跟地址同行只会被挤出屏幕。
16. **自检按钮是「情境化」的，不是设置项**：它长在「最近收到」区块里（配置类操作才进折叠区），只在 `FtpState.Snapshot.anyStored == false`（本进程还没有任何成功入库）时出现 —— 收到真图或自检成功即自动收起，**自检失败则留在列表下可重试**（失败不能把唯一的自检入口关掉）。显隐复用已有状态、不写 SharedPreferences，冷启动回到初始态自然回来（否则「想再自检一次」就得清 App 数据）。自检结果同时往事件列表写一条 `Event(name = "测试图", counts = false)`：`counts = false` 保证它不算进「已收到 N 张」（`FtpStateTest` 守着这两条）。

17. **「相机连上了没」是数出来的，不是猜的**：`SinkFtplet.onConnect/onDisconnect` → `FtpState.clientDelta(±1)`。jar 反编译实查过：只有 `DefaultFtpHandler` 调 ftplet 的 connect/disconnect，且只在 `sessionOpened` / `sessionClosed` —— 数据连接不触发，所以是精确配对（**别改成 `onLogin/onLogout`**：登出和断线是两条路，容易减重）。`lastConnectAt` 断开时**不清**，UI 才能说「相机没连着 · 上次连接 17:41」；服务重启时 `clients` 归零。UI 只当它是「有/没活动」的实证，不保证相机侧真的在拍。
18. **热点没开 = 顶部错误色警示卡（卡里只剩标题）+ 卡下常驻的「打开热点设置」按钮**：相机只能连热点，不在热点上那个 IP 对相机**完全没用**，所以 34sp 读数一并收起来，卡里只留一行引导（`camera_need_hotspot`）+ 一行 `当前网卡：…` 兜底（热点探测靠「网卡名形状 + 排除当前 STA」，换 ROM 猜错时地址还抄得到 —— 小米 MIX Flip 2 上这条兜底真救了场：界面里那句 `当前网卡：192.168.1.103(wlan0) 10.130.223.120(wlan2)` 一眼看出热点是 wlan2，这是不把兜底删掉的理由）。警示卡用 `?attr/colorErrorContainer`（`Theme.CamFtp` 里映射），是全屏唯一的错误色用法。文案铁律：只说下一步该干什么，**不解释你观察到了什么网络状态**（用户原话：写「连的是别的网络」「地址会显示在下面」都没意义；上一版就因为解释句太长被退过两回）。

19. **按钮不放卡里，也不跟卡一起隐显**：「打开热点设置」常驻在卡下面。卡是「现在缺什么」的提醒，按钮是「该怎么办」的动作，拆成两行各司其职；按钮跟着卡一起出现/消失的话，热点开着时就没地方改热点设置了（关掉、换密码都要回到这个页）。同理**不放开始/停止按钮** —— 那是热点的事（§3.20）。

20. **「什么时候开始接收」只有一条规则：热点开着就该在收**（`HotspotWatch`）。触发点两个：① 系统广播 `WIFI_AP_STATE_CHANGED` / `TETHER_STATE_CHANGED`（清单里声明了 `HotspotReceiver` 一份、`attach()` 在 application context 上挂运行时接收器一份 —— **实测干活的是后者**：清单那份每次都被 `skipped by policy at enqueue: Background execution not allowed` 拦掉（§5）。**小米 HyperOS 更狠：App 退到后台（`am get-standby-bucket` = 40 RARE）后运行时那份也一起拦**，所以「服务在跑时关热点自动停」照常、「服务已停 + App 在后台时开热点自动起」在小米上不会发生，兜底是打开 App（见 §5 / §6）；② 回到前台（`MainActivity.onResume → sync()`）、服务自己被拉回来时（`FtpService.start()` 的兵底检查）。判定一律**重新扫网卡**（`NetworkInfo.ipv4(context)`：名字像 AP 且不是当前 STA），广播里带的数据只当闹钟；**唯一例外**是广播明说 `DISABLING/DISABLED/FAILED` → 直接停（那时热点网卡还挂着几百毫秒，等扫描会把这次「停」漏掉，通知栏就挂着一条假的「正在接收」）。`serviceAction()` 是唯一规则来源，`HotspotWatchTest` 守着（能收没起→起、不能收在跑→停、其余不动）。**别再加手动开关**：有按钮就得回答「用户按了停止之后广播来了要不要再起」，而这个语义没有好答案 —— 用户的习惯已经统一成「关热点就是停」。曾经用 `FLAG_DEBUGGABLE` 放行「家里 Wi-Fi 直连 2121」跑 curl 回归，结果没热点时状态栏也报「正在接收」，跟警示卡自相矛盾 → 删了（见 §5）。要加开发口子先自问：它会不会出现在用户界面里。

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
| M3 反色槽位的属性名是 `colorSurfaceInverse` / `colorOnSurfaceInverse` / `colorPrimaryInverse` | 写成 `colorInverseSurface` 之类会 `resource linking failed`（Material 1.12 的 R.txt 里只有前者） |
| 没有 `?attr/materialButtonTonalStyle` 这个 attr | 想用 tonal 按钮直接写 `style="@style/Widget.Material3.Button.TonalButton"`；`colorPrimary` 那套 attr 都在，唯独按钮风格里只有 `materialButtonStyle` / `materialCardViewOutlinedStyle` / `borderlessButtonStyle` |
| `targetSdk 36` 起系统**强制 edge-to-edge**（`windowOptOutEdgeToEdgeEnforcement` 在 Android 16+ 失效），内容画到状态栏底下被时钟压住；且 Material 1.12 的 M3 主题不设 `android:windowLightStatusBar`，浅色主题下白图标落在浅色背景上基本看不见 | ① `activity_main.xml` 的 ScrollView 加 `android:fitsSystemWindows="true"`，20dp 内边距**必须挪到内层 LinearLayout**（`computeSystemWindowInsets` 只在「该边 padding==0」时才补 inset，padding 留在 root 上会静默失效）；② 新增 `res/values/themes.xml` 的 `Theme.CamFtp`，`windowLightStatusBar` / `windowLightNavigationBar` = `?attr/isLightTheme`，Manifest 改用它 |
| 广播说热点关了，但 `ap0` 还挂着几百毫秒 | 光靠扫网卡会漏掉这次「停」，通知栏会挂着一条假的「正在接收」。`HotspotWatch.onBroadcast()` 对 `DISABLING/DISABLED/FAILED` 直接停，不等扫描 |
| 清单里声明热点广播，**根本收不到** | 实测（ColorOS 17 / Android 17）：`WIFI_AP_STATE_CHANGED state=10/11`、`TETHER_STATE_CHANGED` 都会发，但 `dumpsys activity broadcasts` 里清单那份每次都是 `skipped by policy at enqueue: Background execution not allowed`，投递的只有 `HotspotWatch.attach()` 挂的运行时接收器。结论：别只写清单接收器；进程被杀后切热点不会自动起（兜底是打开 App）。换 ROM 复验就看这句 DELIVERED / skipped |
| 怕 Android 12+ 不允许后台起前台服务 | ColorOS 17 实测**放行**：`ActivityManager: Background started FGS: Allowed [... uidState: LAST; code:UID_VISIBLE ...]`（App 刚在前台待过就允许）。`HotspotWatch.start()` 仍包着 try/catch：真被拦也只丢一行日志，回前台 `sync()` 会补回来 |
| 热点网卡名写死成名单 | 小米 MIX Flip 2 / HyperOS 3 把热点放在 **`wlan2`**（`dumpsys tethering`：`TetherState: wlan2 - TetheredState`），名单里只有 `ap0/swlan0/wlan1/...` → App 判成「没热点」：读数卡收起、服务不起，用户看到的就是「开了热点却显示未开」。修法：名字形状照抄系统自己的 `tetherableWifiRegexs: [wlan\d, softap\d, ap_br_wlan\d, ap_br_softap\d]`，再用 `ConnectivityManager` 里 `TRANSPORT_WIFI` 的网卡（STA）把它排除掉 —— `wlan2` 既可能是热点也可能是 STA，只能靠「哪张卡在连 Wi-Fi」分。`NetworkInfoTest` 守着 |
| 小米上 `wlan2` 的 IP 会不会热点关了还留着（留了就误报） | 不会：3 秒粒度采样，`wlan2=10.130.223.120` 随关热点消失、开热点回来（2026-10-07 19:06）。所以「名字像 AP + 有 IPv4」两个条件就够，不用额外记状态 |
| HyperOS 把热点广播拦到「已退后台的 App」 | `dumpsys activity broadcasts` 实测：`SKIPPED terminal-enq ... #3: BroadcastFilter{... ReceiverList{... io.github.mangome.camftp}}` —— **运行时那份也拦**（ColorOS 只拦清单那份），`am get-standby-bucket` = 40（RARE）。服务在跑（FGS）时广播照常送达：19:12:16 `WIFI_AP_STATE_CHANGED state=10 → TETHER_STATE_CHANGED → state=11` → 服务自动停。对策：代码不动，README 里写「小米上想让开热点自动起，给 App 开自启动 / 后台策略无限制」 |
| HyperOS 上 34sp 等宽粗体的**行高被量成 0.75×** | 地址读数上下被切掉（用户截图报「IP 显示不全」）。实测：`34sp`（fontScale 1.1 / 520dpi）只量出 91px 高，而其它字号都是 1.35~1.38×（22sp→101px、16sp→77px、13sp→64px）；描的字其实一直是 34sp（墨迹 85px = 0.7em ✔），就框子矮了。修法：`activity_main.xml` 里给 `addressValue` 显式 `android:lineHeight="44sp"` → 框 149px、墨迹居中（别指望 ROM 的字体度量） |
| 小米上 `adb install -g`、`adb shell input tap` 都被拒 | `-g` → `SecurityException: ... INSTALL_GRANT_RUNTIME_PERMISSIONS`；`input tap` → `SecurityException: ... INJECT_EVENTS`（开发者选项里没开「USB 调试（安全设置）」就点不了）。所以：装包改用**同签名的 release 包**（`assembleRelease` + `install -r`，配置和已授权限都留着），点 UI 的活儿只能人肉干 |
| debug 构建放行「家里 Wi-Fi 直连 2121」跑 curl 回归 | 没热点时状态栏也报「正在接收」，跟「需要开启热点」的警示卡自相矛盾，用户当场退回来。已删：接收只认热点网卡；真机回归改成让电脑连手机热点 |

**顺带的事实**：这台机器开着热点时 `wlan0` 仍连着家里 Wi-Fi（驱动支持 AP+STA），所以无线调试不会因为开热点而断。

相机侧（不是代码问题，是使用问题）的坑见 [`camera-setup.md`](./camera-setup.md)。

---

## 6. 验收矩阵

### 已验证（真机 一台 OPPO ColorOS 机 + Nikon Z50II）

- 电脑侧：被动 / 主动模式上传 → `226`，落 `/sdcard/DCIM/CamFtp/`，文件权限 `media_rw`（真进 MediaStore）；`.NEF` → `DCIM`（mime `image/x-nikon-nef`）、未知后缀 `.txt` → `Download`；子目录上传（路径带目录与 `CWD` 两种）自动建目录；私有目录 `filesDir/ftp` 无残留
- 匿名登录（App 默认已勾上）：保存（服务热重启）后，匿名 PASV / 主动模式上传都 `226` 入库；取消勾选后匿名登录回 `530`、具名账号照常 `226`（真机 2026-10-07，另有 2 个 JVM 用例守这两条）
- 服务：通知 `importance=LOW` / 静音 / ongoing / 点击回 App；前台服务类型 `0x10`（connectedDevice）；改端口 2121→2122 自动热重启（新端口通、旧端口拒连）
- UI：热点识别（地址行显示 `10.129.14.x（热点）`，虚拟网卡被过滤）、配置校验、自检图入库、事件列表
- 「打开热点设置」按钮 → ColorOS「个人热点」页（`com.android.settings.WIFI_TETHER_SETTINGS`；即使用该设置应用已停在「网络共享」页，再点也能切过去）
- **相机真人验收**：Z50II 经手机热点连 `10.129.14.x:2121`，回放上传成功，相册看到原文件名；连续多张 + 息屏不断线。2026-10-07 新版又跑一遍：`DSC_1794/1795/1798.NEF` 入库 `DCIM/CamFtp`（权限 `media_rw`、mime `image/x-nikon-nef`），私有目录 `filesDir/ftp` 无残留
- **热点自动起停**（真机 2026-10-07，App 不在前台）：关热点 → 服务自动停（常驻通知消失）；再开热点 → 服务**自动起**，系统日志 `Background started FGS: Allowed [... uidState: LAST; code:UID_VISIBLE ...]`；回前台发现「热点开着但没在收」也会补起
- **小米 HyperOS 3 / Android 16 复验**（Xiaomi MIX Flip 2，2026-10-07）：热点网卡 = `wlan2` / `10.130.223.120`（`dumpsys tethering` 佐证），界面显示「正在接收 + 10.130.223.120 + 标题行右侧热点标记」，警示卡消失、服务自动起（`isForeground=true types=0x00000010`、通知在）—— 修复前这里显示的是「需要开启热点」+ `192.168.1.103(wlan0)`
- 小米上关热点：`HotspotWatch: 收到 WIFI_AP_STATE_CHANGED state=10 → TETHER_STATE_CHANGED → state=11`，服务与通知自动停 ✔；**服务停掉、App 退回后台后再开热点：广播被 HyperOS 拦掉，不会自动起**（预期行为，见 §5），打开 App 立刻补起 ✔
- 上传回归（小米，电脑经家里 Wi-Fi 直连 `192.168.1.103:2121`）：`226`，`.txt` 落 `Download/CamFtp`（界面 `✓ regress.txt Download/CamFtp`、已收到 1 张）—— 验的是服务监听 + 入库链路；相机经热点那条仍以 Z50II 为准
- 大字读数不截断（小米 34sp 行高 0.75× 那个坑）：`addressValue` 框 149px、墨迹 890..974（上下各留 30~35px，不再贴着框边）
- **没热点时的引导**（真机 2026-10-07，热点没开、手机连着家里 Wi-Fi）：顶部错误色卡「需要开启热点」、卡下常驻「打开热点设置」；状态行「未接收」；读数卡不显示那个用不上的 IP，兜底显示「当前网卡：192.168.1.104(wlan0)」
- **没热点时的引导**（真机 2026-10-07，手机连着家里 Wi-Fi、热点没开）：顶部出错误色卡「需要开启热点」+ 主色「打开热点设置」按钮；「开始接收」置灰不可点；相机读数卡不再显示那个用不上的 IP、连复制一起收起，兜底显示「当前网卡：192.168.1.104(wlan0)」（置灰、断连两种状态都截图验过）
- **相机连接状态**：`开始接收` 后显示「等相机连上来 · 已收到 0 张」；电脑裸 TCP 连 `2121`（只发 `USER`、未登录）2 秒内变成「相机已连接 · 已收到 0 张」；断开后变「相机没连着 · 上次连接 17:41 · 已收到 0 张」。服务内测：用 `adb shell input tap` 点按钮 + PowerShell `TcpClient` 手动开连接（App 的 FGS 不 exported，`am start-foreground-service` 会被拒）

### 未验证 / 已知风险（接手时先知道）

1. 热点广播在 ColorOS 17 与 HyperOS 3 上都实测过（发得出来，投递情况见 §5），更老的 Android / 其它 ROM 未验：复验看 logcat 有没有 `HotspotWatch: 收到 …` + `dumpsys activity broadcasts` 里是 DELIVERED 还是 skipped；被拦也不致命（打开 App 那次 `sync()` 兜得住）
2. 相机**重传同名文件** → `DSC_0001 (1).JPG` 改名，未实测
3. `START_STICKY` 被杀后自恢复没实测
4. **只在 ColorOS / Android 17 与 Xiaomi HyperOS 3 / Android 16 两台机上验过**，其它 ROM / Android 10–15 未验（HyperOS 的热点网卡名、行高、后台广播策略都跟 ColorOS 不一样，换 ROM 复验时走上面 §5 的「验证方法」）
5. 配置的失败路径（如端口被占用）没测
6. 批量几百张 / 超大文件没测（8MB 连拍过了）

---

## 7. 待办

1. **升 `targetSdk` 到 37 时第一件事：加本地网络权限**。Android 17 起对 `targetSdk ≥ 37` 强制；接受入站 TCP 连接算「本地网络访问」，必须声明 `android.permission.ACCESS_LOCAL_NETWORK` + 运行时申请（权限组 `NEARBY_DEVICES`，用户看到「附近的设备」弹窗），否则**相机的连接会被静默丢弃**（表现为 TCP 超时，无任何报错）。落点：`MainActivity.ensureNotificationPermission()` 旁加同款方法 + README 写清"第一次启动要允许附近的设备"。<br>⚠️ `targetSdk ≤ 36` 时**不要**提前声明该权限（`INTERNET` 会隐式授予）。来源：`developer.android.com/privacy-and-security/local-network-permission`
2. **热点自动化一半做了、一半故意不做**：「热点开着就收、关掉就停」已落地（§3.20）。「替用户开热点」理论上能用 `WifiManager.startLocalOnlyHotspotWithConfiguration()`（API 36）+ `SoftApConfiguration.Builder`（36.1+），但 **LOH 和系统「个人热点」是两套东西**：SSID / 密码由代码定、相机侧已填的配置要重填、ColorOS 行为不可控 —— 收益不抵复杂度，不做。真要做：单独文件 + `@RequiresApi(36)` + 运行时版本判断兜住，不许影响现有构建。
3. 换机器开发时：把 keystore `<你的 release keystore 路径>` 和仓库根 `keystore.properties`（密码是随机生成的）**复制过去并备份**，丢了就没法给已装用户升级。

---

## 8. 复现 / 回归命令

```powershell
gradle test                     # 17 个 JVM 用例
gradle :app:testDebugUnitTest --rerun    # 强制重跑（复现竞态用）
gradle assembleDebug --console=plain
adb install -r -g app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n io.github.mangome.camftp/.MainActivity
adb logcat -s HotspotWatch FtpService     # 热点广播收没收到 / 每张图的入库结果
adb shell dumpsys activity broadcasts > temp\bc.txt   # 广播投给谁了：搜包名看 DELIVERED / skipped by policy
```

无线调试连真机（HyperOS 上 `install -g` 和 `input tap` 都会被拒，改打 release 包覆盖装）：

```powershell
adb mdns services                        # 找 _adb-tls-connect / _adb-tls-pairing 的 IP:端口（配对弹窗开着才有前者）
adb pair <ip>:<配对端口> <6位码>          # 手机：开发者选项 → 无线调试 → 使用配对码配对设备
adb connect <ip>:<连接端口>
# HyperOS 专用取证：热点网卡叫什么 / 哪张网卡在 tether / App 会不会被拦广播
adb shell ip -4 -o addr show                     # 小米实测热点 = wlan2，STA = wlan0
adb shell dumpsys tethering | Select-String "Tether state" -Context 0,4
adb shell am get-standby-bucket io.github.mangome.camftp   # 40=RARE：后台广播会被拦
adb install -r app\build\outputs\apk\release\app-release.apk   # 同签名覆盖装，配置与已授权限都保留
```

真机：电脑当 FTP 客户端 —— **电脑得连手机热点**（接收只认热点网卡，没有 debug 例外，见 §3.20）：

```powershell
# 热点网卡名各 ROM 不同：ColorOS = ap0、HyperOS = wlan2（不确定就抄 App 界面上的地址）
$iface = 'ap0'
$ip = (adb shell ip -4 -o addr show $iface) -replace '.*inet ([0-9.]+)/.*','$1'
curl.exe -sS --user camftp:123456 -T .\x.jpg "ftp://${ip}:2121/"                      # 被动
curl.exe -sS --user camftp:123456 --ftp-port - -T .\x.jpg "ftp://${ip}:2121/x.jpg"   # 主动
curl.exe -sS -T .\x.jpg "ftp://${ip}:2121/anon.jpg"                                 # 匿名（App 默认就开着）
adb shell ls -l /sdcard/DCIM/CamFtp/
```

清理测试图要用 MediaStore 删（`content delete --uri content://media/external/images/media/<id>`，id 先 `content query` 拿），直接 `rm` 会留残留行。

相机侧验收步骤交给用户跑 → [`camera-setup.md`](./camera-setup.md)。

---

## 9. 备忘

- **临时文件一律放 `temp/`**（截图、dumpsys 转储），已在 `.gitignore` 里 —— 仓库根目录不落临时文件。
- **关热点 = 停止接收**：通知栏那条「CamFtp · IP:2121 · 已收到 N 张」消失就是停了；App 里没有开始/停止按钮是故意的（§3.20），别加回去。
- **「最近收到」里出现「测试图」不是异常**：自检按钮的结果按设计进事件列表（`self_test_event`，`counts=false` 不计入张数），不是来路不明的文件。
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
