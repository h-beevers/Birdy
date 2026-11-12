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


class ScreenSizeTests(unittest.TestCase):
    def test_xrandr_prefers_the_primary_output(self):
        out = (
            "Screen 0: minimum 320 x 200, current 4480 x 1440\n"
            "HDMI-1 connected 1920x1080+2560+0 (normal left inverted) 527mm x 296mm\n"
            "DP-1 connected primary 2560x1440+0+0 (normal left inverted) 597mm x 336mm\n"
            "DP-2 disconnected (normal left inverted right x axis y axis)\n"
        )
        self.assertEqual(b.parse_xrandr(out), (2560, 1440))
        self.assertEqual(
            b.parse_xrandr("eDP-1 connected 1366x768+0+0 (normal) 309mm x 174mm\n"),
            (1366, 768),
        )
        self.assertIsNone(b.parse_xrandr("nothing useful"))

    def test_system_profiler_prefers_the_main_display(self):
        out = (
            "        LG HDR 4K:\n          Resolution: 3840 x 2160 (2160p/4K UHD 1)\n"
            "        Color LCD:\n          Resolution: 2880 x 1800 Retina\n"
            "          Main Display: Yes\n"
        )
        self.assertEqual(b.parse_system_profiler(out), (2880, 1800))
        self.assertEqual(
            b.parse_system_profiler("          Resolution: 1920 x 1080\n"), (1920, 1080)
        )

    def test_wallpaper_size_override(self):
        self.assertEqual(b.parse_wallpaper_size("2560", "1440"), (2560, 1440))
        self.assertEqual(b.parse_wallpaper_size("", ""), (None, None))
        self.assertEqual(b.parse_wallpaper_size("2560", ""), (None, None))
        self.assertEqual(b.parse_wallpaper_size("abc", "1440"), (None, None))
        self.assertEqual(b.parse_wallpaper_size("10", "10"), (None, None))

    def test_cutout_size_grows_with_the_screen(self):
        self.assertEqual(b.cutout_dim_for(1920, 1080), 453)
        self.assertEqual(b.cutout_dim_for(1280, 720), 420)
        self.assertEqual(b.cutout_dim_for(3840, 2160), 900)


class FlockPlacementTests(unittest.TestCase):
    def _pack(self, width, height, n=22):
        tiles = []
        for i in range(n):
            size = 90 + (i * 37) % 120
            cut = Image.new("RGBA", (size, int(size * 0.8)), (10, 10, 10, 255))
            tiles.append({
                "species": {"name": f"b{i}"}, "cutout": cut,
                "grid": b.dilate_grid(b.compute_silhouette_grid(cut), radius=1),
                "aspect": cut.width / cut.height, "count": 1,
                "w": float(cut.width), "h": float(cut.height),
            })
        top, mx, mb = 170, round(width * 0.02), round(height * 0.05)
        bx, by = b.flock_bias(width - 2 * mx, height - mb - top)
        placed = b.pack_flock(
            tiles, width / 2, top + (height - mb - top) / 2, width, height, top,
            aspect_bias=bx, aspect_bias_y=by, margin_x=mx, bottom_margin=mb)
        return placed, top, mx, mb

    def test_flock_stays_inside_margins_on_any_screen_shape(self):
        for width, height in ((1920, 1080), (3440, 1440), (1080, 1920), (1280, 1024)):
            placed, top, mx, mb = self._pack(width, height)
            self.assertEqual(len(placed), 22)
            for t in placed:
                self.assertGreaterEqual(t["x"], mx - 0.5, (width, height))
                self.assertGreaterEqual(t["y"], top - 0.5, (width, height))
                self.assertLessEqual(t["x"] + t["w"], width - mx + 0.5, (width, height))
                self.assertLessEqual(t["y"] + t["h"], height - mb + 0.5, (width, height))
            lo = min(t["x"] for t in placed)
            hi = max(t["x"] + t["w"] for t in placed)
            self.assertLess(abs((lo + hi) / 2 - width / 2), width * 0.08, (width, height))

    def test_portrait_screens_stretch_the_spiral_downwards(self):
        self.assertEqual(b.flock_bias(1920, 900), (1.8133333333333332, 1.0))
        bx, by = b.flock_bias(1000, 1700)
        self.assertEqual(bx, 1.0)
        self.assertAlmostEqual(by, 1.7)


class ClassicPackTests(unittest.TestCase):
    def setUp(self):
        import birdy_classic_pack as cp
        self.cp = cp
        self.manifest = cp.load_manifest()

    def test_bundled_index_is_pinned_and_resolves_names(self):
        m = self.manifest
        self.assertTrue(m)
        self.assertRegex(m.commit, r"^[0-9a-f]{40}$")
        self.assertGreater(len(m.species), 500)
        self.assertEqual(m.resolve("Erithacus rubecula"), "erithacus-rubecula")
        # BirdNET's older name for the jackdaw reaches the current plate.
        self.assertEqual(m.resolve("Corvus monedula"), "coloeus-monedula")
        self.assertIsNone(m.resolve("Notabird imaginarius"))
        self.assertIsNone(m.resolve(""))

    def test_urls_are_pinned_https_and_reject_odd_input(self):
        url = self.cp.url_for(self.manifest.commit, "pica-pica")
        self.assertTrue(url.startswith(
            "https://raw.githubusercontent.com/arnegiacomo/fugleramme/"
            + self.manifest.commit))
        self.assertTrue(url.endswith("/assets/artwork/classic/birds/pica-pica.webp"))
        with self.assertRaises(ValueError):
            self.cp.url_for("main", "pica-pica")
        with self.assertRaises(ValueError):
            self.cp.url_for(self.manifest.commit, "../secrets")

    def test_plates_are_downloaded_once_and_bad_payloads_rejected(self):
        import tempfile
        calls = []

        def fetch(url):
            calls.append(url)
            if "pica-pica" in url:
                return b"<html>not found</html>"
            return b"RIFF\x00\x00\x00\x00WEBPfake"

        with tempfile.TemporaryDirectory() as tmp:
            names = ["Erithacus rubecula", "Corvus monedula", "Coloeus monedula",
                     "Pica pica", "Notabird imaginarius"]
            paths, downloaded, failed = self.cp.ensure_many(
                self.manifest, names, tmp, fetch=fetch)
            self.assertEqual((downloaded, failed), (2, 1))
            self.assertEqual(paths["Corvus monedula"], paths["Coloeus monedula"])
            self.assertNotIn("Pica pica", paths)
            self.assertTrue(os.path.exists(os.path.join(tmp, "ATTRIBUTION.txt")))
            before = len(calls)
            self.cp.ensure_many(self.manifest, ["Erithacus rubecula"], tmp, fetch=fetch)
            self.assertEqual(len(calls), before)

    def test_art_style_decides_which_source_wins(self):
        species = {"name": "European Robin", "scientific": "Erithacus rubecula"}
        index = b.build_illustration_index(b.ILLUSTRATIONS_DIR)
        local = b.find_local_illustration("European Robin", index, "Erithacus rubecula")
        self.assertTrue(local)
        old_style, old_art = b.ART_STYLE, dict(b._CLASSIC_ART)
        try:
            b._CLASSIC_ART["European Robin"] = "/plates/erithacus-rubecula.webp"
            b.ART_STYLE = "classic"
            self.assertEqual(b.find_art(species, index), "/plates/erithacus-rubecula.webp")
            b.ART_STYLE = "birdy"
            self.assertEqual(b.find_art(species, index), local)
        finally:
            b.ART_STYLE = old_style
            b._CLASSIC_ART.clear()
            b._CLASSIC_ART.update(old_art)

    def test_plate_paper_takes_the_canvas_colour(self):
        plate = Image.new("RGBA", (120, 120), (0, 0, 0, 0))
        d = ImageDraw.Draw(plate)
        d.rectangle((20, 20, 100, 100), fill=(250, 246, 238, 255))   # scan paper
        d.rectangle((50, 50, 70, 70), fill=(40, 60, 120, 255))       # the bird
        out = b.level_plate_paper(plate, (244, 237, 224))
        self.assertEqual(out.getpixel((35, 35))[:3], (244, 237, 224))
        self.assertEqual(out.getpixel((60, 60)), (40, 60, 120, 255))
        self.assertEqual(out.getpixel((5, 5))[3], 0)
        self.assertLess(out.getpixel((20, 60))[3], 255)


if __name__ == "__main__":
    unittest.main()
