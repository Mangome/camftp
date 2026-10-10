# CamFtp 标识：母版与使用规范

定案：**一张相纸**——半宽 58（116×116）、上/左右纸边 11、底唇 26、窗口比 0.84、侧倾 12°。

为什么只有相纸（这是第四版）：前三版是「四只不等臂取景角 + 相纸」。角标在启动图标里过重（亮线压深底会往外涨），减到 18 仍然和相纸互相挤；且角标占掉了安全圆，图标里相纸只能占可见区 67%。去掉角标后相纸能放到 76%，notification 也从「四角 + 纸」简化成一张纸。**代价**：标识退回「一张相纸」这个较通用的图形，性格靠 12° 倾角和宽底唇撑；如果哪天觉得太素，可以从 `temp/logo-design/board-res/paper/` 里的切角/缺口档找回来（但别用在图标上，小于 24px 会读成瑕疵）。

这套是**母版**。App 里用的资源是从这里派生的，改标识请改 `temp/logo-design/brand_params.py` 再跑 `build_brand.py`，别直接改 `app/src/main/res`。

## 1. 文件

| 文件 | 用途 |
| --- | --- |
| `mark.svg` | 主符号，单色（黑）。窗口是 evenodd 真洞 |
| `mark-color.svg` | 浅底彩色版（纸 `#101820` / 窗口 `#0E6E8A`） |
| `mark-reversed.svg` | 深底反白版（纸 `#E9EFF3` / 窗口 `#8FD9EF`） |
| `lockup-horizontal.svg` | 横排 lockup（符号 + 字标），默认场合 |
| `lockup-stacked.svg` | 堆叠 lockup，方形/窄栏场合 |
| `wordmark.svg` | 只用字标的场合（字形见 §6） |
| `icon-foreground/background/monochrome/full/legacy.svg` | 启动图标的五份设计源 |
| `icon-notification.svg` | 通知图标设计源（24dp 单色剪影） |
| `icons/ic_chevron_down.svg` | App 界面图标（折叠箭头），几何语言同源 |
| `png/mark-512.png` `png/mark.png` `png/favicon.ico` | 位图导出 |

全部是纯路径：没有 `<text>`、没有遮罩、没有渐变、没有位图。

## 2. App 里的对应关系

| 母版 | 落地位置 |
| --- | --- |
| `icon-foreground.svg` | `res/drawable/ic_launcher_foreground.xml`（矢量，108dp） |
| `icon-monochrome.svg` | `res/drawable/ic_launcher_monochrome.xml`（矢量；窗口是真洞，主题图标下才不会被染色吃掉） |
| `icon-background.svg` | `res/drawable/ic_launcher_background.xml`（纯色 `#101820`，吃满 108dp） |
| `icon-notification.svg` | `res/drawable/ic_notification.xml`（24dp 单色剪影） |
| `icon-legacy.svg` | `res/mipmap-{m,h,xh,xxh,xxx}dpi/ic_launcher.png`（48/72/96/144/192） |
| `icons/ic_chevron_down.svg` | `res/drawable/ic_chevron_down.xml` |

自适应图标的前景/主题层用的是**矢量**（全是填充路径，不描边 —— 描边粗细在不同渲染器下会飘），换密度不用重出图；`mipmap-*` 只剩 legacy 的 `ic_launcher.png`。

## 3. 几何（可复现，别随手改）

256×256 画布，相纸居中，侧倾 12°：

- 相纸 116×116，圆角 7.54（= 半宽 × 0.13）
- 纸边：上 11 / 左右 11 / **底唇 26**（下纸边更宽，这是「相纸」而非「相框」的关键，别做对称）
- 窗口 94 × 79（比 0.84），圆角 6
- 启动图标：缩放 **0.40**（相纸半对角 82 × 0.40 = 32.8dp，落在 66dp 安全圆内；相纸占可见区 76%）
- 通知图标：缩到 21.5dp 见方（24dp 画布内留 1.25dp）
- 没有小尺寸专用几何：相纸的比例到 16px 仍读得出（当年另做一档是因为角标会和相纸粘成一块）

## 4. 颜色

| 角色 | 浅底 | 深底 | 出处 |
| --- | --- | --- | --- |
| 相纸 | `#101820` | `#E9EFF3` | `cam_instrument` / `cam_on_instrument` |
| 画面窗口 | `#0E6E8A` | `#8FD9EF` | `cam_primary` / `cam_inverse_primary` |

信号绿 `#4ED88F`（`cam_link_on`）**不进 Logo** —— 它是界面里状态灯的专用色。单色场合一律用 `mark.svg`，不要把彩色版转灰度。

## 5. 净空与最小尺寸

- 净空：四周 ≥ 底唇的宽度（26 单位 ≈ 相纸宽的 22%），或 ≥ 字标里 "C" 的宽度，取大者。
- 最小尺寸：符号 16px（更小就别用窗口，见下）；横排 lockup 宽 ≥ 140px；通知图标 24dp。
- 16px 下窗口收成一条缝，整体仍读得出是一张倾斜的相纸 —— 设计好的退化，不是 bug。

## 6. 字标

`lockup-*.svg` 与 `wordmark.svg` 的字形来自 **Noto Sans SC（SIL OFL）**，已转成路径 —— **占位字形**，不是最终设计。

- 直接可用，但只应视为「能看的最小成品」；
- 定稿建议二选一：换一款允许 logo 使用的字体（OFL 的 Inter、IBM Plex Sans 可商用）重新排版；或自画字形（保留「Cam 常规 / Ftp 加粗」这个对比关系）。
- 换字形不用动符号：lockup 里符号与字标是两组独立路径。

## 7. 禁止

- 拉伸、压扁；把 12° 倾角改小或改大（它是标识唯一的手势）。
- 把纸边做成四边等宽（底唇一没，就成了通用「相框」）。
- 加投影、渐变、外发光、3D。
- 把画面窗口换成照片、图标或文字。
- 彩色版配深底、反白版配浅底（对比度不够）。

## 8. 已知限制

- **未做商标检索**。对外发布前请查一次（商标库 + 反向图片搜索）—— 相纸这类图形更要做。
- 相纸是斜的，在 lockup 里挨着正立的字标会显得「贴歪了」；这是有意的（照片落上去的那一下），但客户若坚持要正，就把符号单独摆正、不要带倾角的一半。
- 通知图标在 24dp 下窗口只有 8.4×7dp，系统再缩会糊成实心块 —— 正常取舍。
