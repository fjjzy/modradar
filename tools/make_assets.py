#!/usr/bin/env python3
"""生成 ModRadar 的图标与 README 预览图（可重复执行）。

产物：
  app/src/main/res/mipmap-*/ic_launcher.png            传统方形图标
  app/src/main/res/mipmap-*/ic_launcher_round.png      圆形图标
  app/src/main/res/mipmap-*/ic_launcher_foreground.png 自适应图标前景
  app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml   自适应图标
  docs/ui/preview.png                                   首页预览条（5 屏）
  docs/ui/gallery.png                                   全部 12 屏网格
"""

import math
import os

from PIL import Image, ImageChops, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

BG_TOP = (18, 18, 26)
BG_BOTTOM = (8, 8, 12)
ACCENT = (140, 140, 255)
ACCENT_DIM = (58, 58, 110)
BLIP = (255, 107, 107)

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

MASTER = 1024


def vertical_gradient(size, top, bottom):
    w, h = size
    strip = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        strip.putpixel((0, y), tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return strip.resize((w, h), Image.BILINEAR)


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return mask


def circle_mask(size):
    mask = Image.new("L", size, 0)
    d = ImageDraw.Draw(mask)
    d.ellipse([0, 0, size[0] - 1, size[1] - 1], fill=255)
    return mask


def draw_radar(size, scale=1.0, offset_y=0):
    """雷达主体：同心圈 + 扫描扇区 + 目标点。透明底。"""
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    cx, cy = size[0] // 2, size[1] // 2 + offset_y
    r_max = int(min(size) * 0.40 * scale)

    # 同心圈
    for i, frac in enumerate((0.42, 0.70, 1.0)):
        r = int(r_max * frac)
        color = ACCENT_DIM + (150 if i == 2 else 110,)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=color, width=max(2, int(size[0] * 0.008)))

    # 扫描扇区（用多层楔形模拟渐变）
    sweep_deg = 52
    start = -90 - sweep_deg
    steps = 46
    for i in range(steps):
        a0 = start + sweep_deg * i / steps
        a1 = start + sweep_deg * (i + 1) / steps
        alpha = int(8 + 120 * (i / steps) ** 2)
        d.pieslice(
            [cx - r_max, cy - r_max, cx + r_max, cy + r_max],
            start=a0, end=a1, fill=ACCENT + (alpha,),
        )

    # 扫描线
    ang = math.radians(-90)
    d.line(
        [cx, cy, cx + r_max * math.cos(ang), cy + r_max * math.sin(ang)],
        fill=ACCENT + (235,), width=max(3, int(size[0] * 0.012)),
    )

    # 目标点
    bx = cx + r_max * 0.62 * math.cos(math.radians(-38))
    by = cy + r_max * 0.62 * math.sin(math.radians(-38))
    br = max(4, int(size[0] * 0.032))
    d.ellipse([bx - br, by - br, bx + br, by + br], fill=BLIP + (255,))

    # 中心点
    cr = max(3, int(size[0] * 0.022))
    d.ellipse([cx - cr, cy - cr, cx + cr, cy + cr], fill=ACCENT + (255,))

    return layer


def make_master_icon():
    size = (MASTER, MASTER)
    base = vertical_gradient(size, BG_TOP, BG_BOTTOM).convert("RGBA")
    radar = draw_radar(size)
    # 轻微外发光，避免小尺寸下发灰
    glow = radar.filter(ImageFilter.GaussianBlur(MASTER * 0.02))
    base.alpha_composite(glow)
    base.alpha_composite(radar)
    return base


def save_sizes(img, mask_fn, out_pattern):
    for name, px in DENSITIES.items():
        out_dir = os.path.join(ROOT, "app/src/main/res", f"mipmap-{name}")
        os.makedirs(out_dir, exist_ok=True)
        small = img.resize((px, px), Image.LANCZOS)
        if mask_fn:
            m = mask_fn((px, px))
            out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
            out.paste(small, (0, 0), m)
        else:
            out = small
        out.save(os.path.join(out_dir, out_pattern))


def write_adaptive_xml():
    out_dir = os.path.join(ROOT, "app/src/main/res/mipmap-anydpi-v26")
    os.makedirs(out_dir, exist_ok=True)
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n'
    )
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(out_dir, name), "w", encoding="utf-8") as f:
            f.write(xml)


def write_colors():
    path = os.path.join(ROOT, "app/src/main/res/values/colors.xml")
    with open(path, "w", encoding="utf-8") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<resources>\n"
            '    <color name="ic_launcher_background">#0B0B12</color>\n'
            "</resources>\n"
        )


def build_icons():
    master = make_master_icon()

    # 传统方形（圆角）
    save_sizes(master, lambda s: rounded_mask(s, int(s[0] * 0.22)), "ic_launcher.png")
    # 圆形
    save_sizes(master, circle_mask, "ic_launcher_round.png")

    # 自适应前景：内容缩小到安全区（约 66%），透明底
    fg = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    inner = draw_radar((MASTER, MASTER), scale=0.66)
    fg.alpha_composite(inner)
    save_sizes(fg, None, "ic_launcher_foreground.png")

    write_adaptive_xml()
    write_colors()
    print("图标已生成：mipmap-* + anydpi-v26")


# ---------- README 预览图 ----------

# 深色截图的原生比例（1536x2752）
TARGET_RATIO = 1536 / 2752
DARK_BG = (8, 8, 12)


def _recolor_light_bg(im, thresh=64):
    """
    把与边缘连通的浅色背景泛洪成深色底。

    只影响从四角/边缘能连通到的区域，因此不会误伤界面内部的白字；
    部分出图带白底或浅色卡片，混排时非常跳。
    """
    w, h = im.size
    if not all(c > 200 for c in im.getpixel((2, 2))):
        return im
    out = im.copy()
    seeds = [
        (2, 2), (w - 3, 2), (2, h - 3), (w - 3, h - 3),
        (w // 2, 2), (w // 2, h - 3), (2, h // 2), (w - 3, h // 2),
    ]
    for pt in seeds:
        try:
            ImageDraw.floodfill(out, pt, DARK_BG, thresh=thresh)
        except Exception:
            pass
    return out


def _fit_ratio(im):
    """统一到目标比例，空白处填深色底。"""
    w, h = im.size
    if w / h > TARGET_RATIO:
        nh = int(w / TARGET_RATIO)
        canvas = Image.new("RGB", (w, nh), DARK_BG)
        canvas.paste(im, (0, (nh - h) // 2))
        return canvas
    nw = int(h * TARGET_RATIO)
    canvas = Image.new("RGB", (nw, h), DARK_BG)
    canvas.paste(im, ((nw - w) // 2, 0))
    return canvas


def load_screen(name, height):
    im = Image.open(os.path.join(ROOT, "docs/ui", name)).convert("RGB")
    im = _fit_ratio(_recolor_light_bg(im))
    w = int(im.width * height / im.height)
    return im.resize((w, height), Image.LANCZOS)


def rounded(im, radius):
    im = im.convert("RGBA")
    mask = rounded_mask(im.size, radius)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def build_preview():
    hero = [
        "01-dashboard.png", "06-report.png", "08-ai-workspace.png",
        "05-scanning.png", "04-ksu-modules.png",
    ]
    h = 760
    gap = 34
    pad = 44
    shots = [rounded(load_screen(n, h), 26) for n in hero]
    total_w = pad * 2 + sum(s.width for s in shots) + gap * (len(shots) - 1)
    canvas = Image.new("RGB", (total_w, h + pad * 2), DARK_BG)
    x = pad
    for s in shots:
        canvas.paste(s, (x, pad), s)
        x += s.width + gap
    canvas.save(os.path.join(ROOT, "docs/ui/preview.png"))
    print("preview.png", canvas.size)


def build_gallery():
    names = sorted(n for n in os.listdir(os.path.join(ROOT, "docs/ui")) if n.endswith(".png") and n[0].isdigit())
    cols = 4
    h = 470
    gap = 22
    pad = 30
    shots = [rounded(load_screen(n, h), 18) for n in names]
    cell_w = max(s.width for s in shots)
    rows = (len(shots) + cols - 1) // cols
    canvas = Image.new(
        "RGB",
        (pad * 2 + cols * cell_w + gap * (cols - 1), pad * 2 + rows * h + gap * (rows - 1)),
        DARK_BG,
    )
    for i, s in enumerate(shots):
        r, c = divmod(i, cols)
        x = pad + c * (cell_w + gap) + (cell_w - s.width) // 2
        y = pad + r * (h + gap)
        canvas.paste(s, (x, y), s)
    canvas.save(os.path.join(ROOT, "docs/ui/gallery.png"))
    print("gallery.png", canvas.size, f"{len(shots)} 屏")


if __name__ == "__main__":
    build_icons()
    build_preview()
    build_gallery()
