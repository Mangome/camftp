# Changelog

本项目遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [0.1.0] — 2026-10-07

首个可用版本：手机当 FTP 服务器接收相机上传的照片（Nikon Z50II 真机验收通过）。

### 新增

- FTP 服务器引擎（Apache FtpServer 1.2.1），被动 / 主动模式都支持，监听 `0.0.0.0:2121`
- 上传自动入库：图片/视频 → `DCIM/<目录名>`，其它类型 → `Download/<目录名>`；同名冲突交给 MediaStore 自动改名
- 客户端 `CWD` / `STOR` 目标目录不存在时自动创建（curl 与相机都走这条路）
- 前台服务常驻（类型 `connectedDevice`）+ `PARTIAL_WAKE_LOCK` + `START_STICKY`，全静音通知
- 单屏 UI：启动/停止、相机地址块一键复制、配置（端口/被动端口范围/用户名/密码/目录名）、本机地址列表（热点网卡高亮）、打开热点设置、事件列表、写测试图自检
- 配置持久化 + 运行中保存自动热重启服务
- 入库失败不删源文件，服务启动时自动重试一次并在事件列表标红
- 7 个 JVM 单测（登录 / 上传 / 被动模式 / 主动模式 / 密码错误 / 目录 / 清理）

### 已知限制

- 只在 Android 17 / ColorOS 一台机器（一台 OPPO ColorOS 机）上验证过
- 热点需要手动开（App 只能跳转到系统热点设置页）
- 相机侧「目标文件夹」建议选「主文件夹」

[0.1.0]: https://github.com/Mangome/camftp/releases/tag/v0.1.0
