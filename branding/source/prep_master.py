"""把 VTracer 描摹稿整理成方画布母版：透明底、外接框居中、填色分桶。

源 → 母版 → 派生 三段里的第一段（后两段在 ../build_brand.py）：

    k1jj-source.jpg  --VTracer-->  k1jj-v2.svg  --本脚本-->  mark.svg  --build_brand.py--> 全部资产

输入 branding/source/k1jj-v2.svg（1024x897），输出 temp/brand-source/master-*.svg 三档候选，
人工挑一档（现在是 3tone）另存成 branding/mark.svg，并把外接框尺寸记进 build_brand.py 的 PARAMS。
手工改过描摹稿（比如修描边接缝）之后重跑这里，再跑 build_brand.py。
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SVG = "{http://www.w3.org/2000/svg}"
SRC = Path("branding/source/k1jj-v2.svg")
OUT = Path("temp/brand-source")
CANVAS = 1024
INK = "#101820"      # 品牌墨蓝 cam_instrument
PAPER = "#FFFFFF"
EDGE = Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")


def lum(hexs: str) -> float:
    h = hexs.lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255


def collect(root):
    """按文档顺序收集 (path 元素, 生效 fill)，处理 <g fill=...> 继承。"""
    out = []

    def walk(el, inherited):
        fill = el.get("fill") or inherited
        for ch in el:
            if ch.tag == SVG + "path":
                out.append((ch, ch.get("fill") or fill))
            else:
                walk(ch, fill)

    walk(root, None)
    return out


def raster_bbox(svg: Path, w: int, h: int):
    """用 Edge 无头渲染出带 alpha 的 PNG，量真实外接框。

    路径数据里混着相对指令的增量，直接扫数字量的框会虚大（实测 1271x1118 vs 真值 1005x868）。
    """
    import subprocess
    from PIL import Image
    import numpy as np
    png = svg.with_suffix(".bbox.png")
    subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                    "--default-background-color=00000000", "--force-device-scale-factor=1",
                    f"--screenshot={png.resolve()}", f"--window-size={w},{h}",
                    f"file:///{svg.resolve().as_posix()}"], check=True, capture_output=True)
    a = np.array(Image.open(png).convert("RGBA"))
    ys, xs = np.nonzero(a[:, :, 3] > 8)
    return int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())


def build(paths, dark_th, light_th, mid_policy, transform):
    """dark_th/light_th 分桶；mid_policy = 'keep' 保留原色，'paper' 归白。"""
    body = []
    stats = {"ink": 0, "paper": 0, "mid": 0}
    for el, fill in paths:
        L = lum(fill)
        if L < dark_th:
            color, k = INK, "ink"
        elif L >= light_th:
            color, k = PAPER, "paper"
        elif mid_policy == "paper":
            color, k = PAPER, "paper"
        else:
            color, k = fill, "mid"
        stats[k] += 1
        d = el.get("d")
        body.append(f'  <path fill="{color}" d="{d}" />')
    dx, dy = transform
    svg = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{CANVAS}" height="{CANVAS}"'
        f' viewBox="0 0 {CANVAS} {CANVAS}">',
        f'  <g transform="translate({dx},{dy})">',
        *body,
        '  </g>',
        '</svg>',
        '',
    ]
    return "\n".join(svg), stats


def main():
    root = ET.parse(SRC).getroot()
    paths = collect(root)
    d_len = sum(len(el.get("d")) for el, _ in paths)
    print(f"path 数 {len(paths)}，d 总长 {d_len} 字节")

    x0, y0, x1, y1 = raster_bbox(SRC, 1024, 897)
    print(f"内容 bbox ({x0},{y0})-({x1},{y1}) = {x1-x0}x{y1-y0}")
    dx = CANVAS / 2 - (x0 + x1) / 2
    dy = CANVAS / 2 - (y0 + y1) / 2
    print(f"居中位移 translate({dx:.1f},{dy:.1f})")

    OUT.mkdir(parents=True, exist_ok=True)
    variants = [
        ("2tone", 0.5, 0.5, "paper"),
        ("3tone", 0.35, 0.8, "keep"),
        ("3tone-wide", 0.25, 0.7, "keep"),
    ]
    for name, dk, lt, mid in variants:
        svg, st = build(paths, dk, lt, mid, (round(dx, 2), round(dy, 2)))
        p = OUT / f"master-{name}.svg"
        p.write_text(svg, encoding="utf-8")
        print(f"{p}  {len(svg)} 字节  {st}")


if __name__ == "__main__":
    sys.exit(main())
