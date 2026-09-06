"""Lightweight helpers tests — no network, no GUI."""
import math
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import birdweather_local as b
from PIL import Image, ImageDraw, ImageFilter


class ParseBgColorTests(unittest.TestCase):
    def test_hex_and_presets(self):
        self.assertEqual(b.parse_bg_color("#c5d8e8"), (197, 216, 232))
        self.assertEqual(b.parse_bg_color("pastel_blue"), (197, 216, 232))
        self.assertEqual(b.parse_bg_color("blue"), (197, 216, 232))
        self.assertEqual(b.parse_bg_color("197, 216, 232"), (197, 216, 232))
        self.assertEqual(b.parse_bg_color("cream"), (244, 237, 224))
        self.assertEqual(b.parse_bg_color("nope"), (244, 237, 224))
        self.assertEqual(b.rgb_to_hex((197, 216, 232)), "#c5d8e8")


class ConfidenceFilterTests(unittest.TestCase):
    def test_keeps_missing_scores(self):
        nodes = [
            {"score": 0.9},
            {"score": 0.2},
            {"score": None},
            {},
        ]
        kept = b.filter_detections_by_confidence(nodes, 0.5)
        self.assertEqual(len(kept), 3)
        self.assertEqual(len(b.filter_detections_by_confidence(nodes, 0)), 4)


class DropShadowPaddingTests(unittest.TestCase):
    def test_soft_alpha_exists_outside_cutout_box(self):
        # Full opaque cutout (content cropped tight to edges) — the case
        # where an unpadded GaussianBlur clips corner falloff.
        cutout = Image.new("RGBA", (64, 64), (200, 100, 50, 255))
        size = min(cutout.size)
        blur = max(5, size * 0.03)
        offset = (max(2, round(size * 0.01)), max(3, round(size * 0.018)))
        pad = int(math.ceil(blur * 3)) + max(offset) + 2
        erode = max(3, round(size * 0.012)) | 1
        alpha = cutout.split()[-1].filter(ImageFilter.MinFilter(erode))
        eroded = alpha.point(lambda p: 32 if p > 0 else 0)
        padded = (cutout.width + 2 * pad, cutout.height + 2 * pad)
        shadow_alpha = Image.new("L", padded, 0)
        shadow_alpha.paste(eroded, (pad, pad))
        shadow = Image.new("RGBA", padded, (20, 15, 10, 0))
        shadow.putalpha(shadow_alpha)
        shadow = shadow.filter(ImageFilter.GaussianBlur(blur))
        outside = shadow.getpixel((pad - 3, pad + cutout.height // 2))[3]
        self.assertGreater(outside, 0)

    def test_canvas_darkens_beyond_tile(self):
        sq = Image.new("RGBA", (60, 60), (10, 10, 10, 255))
        canvas = Image.new("RGB", (200, 200), (244, 237, 224))
        b.add_drop_shadow(canvas, sq, 50, 50)
        self.assertNotEqual(canvas.getpixel((50 + 59 + 5, 50 + 30)), (244, 237, 224))


if __name__ == "__main__":
    unittest.main()
