#!/usr/bin/env python3
"""
Turns a reseller's logo into the app's launcher icon.

minSdk is 26, so every device that runs this app supports adaptive icons: the icon is a
foreground layer floating over a background layer, and the launcher masks it into whatever
shape it likes (circle, squircle, teardrop). That means the only things a brand supplies are
its logo and its background colour.

The launcher can crop up to the outer ~18dp of a 108dp icon and animates within that margin,
so the logo is drawn at 60% of the canvas, centred — comfortably inside the 72dp safe zone
that is guaranteed to stay visible in every mask.

Legacy square/round bitmaps are regenerated too. Nothing on API 26+ uses them, but some
third-party launchers still read them, and an icon that is right everywhere costs one resize.

Usage:
    make_icons.py --logo logo.png --background '#6D28D9' --res app/src/main/res
"""

import argparse
import os
import sys

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit("Pillow is required:  pip install Pillow")

# Adaptive icons are 108dp; legacy launcher bitmaps are 48dp.
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
ADAPTIVE_DP = 108
LEGACY_DP = 48
DEFAULT_LOGO_FRACTION = 0.60  # of the 108dp canvas — inside the 72dp safe zone

BACKGROUND_XML = """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated per brand by tools/brand/make_icons.py -->
<color xmlns:android="http://schemas.android.com/apk/res/android"
    android:color="{color}" />
"""


def parse_color(value):
    v = value.strip().lstrip("#")
    if len(v) == 3:
        v = "".join(c * 2 for c in v)
    if len(v) != 6:
        raise ValueError("background colour must be #RGB or #RRGGBB, got %r" % value)
    return tuple(int(v[i:i + 2], 16) for i in (0, 2, 4))


def load_logo(path):
    logo = Image.open(path).convert("RGBA")
    # Trim fully transparent edges so the visual weight — not the exported canvas — is what
    # gets centred; logos exported from design tools are usually padded unevenly.
    box = logo.getbbox()
    return logo.crop(box) if box else logo


def fit(logo, target_px):
    """Scale the logo to fit a square of target_px, preserving aspect ratio."""
    w, h = logo.size
    scale = min(target_px / w, target_px / h)
    return logo.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS)


def centred(canvas_px, logo, bg=None):
    canvas = Image.new("RGBA", (canvas_px, canvas_px), bg or (0, 0, 0, 0))
    x = (canvas_px - logo.size[0]) // 2
    y = (canvas_px - logo.size[1]) // 2
    canvas.alpha_composite(logo, (x, y))
    return canvas


def write(img, res_dir, folder, name):
    out_dir = os.path.join(res_dir, folder)
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, name + ".png")
    img.save(path, "PNG", optimize=True)
    # A resource can't exist twice under one name: the checked-in .webp would collide with
    # the .png we just wrote and fail the resource merger.
    stale = os.path.join(out_dir, name + ".webp")
    if os.path.exists(stale):
        os.remove(stale)
    return path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--logo", required=True, help="square PNG, ideally with a transparent background")
    ap.add_argument("--background", default="#6D28D9", help="icon background colour, e.g. #6D28D9")
    ap.add_argument("--res", required=True, help="path to app/src/main/res")
    ap.add_argument(
        "--scale", type=float, default=DEFAULT_LOGO_FRACTION,
        help="logo size as a fraction of the icon canvas. The 0.60 default keeps a "
             "transparent mark inside the safe zone; artwork that is already a finished "
             "circular icon on its own dark ground wants 1.0 so the launcher's mask crops "
             "it directly instead of shrinking it onto a coloured square.")
    args = ap.parse_args()

    bg_rgb = parse_color(args.background)
    logo = load_logo(args.logo)
    written = 0

    for density, factor in DENSITIES.items():
        adaptive_px = round(ADAPTIVE_DP * factor)
        legacy_px = round(LEGACY_DP * factor)

        # Adaptive foreground: logo on transparency, the background layer shows through.
        fg = centred(adaptive_px, fit(logo, round(adaptive_px * args.scale)))
        write(fg, args.res, "drawable-" + density, "ic_launcher_foreground")
        written += 1

        # Legacy square: same logo, but the background has to be baked in.
        square = centred(legacy_px, fit(logo, round(legacy_px * min(1.0, args.scale + 0.10))), bg_rgb + (255,))
        write(square, args.res, "mipmap-" + density, "ic_launcher")
        written += 1

        # Legacy round: same again, masked to a circle.
        mask = Image.new("L", (legacy_px, legacy_px), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, legacy_px - 1, legacy_px - 1), fill=255)
        rounded = square.copy()
        rounded.putalpha(mask)
        write(rounded, args.res, "mipmap-" + density, "ic_launcher_round")
        written += 1

    # The checked-in foreground is a vector; a same-named PNG in drawable-*/ would collide.
    vector = os.path.join(args.res, "drawable", "ic_launcher_foreground.xml")
    if os.path.exists(vector):
        os.remove(vector)

    os.makedirs(os.path.join(args.res, "drawable"), exist_ok=True)
    bg_path = os.path.join(args.res, "drawable", "ic_launcher_background.xml")
    with open(bg_path, "w", encoding="utf-8") as f:
        f.write(BACKGROUND_XML.format(color="#%02X%02X%02X" % bg_rgb))

    print("icons: wrote %d bitmaps across %d densities, logo at %d%%, background %s"
          % (written, len(DENSITIES), round(args.scale * 100), "#%02X%02X%02X" % bg_rgb))


if __name__ == "__main__":
    main()
