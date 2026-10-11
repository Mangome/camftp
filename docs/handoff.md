# CamFtp 开发笔记

**仓库** <https://github.com/Mangome/camftp> ｜ **状态**：功能完成，`v0.1.6` 已发布（变更见 `CHANGELOG.md`）
**面向**：要改这个仓库的人 / 新的 agent session。**§2 是改代码前的必读清单**（每条一行，违反了基本都会出问题）；实测踩坑记在 §4。本机 / 个人工作台内容（环境、真机验收流水、取证命令）在 `temp/dev-notes.local.md`（已 gitignore）。

---

## 0. 是什么 / 怎么分层

- 前台服务里跑 **Apache FtpServer 1.2.1**：控制端口 `2121`（可配），被动端口 `32768-61000`（尼康手册写死的范围）
- 相机（实测 **Nikon Z50II**）经**手机热点**连上来，FTP `STOR` 推图 → 写入 MediaStore（图片 / 视频 → `DCIM/<目录>`，其它类型 → `Download/<目录>`）→ 通知栏显示已收张数
- **不申请存储权限**（走 MediaStore，Android 10+ 免权限）、**不替用户开热点**（只跳设置）、**热点就是总开关**：开着就在收、关掉就停，App 里没有开始 / 停止按钮、只有一屏 UI

```
MainActivity (XML + ViewBinding)          状态 / 热点设置入口 / 配置 / 相机地址块 / 事件
HotspotWatch (+ HotspotReceiver)          热点开/关 → 起/停 FtpService（回前台再对齐一次）
  └─ FtpService      前台服务(connectedDevice) + 静音常驻通知 + PARTIAL_WAKE_LOCK
       └─ FtpEngine   纯 JVM，不 import android.* → 可单测
            ├─ CameraProfile  机型差异=数据（NIKON_Z50II / GENERIC_NIKON）
            ├─ Sink → MediaStoreSink（真机）/ FakeSink（单测）
            └─ FtpServerFactory + SinkFtplet + SimpleUserManager
```

`FtpEngine` 只依赖 `java.io` + ftpserver，是全部可测性的来源。

---

## 1. 代码结构

```
app/src/main/java/io/github/mangome/camftp/
├── Config.kt              配置唯一出处（端口/账号/目录名/匿名开关）+ 校验
├── CameraProfile.kt       机型差异=数据（NIKON_Z50II / GENERIC_NIKON）
├── Sink.kt                interface Sink + StoreResult（含缩略图字节）
├── MediaStoreSink.kt      入库：图片视频→DCIM/<dir>，其它→Download/<dir>；失败保留源文件；成功时生成缩略图
├── Thumbnailer.kt         入库时生成 384px 小图（图片 / 视频首帧 / RAW 走 EmbeddedJpeg；自己修 EXIF 方向）
├── ExifTransform.kt       **纯 JVM**：EXIF orientation → 旋转角 + 翻转（表错了竖拍就躺倒，单测守着）
├── EmbeddedJpeg.kt        **纯 JVM**：从任意文件里扫出最大的内嵌 JPEG（NEF 出图的唯一通路，单测守着）
├── FtpEngine.kt           纯 JVM：FtpServer 配置/生命周期 + 单线程入库 executor + retryPending()
├── SimpleUserManager.kt   单用户明文认证 + 可选的匿名账号（坑最密集的地方，见 §4）
├── SinkFtplet.kt          上传回调：CWD/STOR 自动建目录，把文件丢给 executor；顺带数控制连接数
├── HotspotWatch.kt        热点=开关：广播 + 回前台对齐 → 起/停 FtpService；HotspotReceiver 是清单里那份
├── FtpService.kt          前台服务 connectedDevice、静音常驻通知、wakelock、START_STICKY
├── FtpState.kt            StateFlow 单例状态通道（服务→UI；另存相机会话数 / 上次连接时间）；「最近收到」落盘在 filesDir/recent.bin
├── NetworkInfo.kt         枚举 IPv4、过滤虚拟网卡、识别热点网卡
├── SquareFrameLayout.kt   方图容器：网格 3 列等宽 + 每格 1:1
└── MainActivity.kt        单屏 UI
app/src/main/res/
├── values/colors.xml + values-night/colors.xml   全部颜色（cam_* 命名，深浅两套）
├── values/themes.xml      Theme.CamFtp：M3 槽位映射 + 状态栏/导航栏图标明暗
├── drawable/              启动图标三层 + 通知图标 + 界面图标（chevron / 信号塔 / 失败叉 / 划掉的相纸）+ bg_tag / bg_failure / bg_field(_error) / bg_event_empty
├── layout/activity_main.xml   根是 FrameLayout：panelBackdrop + ScrollView，面板通栏；内容 = 热点按钮 → 读数卡 → 折叠的高级设置 → 最近收到（空态虚线卡 + 网格 + 失败条）→ 关于
├── layout/item_recent.xml     「最近收到」的一格：圆角方图 + 后缀徽标 + 文件名
└── layout/item_failure.xml    失败条目的一条：时间 + ✗ + 文件名 + 原因
app/src/test/java/.../  FtpEngineTest 11 · FtpStateTest 4 · EmbeddedJpegTest 4 · ExifTransformTest 3 · HotspotWatchTest 1 · NetworkInfoTest 5
```

`compileSdk`/`targetSdk` 36、`minSdk` 29、ViewBinding、AGP 8.13.2 / Kotlin 2.2.21（见 `gradle/libs.versions.toml`）。

标识资产的**母版在仓库根目录 `branding/`**（SVG + 使用规范），App 里的资源是它的派生品 —— 改标识改母版再派生，别直接改 `res`。

---

## 2. 改代码前的规矩

### 引擎 / 线程

- **`FtpEngine` 不 import 任何 `android.*`** —— 破了它就没法跑 JVM 单测，全部可测性都在这条上。
- **入库 executor 留在 `FtpEngine` 里**（单线程、保序）—— `Ftplet` 回调跑在 MINA IO 线程上，绝不能阻塞。
- **入库失败绝不删源文件** —— 留在 `filesDir/ftp`，服务启动时 `retryPending()` 重试一次。宁可留垃圾不丢图。
- **`SinkFtplet.beforeCommand` 里自动建目录**（`CWD` 与 `STOR/APPE/STOU` 两条路径）—— curl 这类客户端是「先 CWD 再 STOR」，不处理直接 550。
- **别自己写 FTP 协议栈、别引第三方 FTP 库**；机型差异只做成数据，不为只有一种实现的东西抽 interface / factory；真出现第二种传输协议（PTP/IP）再抽 `Transport`。

### 状态与 UI

- **状态通道只用 `FtpState`（StateFlow 单例）** —— `LocalBroadcastManager` 已废弃。
- **「相机连上了没」只能数 `SinkFtplet.onConnect/onDisconnect`**（jar 反编译实查：只有 `DefaultFtpHandler` 在 `sessionOpened`/`sessionClosed` 调它，数据连接不触发）—— 别改成 `onLogin/onLogout`，登出和断线是两条路，容易减重。
- **面板状态词的唯一出处是 `updateCameraHint()`**（只有它知道有没有热点），`render()` 别再自己 setText，会被它盖掉。
- **颜色只写 `values/colors.xml` 与 `values-night/colors.xml`**（`cam_*` 命名），由 `Theme.CamFtp` 映射到 M3 槽位 —— 别在 layout 里写死颜色。
- **34sp 地址与 26sp 状态词必须显式写 `android:lineHeight`** —— 不写就是赌 ROM 的字体度量（HyperOS 上被量成 0.75×，字被切掉，见 §4）。
- **「热点」标记留在读数卡标题行右侧** —— 34sp 等宽下 `10.130.223.120` 已占满卡片（实测 953/964px），跟地址同行只会被挤出屏幕。
- **「整屏只有一个视觉高峰」= 连接面板那块暗底色**，不靠字号抢：白卡是「往相机里填什么」，暗面板是「现在怎么样」，两块各管一事。地址读数保持 34sp 等宽不缩（相机要照着输）。
- **连接面板：五态 + 一块会呼吸的灯**（闲置墨蓝 / 连上·传输中暗绿 / 缺热点暗红，只换色相不换明度），副行只在**传输中**（报正在传的文件名）与**断开但连过**（报「上次连接 HH:mm」）时出现。**整屏只有这一处动效**（`setLinkLamp()`，动画关掉的机器就常亮）。
- **热点没开就把 34sp 读数一并收起来**（那个 IP 对相机完全没用），读数卡里只留一行引导 + 一行「当前网卡：…」兜底 —— 换 ROM 认错热点网卡名时地址还抄得到，别删。
- **「打开热点设置」按钮常驻在卡下面，不跟卡一起隐显** —— 卡是「现在缺什么」的提醒，按钮是「该怎么办」的动作；按钮跟着卡消失的话，热点开着时就没地方改热点设置了（关掉、换密码都要回到这个页）。
- **不熄屏用 layout 的 `android:keepScreenOn`**，别写 `FLAG_KEEP_SCREEN_ON` —— 前者按「窗口可见」自动失效，后者要在 `onResume`/`onPause` 配对加清，漏一边就把用户屏幕焊死。
- **「最近收到」不引 RecyclerView**（≤12 格整块重建更省事，material 只带进来 1.1.0 的老版本），也不上 Compose（单屏，只换来编译器 / BOM 对齐风险）。
- **网格几何**：`SquareFrameLayout` 保 1:1；最后一行不满要补 `Space`，否则格子被拉宽、跟上面几行对不齐。
- **输入框别用 `TextInputLayout`** —— 它的浮动标签会给 EditText 强加 16dp 上下内边距，44dp 的框把 15sp 的字裁掉一半（真机实测）。现在是 `EditText` + `bg_field.xml` 自绘，错误提示是框下独立 TextView，`showFieldError()` 一次换描边与显隐。
- **界面尺寸以 Pixso 设计稿为准**（「主屏 · 连接与读数」，6 状态 × 深浅 = 12 画板），别凭感觉调。相对上一版的结构性改动：面板通栏到屏幕顶端（`panelBackdrop` + `syncBackdrop()`，配 `windowLightStatusBar=false`，状态栏那一条永远是暗面板色）、地址下有 1px 分隔线、「热点」是药丸底、最近收到标题右侧有「N / 12」计数（空态时它也写「0 / 12」）、失败条目是独立小条、空态是一块虚线卡（划掉的相纸 + 「还没收到照片」+ 描边按钮，`anyStored` 一真就整块收起，自检失败留在原地可重试）、界面图标是线性描边圆头（母版在 `branding/icons/`，跟启动图标那套「填充不描边」是两回事）。

### 配置与网络

- **配置唯一出处 `Config`**；保存时若服务在跑 → `FtpService.ACTION_RESTART` 热重启（比「提示用户手动重启」少一步无意义操作）。
- **账号只允许 ASCII 简单字符**：相机上用遥控器打字很痛苦，别逼用户输复杂密码。
- **匿名登录默认开**（`Config.anonymous` 初值 true），引擎构造参数默认 false（引擎级保守默认，运行时由 `FtpService` 传 `Config.anonymous`）。机制见 §4 的「匿名登录」两条。
- **监听 `0.0.0.0`**（热点 / 家里 Wi-Fi / USB 网都通）；**IP 一律运行时枚举，不要写死**（网段随 ROM 变，ColorOS 每次开热点都可能是新网段 —— 这正是首屏那块 34sp 大字存在的意义）。
- **不设 `passiveAddress`**：FtpServer 会回落到控制连接的本地地址（= 当前热点 IP），正好是相机要的。
- **被动端口 `32768-61000` 不可改**（相机侧根本不填这个，改坏了只会白断连）；`idleTimeout` 300s、`maxLogins` 4 —— **别把 `maxLogins` 设成 1**，相机两次操作之间会停很久，且反复登录登出。
- **通知**：`IMPORTANCE_LOW` + `setOnlyAlertOnce(true)` + ongoing + 全静音 —— 拍摄现场手机不该响。
- **跳「热点设置」的 action 只有 `com.android.settings.WIFI_TETHER_SETTINGS` 直达**「个人热点」页，其余候选在 ColorOS 上全落空（见 §4）；换 ROM 要复验。

### 功能边界（故意不做的）

- **不申请存储权限**（走 MediaStore）、**不加 `MANAGE_EXTERNAL_STORAGE`**、**不开机自启**。
- **不放开始 / 停止按钮**：「什么时候开始接收」只有一条规则 —— 热点开着就该在收（`HotspotWatch`）。有按钮就得回答「用户按了停止之后广播来了要不要再起」，这个语义没有好答案；用户的习惯已经统一成「关热点就是停」。
- **不替用户开热点**：跳设置页就够。`startLocalOnlyHotspot` 跟系统「个人热点」是两套东西（SSID / 密码由代码定、相机侧已填的配置要重填），收益不抵复杂度。
- **不加任何只对开发有用的口子**（曾经用 `FLAG_DEBUGGABLE` 放行「家里 Wi-Fi 直连 2121」，结果没热点时也报「正在接收」，跟面板自相矛盾 → 已删）。加之前先自问：它会不会出现在用户界面里？
- **不发遥测、不加广告 / 统计 SDK、不引入新的出站网络行为**；新增依赖先确认。
- 不为「以后可能」加抽象、配置项、兼容分支 —— **读到不认识的落盘 `MAGIC` 就当没存过**，别好心加回兼容分支（加了就把旧格式永久钉死）。

### 几条「只能这样写」的实现细节

- **MediaStore 入库走两段式**：`insert(IS_PENDING=1)` → 拷字节 → `update(IS_PENDING=0)` → 删源文件。`RELATIVE_PATH` 图片视频用 `DCIM/<dir>`、其它用 `Download/<dir>`；`.nef` 手动映射成 `image/x-nikon-nef`，推不出来就落 `Download/`，不崩。**同名冲突交给 MediaStore 自动改名**（`DSC_0001 (1).JPG`），不自己处理。
- **「最近收到」落盘别改成 SharedPreferences + JSON**：`org.json` 在 JVM 单测里是桩，一用 `FtpStateTest` 就废。现在是 `DataOutputStream` 定长字段 + `MAGIC` + 每条末端带缩略图（`writeUTF` 自带长度前缀，文件名里带制表符换行也不串行）。落盘失败全吞（`runCatching`）：丢的只是历史，不能因为写文件失败把接收链路带停。**会话数 / `lastConnectAt` 不落盘** —— 那是「这一次运行」的实况。
- **缩略图必须跟条目一起存**：原图被相册删掉后网格照样要显示它，这样也没有「缩略图缓存目录」的生命周期问题（上限 12 条，整块重写，永不留孤儿文件）。
- **缩略图自己生成，不用 `ContentResolver.loadThumbnail`**：它对 NEF / RAW 无效（`BitmapFactory` 不认 TIFF），且原图被删后就没了。规格：长边 384px / JPEG q=80。UI 侧按 `uri` 做 `LruCache`（8MB）在 IO 线程解码，解回来**认 `view.tag` 不认 view**（每次入库都整块重建网格）。
- **EXIF 方向自己修**：`BitmapFactory` 不应用 orientation，不修的话竖拍在网格里是躺倒的（方形裁剪下就是构图错不错的问题）。**先缩后转**，转 8000×6000 要多占几十 MB。**别指望 `ImageDecoder`**：查过 AOSP 源码，它一处 `orientation` 都没用。
- **RAW 不认后缀白名单**：`BitmapFactory` 解不出来就去 `EmbeddedJpeg.largest()`，谁嵌了 JPEG 谁就有图，换品牌 / 机型不用改代码。不认后缀的视频（`.mts` 等）按 `VIDEO` 集合兜底试首帧，否则会把几 GB 的文件当图片整读一遍找 JPEG；找预览前还有一道 256MB 的大小阀（入库是单线程，绝不能被大文件堵住）。
- **点「最近收到」的一格打开原图前先探活**（`openAssetFileDescriptor(uri, "r")`）：图在相册里被删掉后 `ACTION_VIEW` 照样启动得起来，用户看到的是「应用打不开这个文件」而不是我们的提示。**缩略图还在不代表原图还在**。只有 `uri` 非空的格子才可点，所以每条成功入库都必须带上 `uri`。
- **自检按钮是「情境化」的，不是设置项**：长在「最近收到」里，只在 `Snapshot.anyStored == false` 时出现（收到真图或自检成功即收起，自检失败则留着可重试）；显隐跟着落盘，代价是自检成功过就不再问（想再自检要清 App 数据）。自检结果写 `Event(counts = false)`，不算进「已收到 N 张」。
- **文案铁律：只说下一步该干什么，不解释观察到的网络状态**（「连的是别的网络」「地址会显示在下面」这类都没意义，太长会被打回）。
- **标识 = 一张相纸，母版在 `branding/`**。三条别改：① 启动图标前景 / 主题层**全是填充路径不描边**（描边粗细在不同渲染器下会飘）；② 主题层与通知图标的**窗口是真洞**（`fillType="evenOdd"`，画成另一种颜色会被系统一并染色）；③ 前景层缩放固定 **0.40**，放大就会被正圆遮罩切到角。四份几何只在 `temp/logo-design/brand_params.py` 写一份，`build_brand.py` 生成全部 —— 别各写一套。

---

## 3. 协议与平台事实（有出处，改配置时别推翻）

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

## 4. 踩过的坑（实测，与直觉相反）

一条总规律：**这里的每条都是「现象跟原因对不上」** —— 421 不是并发数超了、550 不是权限没给、530 不是密码错、白图标不是主题没设、字被切不是字号不对。顺着现象猜基本都会猜错：要么回去读源码 / 反编译，要么回真机取证（`logcat` / `dumpsys` / `uiautomator dump`）。

### FTP 引擎与依赖

| 问题 | 现象 / 修法 |
| --- | --- |
| Kotlin `apply` 里的属性名遮蔽（踩了两次） | ① `BaseUser().apply { setName(name) }` —— `name` 解析成接收者自己的属性（此时 null），用户名 / 密码被写成 null，登录必失败，构造参数得叫 `userName/userPassword`；② `private val authorities = …` 再 `apply { setAuthorities(authorities) }` —— 解析成 `getAuthorities()`（空列表），**所有账号**在 USER 阶段被 `421 Maximum login limit has been reached.` 拒掉（看着像并发数超了，实际是权限为空那条分支），改叫 `permissions`。**断点看不出来，只能靠回复码定位** |
| `setPassiveEnabled(true)` 在 FtpServer 1.2.1 里不存在 | 编译不过。被动模式没有开关（只有 active 有），删掉 |
| `setFtplets(mapOf(...))` | Kotlin `mapOf` 不可变，而 `DefaultFtpServerContext.dispose()` 会 `clear()` 它 → `stop()` 抛 `UnsupportedOperationException`。用 `mutableMapOf<String, Ftplet>` |
| 只给 `WritePermission` | `USER` 命令还会 `authorize(ConcurrentLoginRequest)` → `421`。必须加 `ConcurrentLoginPermission(MAX, MAX)` |
| `WritePermission(homeDir.absolutePath)` 传的是物理路径 | 每个 `STOR` 都 `550 Permission denied`。它按**相对家目录的虚拟路径**前缀匹配，应传 `"/"` |
| 匿名登录的两个坑 | ① 相机 / curl / 资源管理器发的用户名是字面量 `anonymous`，`USER` 命令里是**大小写敏感**的 `equals`，别做归一化；② 返回的 `User` 名字必须叫 `anonymous`，否则 `DefaultFtpStatistics` 不把它算作匿名会话。另：`maxAnonymousLogins` **别传 0** —— 源码里 `currAnonLogin >= maxAnonymousLogins` 永远成立，会把匿名登录全拒掉（「0 = 不限」只活在日志文案里）。匿名与具名**共存**，不互斥 |
| FTP 会话的 `TYPE I` **必须在 `login()` 之后**发 | 登录前发被 530 拒，会话停在 ASCII，字节被 LF→CRLF 改写（65536→66060，单测数据对不上） |
| `onUploadEnd` 回调**可能晚于 226 响应** | 单测里 `storeFile()` 返回 ≠ 已派发入库，「排空队列」不够，要轮询等结果 |
| `org.apache.commons:commons-net:3.11.1` 坐标不存在 | 正确坐标是 `commons-net:commons-net`（单测客户端） |
| 依赖自带 `META-INF/DEPENDENCIES`、`LICENSE`、`NOTICE` 三份撞车 | `mergeJavaResource` 失败。要 `packaging { resources { excludes += … } }` |

这组的共性：**回复码是对的，解释是错的** —— 先回源码看那条分支，别顺着现象改配置。

### Android 平台与各 ROM 的脾气

| 问题 | 现象 / 修法 |
| --- | --- |
| 热点网卡名写死成名单（`ap0/swlan0/…`） | HyperOS 3 的热点在 **`wlan2`**（`dumpsys tethering`: `TetherState: wlan2 - TetheredState`）→ App 判成「没热点」：读数收起、服务不起，用户看到「开了热点却显示未开」。修法：名字形状照抄系统自己的 `tetherableWifiRegexs: [wlan\d, softap\d, ap_br_wlan\d, ap_br_softap\d]`，再用「哪张卡在连 Wi-Fi」把 STA 排除。`NetworkInfoTest` 守着 |
| 拿 STA 名单去否决**所有**名字（修小米时顺手引入的回归） | ColorOS 17 上 `ap0` 被当成 STA 排掉，开热点却显示「需要开启热点」。实测 logcat：`ap0=10.129.14.60(热点), wlan0=192.168.1.104 ｜ STA [wlan0, ap0]` —— ColorOS **确实**把热点那张卡也报进 `TRANSPORT_WIFI`。修法：STA 名单只用来判 `wlanN` 这种两边都可能叫的名字，`ap`/`softap`/`swlan`/`wlan-ap`/`ap_br_*` 直接定案 |
| 清单里声明热点广播，**根本收不到** | ColorOS 17：`dumpsys activity broadcasts` 里清单那份每次都是 `skipped by policy at enqueue: Background execution not allowed`，干活的只有 `HotspotWatch.attach()` 挂的运行时接收器。**HyperOS 3 更狠**：App 退到后台（`am get-standby-bucket` = 40 RARE）后运行时那份也一起拦。结论：别只写清单接收器；进程被杀后切热点不会自动起，兜底是打开 App。复验看 DELIVERED / skipped |
| 广播说热点关了，但 `ap0` 还挂着几百毫秒 | 光靠扫网卡会漏掉这次「停」，通知栏挂着一条假的「正在接收」。`onBroadcast()` 对 `DISABLING/DISABLED/FAILED` 直接停，不等扫描 |
| 怕 Android 12+ 不允许后台起前台服务 | ColorOS 17 实测**放行**（`Background started FGS: Allowed … uidState: LAST`）。`HotspotWatch.start()` 仍包 try/catch：真被拦只丢一行日志，回前台 `sync()` 补回来 |
| 跳「热点设置」的 action 名**记错一个前缀就静默跑偏** | ColorOS 17 实测：`com.android.settings.TETHER_SETTINGS` **无 App 注册**；`android.settings.TETHER_SETTINGS` 落到「网络共享」页；只有 `com.android.settings.WIFI_TETHER_SETTINGS` 直接是「个人热点」页；`Panel.ACTION_INTERNET_CONNECTIVITY` 这台机 SystemUI 没注册。**验证方法**：`adb shell dumpsys package <pkg> \| grep -i tether` 看谁真注册了，再用 `am start -a <action>` + `uiautomator dump` 看落点页面 |
| README 说 ColorOS 热点网段是 `192.168.43.1` | 实际是 **`ap0` / `10.129.14.x`**；同时还有 `wlan0`、`vgate0`、`ccmni*`（数据）、tun/gre/ifb/dummy 一堆虚拟网卡 —— 枚举 IPv4 必须黑名单过滤 + 热点名置顶 |
| `getString(R.string.x, "2121")` 配 `%d` 占位符 | **一启动就闪退** `IllegalFormatConversionException: d != java.lang.String`。资源占位符类型必须和实参一致 |
| `targetSdk 36` 起系统**强制 edge-to-edge** | 内容画到状态栏底下被时钟压住；Material 1.12 的 M3 主题又不设 `android:windowLightStatusBar`，浅色主题下白图标落在浅底上看不见。修法：① root 上 `fitsSystemWindows="true"`，**20dp 内边距必须挪到内层**（`computeSystemWindowInsets` 只在「该边 padding==0」时才补 inset，留在 root 上会静默失效）；② `Theme.CamFtp` 里声明状态栏 / 导航栏图标明暗。Pixso 那版把面板做成通栏后，状态栏图标改为**恒亮**（面板底色永远是暗的） |
| HyperOS 上 34sp 等宽粗体的**行高被量成 0.75×** | 地址读数上下被切掉。实测（fontScale 1.1 / 520dpi）：34sp 只量出 91px 高，其它字号都是 1.35~1.38×（22sp→101px、16sp→77px）。描的字一直是 34sp，就框子矮了。修法：显式 `android:lineHeight="44sp"`，别指望 ROM 的字体度量 |

这组的共性：**跟 ROM 有关的行为一律真机实测** —— 网卡名、广播投递、字体度量、后台策略，文档和其它 ROM 的经验都会骗你；复验方法写在各条里。

### 界面与资源

| 问题 | 现象 / 修法 |
| --- | --- |
| `TextInputLayout` 里的 `EditText` 固定 44dp 高 | 浮动标签会强制加 16dp 上下内边距，15sp 的字被裁掉一半（ColorOS 实测）。改成自绘：`EditText` + `bg_field.xml` + 框下独立错误 TextView |
| M3 反色槽位的属性名 | 是 `colorSurfaceInverse` / `colorOnSurfaceInverse` / `colorPrimaryInverse`，写成 `colorInverseSurface` 之类会 `resource linking failed`（Material 1.12 的 R.txt 里只有前者） |
| 没有 `?attr/materialButtonTonalStyle` 这个 attr | 想用 tonal 按钮直接写 `style="@style/Widget.Material3.Button.TonalButton"`；按钮风格里只有 `materialButtonStyle` / `materialCardViewOutlinedStyle` / `borderlessButtonStyle` |

### 缩略图与 RAW

| 问题 | 现象 / 修法 |
| --- | --- |
| 想让网格显示刚收到的图，第一反应是 `ContentResolver.loadThumbnail` | 对 **NEF 无效**（它走 `BitmapFactory`，不认 TIFF 系 RAW）→ 整天拍 NEF 就是一整屏占位格。改成入库时自己生成、存进落盘列表 |
| **竖拍的照片在缩略图里躺倒**（所有品牌、所有格式的通用坑） | `BitmapFactory` 不应用 EXIF orientation，系统相册里看着对、自己画的图就是躺的。修法：自己读 `android.media.ExifInterface` + `Matrix` 转；**别赌 `ImageDecoder`**（查过 AOSP 源码，它一处 `orientation` 都没用） |
| 按品牌列 RAW 后缀白名单 | 不是坏事但守不住（`.raw`/`.tif`/`.sr2`/`.rwl` 一漏就是灰格）。改成「`BitmapFactory` 解不出来就找内嵌 JPEG」，跟谁家的 RAW 无关 |

### 单测与工具链

| 问题 | 现象 / 修法 |
| --- | --- |
| 单测里 `javax.imageio` / `java.awt` 编译不过（`Unresolved reference 'image'`） | AGP 拿 android.jar 当 bootclasspath，`java.desktop` 不在单测的**编译**类路径上（Android 上本来也没有）。给 JPEG 造夹具改用内置的 base64 最小 JPEG（1x1、160 字节：SOI 在 0、EOI 在末尾），断言「抽出来的字节和写进去的一模一样」——比解出像素尺寸更严 |

相机侧（不是代码问题，是使用问题）的坑见 [`camera-setup.md`](./camera-setup.md)。

---

## 5. 验收状态与已知风险

### 验过的

- 电脑侧 curl：被动 / 主动模式、匿名、子目录（`CWD` 与路径两种）、`.NEF` → `DCIM/<目录>`（mime `image/x-nikon-nef`）、未知后缀 `.txt` → `Download/<目录>`，一律 `226` 入库、`filesDir/ftp` 无残留
- **Nikon Z50II 真机经手机热点**：回放上传 JPEG + NEF 成功，相册见原文件名，连续多张 + 息屏不断线；NEF 在我们自己的缩略图网格里出真图
- 热点识别与自动起停：ColorOS（`ap0`）与 HyperOS 3（`wlan2`）两套 ROM；服务在跑时关热点 → 自动停
- UI：连接面板五态、「最近收到」网格（落盘、强停重开还在、点格打开原图、原图被删后弹提示）、自检按钮显隐与落盘、配置校验与热重启、「打开热点设置」直达个人热点页
- **Pixso 那版界面在 OPPO PLG110 上逐状态验过**：缺热点 / 等待 / 断开（含「上次连接」副行）/ 正在接收（含文件名）/ 地址卡 / 网格与计数 / 展开态 / 校验红框 / 深浅两套
- 服务：通知 LOW / 静音 / ongoing，前台服务类型 `connectedDevice`，息屏接收照旧

### 没验 / 有风险

1. **只在 ColorOS 17（Android 17）与 Xiaomi HyperOS 3（Android 16）两台机上验过**，其它 ROM / Android 10–15 未验 —— 热点网卡名、字体度量、后台广播策略都跟 ROM 有关，换 ROM 复验走 §4 里各条的「验证方法」
2. **失败条目（`item_failure.xml`）没真机验过**：要造它得让入库真的失败，而那台机 `adb shell id` = `uid=2000(shell)`（无 root，写不进 `filesDir`）
3. **竖拍方向（EXIF）没在真机上验过**：相机竖拍一张传上来，看**新收到的那一格**（已经落盘的旧条目用旧缩略图，不会变）
4. 相机**重传同名文件** → MediaStore 改名 `DSC_0001 (1).JPG`，未实测
5. `START_STICKY` 被杀后自恢复、配置的失败路径（如端口被占用）、批量几百张 / 超大文件，都没测（8MB 连拍过了）
6. 缩略图让 `recent.bin` 涨到几百 KB（每次入库整块重写），落盘耗时没测过；量大到能卡住入库再说（代码里留了 `ponytail:` 注记）

---

## 6. 待办

1. **升 `targetSdk` 到 37 时第一件事：加本地网络权限**。Android 17 起对 `targetSdk ≥ 37` 强制；接受入站 TCP 连接算「本地网络访问」，必须声明 `android.permission.ACCESS_LOCAL_NETWORK` + 运行时申请（权限组 `NEARBY_DEVICES`），否则**相机的连接会被静默丢弃**（表现为 TCP 超时，无任何报错）。落点：`MainActivity.ensureNotificationPermission()` 旁加同款方法 + README 写清「第一次启动要允许附近的设备」。⚠️ `targetSdk ≤ 36` 时**不要**提前声明（`INTERNET` 会隐式授予）。来源：`developer.android.com/privacy-and-security/local-network-permission`

---

## 7. 复现 / 回归命令

发布：改 `app/build.gradle.kts` 的 `versionName`/`versionCode` 并提交 → `git tag v0.1.7 && git push origin v0.1.7` → `.github/workflows/release.yml` 自动跑单测、签名打包、建 Release（正文取 `CHANGELOG.md` 里对应那节）。tag 与 `versionName` 不一致第一步就失败；同一个 tag 重跑要先 `gh release delete v0.1.7`。签名凭据在仓库 Secrets。

```powershell
gradle test                              # 28 个 JVM 用例
gradle :app:testDebugUnitTest --rerun    # 强制重跑（复现竞态用）
gradle assembleDebug --console=plain
adb install -r -g app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n io.github.mangome.camftp/.MainActivity
adb logcat -s HotspotWatch NetworkInfo FtpService    # 热点广播 / 网卡与 STA 名单 / 每张图的入库结果
adb shell dumpsys activity broadcasts > temp\bc.txt  # 广播投给谁：搜包名看 DELIVERED / skipped
```

电脑当 FTP 客户端，**不连热点也能验入库链路**（控制连接 `forward`，主动模式的数据连接 `reverse` 回电脑）：

```powershell
adb -s <serial> forward tcp:2121 tcp:2121
adb -s <serial> reverse tcp:33333 tcp:33333   # 手机连自己的 127.0.0.1:33333，会被转回电脑的 33333
curl.exe -sS --user camftp:123456 --ftp-port 127.0.0.1:33333 -T .\x.jpg ftp://127.0.0.1:2121/x.jpg
adb -s <serial> forward --remove-all; adb -s <serial> reverse --remove-all
```

上面这条走的是**主动模式**；相机实际走**被动模式**，同样能在 adb 下跑通（`adb forward` 不支持端口范围，所以从 `PASV` 响应里读出端口再单独 forward 那一个；24MB 实测走通）：

```bash
adb forward tcp:2121 tcp:2121
exec 3<>/dev/tcp/127.0.0.1/2121     # USER anonymous / PASS x / PASV
# 227 Entering Passive Mode (127,0,0,1,p1,p2) → dport=p1*256+p2 → adb forward tcp:$dport tcp:$dport
# STOR 名 → 150 → exec 4<>/dev/tcp/127.0.0.1/$dport; cat 文件 >&4 → 226
```

电脑能连手机热点时（相机那条路，接收只认热点网卡）：

```powershell
# 热点网卡名各 ROM 不同：ColorOS = ap0、HyperOS = wlan2（不确定就抄 App 界面上的地址）
$iface = 'ap0'
$ip = (adb shell ip -4 -o addr show $iface) -replace '.*inet ([0-9.]+)/.*','$1'
curl.exe -sS --user camftp:123456 -T .\x.jpg "ftp://${ip}:2121/"                      # 被动
curl.exe -sS --user camftp:123456 --ftp-port - -T .\x.jpg "ftp://${ip}:2121/x.jpg"   # 主动
curl.exe -sS -T .\x.jpg "ftp://${ip}:2121/anon.jpg"                                 # 匿名（默认就开着）
adb shell ls -l /sdcard/DCIM/CamFtp/
```

清理测试图要用 MediaStore 删（直接 `rm` 会留残留行）；按文件名删要把整条命令当一个参数传进去，否则 git-bash 会吞掉单引号：

```bash
MSYS_NO_PATHCONV=1 adb shell "content delete --uri content://media/external/file --where \"_display_name='x.jpg'\""
```

相机侧验收步骤交给用户跑 → [`camera-setup.md`](./camera-setup.md)。

---

## 8. 参考资料

- 尼康 Z50II 手册 FTP 章节：<https://onlinemanual.nikonimglib.com/z50II/en/25-04.html>、<https://onlinemanual.nikonimglib.com/z50II/en/09-09-07.html>
- Android 前台服务类型与前提条件：<https://developer.android.com/develop/background-work/services/fgs/service-types>
- 同类开源实现（可读，别 fork）：[wolpi/prim-ftpd](https://github.com/wolpi/prim-ftpd)、[ppareit/swiftp](https://github.com/ppareit/swiftp)
- 生产环境配置参考（端口 1234 / PASV OFF）：<https://faq.alltuu.com/a538/5a74/3f78>
