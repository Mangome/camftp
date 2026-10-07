# CamFtp · 相机 FTP 接收

**把安卓手机变成一个 FTP 服务器，相机拍完就地传到手机相册。** 不用云、不用电脑、不用 SnapBridge——相机连手机热点，按「上传」即可，照片落在系统相册里（保留原文件名、按日期排序，可以直接发微信）。

Android app that turns your phone into a tiny FTP receiver: the camera connects to the phone's hotspot, uploads photos over FTP, and they land in the system gallery (MediaStore), keeping their original file names.

- 前台服务常驻，息屏不断连
- 被动 / 主动模式都支持，端口、账号、相册目录名都能改
- 原图直存相册（NEF / JPEG / MP4…任何类型都不会崩：图片视频进 `DCIM/`，其它进 `Download/`）
- 只做局域网，没有网络权限滥用、没有遥测

已在 **Nikon Z50II**（真机，JPEG + NEF）上验收通过；其它支持「连接到 FTP 服务器」的相机（大部分尼康机型）理论上同理，只是菜单路径不同。

> 本项目与尼康（Nikon Corporation）无任何关联，未使用其商标、Logo 或代码。

---

## 怎么用（三步）

1. **手机开热点**（SSID 用纯英文/数字），装好 App、打开、点「启动」
2. **相机连上来**：相机里建一个 FTP 配置，地址填 App 显示的那个 IP，端口 `2121`，用户/密码照抄 App
3. **相机回放 → 上传**：照片出现在手机相册里，通知栏显示「已收 N 张」

### 相机端设置（Nikon Z50II，手动核对自官方手册）

```
网络菜单 → 连接到FTP服务器 → 网络设定 → 创建配置文件 → 配置手动
  [常规]   配置文件名称：随意
  [无线]   选刚刚的手机热点（SSID 全英文）
  [TCP/IP] 一般「自动获取」即可（热点有 DHCP）
  [FTP]
     服务器类型：FTP
     地址：App 第一屏显示的 IP（形如 10.129.14.x）
     端口：2121                ← 必须手工改，默认 21 一定连不上
     目标文件夹：主文件夹        ← 最省事，别选子文件夹
     PASV模式：ON（OFF 也支持，两种都实测过）
     匿名登录：OFF
     用户名 / 密码：与 App 里填的一致
```

连上后：`回放` → 选照片 → `i` 菜单 → `选择上传(FTP)`；
想拍完自动传：`Options` → 打开自动上传（视频除外）。

### 通用尼康 / 其它品牌

找相机菜单里的「FTP 上传 / 连接到 FTP 服务器 / 网络设定 → FTP」这类入口，填四项：**地址 = App 显示的 IP、端口 = 2121、用户名密码 = App 里填的、PASV = ON 或自动**。相机如果要求「服务器类型」，选普通 `FTP`（不是 FTPS/SFTP）。不同机型菜单路径不同，但字段是同一批。

---

## 常见错误对照表

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 相机连不上 / 一直转圈后超时 | 热点没开，或相机连的是别的 Wi-Fi | App 里确认「本机地址」那行带「热点」标记；相机重新选自建配置文件里的无线 |
| 相机连不上（端口 21） | 相机文档里的「防火墙端口 21」是默认值，本 App 默认 `2121` | 相机 FTP 配置里把端口改成 2121，或把 App 控制端口改成 21（不推荐，需要 root 才能占用低端口） |
| `530 Login incorrect` | 用户名/密码和 App 不一致（注意大小写、全角字符） | 两边对齐后重新保存配置 |
| `550 Permission denied` / 目标文件夹不存在 | 相机选了不存在的子文件夹 | 目标文件夹改回「主文件夹」（App 也会自动建目录，但选主文件夹最稳） |
| 相册里看不到照片 | 入库失败（存储满、权限被拒） | 看 App「最近事件」里的红字；服务重启会自动重传一次残留在私有目录的文件 |
| 文件名变成 `DSC_0001 (1).JPG` | 相机重传了同名文件，MediaStore 自动改名 | 正常行为，不是 bug |
| 传几张就断，要重连 | 相机有 `Inactive connection timeout`，会主动断开 | 正常；重新点上传即可，服务器能承受反复登录登出；关掉相机的省电/自动关机更能减少断线 |
| 息屏后传不动了 | 系统省电把 App 冻结了 | 手机设置：App 后台策略设为「无限制」、允许自启动；关掉热点的「无连接时自动关闭」 |
| 相机搜不到手机热点 | 热点 SSID 含中文，或频段不匹配 | SSID 改成纯英文/数字；相机「路由器频带」与热点频段一致（2.4G / 5G） |
| 相机连不上 FTP，但网络是通的 | 相机同一时刻只能连一种设备，SnapBridge 的 AP 模式占着 | 先断开 SnapBridge / 关闭其 AP 模式，再连 FTP |
| 传 RAW / 视频没反应 | 部分机型 FTP 只发 JPEG，是否发 NEF 视机型设置而定 | 检查相机的上传设置；App 侧对任意后缀都能存（未知类型进 `Download/`） |
| 相册里多了一张 `camftp-selftest.jpg` | 你点了 App 的「自检」按钮 | 正常，删掉即可 |

---

## 已实测 / 未实测

**实测通过**（一台 OPPO ColorOS 机，Android 17 / ColorOS，相机 Nikon Z50II）：

- 被动模式、主动模式（相机 PASV 关）上传 → `226`，文件真的进了 MediaStore
- `.JPG` / `.NEF` → `DCIM/<目录名>`，未知后缀 `.txt` → `Download/<目录名>`
- 子目录上传（相机 `CWD` 与路径带目录两种方式）自动建目录
- 改端口后服务自动热重启，新端口可用、旧端口拒连
- 通知静音常驻、前台服务类型 `connectedDevice`、热点网卡识别（`ap0` 高亮）
- 息屏连拍若干张不断线

**未覆盖**：只在 ColorOS 一台机型验过，其它 ROM / Android 10–16 未测；App 的「打开热点设置」按钮依赖系统 Settings 页，不同 ROM 表现可能不同。

---

## 构建

需要 JDK 17+（开发用 Temurin 21）、Android SDK（`compileSdk 36` / `minSdk 29`）。

```bash
./gradlew test                # 7 个 JVM 单测（FTP 引擎全流程）
./gradlew assembleDebug       # 输出 app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # 需要签名配置，见下
```

装到手机（`-g` 一次授予运行时权限，Android 17 上 `pm grant` 会被拒）：

```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

### 签名 release

在仓库**根目录**放一个 `keystore.properties`（已在 `.gitignore` 里，绝不会入库）：

```properties
storeFile=<你的 release keystore 路径>
storePassword=****
keyAlias=camftp
keyPassword=****
```

没有这个文件时 `assembleRelease` 会产出一个未签名 APK，不影响 debug 构建。

---

## 隐私

- App **不申请 `INTERNET` 之外的联网用途，不上传任何数据、没有统计/广告 SDK**
- 只在本机监听一个 TCP 端口（默认 2121）被动接收，不会主动连任何服务器
- FTP 认证是**明文**的：这是 FTP 协议本身的限制。局域网内使用，建议把默认用户名/密码改掉，别在公共网络开服务
- 文件只写进本机相册/下载目录，App 私有目录只在入库失败时临时留存，重试成功后删除

## 许可

Apache License 2.0（见 [`LICENSE`](LICENSE)），第三方组件声明见 [`NOTICE`](NOTICE)。变更记录见 [`CHANGELOG.md`](CHANGELOG.md)。

设计与踩坑记录：`docs/app-development.md`（原始设计与需求）、`docs/handoff.md`（交接/环境/验收矩阵）。
