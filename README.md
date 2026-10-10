# CamFtp · 相机 FTP 接收

**安卓手机 = FTP 服务器，相机拍完直接进手机相册。** 不用云、不用电脑、不用 SnapBridge：相机连手机热点，回放 → 上传，照片按原名落到系统相册。

Android app that turns a phone into a tiny FTP receiver: the camera connects to the phone's hotspot, uploads photos over FTP, and they land in the system gallery (MediaStore), keeping their original file names.

<p align="center">
  <img width="446" height="958" alt="CamFtp 主界面：绿色「相机已连接」状态面板、「相机的 FTP 设置」卡片（热点地址与端口 2121）、「最近收到」的 NEF 缩略图网格" src="https://github.com/user-attachments/assets/2ae6cff7-23b0-47a1-a142-14f8450f458d" />
</p>

[下载 APK](https://github.com/Mangome/camftp/releases/latest) · Android 10+（minSdk 29）· Apache-2.0

## 特性

- 屏幕灭着也在收；App 开着时不熄屏
- 热点即开关：开热点就收，关掉就停
- 原图直存：图片 / 视频 → `DCIM/<目录>`，其它类型 → `Download/<目录>`，NEF / MP4 / 任意后缀都能收

已在 **Nikon Z50II**（JPEG + NEF）真机验收；其它支持「连接到 FTP 服务器」的相机（大部分尼康机型）同理，只是菜单路径不同。热点识别、自动起停与后台策略验过两台机型（ColorOS 17 / Android 17、Xiaomi HyperOS 3 / Android 16），更老的 Android 与其它 ROM 未验。本项目与尼康（Nikon Corporation）无关联，未使用其商标、Logo 或代码。

## 怎么用

1. 手机开热点（SSID 用英文 / 数字），装好 App 并允许通知。
2. 相机里建一个 FTP 配置：地址填 App 上显示的 IP，端口 `2121`，打开匿名登录，相机侧用户名 / 密码留空（想用账号密码，就在 App「高级设置」里关掉匿名登录再填）。
3. 相机回放选照片上传：照片进手机相册，通知栏显示已收张数。

相机侧的逐级菜单路径、连接失败与报错排查 → [`docs/camera-setup.md`](docs/camera-setup.md)

## 构建

需要 JDK 17+（开发用 Temurin 21）、Android SDK（`compileSdk 36` / `minSdk 29`）。

```bash
./gradlew test              # JVM 单测（FTP 引擎全流程）
./gradlew assembleDebug     # 输出 app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/debug/app-debug.apk   # -g 一次授予运行时权限
```

## 隐私

只在本机监听端口接收（默认被动模式；相机把 PASV 关掉时回连相机，同样只走局域网），不连任何外部服务器、不上传数据、无统计 / 广告 SDK。**只在手机自己开热点时接收**。FTP 认证是**明文**的（协议本身限制）。**匿名登录默认开着**（相机上少填两项）：同一局域网内任何人都能往相册里写文件，所以只在自己开的热点下用；要收紧就在「高级设置」里关掉匿名登录，并把默认用户名密码 `camftp` / `123456` 改掉。

## 文档

| 文件 | 内容 |
| --- | --- |
| [`docs/camera-setup.md`](docs/camera-setup.md) | 相机逐级设置 + 常见报错排查 |
| [`docs/handoff.md`](docs/handoff.md) | 开发笔记：设计决策、踩过的坑、已知风险、待办、回归命令 |
| [`CHANGELOG.md`](CHANGELOG.md) | 变更记录 |

Apache License 2.0（见 [`LICENSE`](LICENSE)），第三方组件声明见 [`NOTICE`](NOTICE)。
