"""Builds the iOS AppIcon.appiconset from the Android adaptive launcher icon.

Android adaptive icons are 108dp layers with the important content inside the
central 72dp safe zone. This script composites background + foreground at 1.5x
and center-crops to a full-bleed 1024x1024 iOS marketing icon.

Usage: python tools/make_app_icon.py
"""
import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC_DIR = os.path.join(ROOT, "app", "src", "main", "res", "mipmap-xxxhdpi")
OUT_DIR = os.path.join(
    ROOT, "ios", "YomiApp", "Resources", "Assets.xcassets", "AppIcon.appiconset"
)

SIZE = 1024
OVERSAMPLE = 1.5  # 108dp layer -> crop to 72dp safe zone at full bleed


def main() -> None:
    foreground = Image.open(os.path.join(SRC_DIR, "ic_launcher_foreground.png")).convert("RGBA")
    background = Image.open(os.path.join(SRC_DIR, "ic_launcher_background.png")).convert("RGBA")

    large = int(SIZE * OVERSAMPLE)
    fg = foreground.resize((large, large), Image.LANCZOS)
    bg = background.resize((large, large), Image.LANCZOS)

    canvas = Image.new("RGBA", (large, large), (12, 14, 20, 255))
    canvas = Image.alpha_composite(canvas, bg)
    canvas = Image.alpha_composite(canvas, fg)

    offset = (large - SIZE) // 2
    icon = canvas.crop((offset, offset, offset + SIZE, offset + SIZE))

    os.makedirs(OUT_DIR, exist_ok=True)
    icon.save(os.path.join(OUT_DIR, "AppIcon-1024.png"))
    print(f"wrote {os.path.join(OUT_DIR, 'AppIcon-1024.png')}")


if __name__ == "__main__":
    main()
