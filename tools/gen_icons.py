#!/usr/bin/env python3
"""Generates res/drawable vector icons and the launcher mipmaps for Nota."""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DRAWABLE = os.path.join(ROOT, "res", "drawable")

# The Material paths are mitred, so bare fills end in needle-sharp tips. Stroking each
# path with a round join blunts every corner by STROKE/2; the group scale takes back the
# width the stroke adds so the icons keep their original footprint.
STROKE = 0.9
SCALE = 0.93

TPL = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="{tint}">
    <group android:pivotX="12" android:pivotY="12"
        android:scaleX="{scale}" android:scaleY="{scale}">
        <path android:fillColor="#FFFFFFFF"
            android:strokeColor="#FFFFFFFF"
            android:strokeWidth="{stroke}"
            android:strokeLineCap="round"
            android:strokeLineJoin="round"
            android:pathData="{d}" />
    </group>
</vector>
'''

SECONDARY = "@color/text_secondary"
PRIMARY = "@color/text_primary"
ACCENT = "@color/accent"

ICONS = {
    "ic_play": (PRIMARY, "M8,5v14l11,-7z"),
    "ic_pause": (PRIMARY, "M6,19h4V5H6v14zm8,-14v14h4V5h-4z"),
    "ic_next": (PRIMARY, "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z"),
    "ic_prev": (PRIMARY, "M6,6h2v12H6zm3.5,6l8.5,6V6z"),
    "ic_stop": (PRIMARY, "M6,6h12v12H6z"),
    "ic_shuffle": (SECONDARY,
        "M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 "
        "17.96,7.46 20,9.5V4h-5.5zm0.33,9.41l-1.41,1.41 3.13,3.13L14.5,20H20v-5.5l-2.04,2.04 "
        "-3.13,-3.13z"),
    "ic_repeat": (SECONDARY,
        "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zm10,10H7v-3l-4,4 4,4v-3h12v-6h-2v4z"),
    "ic_repeat_one": (SECONDARY,
        "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zm10,10H7v-3l-4,4 4,4v-3h12v-6h-2v4zm-4,-2V9h-1l-2,1v1h1.5v4H13z"),
    "ic_note": (SECONDARY,
        "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 "
        "4,-1.79 4,-4V7h4V3h-6z"),
    "ic_album": (SECONDARY,
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zm0,14.5c-2.49,0 "
        "-4.5,-2.01 -4.5,-4.5S9.51,7.5 12,7.5s4.5,2.01 4.5,4.5 -2.01,4.5 -4.5,4.5zm0,-5.5c-0.55,0 "
        "-1,0.45 -1,1s0.45,1 1,1 1,-0.45 1,-1 -0.45,-1 -1,-1z"),
    "ic_artist": (SECONDARY,
        "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zm0,2c-2.67,0 "
        "-8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z"),
    "ic_folder": (SECONDARY,
        "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 "
        "-0.9,-2 -2,-2h-8l-2,-2z"),
    "ic_playlist": (SECONDARY,
        "M15,6H3v2h12V6zm0,4H3v2h12v-2zM3,16h8v-2H3v2zM17,6v8.18c-0.31,-0.11 -0.65,-0.18 "
        "-1,-0.18 -1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3V8h3V6h-5z"),
    "ic_queue": (SECONDARY, "M3,10h11v2H3v-2zm0,-4h11v2H3V6zm0,8h7v2H3v-2zm13,-1v8l6,-4 -6,-4z"),
    "ic_radio": (SECONDARY,
        "M3.24,6.15C2.51,6.43 2,7.17 2,8v12c0,1.1 0.89,2 2,2h16c1.11,0 2,-0.9 2,-2V8c0,-1.11 "
        "-0.89,-2 -2,-2H8.3l8.26,-3.34L15.88,1 3.24,6.15zM7,20c-1.66,0 -3,-1.34 -3,-3s1.34,-3 "
        "3,-3 3,1.34 3,3 -1.34,3 -3,3zm13,-8h-2v-2h-2v2H4V8h16v4z"),
    "ic_settings": (SECONDARY,
        "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58"
        "c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96"
        "c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84"
        "c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33"
        "c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58"
        "C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61"
        "l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54"
        "c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54"
        "c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32"
        "c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6"
        "s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z"),
    "ic_search": (SECONDARY,
        "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 "
        "3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5z"
        "m-6,0C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"),
    "ic_more": (SECONDARY,
        "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zm0,2c-1.1,0 -2,0.9 -2,2"
        "s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zm0,6c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 "
        "-0.9,-2 -2,-2z"),
    "ic_favorite": (ACCENT,
        "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 "
        "4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"),
    "ic_favorite_border": (SECONDARY,
        "M16.5,3c-1.74,0 -3.41,0.81 -4.5,2.09C10.91,3.81 9.24,3 7.5,3 4.42,3 2,5.42 2,8.5"
        "c0,3.78 3.4,6.86 8.55,11.54L12,21.35l1.45,-1.32C18.6,15.36 22,12.28 22,8.5 22,5.42 "
        "19.58,3 16.5,3zm-4.4,15.55l-0.1,0.1 -0.1,-0.1C7.14,14.24 4,11.39 4,8.5 4,6.5 5.5,5 "
        "7.5,5c1.54,0 3.04,0.99 3.57,2.36h1.87C13.46,5.99 14.96,5 16.5,5c2,0 3.5,1.5 3.5,3.5 "
        "0,2.89 -3.14,5.74 -7.9,10.05z"),
    "ic_back": (PRIMARY, "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"),
    "ic_chevron_down": (PRIMARY, "M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z"),
    "ic_close": (PRIMARY,
        "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 "
        "19,17.59 13.41,12z"),
    "ic_add": (SECONDARY, "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"),
    "ic_equalizer": (SECONDARY,
        "M3,17v2h6v-2H3zM3,5v2h10V5H3zm10,16v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zm14,4v-2"
        "H11v2h10zm-6,-4h2V7h4V5h-4V3h-2v6z"),
    "ic_lyrics": (SECONDARY, "M4,6h16v2H4zm0,5h16v2H4zm0,5h10v2H4z"),
    "ic_timer": (SECONDARY,
        "M15,1H9v2h6V1zm-4,13h2V8h-2v6zm8.03,-6.61l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41"
        "l-1.42,1.42C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9"
        "c0,-2.12 -0.74,-4.07 -1.97,-5.61zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 "
        "7,7 -3.13,7 -7,7z"),
    "ic_sort": (SECONDARY, "M3,18h6v-2H3v2zM3,6v2h18V6H3zm0,7h12v-2H3v2z"),
    "ic_delete": (SECONDARY,
        "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"),
    "ic_edit": (SECONDARY,
        "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 "
        "0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z"),
    "ic_refresh": (SECONDARY,
        "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -8,8s3.58,8 8,8c3.73,0 6.84,-2.55 "
        "7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 "
        "3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z"),
    "ic_share": (SECONDARY,
        "M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7"
        "s-0.04,-0.47 -0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3"
        "s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9"
        "c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16c-0.05,0.21 "
        "-0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92s2.92,-1.31 2.92,-2.92 -1.31,-2.92 "
        "-2.92,-2.92z"),
    "ic_info": (SECONDARY,
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zm1,15h-2v-6h2v6zm0,-8"
        "h-2V7h2v2z"),
}


def write_vectors():
    os.makedirs(DRAWABLE, exist_ok=True)
    for name, (tint, d) in ICONS.items():
        with open(os.path.join(DRAWABLE, name + ".xml"), "w") as f:
            f.write(TPL.format(tint=tint, d=d, stroke=STROKE, scale=SCALE))
    print("%d vector icons -> res/drawable" % len(ICONS))


def write_launcher():
    from PIL import Image, ImageDraw

    sizes = {"mipmap-hdpi": 72, "mipmap-xhdpi": 96,
             "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}
    for folder, size in sizes.items():
        s = size * 4  # supersample
        img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)

        # Rounded square with a vertical violet gradient.
        top, bottom = (124, 92, 255), (74, 44, 190)
        grad = Image.new("RGB", (1, s))
        gd = ImageDraw.Draw(grad)
        for y in range(s):
            t = y / (s - 1)
            gd.point((0, y), fill=tuple(
                int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
        grad = grad.resize((s, s))
        mask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(mask).rounded_rectangle(
            [0, 0, s - 1, s - 1], radius=int(s * 0.22), fill=255)
        img.paste(grad, (0, 0), mask)

        # Eighth note: stem, flag, filled head.
        white = (255, 255, 255, 255)
        stem_w = s * 0.075
        stem_x = s * 0.60
        d.rectangle([stem_x, s * 0.24, stem_x + stem_w, s * 0.70], fill=white)
        d.polygon([(stem_x + stem_w, s * 0.24),
                   (s * 0.80, s * 0.33),
                   (s * 0.80, s * 0.47),
                   (stem_x + stem_w, s * 0.38)], fill=white)
        head_w, head_h = s * 0.30, s * 0.22
        d.ellipse([stem_x + stem_w - head_w, s * 0.70 - head_h / 2,
                   stem_x + stem_w, s * 0.70 + head_h / 2], fill=white)

        img = img.resize((size, size), Image.LANCZOS)
        out = os.path.join(ROOT, "res", folder)
        os.makedirs(out, exist_ok=True)
        img.save(os.path.join(out, "ic_launcher.png"))

    # Monochrome status bar icon (notification icons must be white-on-alpha).
    for folder, base in sizes.items():
        size = int(base * 24 / 48)
        s = size * 8
        img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        white = (255, 255, 255, 255)
        stem_w, stem_x = s * 0.09, s * 0.56
        d.rectangle([stem_x, s * 0.14, stem_x + stem_w, s * 0.68], fill=white)
        d.polygon([(stem_x + stem_w, s * 0.14), (s * 0.84, s * 0.25),
                   (s * 0.84, s * 0.41), (stem_x + stem_w, s * 0.30)], fill=white)
        head_w, head_h = s * 0.34, s * 0.26
        d.ellipse([stem_x + stem_w - head_w, s * 0.68 - head_h / 2,
                   stem_x + stem_w, s * 0.68 + head_h / 2], fill=white)
        img = img.resize((size, size), Image.LANCZOS)
        img.save(os.path.join(ROOT, "res", folder, "ic_stat_note.png"))

    print("launcher + status bar icons -> res/mipmap-*")


if __name__ == "__main__":
    write_vectors()
    write_launcher()
