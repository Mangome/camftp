# CamFtp · 相机 FTP 接收

**安卓手机 = FTP 服务器，相机拍完直接进手机相册。** 不用云、不用电脑、不用 SnapBridge：相机连手机热点，回放 → 上传，照片按原名落到系统相册（可直接发微信）。

[下载 APK](https://github.com/Mangome/camftp/releases/latest) · Android 10+（minSdk 29）· Apache-2.0

Android app that turns a phone into a tiny FTP receiver: the camera connects to the phone's hotspot, uploads photos over FTP, and they land in the system gallery (MediaStore), keeping their original file names.

## 特性

- 前台服务常驻，息屏不断连；被动 / 主动模式都支持
- 端口、账号、相册目录名都能改，改完自动热重启
- 原图直存：图片 / 视频 → `DCIM/<目录>`，其它类型 → `Download/<目录>`，NEF / MP4 / 任意后缀都不会崩
- 只监听本机端口被动接收，无遥测、无广告 SDK

已在 **Nikon Z50II**（JPEG + NEF）真机验收；其它支持「连接到 FTP 服务器」的相机（大部分尼康机型）同理，只是菜单路径不同。只在 ColorOS / Android 17 一台机型上验过。本项目与尼康（Nikon Corporation）无关联，未使用其商标、Logo 或代码。

## 三步用起来

1. **手机开热点**（SSID 用纯英文 / 数字），装好 App，打开点「启动」
2. **相机连上来**：相机里建一个 FTP 配置，地址填 App 显示的 IP，端口 `2121`，用户名 / 密码照抄 App
3. **相机回放 → 上传**：照片出现在手机相册里，通知栏显示「已收 N 张」

Z50II 的逐级菜单路径、其它品牌大致位置 → [`docs/camera-setup.md`](docs/camera-setup.md)

## 连不上？

先看这四条，完整对照表（12 条）见 [`docs/camera-setup.md`](docs/camera-setup.md)：

| 现象 | 处理 |
| --- | --- |
| 连不上 / 一直转圈后超时 | 端口必须改成 **2121**（相机默认 21）；确认 App 显示的地址带「（热点）」标记，且相机连的是这个热点 |
| `530 Login incorrect` | 用户名 / 密码与 App 不一致（注意大小写、全角字符） |
| `550 Permission denied` | 相机的「目标文件夹」改回**主文件夹** |
| 息屏后传不动 | 系统把 App 冻结了：后台策略设为「无限制」+ 允许自启动 |

## 构建

需要 JDK 17+（开发用 Temurin 21）、Android SDK（`compileSdk 36` / `minSdk 29`）。

```bash
./gradlew test              # JVM 单测（FTP 引擎全流程）
./gradlew assembleDebug     # 输出 app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # 需要签名配置，见下
adb install -r -g app/build/outputs/apk/debug/app-debug.apk   # -g 一次授予运行时权限
```

release 签名：仓库根目录放 `keystore.properties`（已 gitignore，不会入库）

```properties
storeFile=<你的 release keystore 路径>
storePassword=****
keyAlias=camftp
keyPassword=****
```

缺这个文件时 `assembleRelease` 产出未签名 APK，不影响 debug 构建。

## 隐私

只在本机监听一个 TCP 端口被动接收，不主动连任何服务器、不上传数据、无统计 / 广告 SDK。FTP 认证是**明文**的（协议本身限制），请改掉默认用户名密码，别在公共网络开服务。

## 文档

| 文件 | 内容 |
| --- | --- |
| [`docs/camera-setup.md`](docs/camera-setup.md) | 相机逐级设置 + 常见报错排查 |
| [`docs/handoff.md`](docs/handoff.md) | 开发笔记：环境、设计决策、踩坑、验收矩阵、待办 |
| [`CHANGELOG.md`](CHANGELOG.md) | 变更记录 |

Apache License 2.0（见 [`LICENSE`](LICENSE)），第三方组件声明见 [`NOTICE`](NOTICE)。
