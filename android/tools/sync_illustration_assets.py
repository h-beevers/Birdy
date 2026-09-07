#!/usr/bin/env python3
"""Rebuild the Android app's bundled illustration assets from ``Illustrations/``.

The Android collage falls back to remote BirdWeather photos whenever a species
has no bundled illustration, which produces a mixed photo/illustration
wallpaper.  Bundling every illustration keeps the collage consistent.

Source art is ~1024px PNG/JPEG (~100 MB in total), far more than a sideload APK
needs, so each file is downscaled to fit ``MAX_EDGE`` and re-encoded as WebP.

Usage:  python3 android/tools/sync_illustration_assets.py [--check]
"""

from __future__ import annotations

import argparse
import os
import sys

try:
    from PIL import Image
except ImportError:  # pragma: no cover - developer tooling only
    sys.exit("Pillow is required: pip install pillow")

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SOURCE_DIR = os.path.join(REPO_ROOT, "Illustrations")
ASSET_DIR = os.path.join(
    REPO_ROOT, "android", "app", "src", "main", "assets", "illustrations"
)
SOURCE_EXTS = (".png", ".jpg", ".jpeg", ".webp")
# Files whose names are not species names (generator scratch output).
SKIP_PREFIXES = ("Gemini_Generated",)
MAX_EDGE = 512
WEBP_QUALITY = 88


def source_files() -> list[str]:
    names = []
    for fname in sorted(os.listdir(SOURCE_DIR)):
        if fname.startswith(SKIP_PREFIXES) or fname.startswith("."):
            continue
        if os.path.splitext(fname)[1].lower() not in SOURCE_EXTS:
            continue
        names.append(fname)
    return names


def convert(src_path: str, dest_path: str) -> None:
    with Image.open(src_path) as im:
        im.load()
        width, height = im.size
        scale = MAX_EDGE / max(width, height)
        if scale < 1:
            im = im.resize(
                (max(1, round(width * scale)), max(1, round(height * scale))),
                Image.LANCZOS,
            )
        # The illustrations are fully opaque (cream paper background baked in),
        # so an alpha channel would only add bytes.
        im = im.convert("RGB")
        im.save(dest_path, "WEBP", quality=WEBP_QUALITY, method=6)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="only report which species are missing from the bundled assets",
    )
    args = parser.parse_args()

    expected = {
        os.path.splitext(name)[0] + ".webp": name for name in source_files()
    }
    os.makedirs(ASSET_DIR, exist_ok=True)
    existing = set(os.listdir(ASSET_DIR))

    if args.check:
        missing = sorted(set(expected) - existing)
        extra = sorted(existing - set(expected))
        for name in missing:
            print(f"missing: {name}")
        for name in extra:
            print(f"stale:   {name}")
        if missing or extra:
            return 1
        print(f"{len(expected)} illustrations bundled, all in sync")
        return 0

    for name in sorted(existing - set(expected)):
        os.remove(os.path.join(ASSET_DIR, name))
        print(f"removed {name}")

    total = 0
    for dest_name, src_name in sorted(expected.items()):
        dest_path = os.path.join(ASSET_DIR, dest_name)
        convert(os.path.join(SOURCE_DIR, src_name), dest_path)
        total += os.path.getsize(dest_path)
    print(f"wrote {len(expected)} illustrations ({total / 1024:.0f} KiB) to {ASSET_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
