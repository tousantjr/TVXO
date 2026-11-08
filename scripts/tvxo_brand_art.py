#!/usr/bin/env python3
"""Regenerate every TVXO launcher icon, adaptive icon, TV banner and wordmark from one logo.

Source: branding/tvxo_logo_source.jpg (square artwork on a pure black background).
Every existing PNG is overwritten in place with the SAME pixel size and colour mode, so no
resource reference changes. Adaptive icons (API 26+) are written as mipmap-anydpi-v26 XML that
point at a generated transparent foreground plus a solid black background colour.

Run from the repo root:   python3 scripts/tvxo_brand_art.py [path/to/new_logo.jpg]
Also writes branding/tvxo_assets_preview.png (contact sheet of the main outputs).

If you swap in a logo with a different layout, adjust the LAYOUT boxes below (pixel boxes in the
source image, measured on a 1098x1098 original; they are scaled to the actual source size).
"""
import glob
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

SOURCE = "branding/tvxo_logo_source.jpg"
PREVIEW = "branding/tvxo_assets_preview.png"
REF = 1098.0
LAYOUT = {
    # (left, top, right, bottom) in the 1098px reference source
    "smiley": (395, 135, 728, 462),     # lime scribble smiley (with its glow)
    "wordmark": (205, 425, 880, 885),   # hot-pink "TVXO" brush script (with its glow)
    "core": (205, 135, 880, 885),       # smiley + wordmark together
}
ALPHA_FLOOR = 18          # source luminance treated as fully transparent (kills JPEG noise / haze)
ADAPTIVE_SAFE_DP = 66.0   # adaptive-icon safe-zone diameter inside the 108dp canvas
ADAPTIVE_MARGIN_DP = 4.0  # extra breathing room inside the safe zone
ICON_CROP = 790           # square crop (reference px) around the core used for legacy icons
FOREGROUND_SIZES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
FG_NAME = "tvxo_ic_launcher_foreground"
BG_COLOR_NAME = "tvxo_launcher_background"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


# ---------- helpers ----------
def box(name, scale):
    return tuple(int(round(v * scale)) for v in LAYOUT[name])


def extract_alpha(rgb, keep=None, feather=False):
    """Glow-on-black -> straight-alpha RGBA: alpha from brightness, colour un-premultiplied.

    Composited over black this reproduces the original; over any other dark colour it has no
    black fringe, because no black is baked into the colour channels.
    """
    a = np.asarray(rgb.convert("RGB")).astype(np.float32)
    mx = a.max(axis=2)
    alpha = np.clip((mx - ALPHA_FLOOR) / (255.0 - ALPHA_FLOOR), 0, 1)
    r, g = a[..., 0], a[..., 1]
    if keep == "pink":  # only the hot-pink wordmark (drops lime splatter dots inside its box)
        alpha = alpha * np.clip((r - g) / 60.0, 0, 1)
    elif keep == "green":  # only the lime smiley (drops the top of the pink "T" below it)
        alpha = alpha * np.clip((g - r + 40) / 60.0, 0, 1)
    if feather:  # soft elliptical edge so the smiley's glow never shows a rectangular crop line
        h, w = alpha.shape
        yy, xx = np.mgrid[0:h, 0:w]
        d = np.sqrt(((xx - w / 2) / (w / 2)) ** 2 + ((yy - h / 2) / (h / 2)) ** 2)
        alpha = alpha * np.clip((1.0 - d) / 0.15, 0, 1)
    color = a * (255.0 / np.maximum(mx, 1.0))[..., None]
    out = np.dstack([np.clip(color, 0, 255), alpha * 255]).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def resize_rgba(img, size):
    """Premultiplied resize so transparent pixels never bleed dark edges."""
    return img.convert("RGBa").resize(size, Image.LANCZOS).convert("RGBA")


def fit(img, max_w, max_h):
    s = min(max_w / img.width, max_h / img.height)
    return resize_rgba(img, (max(1, round(img.width * s)), max(1, round(img.height * s))))


def trim(img):
    bb = img.getchannel("A").point(lambda v: 255 if v > 8 else 0).getbbox()
    return img.crop(bb) if bb else img


def lockup(smiley, word, height, gap_frac=0.10):
    """Horizontal smiley + TVXO lockup on transparency, `height` px tall."""
    w = fit(word, 10**6, height)
    s = fit(smiley, 10**6, height * 0.86)
    gap = int(height * gap_frac)
    out = Image.new("RGBA", (s.width + gap + w.width, height), (0, 0, 0, 0))
    out.alpha_composite(s, (0, (height - s.height) // 2))
    out.alpha_composite(w, (s.width + gap, 0))
    return out


def on_black(art, size, pos=None):
    bg = Image.new("RGBA", size, (0, 0, 0, 255))
    if pos is None:
        pos = ((size[0] - art.width) // 2, (size[1] - art.height) // 2)
    bg.alpha_composite(art, pos)
    return bg


def test_band(img):
    w, h = img.size
    bh = max(6, h // 5)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, w, bh], fill=(255, 193, 7, 255))
    size = bh
    while size > 5:
        f = ImageFont.truetype(FONT, size) if os.path.exists(FONT) else ImageFont.load_default()
        l, t, r, b = d.textbbox((0, 0), "TEST", font=f)
        if r - l <= w * 0.8 and b - t <= bh * 0.8:
            break
        size -= 1
    d.text(((w - (r - l)) / 2 - l, (bh - (b - t)) / 2 - t), "TEST", font=f, fill=(0, 0, 0, 255))
    return img


def save_like(img, path, mode):
    img = img.convert(mode)
    img.save(path, optimize=True)


# ---------- asset builders ----------
class Art:
    def __init__(self, src_path):
        self.src = Image.open(src_path).convert("RGB")
        if self.src.width != self.src.height:
            raise SystemExit("logo must be square")
        sc = self.src.width / REF
        self.scale = sc
        self.full = extract_alpha(self.src)
        self.smiley_raw = extract_alpha(self.src.crop(box("smiley", sc)), keep="green", feather=True)
        self.word_raw = extract_alpha(self.src.crop(box("wordmark", sc)), keep="pink")
        self.smiley = trim(extract_alpha(self.src.crop(box("smiley", sc)), keep="green",
                                                feather=True))
        self.word = trim(extract_alpha(self.src.crop(box("wordmark", sc)), keep="pink"))
        core = box("core", sc)
        self.core_box = core
        self.core_center = ((core[0] + core[2]) / 2, (core[1] + core[3]) / 2)

    def square_crop(self, side_ref):
        """Square crop of the original (with splatter) centred on the core, padded with black."""
        side = int(side_ref * self.scale)
        cx, cy = self.core_center
        l, t = int(cx - side / 2), int(cy - side / 2)
        canvas = Image.new("RGB", (side, side), (0, 0, 0))
        canvas.paste(self.src.crop((max(l, 0), max(t, 0), min(l + side, self.src.width),
                                    min(t + side, self.src.height))), (max(-l, 0), max(-t, 0)))
        return canvas

    def icon(self, size):
        img = self.square_crop(ICON_CROP).resize(size, Image.LANCZOS)
        if size[0] <= 96:  # keep the thin scribbles crisp at launcher sizes
            img = img.filter(ImageFilter.UnsharpMask(radius=1, percent=60, threshold=2))
        return img.convert("RGBA")

    def adaptive_foreground(self, px):
        """Transparent 108dp foreground; all logo pixels inside the 66dp safe circle."""
        # smiley + wordmark only (outer splatter would just get clipped by launcher masks)
        l, t, r, b = self.core_box
        core = Image.new("RGBA", (r - l, b - t), (0, 0, 0, 0))
        for name, part in (("smiley", self.smiley_raw), ("wordmark", self.word_raw)):
            bl, bt = box(name, self.scale)[:2]
            core.alpha_composite(part, (bl - l, bt - t))
        bb = core.getchannel("A").point(lambda v: 255 if v > 24 else 0).getbbox()
        core = core.crop(bb)
        a = np.asarray(core.getchannel("A")) > 24
        ys, xs = np.nonzero(a)
        cx, cy = core.width / 2, core.height / 2
        radius = float(np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2).max())
        # source px covered by the full 108dp canvas so the art fits the (safe - margin) circle
        canvas_src = 2 * radius * 108.0 / (ADAPTIVE_SAFE_DP - 2 * ADAPTIVE_MARGIN_DP)
        s = px / canvas_src
        art = resize_rgba(core, (round(core.width * s), round(core.height * s)))
        out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        out.alpha_composite(art, ((px - art.width) // 2, (px - art.height) // 2))
        return out

    def splatter_background(self, size):
        """Black banner background: the logo's splatter frame split to the far left/right."""
        w, h = size
        clean = self.src.copy()
        ImageDraw.Draw(clean).rectangle(box("core", self.scale), fill=(0, 0, 0))
        side = h
        sq = clean.resize((side, side), Image.LANCZOS)
        bg = Image.new("RGB", size, (0, 0, 0))
        half = side // 2
        bg.paste(sq.crop((0, 0, half, side)), (0, 0))
        bg.paste(sq.crop((side - half, 0, side, side)), (w - half, 0))
        return bg.convert("RGBA")

    def banner(self, size):
        w, h = size
        bg = self.splatter_background(size)
        lk = lockup(self.smiley, self.word, int(h * 0.62))
        lk = fit(lk, w * 0.84, h * 0.62)
        # darken splatter behind the lockup so the wordmark always reads cleanly
        shade = Image.new("L", size, 0)
        ImageDraw.Draw(shade).ellipse([w / 2 - lk.width * 0.62, h / 2 - lk.height * 0.85,
                                       w / 2 + lk.width * 0.62, h / 2 + lk.height * 0.85], fill=255)
        shade = shade.filter(ImageFilter.GaussianBlur(h * 0.08))
        bg = Image.composite(Image.new("RGBA", size, (0, 0, 0, 255)), bg, shade)
        bg.alpha_composite(lk, ((w - lk.width) // 2, (h - lk.height) // 2))
        return bg

    def wordmark(self, size, with_smiley):
        w, h = size
        pad = int(h * 0.04)
        art = lockup(self.smiley, self.word, h) if with_smiley else self.word
        art = fit(art, w - 2 * pad, h - 2 * pad)
        out = Image.new("RGBA", size, (0, 0, 0, 0))
        out.alpha_composite(art, ((w - art.width) // 2, (h - art.height) // 2))
        return out

    def full_square(self, size):
        return self.src.resize(size, Image.LANCZOS).convert("RGBA")


# ---------- main ----------
def classify(path):
    name = os.path.basename(path)
    if name.startswith(FG_NAME):
        return None
    if name in ("app_logo_mark.png", "ic_launcher.png") and "drawable" in path:
        return "square"
    if "ic_launcher" in name:
        return "icon"
    if "banner" in name:
        return "banner"
    if name.startswith("livetv_wordmark"):
        return "wordmark_lockup"
    if "wordmark" in name:
        return "wordmark"
    return None


ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/tvxo_brand_art.py -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/{bg}" />
    <foreground android:drawable="@mipmap/{fg}" />
</adaptive-icon>
"""
COLOR_XML = """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/tvxo_brand_art.py -->
<resources>
    <color name="{bg}">#000000</color>
</resources>
"""


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else SOURCE
    art = Art(src)
    pngs = glob.glob("app/src/*/res/mipmap-*/*.png") + glob.glob("app/src/*/res/drawable*/*.png")
    done = []
    launcher_names = {}  # source set -> set of launcher resource names
    for p in sorted(pngs):
        kind = classify(p)
        if not kind:
            continue
        old = Image.open(p)
        size, mode = old.size, old.mode
        test = "/livetvTest/" in p
        if kind == "icon":
            img = art.icon(size)
            sset = p.split("/")[2]
            launcher_names.setdefault(sset, set()).add(os.path.splitext(os.path.basename(p))[0])
        elif kind == "banner":
            img = art.banner(size)
        elif kind == "square":
            img = art.full_square(size)
        else:
            img = art.wordmark(size, with_smiley=(kind == "wordmark_lockup"))
        if test and kind in ("icon", "banner"):
            img = test_band(img)
        save_like(img, p, mode)
        done.append((kind, size, p))

    # Adaptive icons: foreground PNGs (main + TEST-banded copy for test builds) and XML per name.
    for sset, test in (("main", False), ("livetvTest", True)):
        for dens, px in FOREGROUND_SIZES.items():
            d = f"app/src/{sset}/res/mipmap-{dens}"
            os.makedirs(d, exist_ok=True)
            fg = art.adaptive_foreground(px)
            if test:  # band sits at the top of the 108dp canvas; shift into the visible area
                band = test_band(Image.new("RGBA", (px, px), (0, 0, 0, 0)))
                band = band.crop((0, 0, px, max(6, px // 5)))
                fg.alpha_composite(band.resize((int(px * 0.62), int(band.height * 0.62))),
                                   (int(px * 0.19), int(px * 0.20)))
            p = f"{d}/{FG_NAME}.png"
            fg.save(p, optimize=True)
            done.append(("adaptive-fg", (px, px), p))
    os.makedirs("app/src/main/res/values", exist_ok=True)
    with open(f"app/src/main/res/values/{BG_COLOR_NAME}.xml", "w") as fh:
        fh.write(COLOR_XML.format(bg=BG_COLOR_NAME))
    for sset, names in launcher_names.items():
        if sset == "livetvTest":
            continue  # test builds reuse main/full XML; their foreground PNG is overridden instead
        d = f"app/src/{sset}/res/mipmap-anydpi-v26"
        os.makedirs(d, exist_ok=True)
        for n in sorted(names):
            with open(f"{d}/{n}.xml", "w") as fh:
                fh.write(ADAPTIVE_XML.format(bg=BG_COLOR_NAME, fg=FG_NAME))
            done.append(("adaptive", None, f"{d}/{n}.xml"))

    for kind, size, p in done:
        print(f"{kind:16s} {'' if size is None else f'{size[0]}x{size[1]}':10s} {p}")
    print(f"{len(done)} files written")
    make_preview(art)


def make_preview(art):
    W = 1600
    sheet = Image.new("RGB", (W, 1000), (32, 34, 40))
    d = ImageDraw.Draw(sheet)
    f = ImageFont.truetype(FONT, 22) if os.path.exists(FONT) else ImageFont.load_default()
    x = 30
    for p in ["app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
              "app/src/main/res/mipmap-xhdpi/ic_launcher.png",
              "app/src/main/res/mipmap-mdpi/ic_launcher.png"]:
        im = Image.open(p).convert("RGB")
        sheet.paste(im, (x, 60))
        d.text((x, 30), f"{im.width}px", font=f, fill="white")
        x += im.width + 30
    # adaptive previews: circle + squircle masks over black background
    fg = Image.open(f"app/src/main/res/mipmap-xxxhdpi/{FG_NAME}.png")
    for shape in ("circle", "rounded"):
        tile = on_black(fg, fg.size).convert("RGB")
        m = Image.new("L", fg.size, 0)
        md = ImageDraw.Draw(m)
        inset = int(fg.width * 18 / 108)  # launchers show the inner 72dp
        rect = [inset, inset, fg.width - inset, fg.height - inset]
        if shape == "circle":
            md.ellipse(rect, fill=255)
        else:
            md.rounded_rectangle(rect, radius=fg.width // 7, fill=255)
        tile = tile.crop(rect)
        m = m.crop(rect)
        sheet.paste(tile, (x, 60), m)
        d.text((x, 30), f"adaptive ({shape})", font=f, fill="white")
        x += tile.width + 30
    b = Image.open("app/src/main/res/mipmap-xhdpi/banner.png").convert("RGB")
    sheet.paste(b, (x, 60))
    d.text((x, 30), "TV banner 320x180", font=f, fill="white")
    big = Image.open("app/src/main/res/drawable/tv_banner.png").convert("RGB").resize((640, 360))
    sheet.paste(big, (30, 330))
    d.text((30, 300), "tv_banner 1280x720 (scaled)", font=f, fill="white")
    y = 330
    for p, bgc in [("app/src/main/res/drawable/livetv_wordmark_iptv.png", (13, 13, 13)),
                   ("app/src/main/res/drawable-nodpi/app_logo_wordmark.png", (13, 13, 13)),
                   ("app/src/main/res/drawable-nodpi/app_logo_wordmark.png", (40, 52, 72))]:
        wm = Image.open(p)
        wm = fit(wm, 850, 150)
        tile = Image.new("RGBA", (870, wm.height + 20), bgc + (255,))
        tile.alpha_composite(wm, (10, 10))
        sheet.paste(tile.convert("RGB"), (710, y))
        d.text((710, y - 26), f"{os.path.basename(p)} on #{'%02x%02x%02x' % bgc}", font=f, fill="white")
        y += tile.height + 50
    t = Image.open("app/src/livetvTest/res/mipmap-xxxhdpi/iptv_ic_launcher.png").convert("RGB")
    sheet.paste(t, (30, 760))
    d.text((30, 730), "test build icon", font=f, fill="white")
    sheet.save(PREVIEW, optimize=True)
    print(f"preview -> {PREVIEW}")


if __name__ == "__main__":
    main()
