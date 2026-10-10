# CamFtp 开发笔记

**仓库** <https://github.com/Mangome/camftp> ｜ **状态**：功能完成，`v0.1.5` 已发布（变更见 `CHANGELOG.md`）
**面向**：要改这个仓库的人 / 新的 agent session。原 v1 设计文档 `app-development.md` 已并入本文（连同它被实测证伪的部分，见 §4），不再单独维护。本机 / 个人工作台内容（环境、真机验收流水、取证命令）在 `temp/dev-notes.local.md`（已 gitignore）。

---

## 0. 是什么 / 怎么分层

- 前台服务里跑 **Apache FtpServer 1.2.1**：控制端口 `2121`（可配），被动端口 `32768-61000`（尼康手册写死的范围）
- 相机（实测 **Nikon Z50II**）经**手机热点**连上来，FTP `STOR` 推图 → 写入 MediaStore（图片 / 视频 → `DCIM/<目录>`，其它类型 → `Download/<目录>`）→ 通知栏显示已收张数
- **不申请存储权限**（走 MediaStore，Android 10+ 免权限）、**不替用户开热点**（只跳设置）、**热点就是总开关**：开着就在收、关掉就停，App 里没有开始 / 停止按钮（见 §2.20）、只有一屏 UI

```
MainActivity (XML + ViewBinding)          状态 / 热点设置入口 / 配置 / 相机地址块 / 事件
HotspotWatch (+ HotspotReceiver)          热点开/关 → 起/停 FtpService（回前台再对齐一次）
  └─ FtpService      前台服务(connectedDevice) + 静音常驻通知 + PARTIAL_WAKE_LOCK
       └─ FtpEngine   纯 JVM，不 import android.* → 可单测
            ├─ CameraProfile  机型差异=数据（NIKON_Z50II / GENERIC_NIKON）
            ├─ Sink → MediaStoreSink（真机）/ FakeSink（单测）
            └─ FtpServerFactory + SinkFtplet + SimpleUserManager
```

**别做的**：自己写 FTP 协议栈、引第三方 FTP 库、上 Compose（单屏，只换来编译器/BOM 版本对齐风险）、`MANAGE_EXTERNAL_STORAGE`、开机自启、**手动开始/停止按钮**（§2.20）。机型差异只做成数据，**不要**为它抽 interface / factory（只有一种实现）；真出现第二种传输协议（如 PTP/IP）再抽 `Transport`。
`FtpEngine` 只依赖 `java.io` + ftpserver，是全部可测性的来源。

---

## 1. 代码结构

```
app/src/main/java/io/github/mangome/camftp/
├── Config.kt              配置唯一出处（端口/账号/目录名/匿名开关）+ 校验；具名账号默认 camftp / 123456、**匿名登录默认开**；被动端口固定 32768-61000，不可改（相机侧不填这个）
├── CameraProfile.kt       机型差异=数据（NIKON_Z50II / GENERIC_NIKON）
├── Sink.kt                interface Sink + StoreResult（含缩略图字节）
├── MediaStoreSink.kt      入库：图片视频→DCIM/<dir>，其它→Download/<dir>；失败保留源文件；成功时生成缩略图
├── Thumbnailer.kt         入库时生成 384px 小图（图片 / 视频首帧 / RAW 走 EmbeddedJpeg；自己修 EXIF 方向）
├── ExifTransform.kt       **纯 JVM**：EXIF orientation → 旋转角 + 翻转（表错了竖拍就躺倒，单测守着）
├── EmbeddedJpeg.kt        **纯 JVM**：从任意文件里扫出最大的内嵌 JPEG（NEF 出图的唯一通路，单测守着）
├── FtpEngine.kt           纯 JVM：FtpServer 配置/生命周期 + 单线程入库 executor + retryPending()
├── SimpleUserManager.kt   单用户明文认证 + 可选的匿名账号（坑最密集的地方，见 §4）
├── SinkFtplet.kt          上传回调：CWD/STOR 自动建目录，把文件丢给 executor；顺带数控制连接数（相机连没连）
├── HotspotWatch.kt        热点=开关：广播 + 回前台对齐 → 起/停 FtpService；HotspotReceiver 是清单里那份
├── FtpService.kt          前台服务 connectedDevice、静音常驻通知、wakelock、START_STICKY
├── FtpState.kt            StateFlow 单例状态通道（服务→UI；另存相机会话数 / 上次连接时间）；「最近收到」落盘在 `filesDir/recent.bin`（§2.23）
├── NetworkInfo.kt         枚举 IPv4、过滤虚拟网卡、识别热点网卡（AP 专有名直接定案、wlanN 排除 STA，见 §2.20 / §4）
├── SquareFrameLayout.kt   方图容器：网格 3 列等宽 + 每格 1:1
└── MainActivity.kt        单屏 UI
app/src/main/res/
├── values/colors.xml + values-night/colors.xml   全部颜色（cam_* 命名，深浅两套）
├── values/themes.xml      Theme.CamFtp：M3 槽位映射 + 状态栏/导航栏图标明暗
├── drawable/ic_launcher_foreground.xml + ic_launcher_monochrome.xml + ic_launcher_background.xml  自适应图标三层（矢量；几何与 branding/ 同源，见 §2.27）
├── drawable/ic_notification.xml   通知栏 24dp 单色剪影（窗口是真洞）
├── layout/activity_main.xml   相机连接面板（整屏主角，§2.26）→ 热点设置按钮 → 相机读数卡 → 折叠的高级设置 → 最近收到（网格 + 失败小字）
└── layout/item_recent.xml     「最近收到」的一格：圆角方图 + 后缀徽标 + 文件名
app/src/test/java/.../FtpEngineTest.kt   11 个 JVM 用例（FTP 引擎全流程，含匿名登录开关两种状态、会话数回调）
app/src/test/java/.../FtpStateTest.kt     4 个：自检/真图计数的口径、会话数增量（不变负数、不断清「上次连接」）、条目/缩略图落盘往返、旧格式当没存过
app/src/test/java/.../EmbeddedJpegTest.kt 4 个：内嵌 JPEG 抽取（挑最大的、没图的返回 null、假命中不算、跨 chunk 边界）
app/src/test/java/.../ExifTransformTest.kt 3 个：EXIF 方向表（八种取向、只有 90 的倍数、没定义的当正常）
app/src/test/java/.../HotspotWatchTest.kt 1 个：起/停/不动的规则（「热点=开关」的唯一规则来源）
app/src/test/java/.../NetworkInfoTest.kt 5 个：热点网卡识别（小米 wlan2 算热点、正在连 Wi-Fi 的 wlan0 不算、ColorOS 把 ap0 报进 Wi-Fi 网络里照样认）
```

`compileSdk`/`targetSdk` 36、`minSdk` 29、ViewBinding、AGP 8.13.2 / Kotlin 2.2.21（见 `gradle/libs.versions.toml`）。

标识资产的**母版在仓库根目录 `branding/`**（SVG + 使用规范），App 里的资源是它的派生品 —— 改标识改母版再派生，别直接改 res。

---

## 2. 关键设计决策（改代码前先读）

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

15. **UI 视觉约定**（界面重构后定的，改界面前先看这条）：颜色只写在 `values/colors.xml` 与 `values-night/colors.xml`（`cam_*` 命名），`Theme.CamFtp` 负责把它们映射到 M3 槽位，**别在 layout 里写死颜色**；屏幕从上到下 = 相机连接面板（§2.26）→ 热点设置按钮 → 相机读数卡 → 折叠的高级设置 → 最近收到，全程左对齐；**整屏只留一个视觉高峰，而这个高峰是靠「底色」拿的，不是靠字号**：连接面板是整屏唯一一块暗色，白卡只是「往相机里填什么」，两块各管一事；地址读数保持 34sp 等宽不缩（相机要照着输）；状态只用颜色编码不用装饰；等宽字体只用于地址/端口/账号/事件这些要逐字符比对的地方；配置类低频操作一律进折叠区，校验失败时自动展开（否则错误提示在收起的区域里，用户看不见）。**大字读数必须显式写 `android:lineHeight`**（不写就是赌 ROM 的字体度量，见 §4）；**「热点」标记写在读数卡标题行右侧**：34sp 等宽下 `10.130.223.120` 已占满卡片（实测 953/964px），tag 跟地址同行只会被挤出屏幕。
16. **自检按钮是「情境化」的，不是设置项**：它长在「最近收到」区块里（配置类操作才进折叠区），只在 `FtpState.Snapshot.anyStored == false`（本进程还没有任何成功入库）时出现 —— 收到真图或自检成功即自动收起，**自检失败则留在列表下可重试**（失败不能把唯一的自检入口关掉）。显隐复用已有状态（`anyStored`），**跟着「最近收到」一起落盘**（§2.23）：自检成功过就不在重启后再问一遍（代价：想再自检要清 App 数据）。自检结果同时往事件列表写一条 `Event(name = "测试图", counts = false)`：`counts = false` 保证它不算进「已收到 N 张」（`FtpStateTest` 守着这两条）。

17. **「相机连上了没」是数出来的，不是猜的**：`SinkFtplet.onConnect/onDisconnect` → `FtpState.clientDelta(±1)`。jar 反编译实查过：只有 `DefaultFtpHandler` 调 ftplet 的 connect/disconnect，且只在 `sessionOpened` / `sessionClosed` —— 数据连接不触发，所以是精确配对（**别改成 `onLogin/onLogout`**：登出和断线是两条路，容易减重）。`lastConnectAt` 断开时**不清**，UI 才能说「相机没连着 · 上次连接 17:41」；服务重启时 `clients` 归零。UI 只当它是「有/没活动」的实证，不保证相机侧真的在拍。
18. **热点没开 = 连接面板说「需要开启热点」（`status_need_hotspot`）+ 面板下常驻的「打开热点设置」按钮**：相机只能连热点，不在热点上那个 IP 对相机**完全没用**，所以 34sp 读数一并收起来，读数卡里只留一行引导（`camera_need_hotspot`）+ 一行 `当前网卡：…` 兜底（热点探测靠「网卡名形状 + 排除当前 STA」，换 ROM 猜错时地址还抄得到 —— 小米 HyperOS 上正是靠它看出热点网卡是 `wlan2`，别删）。**原来那张错误色警示卡已删**：它跟面板说的是同一件事，同一句提示不占两处；面板状态词（含这四种态）的唯一出处是 `updateCameraHint()`（`render()` 别再自己 setText，会被它盖掉）。文案铁律：只说下一步该干什么，**不解释观察到的网络状态**（写「连的是别的网络」「地址会显示在下面」都没意义，解释句太长会被打回）。

19. **按钮不放卡里，也不跟卡一起隐显**：「打开热点设置」常驻在卡下面。卡是「现在缺什么」的提醒，按钮是「该怎么办」的动作，拆成两行各司其职；按钮跟着卡一起出现/消失的话，热点开着时就没地方改热点设置了（关掉、换密码都要回到这个页）。同理**不放开始/停止按钮** —— 那是热点的事（§2.20）。

20. **「什么时候开始接收」只有一条规则：热点开着就该在收**（`HotspotWatch`）。触发点两个：① 系统广播 `WIFI_AP_STATE_CHANGED` / `TETHER_STATE_CHANGED`（清单里声明了 `HotspotReceiver` 一份、`attach()` 在 application context 上挂运行时接收器一份 —— **实测干活的是后者**：清单那份每次都被 `skipped by policy at enqueue: Background execution not allowed` 拦掉（§4）。**小米 HyperOS 更狠：App 退到后台（`am get-standby-bucket` = 40 RARE）后运行时那份也一起拦**，所以「服务在跑时关热点自动停」照常、「服务已停 + App 在后台时开热点自动起」在小米上不会发生，兜底是打开 App（见 §4 / §5）；② 回到前台（`MainActivity.onResume → sync()`）、服务自己被拉回来时（`FtpService.start()` 的兵底检查）。判定一律**重新扫网卡**（`NetworkInfo.ipv4(context)`：`ap0` 这类 AP 专有名直接定案，只有 `wlanN` 才去比对「谁在连 Wi-Fi」），广播里带的数据只当闹钟；**唯一例外**是广播明说 `DISABLING/DISABLED/FAILED` → 直接停（那时热点网卡还挂着几百毫秒，等扫描会把这次「停」漏掉，通知栏就挂着一条假的「正在接收」）。`serviceAction()` 是唯一规则来源，`HotspotWatchTest` 守着（能收没起→起、不能收在跑→停、其余不动）。**别再加手动开关**：有按钮就得回答「用户按了停止之后广播来了要不要再起」，而这个语义没有好答案 —— 用户的习惯已经统一成「关热点就是停」。曾经用 `FLAG_DEBUGGABLE` 放行「家里 Wi-Fi 直连 2121」跑 curl 回归，结果没热点时状态栏也报「正在接收」，跟警示卡自相矛盾 → 删了（见 §4）。要加开发口子先自问：它会不会出现在用户界面里。

21. **不熄屏（前台常亮）用 layout 的 `android:keepScreenOn`，不写 `FLAG_KEEP_SCREEN_ON` 代码**：`activity_main.xml` 根 ScrollView 上一个属性，系统按「窗口可见」判定 —— 退到后台 / 息屏后自动失效，不用在 `onResume`/`onPause` 里配对加清标志（配对漏一边就是后台把用户屏幕焊死）。它跟服务是两件事：**息屏接收照旧**（前台服务管），常亮只管「App 打开着的时候别灭」。

22. **「最近收到」是一个 3 列缩略图网格**（不是文本日志）：`MainActivity.renderGrid()` 按行拼 `LinearLayout`（每格 `item_recent.xml`），格子 = 圆角 12dp 的 `MaterialCardView`（水波纹自带点击反馈，`cardElevation=0`、无描边 —— **整屏唯一的高峰还是连接面板那块暗底**）+ `SquareFrameLayout` 保证 1:1 + 下面一行等宽 11sp 文件名（`ellipsize=middle`：同场景连拍的一串 `DSC_18xx` 靠图分不出来）。**成功条目不再显示时间与目录**：目录就是配置的 `DCIM/<目录>`（每行都一样），「什么时候收的」图本身带着；`Event.at` 仍然存在，只给失败条目用。**失败条目在网格下方走回日志小字**（`eventLog`：`HH:mm:ss ✗ 文件名 原因`，非今天带月日；固定 24 小时制 `SimpleDateFormat` 而不是 `android.text.format.DateFormat` —— 12/24 小时制在 ROM 上不一致，跟 §4 行高那条坑同源）：它们没有图，时间和原因是「相机传了但没进相册」的唯一线索，但**不占格位**（否则失败条目会挤掉照片）。出不了图的格子写后缀（`NEF`/`MP4`/`TXT`/`FILE`），比通用破图图标说得清楚。**不引 RecyclerView**（material 只带进来一个 1.1.0 的老版本，12 格整块重建更省事）；最后一行不满要补 `Space` 空位，否则那几个格子会被拉宽、跟上面几行对不齐。

23. **「最近收到」落盘**：`FtpState.attach(filesDir)`（`MainActivity.onCreate` + `FtpService.onCreate` 各一次，幂等；服务可能先于 UI 起来），条目 / `received` / `anyStored` 一起存 —— 重启 App 列表和「已收到 N 张」都不清空。格式是 `DataOutputStream` 的定长字段、开头 `MAGIC`（当前 = 2），**每条末端是缩略图**（`writeInt(长度) + 字节`，0 = 没有）。**缩略图必须跟条目一起存**：原图被相册删掉后网格照样要显示它，而且这样就没有「缩略图缓存目录」的生命周期问题（上限就 12 条，每次落盘整块重写，永不留孤儿文件；代码里留了 `ponytail:` 注记）。**读到不认识的 `MAGIC` 就当没存过 —— 不兼容旧格式（别好心加回兼容分支，加了就把旧格式永久钉死）**：升级后历史条目、已收到张数、`anyStored` 归零一次，代价是「写入测试图」按钮重冒一次（收到一张新图后照旧收起）。上限 12 = 3 列的整 4 行。**别改成 SharedPreferences + JSON**：`org.json` 在 JVM 单测里是桩，一用 `FtpStateTest` 就废；`writeUTF` 自带长度前缀，文件名里带制表符换行也不串行。落盘失败全吞（`runCatching`）：丢的只是历史，不能因为写文件失败把接收链路带停。**会话数 / `lastConnectAt` 不落盘** —— 那是「这一次运行」的实况，服务重启就该归零（§2.17）。

24. **点「最近收到」的一格 → 打开那张图**（`MainActivity.openStored`）：整块格子是热区（`MaterialCardView` 的 `setOnClickListener`），**只有 `uri` 非空的格子才可点**，所以每条成功的入库都必须带上 `uri`（新增调用点别忘了）。打开前先 `openAssetFileDescriptor(uri, "r")` 探活：图在相册里被删掉后 `ACTION_VIEW` 照样启动得起来（查看器空转或自己报错，用户看到的是「应用打不开这个文件」而不是我们的提示），探不到就 `Toast`「文件已被删除」（`event_gone`），不启查看器 —— 注意**缩略图还在不代表原图还在**（§2.25），这两件事分得很开：格子永远画得出来，点开才知道文件死没死。

25. **缩略图在入库时自己生成、存进落盘列表**（`Thumbnailer`），**不用 `ContentResolver.loadThumbnail`**：① NEF / RAW 系统解码器解不了（`BitmapFactory` 不认 TIFF），只有抽内嵌预览才有图（`EmbeddedJpeg`，纯 JVM + 单测）；② 网格必须在原图被相册删掉之后照样显示 —— 那这份缩略图只能由我们自己留。生成时机在 `MediaStoreSink` **删源文件之前**（删了就再也没得抽），`Thumbnailer` 自己吞掉所有异常返回 null（缩略图是装饰，绝不能让入库背锅；它跑在入库 executor 上，不占 MINA IO 线程）。规格：长边 384px / JPEG q=80（≈30KB）。UI 侧按 `uri` 做 `LruCache`（8MB）、在 `Dispatchers.IO` 上解码，解回来**认 `view.tag` 不认 view** —— 每次入库都整块重建网格，回调回来时那个格子可能已经不在屏幕上了。
   ③ **方向得自己修**：`BitmapFactory` 不应用 EXIF orientation（系统相册会转，所以这个坑只在这一层看得见），不修的话**竖拍的照片在网格里是躺倒的**，而方形裁剪下这就是构图错不错的问题。做法：用 `android.media.ExifInterface` 读方向（文件路径给普通图片，`ByteArrayInputStream` 给 RAW 抽出来的预览 JPEG —— 相机一般把主图的 EXIF 也抄了一份进去），按 [ExifTransform] 的表用 `Matrix` 转。读不到 / 格式不支持就当正常（装饰品不能因为读 EXIF 失败就把图丢了）。**先缩后转**：转一张 8000×6000 的原图要多占几十 MB。
   ④ **RAW 不认后缀白名单**：`BitmapFactory` 解不出来就去 `EmbeddedJpeg.largest()`，谁嵌了 JPEG 谁就有图 —— 换品牌/换机型不用改代码（原来那 10 个后缀的白名单已经删了）。不认后缀的视频（`.mts` 等）按 `VIDEO` 集合兜底去试首帧，否则会把一个几 GB 的文件当图片整读一遍找 JPEG；找预览前还有一道 256MB 的大小阀（入库是单线程，绝不能被一个大文件堵住）。

26. **相机连接状态有专门的面板，它是整屏主角**（连接状态是整屏最重要的一条信息）。原来那行 22sp 的「未接收 / 正在接收」已删（「正在接收」后来以面板状态词 `status_transferring` 回来，见 ②），换成屏幕最上面一块暗底面板：① 底色 `cam_instrument`，**两个主题下都是暗的**（夜里不能被一整块亮色砸到；也正因为这样它跟两张白卡不靠字号就能分层），但**色相按状态换**（`updateCameraHint()` 里 `backgroundTintList`）：闲置 `cam_instrument` 中性墨蓝 / 连上·传输中 `cam_instrument_live` 暗绿 / 缺热点 `cam_instrument_alert` 暗红，三档明度都在同一条暗带上 —— 要的是「一眼看出现在属于哪一类」，**不是换一块亮色**（夜里那档还比白天再暗一档）；② 26sp 状态词 + 等宽副行，五态：`status_need_hotspot`（暗字）/ `status_camera_waiting`（亮字）/ `status_camera_online`（绿字）/ `status_camera_offline`（亮字）/ `status_transferring`（绿字，`STOR` 一开始就切过来、传完 / 传到一半断线切回去），副行只在传输中报正在传的文件名、或「断开但连过」时报「上次连接 HH:mm」（**不再报「已收到 N 张」**：张数归整个会话，跟面板当下说的是哪一态没关系）；③ **只有一处动效**：连上时左边那颗灯呼吸（`setLinkLamp()`，`ValueAnimator.areAnimatorsEnabled()` 为假就常亮），`lampOn` 挡重复重启；④ 状态词只有 `updateCameraHint()` 一处出处（它才知道有没有热点），字色语义只三个：连上/传输中=绿（跟灯同色）、等/断开=亮字、缺热点=暗字，且与底色同一个 `when`（`hotspotIface == null` 在最前：没热点时即使还挂着残留会话也不报绿）；⑤ 副行时间戳用固定 24 小时制 `SimpleDateFormat`（同 §2.22）。**传输中状态来自 `SinkFtplet` 的 `onTransfer`（`STOR` 那一刻报名字、传完清空，见 §2.26）**。**别把 IP 搬进面板、也别把面板字号缩下去给地址让路**：两者分工是「暗面板 = 现在怎么样 / 白卡 = 往相机里填什么」。

27. **标识 = 一张相纸，母版在 `branding/`，App 资源是派生品**（第四版；前三版都是「四只不等臂取景角 + 相纸」，角标在启动图标里过重、还占掉安全圆，见 `branding/README.md` 开头）。几何：半宽 58（116×116）、上/左右纸边 11、**底唇 26**、窗口 94×79（比 0.84）、侧倾 12°；颜色全部取自现有色板（纸 `cam_instrument`、窗口 `cam_primary`，深底换成 `cam_on_instrument` + `cam_inverse_primary`）。三条别改的：① **启动图标前景/主题层用矢量、全是填充路径不描边**（`ic_launcher_foreground.xml` / `ic_launcher_monochrome.xml`）—— 描边粗细在不同渲染器下会飘；换密度不用重出图。② **主题层与通知图标的窗口必须是真洞**（同一条 path 里 `fillType="evenOdd"`），画成另一种颜色会被系统一并染色，窗口就没了。③ 前景层缩放固定 0.40（相纸半对角 82 × 0.40 = 32.8dp，落在 66dp 安全圆内，可见区 76%），**别再放大**，放大就会被正圆遮罩切到角。**没有小尺寸专用几何了**（当年另做一档是因为角标会和相纸粘成一块，现在只剩相纸）：同一份几何从 512 一路用到 16px。四份几何（主符号/启动图标/通知图标/界面图标）只在 `temp/logo-design/brand_params.py` 写一份，`build_brand.py` 一次生成母版 + res 矢量 + 各密度位图 —— 别再各写一套（踩过：母版 5° / res 2° 不一致、相纸过大和角标粘成一块）。字标字形目前是 Noto Sans SC（OFL）转的路径，属**占位字形**，换字形不用动符号。

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

## 4. 🐞 踩过的坑（**照 v1 文档写代码会崩**，全部实测）

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
| M3 反色槽位的属性名是 `colorSurfaceInverse` / `colorOnSurfaceInverse` / `colorPrimaryInverse` | 写成 `colorInverseSurface` 之类会 `resource linking failed`（Material 1.12 的 R.txt 里只有前者） |
| 没有 `?attr/materialButtonTonalStyle` 这个 attr | 想用 tonal 按钮直接写 `style="@style/Widget.Material3.Button.TonalButton"`；`colorPrimary` 那套 attr 都在，唯独按钮风格里只有 `materialButtonStyle` / `materialCardViewOutlinedStyle` / `borderlessButtonStyle` |
| `targetSdk 36` 起系统**强制 edge-to-edge**（`windowOptOutEdgeToEdgeEnforcement` 在 Android 16+ 失效），内容画到状态栏底下被时钟压住；且 Material 1.12 的 M3 主题不设 `android:windowLightStatusBar`，浅色主题下白图标落在浅色背景上基本看不见 | ① `activity_main.xml` 的 ScrollView 加 `android:fitsSystemWindows="true"`，20dp 内边距**必须挪到内层 LinearLayout**（`computeSystemWindowInsets` 只在「该边 padding==0」时才补 inset，padding 留在 root 上会静默失效）；② 新增 `res/values/themes.xml` 的 `Theme.CamFtp`，`windowLightStatusBar` / `windowLightNavigationBar` = `?attr/isLightTheme`，Manifest 改用它 |
| 广播说热点关了，但 `ap0` 还挂着几百毫秒 | 光靠扫网卡会漏掉这次「停」，通知栏会挂着一条假的「正在接收」。`HotspotWatch.onBroadcast()` 对 `DISABLING/DISABLED/FAILED` 直接停，不等扫描 |
| 清单里声明热点广播，**根本收不到** | 实测（ColorOS 17 / Android 17）：`WIFI_AP_STATE_CHANGED state=10/11`、`TETHER_STATE_CHANGED` 都会发，但 `dumpsys activity broadcasts` 里清单那份每次都是 `skipped by policy at enqueue: Background execution not allowed`，投递的只有 `HotspotWatch.attach()` 挂的运行时接收器。结论：别只写清单接收器；进程被杀后切热点不会自动起（兜底是打开 App）。换 ROM 复验就看这句 DELIVERED / skipped |
| 怕 Android 12+ 不允许后台起前台服务 | ColorOS 17 实测**放行**：`ActivityManager: Background started FGS: Allowed [... uidState: LAST; code:UID_VISIBLE ...]`（App 刚在前台待过就允许）。`HotspotWatch.start()` 仍包着 try/catch：真被拦也只丢一行日志，回前台 `sync()` 会补回来 |
| 热点网卡名写死成名单 | 小米 MIX Flip 2 / HyperOS 3 把热点放在 **`wlan2`**（`dumpsys tethering`：`TetherState: wlan2 - TetheredState`），名单里只有 `ap0/swlan0/wlan1/...` → App 判成「没热点」：读数卡收起、服务不起，用户看到的就是「开了热点却显示未开」。修法：名字形状照抄系统自己的 `tetherableWifiRegexs: [wlan\d, softap\d, ap_br_wlan\d, ap_br_softap\d]`，再用 `ConnectivityManager` 里 `TRANSPORT_WIFI` 的网卡（STA）把它排除掉 —— `wlan2` 既可能是热点也可能是 STA，只能靠「哪张卡在连 Wi-Fi」分。`NetworkInfoTest` 守着 |
| 拿 STA 名单去否决**所有**名字（修小米时顺手引入的回归） | ColorOS 17 上开了热点却显示「需要开启热点」：`ap0` 被当成 STA 排掉了。实测（ColorOS 17 logcat）：`NetworkInfo: 网卡 ap0=10.129.14.60(热点), wlan0=192.168.1.104 ｜ STA [wlan0, ap0]` —— ColorOS **确实**把热点那张卡也算进 Wi-Fi 类型的网络（`wifiSta()` 取的就是「所有 `TRANSPORT_WIFI` 网络的 interfaceName」）。修法：STA 名单只用来判 `wlanN` 这种「热点和 STA 都可能叫」的名字；`ap`/`softap`/`swlan`/`wlan-ap`/`ap_br_*` 直接定案（没有任何 ROM 拿这些名字去连 Wi-Fi）。`NetworkInfoTest` 守着；下次换 ROM 复验就看那行日志的「｜ STA …」 |
| 小米上 `wlan2` 的 IP 会不会热点关了还留着（留了就误报） | 不会：3 秒粒度采样，`wlan2=10.130.223.120` 随关热点消失、开热点回来。所以「名字像 AP + 有 IPv4」两个条件就够，不用额外记状态 |
| HyperOS 把热点广播拦到「已退后台的 App」 | `dumpsys activity broadcasts` 实测：`SKIPPED terminal-enq ... #3: BroadcastFilter{... ReceiverList{... io.github.mangome.camftp}}` —— **运行时那份也拦**（ColorOS 只拦清单那份），`am get-standby-bucket` = 40（RARE）。服务在跑（FGS）时广播照常送达（`WIFI_AP_STATE_CHANGED state=10 → TETHER_STATE_CHANGED → state=11` → 服务自动停）。对策：代码不动，README 里写「小米上想让开热点自动起，给 App 开自启动 / 后台策略无限制」 |
| HyperOS 上 34sp 等宽粗体的**行高被量成 0.75×** | 地址读数上下被切掉。实测：`34sp`（fontScale 1.1 / 520dpi）只量出 91px 高，而其它字号都是 1.35~1.38×（22sp→101px、16sp→77px、13sp→64px）；描的字其实一直是 34sp（墨迹 85px = 0.7em ✔），就框子矮了。修法：`activity_main.xml` 里给 `addressValue` 显式 `android:lineHeight="44sp"` → 框 149px、墨迹居中（别指望 ROM 的字体度量） |
| debug 构建放行「家里 Wi-Fi 直连 2121」跑 curl 回归 | 没热点时状态栏也报「正在接收」，跟「需要开启热点」的提示自相矛盾，已删：接收只认热点网卡；真机回归改成让电脑连手机热点 |
| 单测里 `javax.imageio` / `java.awt` 编译不过（`Unresolved reference 'image'`） | AGP 拿 android.jar 当 bootclasspath，`java.desktop` 不在单测的**编译**类路径上（Android 上本来也没有）。给 JPEG 造夹具 / 验证改用内置的 base64 最小 JPEG（1x1、160 字节：SOI 在 0、EOI 在末尾），断言「抽出来的字节和写进去的一模一样」—— 比解出像素尺寸更严 |
| 想让网格显示刚收到的图，第一反应是 `ContentResolver.loadThumbnail`（minSdk 29 起可用、零依赖） | 对 **NEF 无效**：它走 MediaProvider 的解码器（`BitmapFactory`），而 `BitmapFactory` 不认 TIFF 系 RAW → 整天拍 NEF 的相机上传后是一整屏占位格。改成入库时自己生成、存进落盘列表（§2.25），顺带解决了「原图被相册删掉后网格空白」 |
| **竖拍的照片在缩略图里躺倒**（所有品牌、所有格式的通用坑） | `BitmapFactory` 不应用 EXIF orientation，`ImageView` 也不会自己转 —— 系统相册里看着是对的，只有自己画的图会错。修法：自己读 `android.media.ExifInterface` + `Matrix` 转（§2.25 ③）；**不要指望 `ImageDecoder` 帮你转**：网上的说法是“API 28+ 会自动应用方向”，但查 AOSP 源码（`graphics/java/android/graphics/ImageDecoder.java` 与 `libs/hwui/jni/ImageDecoder.cpp`）里一处 `orientation` 都没有（`grep -i orienta` 为空），所以没赌它 |
| 按品牌列 RAW 后缀白名单（`nef/cr2/arw/dng/...`） | 不是坏事但守不住：`.raw`/`.tif`/`.sr2`/`.rwl` 等一漏就是灰格。改成「`BitmapFactory` 解不出来就找内嵌 JPEG」，反正这条路本来就只依赖“文件里有没有一段完整 JPEG”，跟谁家的 RAW 无关 |

相机侧（不是代码问题，是使用问题）的坑见 [`camera-setup.md`](./camera-setup.md)。

---

## 5. 验收与已知风险

### 已验证（要点）

- 电脑侧 curl：被动 / 主动模式、匿名、子目录（`CWD` 与路径两种）、`.NEF` → `DCIM/<目录>`（mime `image/x-nikon-nef`）、未知后缀 `.txt` → `Download/<目录>`，一律 `226` 入库、`filesDir/ftp` 无残留
- **Nikon Z50II 真机经手机热点**：回放上传 JPEG + NEF 成功，相册见原文件名，连续多张 + 息屏不断线；NEF 在我们自己的缩略图网格里出真图（`EmbeddedJpeg` 成立）
- 热点识别与自动起停：ColorOS（`ap0`）与 Xiaomi HyperOS 3（`wlan2`）两套 ROM 都验过；服务在跑时关热点 → 自动停
- UI：连接面板五态（等待 / 连上 / 断开 / 传输中 / 缺热点，缺热点那态只核对了代码没截真机图）、「最近收到」缩略图网格（落盘、强停重开还在、点格打开原图、原图被删后弹「文件已被删除」）、自检按钮显隐与落盘、配置校验与热重启、「打开热点设置」直达 ColorOS「个人热点」页
- 服务：通知 LOW / 静音 / ongoing，前台服务类型 `connectedDevice`，息屏接收照旧

### 未验证 / 已知风险（接手时先知道）

1. 热点广播在 ColorOS 17 与 HyperOS 3 上都实测过（发得出来，投递情况见 §4），更老的 Android / 其它 ROM 未验：复验看 logcat 有没有 `HotspotWatch: 收到 …` + `dumpsys activity broadcasts` 里是 DELIVERED 还是 skipped；被拦也不致命（打开 App 那次 `sync()` 兜得住）
2. 相机**重传同名文件** → `DSC_0001 (1).JPG` 改名，未实测
3. `START_STICKY` 被杀后自恢复没实测
4. **只在 ColorOS / Android 17 与 Xiaomi HyperOS 3 / Android 16 两台机上验过**，其它 ROM / Android 10–15 未验（HyperOS 的热点网卡名、行高、后台广播策略都跟 ColorOS 不一样，换 ROM 复验时走上面 §4 的「验证方法」）
5. 配置的失败路径（如端口被占用）没测
6. 批量几百张 / 超大文件没测（8MB 连拍过了）
7. **缩略图网格里两条分支没上真机**：① 占位格（`thumb == null` → 灰底 + `NEF`/`TXT` 后缀）、② 失败小字（`eventLog`）。核心链路已在真机验证；这两条没验只是因为造样本要往 `filesDir/ftp` 塞文件，而这台机 `adb shell id` = `uid=2000(shell)`（无 root，写不进私有目录），而 passive FTP 经 `adb forward` 也打不进去（服务公布的是热点地址 `10.129.14.x`，电脑不在那个网段）。要验就走真相机/curl 连热点上传一个 `.txt`
8. **竖拍方向（EXIF）还没真机上验过**（代码刚补上）：相机竖着拍一张传上来，网格里应当是正立的而不是躺倒的。注意验的时候看**新收到的那一格**：已经落盘的旧条目用的是旧缩略图，不会变（这正是「缩略图自己存一份」的副作用）
9. 缩略图让 `recent.bin` 涨到几百 KB（每次入库整块重写），落盘耗时没测过；量大到能卡住入库再说（代码里留了 `ponytail:` 注记）

---

## 6. 待办

1. **升 `targetSdk` 到 37 时第一件事：加本地网络权限**。Android 17 起对 `targetSdk ≥ 37` 强制；接受入站 TCP 连接算「本地网络访问」，必须声明 `android.permission.ACCESS_LOCAL_NETWORK` + 运行时申请（权限组 `NEARBY_DEVICES`，用户看到「附近的设备」弹窗），否则**相机的连接会被静默丢弃**（表现为 TCP 超时，无任何报错）。落点：`MainActivity.ensureNotificationPermission()` 旁加同款方法 + README 写清"第一次启动要允许附近的设备"。<br>⚠️ `targetSdk ≤ 36` 时**不要**提前声明该权限（`INTERNET` 会隐式授予）。来源：`developer.android.com/privacy-and-security/local-network-permission`
2. **热点自动化一半做了、一半故意不做**：「热点开着就收、关掉就停」已落地（§2.20）。「替用户开热点」理论上能用 `WifiManager.startLocalOnlyHotspotWithConfiguration()`（API 36）+ `SoftApConfiguration.Builder`（36.1+），但 **LOH 和系统「个人热点」是两套东西**：SSID / 密码由代码定、相机侧已填的配置要重填、ColorOS 行为不可控 —— 收益不抵复杂度，不做。真要做：单独文件 + `@RequiresApi(36)` + 运行时版本判断兜住，不许影响现有构建。

---

## 7. 复现 / 回归命令

```powershell
gradle test                     # 28 个 JVM 用例
gradle :app:testDebugUnitTest --rerun    # 强制重跑（复现竞态用）
gradle assembleDebug --console=plain
adb install -r -g app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n io.github.mangome.camftp/.MainActivity
adb logcat -s HotspotWatch NetworkInfo FtpService   # 热点广播收没收到 / 网卡与 STA 名单 / 每张图的入库结果
adb shell dumpsys activity broadcasts > temp\bc.txt   # 广播投给谁了：搜包名看 DELIVERED / skipped by policy
```

真机：电脑当 FTP 客户端 —— **不连热点也能验入库链路**（控制连接 `forward`，主动模式的数据连接 `reverse` 回电脑，跟防火墙、热点都无关）：

```powershell
adb -s <serial> forward tcp:2121 tcp:2121
adb -s <serial> reverse tcp:33333 tcp:33333   # 手机连自己的 127.0.0.1:33333，会被转回电脑的 33333
curl.exe -sS --user camftp:123456 --ftp-port 127.0.0.1:33333 -T .\x.jpg ftp://127.0.0.1:2121/x.jpg
adb -s <serial> forward --remove-all; adb -s <serial> reverse --remove-all   # 用完清掉
```

验真机（相机那条路）时，电脑得连手机热点（接收只认热点网卡，没有 debug 例外，见 §2.20）：

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

## 8. 参考资料

- 尼康 Z50II 手册 FTP 章节：<https://onlinemanual.nikonimglib.com/z50II/en/25-04.html>、<https://onlinemanual.nikonimglib.com/z50II/en/09-09-07.html>
- Android 前台服务类型与前提条件：<https://developer.android.com/develop/background-work/services/fgs/service-types>
- 同类开源实现（可读，别 fork）：[wolpi/prim-ftpd](https://github.com/wolpi/prim-ftpd)、[ppareit/swiftp](https://github.com/ppareit/swiftp)
- 生产环境配置参考（端口 1234 / PASV OFF）：<https://faq.alltuu.com/a538/5a74/3f78>
