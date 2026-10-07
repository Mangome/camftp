# Changelog

本项目遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 新增

- 配置新增「允许匿名登录」：勾上后相机侧开「匿名登录」即可不填用户名密码推图（FTP 的 `anonymous` 账号，密码随便填；具名账号仍照常可用）。真机实测匿名 PASV / 主动模式都 226，关掉后匿名登录取 530

### 变更

- 删掉「本机地址（带「热点」的那个念给相机）」那块网卡清单：标题不通顺，且与「相机里填这些」重复。热点标记并到地址那一行（`地址 10.129.14.x（热点）`），「打开热点设置」按钮挪到顶部动作区
- 界面重构（让人看懂优先）：状态行改成「圆点 + 大字状态」，颜色表达运行/停止，主按钮随状态换主次色；「相机里填这些」做成卡片，34sp 等宽地址当主角，端口/用户/密码各占一行，匿名时只提示「相机侧开匿名登录、用户名密码留空」；配置收进「高级设置」折叠区（默认收起，校验失败自动展开），输入框换成 M3 outlined 样式；反馈由 Toast 改为 Snackbar；颜色集中到 `values/colors.xml` + `values-night/`，深色 / 浅色真机各过一遍

### 修复

- 「打开热点设置」打开的其实是 WiFi / 网络设置页：原三个候选 action 在 ColorOS 上全部落空（`com.android.settings.TETHER_SETTINGS` 根本没 App 注册、`Settings.Panel.ACTION_INTERNET_CONNECTIVITY` 也没注册），最终回落到 `ACTION_WIRELESS_SETTINGS`。改用实测有效的 `com.android.settings.WIFI_TETHER_SETTINGS`（真机直达「个人热点」页），保留「网络共享」页与网络设置两级兜底
- 顶部内容与系统状态栏重叠：`targetSdk 36` 强制 edge-to-edge，根布局补系统栏内边距（`fitsSystemWindows`）
- 浅色主题下状态栏图标是白色的（M3 主题不设 `windowLightStatusBar`，在自家浅色背景上几乎看不见）：新增 `Theme.CamFtp`，按主题明暗声明状态栏/导航栏图标颜色
- 「打开热点设置」按钮放回「相机里填这些」卡片：地址显示不出来（或当前不是热点地址）时它就是下一步该做的事，在卡片里比在顶部更贴近上下文（只在需要时出现）

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
