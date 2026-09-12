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


class PeriodFromHoursTests(unittest.TestCase):
    def test_blank_and_zero_use_days(self):
        self.assertEqual(b.period_from_hours_and_days("", 3), (3, "day"))
        self.assertEqual(b.period_from_hours_and_days(None, 3), (3, "day"))
        self.assertEqual(b.period_from_hours_and_days("0", 3), (3, "day"))
        self.assertEqual(b.period_from_hours_and_days("-1", 3), (3, "day"))
        self.assertEqual(b.period_from_hours_and_days("12h", 3), (3, "day"))

    def test_positive_hours_override_days(self):
        self.assertEqual(b.period_from_hours_and_days("12", 3), (12, "hour"))
        self.assertEqual(b.period_from_hours_and_days(" 6 ", 1), (6, "hour"))


class DedupeSpeciesTests(unittest.TestCase):
    def test_dated_detection_replaces_undated(self):
        nodes = [
            {"timestamp": None, "species": {"commonName": "Robin"}},
            {"timestamp": "2026-09-12T10:00:00Z", "species": {"commonName": "Robin"}},
        ]
        out = b.dedupe_species(nodes)
        self.assertEqual(len(out), 1)
        self.assertIsNotNone(out[0]["ts"])

    def test_newer_timestamp_wins(self):
        nodes = [
            {"timestamp": "2026-09-12T08:00:00Z", "species": {"commonName": "Robin"}},
            {"timestamp": "2026-09-12T11:00:00Z", "species": {"commonName": "Robin"}},
        ]
        out = b.dedupe_species(nodes)
        self.assertEqual(out[0]["ts_raw"], "2026-09-12T11:00:00Z")


class PercentInConfigTests(unittest.TestCase):
    def test_percent_in_title_does_not_crash(self):
        import tempfile
        td = tempfile.mkdtemp()
        path = os.path.join(td, "config.ini")
        old = b.CONFIG_PATH
        try:
            b.CONFIG_PATH = path
            b.save_user_config({"title_text": "Birds 100%"})
            cfg = b.load_user_config()
            self.assertEqual(cfg.get("title_text"), "Birds 100%")
        finally:
            b.CONFIG_PATH = old


class SaveConfigMergeTests(unittest.TestCase):
    def test_preserves_keys_not_in_payload(self):
        import tempfile
        td = tempfile.mkdtemp()
        path = os.path.join(td, "config.ini")
        old = b.CONFIG_PATH
        try:
            b.CONFIG_PATH = path
            b.save_user_config({
                "postcode": "SW1A 1AA",
                "fallback_lat": "51.5",
                "fallback_lon": "-0.12",
            })
            b.save_user_config({"postcode": "E1 6AN", "title_text": "Hi"})
            cfg = b.load_user_config()
            self.assertEqual(cfg.get("postcode"), "E1 6AN")
            self.assertEqual(cfg.get("fallback_lat"), "51.5")
            self.assertEqual(cfg.get("fallback_lon"), "-0.12")
            self.assertEqual(cfg.get("title_text"), "Hi")
        finally:
            b.CONFIG_PATH = old


class SafeDownloadUrlTests(unittest.TestCase):
    def test_http_https_only(self):
        self.assertTrue(b.is_safe_download_url("https://example.com/a.png"))
        self.assertTrue(b.is_safe_download_url("http://example.com/a.png"))
        self.assertFalse(b.is_safe_download_url("file:///etc/passwd"))
        self.assertFalse(b.is_safe_download_url("javascript:alert(1)"))
        self.assertFalse(b.is_safe_download_url("data:image/png;base64,xx"))
        self.assertFalse(b.is_safe_download_url(""))


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
