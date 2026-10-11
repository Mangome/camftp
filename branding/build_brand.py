#!/usr/bin/env python3
"""从 branding/mark.svg 派生全部标识资产：branding/ 的 SVG 与 app/src/main/res 的图标。

几何与颜色只写在下面 PARAMS 一处 —— 改标识改这里再跑这个脚本，别手改派生文件。

    python branding/build_brand.py             # 全量：SVG + app 资源 + 位图
    python branding/build_brand.py --svg-only  # 只出 SVG（没装 Chromium 的机器上跑这个）

位图（mipmap PNG / favicon.ico / mark-512.png）走无头 Chromium 渲染：Windows 上默认用 Edge，
换成别的 Chromium 用 CAMFTP_EDGE 指路径。不引 cairo / resvg 这类新依赖，SVG 才是源文件。
"""
from __future__ import annotations

import math
import os
import re
import subprocess
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
BRANDING = ROOT / "branding"
RES = ROOT / "app" / "src" / "main" / "res"
TMP = ROOT / "temp" / "brand-build"

PARAMS = {
    # 母版画布（branding/mark.svg）
    "canvas": 1024,
    # 颜色：线用品牌墨蓝、内填纸白；深底版把线换成浅色、内填留成真洞
    "ink": "#101820",
    "paper": "#FFFFFF",
    "ink_reversed": "#E9EFF3",
    # 分桶阈值（亮度 0–1）：< dark 归线，>= light 归白，夹在中间的抗锯齿灰原样保留
    "dark": 0.35,
    "light": 0.80,
    # 启动图标：母版 1024 → 51.2dp 居中；再往下压 icon_shift 画布单位（图标专属的视觉重心补偿）
    "icon_viewport": 108,
    "icon_scale": 0.05,
    "icon_shift": 71,
    # 母版内容外接框（母版画布坐标，量自渲染结果）：图标/legacy 的越界与安全圆靠它自检
    "content_bbox": (12.0, 92.5, 1012.0, 931.5),
    # legacy 图标（API < 26）：72 画布 = 自适应图标的可见区（72/108），白圆角方块 22% 圆角
    "legacy_viewport": 72,
    "legacy_radius": 0.22,
    # 字标：lockup 里 scale 0.052，ink 宽 = wordmark.svg 的 288 × 0.052/0.08，帽高 = 760 × 0.052
    "wordmark_w": 187.2,
    "wordmark_cap": 39.5,
    "wordmark_descender": 11.2,
    # 横排 lockup：符号块 96 见方居中于字标、间距 24、右边距 18
    "lockup_h": {"symbol": 96, "pad": 10, "gap": 24, "right": 18},
    # 堆叠 lockup：符号块 110 见方居中，字标压在下面
    "lockup_v": {"symbol": 110, "pad": 8, "gap": 19, "width": 340},
    # 各密度倍数
    "densities": [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0)],
    "legacy_dp": 48,
}
EDGE = os.environ.get("CAMFTP_EDGE", r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
SVG = "{http://www.w3.org/2000/svg}"
ET.register_namespace("", "http://www.w3.org/2000/svg")


# ---------------------------------------------------------------- 母版读写

def master() -> tuple[list[tuple[str, str]], tuple[float, float]]:
    """读母版：返回 ([(fill, d), ...], 母版自己的位移)。

    路径保持文档顺序（层叠顺序不能乱）；母版的位移必须一起带走 ——
    派生文件只拿路径、丢掉这个 translate 的话，内容就停在原始位置（实测高了 2.7dp）。
    """
    root = ET.parse(BRANDING / "mark.svg").getroot()
    out = [(el.get("fill"), el.get("d")) for el in root.iter(SVG + "path")]
    if not out:
        raise SystemExit("branding/mark.svg 里没有 path")
    g = root.find(SVG + "g")
    m = re.match(r"translate\(([-\d.]+),([-\d.]+)\)", (g.get("transform") if g is not None else "") or "")
    return out, (float(m.group(1)), float(m.group(2))) if m else (0.0, 0.0)


def lum(hexs: str) -> float:
    h = hexs.lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255


def classify(paths: list[tuple[str, str]]) -> tuple[set, set, list]:
    """按亮度分三档（按填色，不是按路径）：线 / 白 / 灰。

    灰是描摹留下的抗锯齿与网点，保持原色 —— 归到任何一边都会把网点压成硬块。
    """
    ink, paper, mid = set(), set(), []
    for fill, d in paths:
        L = lum(fill)
        if L < PARAMS["dark"]:
            ink.add(fill)
        elif L >= PARAMS["light"]:
            paper.add(fill)
        else:
            mid.append((fill, d))
    return ink, paper, mid


def emit(paths, mapper, indent="  ") -> list[str]:
    """按原顺序输出路径；mapper(fill) 返回 None 表示这条不画。"""
    out = []
    for fill, d in paths:
        f = mapper(fill)
        if f:
            out.append(f'{indent}<path d="{d}" fill="{f}" />')
    return out


def emit_ds(ds, fill, indent="    ") -> list[str]:
    return [f'{indent}<path d="{d}" fill="{fill}" />' for d in ds]


def fmt(v: float) -> str:
    return f"{v:.4f}".rstrip("0").rstrip(".") or "0"


def canvas_group(paths, off, mapper, k: float, tx: float, ty: float, indent="  ") -> list[str]:
    """把母版放进一个坐标系：缩放 k，画布原点落在 (tx,ty)，母版自己的位移折算进 translate。

    mapper 为 None 时保留原填色（前景层要层层叠回原图），否则用它换色。
    """
    dx, dy = tx + off[0] * k, ty + off[1] * k
    body = emit(paths, mapper or (lambda f: f), indent + "  ")
    if k == 1 and dx == 0 and dy == 0:
        return body
    return [f'{indent}<g transform="translate({fmt(dx)},{fmt(dy)}) scale({fmt(k)})">',
            *body, f"{indent}</g>"]


def subtract(ink_paths, hole_paths, off, fill: str, w: float, h: float,
             k: float, tx: float, ty: float) -> list[str]:
    """层叠描摹出来的底层层是一整块剪影：要得到线条，得把“白”那一层挖成真洞。

    洞用 mask（浏览器都支持）；挖洞那层与线条层必须同一套坐标变换，
    否则洞落在遮罩区域外面，等于没挖。
    """
    return [
        "  <defs>",
        f'    <mask id="holes" maskUnits="userSpaceOnUse" x="0" y="0" width="{w:g}" height="{h:g}">',
        f'      <rect width="{w:g}" height="{h:g}" fill="#fff" />',
        *canvas_group(hole_paths, off, lambda f: "#000", k, tx, ty, "      "),
        "    </mask>",
        "  </defs>",
        '  <g mask="url(#holes)">',
        *canvas_group(ink_paths, off, lambda f: fill, k, tx, ty, "    "),
        "  </g>",
    ]


def svg_file(title: str, w: float, h: float, body: list[str], comment: str = "") -> str:
    head = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w:g} {h:g}"'
            f' width="{w:g}" height="{h:g}" role="img" aria-label="{title}">',
            f"  <title>{title}</title>"]
    if comment:
        head.append("  <!-- " + comment.strip().replace("\n", "\n       ") + " -->")
    return "\n".join([*head, *body, "</svg>", ""])


def mappers(paths):
    """换色规则：墨蓝版（图标前景 / lockup）。"""
    ink_set, paper_set, _ = classify(paths)

    def to_ink(f):
        return PARAMS["ink"] if f in ink_set else (PARAMS["paper"] if f in paper_set else f)

    return to_ink


def build_svgs(paths, off) -> dict[Path, str]:
    ink_set, paper_set, _ = classify(paths)
    ink_paths = [(f, d) for f, d in paths if f in ink_set]
    hole_paths = [(f, d) for f, d in paths if f in paper_set]
    canvas = PARAMS["canvas"]
    vp, s, shift = PARAMS["icon_viewport"], PARAMS["icon_scale"], PARAMS["icon_shift"]
    loff = (vp - canvas * s) / 2                       # 母版画布在图标画布里的位置
    ty = loff + shift * s                              # 图标里再往下压一档（视觉重心）
    lvp = PARAMS["legacy_viewport"]
    lvp_off = (lvp - canvas * s) / 2
    x0, y0, x1, y1 = PARAMS["content_bbox"]
    top, bottom = y0 * s + ty, y1 * s + ty
    radius = max(math.hypot(px * s + loff - vp / 2, py * s + ty - vp / 2)
                 for px, py in ((x0, y0), (x1, y0), (x0, y1), (x1, y1)))
    out: dict[Path, str] = {}
    out[BRANDING / "mark-reversed.svg"] = svg_file(
        "CamFtp 符号（深底反白）", canvas, canvas,
        subtract(ink_paths, hole_paths, off, PARAMS["ink_reversed"], canvas, canvas, 1, 0, 0),
        comment="深底用：线换浅色，白内填挖成真洞（洞透出底色）。灰网点原样保留。")
    out[BRANDING / "icon-background.svg"] = svg_file(
        "CamFtp 图标背景层", vp, vp,
        [f'  <path d="M0,0h{vp}v{vp}h-{vp}z" fill="{PARAMS["paper"]}" />'],
        comment=f"底色吃满 {vp}dp。线稿是墨蓝的，底色必须亮。")
    out[BRANDING / "icon-foreground.svg"] = svg_file(
        "CamFtp 图标前景层", vp, vp, canvas_group(paths, off, None, s, loff, ty, "  "),
        comment=f"母版 {canvas} 缩到 {canvas * s:g}dp（缩放 {s}），再往下压 icon_shift={shift:g}\n"
                f"       （{shift * s:.2f}dp，图标专属的视觉重心补偿，不是母版的居中位移）。\n"
                f"       内容高 {bottom - top:.1f}dp，可见区（中央 72dp）上留 {top - vp / 2 + 36:.1f}dp / 下留 {90 - bottom:.1f}dp；\n"
                f"       外接圆 {radius:.1f}dp，正圆遮罩 36dp —— 再放大就切到手臂，再下压就顶到底。")
    out[BRANDING / "icon-full.svg"] = svg_file(
        "CamFtp 图标", vp, vp,
        [f'  <path d="M0,0h{vp}v{vp}h-{vp}z" fill="{PARAMS["paper"]}" />',
         *canvas_group(paths, off, None, s, loff, ty, "  ")],
        comment="底色层 + 前景层预合成（预览与商店物料用）。")
    out[BRANDING / "icon-monochrome.svg"] = svg_file(
        "CamFtp 图标主题层", vp, vp,
        subtract(ink_paths, hole_paths, off, PARAMS["ink"], vp, vp, s, loff, ty),
        comment="主题图标层：只留线 —— 系统按不透明区染色，白内填与灰网点会被涂成实心块。\n"
                "       描摹结果的底层是一整块剪影，线条靠去掉“白”那一层得到（所以有 mask）。")
    rad = lvp * PARAMS["legacy_radius"]
    out[BRANDING / "icon-legacy.svg"] = svg_file(
        "CamFtp legacy 图标", lvp, lvp,
        [f'  <rect width="{lvp}" height="{lvp}" rx="{rad:g}" fill="{PARAMS["paper"]}" />',
         *canvas_group(paths, off, None, s, lvp_off, lvp_off + shift * s, "  ")],
        comment=f"自适应图标的可见区（{lvp}/108）缩到 {lvp} 画布，构图与圆遮罩下看到的一致。")
    return out


# ---------------------------------------------------------------- lockup

def extract_group(text: str, start: int) -> str:
    """从 start 处的 <g 起，按 <g/</g> 配平取整个分组块。"""
    depth, i = 0, start
    while i < len(text):
        ng, nc = text.find("<g", i), text.find("</g>", i)
        if nc == -1:
            break
        if ng != -1 and ng < nc:
            depth += 1
            i = ng + 2
        else:
            depth -= 1
            i = nc + 4
            if depth == 0:
                return text[start:i]
    raise SystemExit("lockup 里的分组标签没配平")


def wordmark_group(path: Path, x: float, y: float) -> str:
    """从现有 lockup 里取出字标那组（只搬位置，不动字形）。"""
    text = path.read_text(encoding="utf-8")
    start = text.index("<g", text.index("</g>") + 4)
    return re.sub(r'transform="translate\([^)]*\)"', f'transform="translate({x:g},{y:g})"',
                  extract_group(text, start), count=1)


def build_lockups(paths, off) -> dict[Path, str]:
    def symbols(size: float, x: float, y: float) -> list[str]:
        return canvas_group(paths, off, None, size / PARAMS["canvas"], x, y, "  ")

    cap, wmw = PARAMS["wordmark_cap"], PARAMS["wordmark_w"]
    lh, lv = PARAMS["lockup_h"], PARAMS["lockup_v"]

    # 横排：符号块竖直居中于字标（字标的视觉中心 = 基线 - 帽高/2）
    baseline_h = 83.564                      # 沿用原字标的基线，字形位不变
    sym_y = baseline_h - cap / 2 - lh["symbol"] / 2
    wm_x = lh["pad"] + lh["symbol"] + lh["gap"]
    width_h = wm_x + wmw + lh["right"]
    out_h = svg_file("CamFtp 横排 lockup", width_h, 130, [
        *symbols(lh["symbol"], lh["pad"], sym_y),
        "  " + wordmark_group(BRANDING / "lockup-horizontal.svg", wm_x, baseline_h),
    ], comment=f"符号块 {lh['symbol']:g} 见方，视觉中心对齐字标；几何见 README §6。")

    # 堆叠：符号块居中在上，字标压在下面
    baseline_v = lv["pad"] + lv["symbol"] + lv["gap"] + cap
    height_v = baseline_v + PARAMS["wordmark_descender"] + lv["pad"]
    out_v = svg_file("CamFtp 堆叠 lockup", lv["width"], height_v, [
        *symbols(lv["symbol"], (lv["width"] - lv["symbol"]) / 2, lv["pad"]),
        "  " + wordmark_group(BRANDING / "lockup-stacked.svg",
                             (lv["width"] - wmw) / 2, baseline_v),
    ], comment="方形 / 窄栏用。")
    return {BRANDING / "lockup-horizontal.svg": out_h, BRANDING / "lockup-stacked.svg": out_v}


# ---------------------------------------------------------------- App 资源

def build_res() -> dict[Path, str]:
    vp = PARAMS["icon_viewport"]
    icon = f"""<?xml version="1.0" encoding="utf-8"?>
<!-- 自适应图标：纸白底色层 + 位图前景层 + 位图主题层。
     前景 / 主题层不用矢量：线稿有 1150 条路径（约 320KB 路径数据），矢量每次冷启动都要解析。
     位图由 branding/build_brand.py 从 branding/icon-*.svg 出，换密度重跑脚本。 -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""
    return {
        RES / "drawable" / "ic_launcher_background.xml": f"""<?xml version="1.0" encoding="utf-8"?>
<!-- 图标底色：纸白。线稿是墨蓝的，底色必须亮（母版 branding/icon-background.svg）。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{vp}dp"
    android:height="{vp}dp"
    android:viewportWidth="{vp}"
    android:viewportHeight="{vp}">
    <path
        android:fillColor="{PARAMS['paper']}FF"
        android:pathData="M0,0h{vp}v{vp}h-{vp}z" />
</vector>
""",
        RES / "mipmap-anydpi-v26" / "ic_launcher.xml": icon,
        RES / "mipmap-anydpi-v26" / "ic_launcher_round.xml": icon,
        # 前景 / 主题层改成位图后，这两份矢量资源作废
        RES / "drawable" / "ic_launcher_foreground.xml": "",
        RES / "drawable" / "ic_launcher_monochrome.xml": "",
    }


# ---------------------------------------------------------------- 位图

def render(svg: Path, size: int, out: Path) -> None:
    if not Path(EDGE).exists():
        raise SystemExit(f"找不到 Chromium：{EDGE}（用 CAMFTP_EDGE 指一个，或加 --svg-only）")
    TMP.mkdir(parents=True, exist_ok=True)
    html = TMP / f"_wrap_{svg.stem}_{size}.html"
    html.write_text(f'<style>*{{margin:0;padding:0}}html,body{{background:transparent}}'
                    f'img{{display:block;width:{size}px;height:{size}px}}</style>'
                    f'<img src="{svg.resolve().as_posix()}">', encoding="utf-8")
    subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                    "--default-background-color=00000000", "--force-device-scale-factor=1",
                    f"--screenshot={out.resolve()}", f"--window-size={size},{size}",
                    f"file:///{html.resolve().as_posix()}"], check=True, capture_output=True)
    im = Image.open(out).convert("RGBA")
    if im.getextrema()[3] == (255, 255):     # 全幅白底的图不存 alpha 通道
        im = im.convert("RGB")
    im.save(out, optimize=True)


def build_pngs() -> None:
    jobs = []
    for name, d in PARAMS["densities"]:
        big = round(PARAMS["icon_viewport"] * d)
        jobs += [(BRANDING / "icon-legacy.svg", round(PARAMS["legacy_dp"] * d),
                  RES / f"mipmap-{name}" / "ic_launcher.png"),
                 (BRANDING / "icon-foreground.svg", big,
                  RES / f"mipmap-{name}" / "ic_launcher_foreground.png"),
                 (BRANDING / "icon-monochrome.svg", big,
                  RES / f"mipmap-{name}" / "ic_launcher_monochrome.png")]
    jobs.append((BRANDING / "mark.svg", 512, BRANDING / "png" / "mark-512.png"))
    for svg, size, out in jobs:
        render(svg, size, out)
        print(f"  {out.relative_to(ROOT)}  {size}px  {out.stat().st_size // 1024}KB")
    fav = TMP / "favicon-256.png"
    render(BRANDING / "icon-legacy.svg", 256, fav)
    Image.open(fav).convert("RGBA").save(BRANDING / "png" / "favicon.ico",
                                         sizes=[(16, 16), (32, 32), (48, 48)])
    print(f"  branding/png/favicon.ico  {(BRANDING / 'png' / 'favicon.ico').stat().st_size} 字节")


def verify(files: dict[Path, str], paths, off) -> None:
    """生成完自己验一遍：换色、真洞、条数、几何、位图有没有落地 —— 错了当场报。"""
    ink_set, paper_set, mid = classify(paths)
    n_ink = sum(1 for f, _ in paths if f in ink_set)
    n_paper = sum(1 for f, _ in paths if f in paper_set)
    rev = files[BRANDING / "mark-reversed.svg"]
    fg = files[BRANDING / "icon-foreground.svg"]
    mono = files[BRANDING / "icon-monochrome.svg"]
    # 只查画出来的那半（defs 里是挖洞用的遮罩，填黑是遮罩语义）
    rev_drawn = rev.split("</defs>")[-1]
    mono_drawn = mono.split("</defs>")[-1]
    assert PARAMS["ink_reversed"] in rev_drawn, "反白版没换成浅色"
    assert PARAMS["paper"] not in rev_drawn, "反白版把白内填当图形画了（应该是真洞）"
    assert PARAMS["ink"] in fg, "前景层没换成品牌墨蓝"
    assert mono_drawn.count("<path") == n_ink, f"主题层画了 {mono_drawn.count('<path')} 条，应为 {n_ink} 条"
    assert PARAMS["paper"] not in mono_drawn, "主题层画了白色（会被系统一并染色）"
    # 几何：母版位移必须真的进了派生文件，且内容不出画布、不越正圆遮罩
    canvas = PARAMS["canvas"]
    vp, s, shift = PARAMS["icon_viewport"], PARAMS["icon_scale"], PARAMS["icon_shift"]
    loff = (vp - canvas * s) / 2
    ty = loff + shift * s
    x0, y0, x1, y1 = PARAMS["content_bbox"]
    want = f'translate({fmt(loff + off[0] * s)},{fmt(ty + off[1] * s)}) scale({fmt(s)})'
    assert want in fg, f"前景层少了母版位移（要 {want}）"
    assert 0 <= x0 * s + loff and x1 * s + loff <= vp, "内容横向出画布"
    assert 0 <= y0 * s + ty and y1 * s + ty <= vp, "内容纵向出画布"
    radius = max(math.hypot(px * s + loff - vp / 2, py * s + ty - vp / 2)
                 for px, py in ((x0, y0), (x1, y0), (x0, y1), (x1, y1)))
    assert radius <= 36, f"内容外接圆 {radius:.1f}dp 超出正圆遮罩 36dp"
    center_dy = (y0 + y1) / 2 * s + ty - vp / 2
    assert abs(center_dy - shift * s) < 0.01, f"图标里内容没按下压量居中（偏 {center_dy:.2f}dp）"
    for f in files:
        if f.suffix == ".svg" and f.parent == BRANDING and f.name != "icon-background.svg":
            assert f.stat().st_size > 10_000, f"{f.name} 只有 {f.stat().st_size} 字节"
    app_pngs = sorted(RES.glob("mipmap-*/ic_launcher*.png"))
    assert len(app_pngs) == 15, f"mipmap 位图 {len(app_pngs)} 张，应为 15 张"
    assert all(p.stat().st_size > 200 for p in app_pngs), "有空的 mipmap 位图"
    print(f"  自检通过：线 {n_ink} 条 / 白 {n_paper} 条 / 灰 {len(mid)} 条；"
          f"图标里内容外接圆 {radius:.1f}dp、重心比画布中心低 {center_dy:.2f}dp；mipmap {len(app_pngs)} 张")


def main() -> int:
    paths, off = master()
    print(f"母版 {len(paths)} 条路径，自身位移 translate({fmt(off[0])},{fmt(off[1])})")
    files = {**build_svgs(paths, off), **build_lockups(paths, off), **build_res()}
    for path, text in files.items():
        if text:
            path.write_text(text, encoding="utf-8")
            print(f"  写出 {path.relative_to(ROOT)}  {len(text) // 1024}KB")
        else:
            path.unlink(missing_ok=True)
            print(f"  删除 {path.relative_to(ROOT)}（改成位图了）")
    if "--svg-only" in sys.argv:
        print("位图：跳过（--svg-only）")
    else:
        print("位图：")
        build_pngs()
    verify(files, paths, off)
    return 0


if __name__ == "__main__":
    sys.exit(main())
