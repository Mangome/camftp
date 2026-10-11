# CamFtp 标识：母版与使用规范

定案：**一张线稿** —— 相机 + 举着手机取景的手（第五版标识）。

这套是**母版**。App 里的资源全部是它的派生品，派生只走一个脚本：

```
python branding/build_brand.py             # SVG + app 资源 + 位图（位图要本机有 Chromium）
python branding/build_brand.py --svg-only  # 只出 SVG
```

几何与颜色只写在 `build_brand.py` 顶部的 `PARAMS` 一处 —— 别手改派生文件，也别在 `app/src/main/res` 里改。位图（mipmap PNG / favicon）走无头 Chromium 渲染，Windows 上默认 Edge，换别的用 `CAMFTP_EDGE` 指路径。

为什么换掉相纸（第四版）：相纸的性格只靠 12° 倾角和宽底唇撑着，太素。这张线稿自己就说完了「这是干什么的 App」。**代价**：线稿信息量大，48px 以下读不出来（见 §8），启动图标里只能占到可见区的 71%（相纸是 76%）。

## 1. 文件

| 文件 | 用途 |
| --- | --- |
| `mark.svg` | 主符号，1024 方画布透明底，墨蓝线 + 白内填 |
| `mark-reversed.svg` | 深底反白版（线 `#E9EFF3`，白内填挖成真洞） |
| `lockup-horizontal.svg` | 横排 lockup（符号 + 字标），默认场合 |
| `lockup-stacked.svg` | 堆叠 lockup，方形/窄栏场合 |
| `wordmark.svg` | 只用字标的场合（字形见 §6） |
| `icon-foreground/background/monochrome/full/legacy.svg` | 启动图标的五份设计源 |
| `icon-notification.svg` | 通知图标设计源（24dp 单色相纸剪影） |
| `icons/ic_*.svg` | App 界面图标：折叠/进入箭头、信号塔、失败叉、划掉的相纸 |
| `png/mark-512.png`、`png/favicon.ico` | 网页 / 仓库头像 |
| `build_brand.py` | 唯一的派生脚本（母版 → 全部资产） |
| `source/k1jj-source.jpg` | 描摹用的原图（生成的图，返修从这里也回不去，只作重描底稿） |
| `source/k1jj-v2.svg` | 描摹稿（VTracer 原样路径，**要改形状改这一层**） |
| `source/prep_master.py` | 描摹稿 → 母版那一步的整理脚本 |

三段流程，都能重跑：

```
source/k1jj-source.jpg  --VTracer-->  source/k1jj-v2.svg  --source/prep_master.py-->  mark.svg  --build_brand.py-->  全部资产
```

母版是**机器描摹**的（1150 条填充路径，抗锯齿灰阶原样保留），不是手画路径：改小形状就改 `source/k1jj-v2.svg`（能直接编辑路径），再往下重跑；返修后 `build_brand.py` 里的 `content_bbox` 与 `icon_shift` 要跟着重算（见 §3）。`mark-reversed.svg` 与 `icon-monochrome.svg` 里有 `<mask>`：描摹的底层层是一整块剪影，线条靠把「白」那层挖成洞得到。

界面图标（`icons/`）是**线性描边圆头**风格（Pixso 设计稿定的），和标识那套「填充路径不描边」不是一回事。

## 2. App 里的对应关系

| 母版 | 落地位置 |
| --- | --- |
| `icon-foreground.svg` | `res/mipmap-{m,h,xh,xxh,xxx}dpi/ic_launcher_foreground.png`（**位图**，108/162/216/324/432） |
| `icon-monochrome.svg` | `res/mipmap-{...}dpi/ic_launcher_monochrome.png`（同上五档） |
| `icon-background.svg` | `res/drawable/ic_launcher_background.xml`（纯白矢量，吃满 108dp） |
| `icon-legacy.svg` | `res/mipmap-{...}dpi/ic_launcher.png`（48/72/96/144/192） |
| `icon-notification.svg` | `res/drawable/ic_notification.xml`（24dp 单色剪影） |
| `icons/ic_*.svg` | `res/drawable/ic_chevron_down/_right/_fail/_hotspot/_image_off.xml` |
| lockup / wordmark / png | 不进 App，给 README、发布物料、网页用 |

**启动图标的前景与主题层这次改成位图**：线稿有 1150 条路径（约 320KB 路径数据），做成矢量每次冷启动都要解析一遍，密度也不用再省。底色层仍是矢量（一个矩形）。

## 3. 几何（可复现，别随手改）

母版画布 `1024 × 1024`，内容外接框 `1001 × 839`（x `12..1012`、y `92.5..931.5`，左右各留 12 / 11、上下各留 92.5）—— 母版那行 `translate(-7,63.5)` 就是按这个外接框居中算的。

**图标里的视觉重心补偿是单独一档**（`PARAMS["icon_shift"]` = 71 画布单位 = 3.55dp，按「面积重心落在画布中心下方 25 单位」这条定稿规则算出来的）：这张线稿的面积重心比外接框中心高 46 单位、暗线重心高 78，只按外接框居中时图标看着往上飘。启动图标与 legacy 图标把这个补偿加在母版位移之外，**lockup 不加**（那边按外接框对齐才对）。原图返修后重心会变，这个数要跟着重算。

- 启动图标：母版缩到 **51.2dp**（缩放 0.05）再下压 → 内容 50 × 42dp，可见区（中央 72dp）上留 18.6dp / 下留 11.5dp；内容外接圆 **35.0dp**，正圆遮罩半径 36dp；再放大或再下压都会切到手臂
- 水平方向按外接框居中（左右留 12 / 11）。面积重心在 x=532（右偏 20），是手与手臂那块面积大造成的，没动：再往左挪就切到相机左缘
- legacy 图标：自适应图标的可见区（72/108）缩到 72 画布，白圆角方块圆角 22%
- lockup：符号块 96 见方（横排，字标 x=130、画布 335.2 × 130）/ 110 见方（堆叠，画布 340 × 195.7）
- **母版那行 `translate` 必须跟着路径一起派生**：`master()` 把它读出来再折算进各派生文件的 `translate`。丢了它，内容就停在原始位置（比外接框中心高 2.5dp，真机量到过）—— `verify()` 里有断言拦这个

## 4. 颜色

| 角色 | 值 | 说明 |
| --- | --- | --- |
| 线 | `#101820` | 品牌墨蓝 `cam_instrument` |
| 内填 | `#FFFFFF` | 纸白 |
| 反白线 | `#E9EFF3` | `cam_on_instrument`，深底用 |
| 网点灰 | 原样保留 | 描摹留下的网点与抗锯齿，按亮度分桶：`< 0.35` 归线、`>= 0.80` 归白，中间不动 |

底色**必须亮**：线是深色，压在深底上就消失了（深底要反白版）。信号绿 `#4ED88F` 不进标识。

## 5. 净空与最小尺寸

- 净空：四周 ≥ 符号宽的 10%
- 最小尺寸：符号 **96px**；48px 只剩「一个相机 + 一个手机」的轮廓，24px 是墨点
- 启动图标里符号占可见区 71%（可见区 = 108dp 画布的中央 72dp）

## 6. 字标

`lockup-*.svg` 与 `wordmark.svg` 的字形来自 **Noto Sans SC（SIL OFL）**，已转成路径 —— **占位字形**，不是最终设计。换字形不用动符号：lockup 里符号与字标是两组独立路径，`build_brand.py` 只搬字标的位置（`wordmark_w` / `wordmark_cap` 两个数）。

## 7. 禁止

- 拉伸、压扁；改线的粗细或把网点抹掉（那是原图的笔触）
- 深色线配深底、浅色线配浅底（对比度不够），深底一律用 `mark-reversed.svg`
- 加描边、投影、渐变、外发光
- 把网点灰当独立颜色用（它不是调色板，是描摹残留）
- 手改 `app/src/main/res` 或派生 SVG —— 改 `PARAMS` 重跑脚本

## 8. 已知限制

- **48px 以下不能用**。线稿的真机渲染（启动器 144px 那档）里线条约 1px：看得出「相机 + 手机 + 手」，取景框、按钮、网点全糊成灰
- **通知图标仍是相纸剪影**：24dp 单色放线稿必糊。通知栏放的是「正在接收」这个状态符号，不是品牌展示位，所以这里留着一张相纸
- favicon 16px 同理，只看得出一个灰点
- 手臂在母版右边与下边是**被画布切掉的**（原图如此），换构图要回栅格重描
- **未做商标检索**。对外发布前请查一次（商标库 + 反向图片搜索）—— 线稿类图形更要查
