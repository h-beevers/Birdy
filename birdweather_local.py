#!/usr/bin/env python3
"""
birdweather_local.py

Generates a static local HTML page showing recent bird detections near you,
pulled from BirdWeather's public GraphQL API. No mic, no Pi required — this
is a "phase 0" preview of what your BirdNET-Pi /dashboard will eventually
show, using BirdWeather's community station network in the meantime.

Usage:
    python3 birdweather_local.py

Config is at the top of the file.

Requires: pip install Pillow  (used for rendering the desktop wallpaper)

Data sources:
- Postcode -> lat/lon: api.postcodes.io (free, no key, UK only)
- Detections/stations: app.birdweather.com/graphql (free, public, no key)

Species thumbnails are served directly from BirdWeather and are separately
credited/licensed per-image (see imageCredit/imageLicense in the API) — this
script does NOT pull illustrations from the AvianVisitors GitHub repo, to
keep things simple and avoid getting the CC-BY-NC-SA attribution wrong.

Copyright (C) 2026 h-beevers

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <https://www.gnu.org/licenses/>.
"""

import configparser
import json
import math
import os
import re
import shutil
import base64
import subprocess
import sys
import textwrap
import urllib.request
import urllib.error
from io import BytesIO
from datetime import datetime, timezone
from collections import Counter

try:
    from PIL import Image, ImageDraw, ImageFont, ImageChops, ImageFilter, ImageEnhance
    PIL_AVAILABLE = True
except ImportError:
    PIL_AVAILABLE = False

import random

# ---------------------------------------------------------------------------
# CONFIG
# ---------------------------------------------------------------------------
# When run from source, everything below is edited directly in this file, as
# before. When run as the packaged Birdy.exe, the handful of per-user
# settings (postcode, radius, days) instead live in config.ini next to the
# exe — created by a one-time setup wizard on first launch, so nothing here
# needs editing at all. See app_dir()/resource_path()/load_user_config().

FROZEN = getattr(sys, "frozen", False)


def app_dir():
    """Directory the exe/script lives in — where config.ini, the log, the
    rendered wallpaper, and a user Illustrations/ folder should live.
    Deliberately NOT the same as resource_path()'s temp extraction dir."""
    if FROZEN:
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


def resource_path(*parts):
    """Path to a bundled read-only resource (e.g. the default Illustrations/
    shipped inside the exe via PyInstaller --add-data). Falls back to the
    source tree when not frozen."""
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, *parts)


CONFIG_PATH = os.path.join(app_dir(), "config.ini")

# Defaults used both as the source-run fallback and as the config.ini
# scaffold the setup wizard writes on first launch.
_DEFAULTS = {
    "postcode": "HG3 1AP",
    "fallback_lat": "53.93",
    "fallback_lon": "-1.45",
    "radius_km": "20",
    "days": "1",
    "hours": "",  # set to override `days` with a sub-day window, e.g. "12" or "6"
    "show_title": "true",
    "show_labels": "false",
}


def load_user_config():
    parser = configparser.ConfigParser()
    parser["birdy"] = dict(_DEFAULTS)
    if os.path.exists(CONFIG_PATH):
        parser.read(CONFIG_PATH)
    return parser["birdy"]


def save_user_config(values):
    parser = configparser.ConfigParser()
    parser["birdy"] = {**_DEFAULTS, **values}
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        parser.write(f)


_cfg = load_user_config()

POSTCODE = _cfg.get("postcode") or None   # Set to None to use fallback lat/lon
FALLBACK_LAT = _cfg.getfloat("fallback_lat")
FALLBACK_LON = _cfg.getfloat("fallback_lon")  # (Spofforth, North Yorkshire by default)

RADIUS_KM = _cfg.getint("radius_km")   # How far around the point to search
DAYS = _cfg.getint("days")             # How many days of detections to pull

# `hours` in config.ini overrides `days` with a sub-day window (e.g. "12",
# "6", "1") — BirdWeather's API takes a {count, unit} duration either way,
# so this just swaps which unit gets sent instead of bolting on a separate
# code path. Leave `hours` blank (the default) to use DAYS as before.
_hours_raw = (_cfg.get("hours") or "").strip()
if _hours_raw:
    PERIOD_COUNT = int(_hours_raw)
    PERIOD_UNIT = "hour"
else:
    PERIOD_COUNT = DAYS
    PERIOD_UNIT = "day"

MAX_SPECIES_CARDS = 40    # Cap on how many species cards to render (HTML view)

SCRIPT_DIR = app_dir()

# Folder of your own locally-generated illustrations (e.g. Gemini/Comfy
# output). Filenames just need to loosely match the species' common name —
# spaces, case, and punctuation don't matter (e.g. "Hooded Crow.png" or
# "hooded_crow.png" both match "Hooded Crow"). Falls back to the BirdWeather
# thumbnail for any species without a local match. Set to None to disable.
ILLUSTRATIONS_DIR = os.path.join(SCRIPT_DIR, "Illustrations")

# Render a JPG and set it as the actual Windows desktop wallpaper directly —
# no Lively involved. Requires: pip install Pillow
SET_DESKTOP_WALLPAPER = True
OUTPUT_IMAGE = os.path.join(SCRIPT_DIR, "birdweather_wallpaper.jpg")
# Leave as None to auto-detect your screen resolution.
IMAGE_WIDTH = None
IMAGE_HEIGHT = None
# Show the "Garden Visitors" title above the collage?
SHOW_TITLE = _cfg.getboolean("show_title")
# Show a small species name under each bird? The reference collage look
# (unlabelled flock) has this off by default.
SHOW_LABELS = _cfg.getboolean("show_labels")

OUTPUT_FILE = os.path.join(SCRIPT_DIR, "birdweather_snapshot.html")

POSTCODES_API = "https://api.postcodes.io/postcodes/{}"
BIRDWEATHER_GRAPHQL = "https://app.birdweather.com/graphql"

# ---------------------------------------------------------------------------
# First-run setup (packaged Birdy.exe only) — everything below this point is
# skipped entirely for a source run, which keeps editing this file at the top
# as the supported path for developers.
# ---------------------------------------------------------------------------

TASK_NAME = "Birdy Wallpaper Refresh"


def bootstrap_illustrations():
    """First launch of the exe: seed a writable Illustrations/ folder next
    to the exe from the read-only copy bundled inside it, so there's art to
    render immediately. Never overwrites — once the folder exists it's the
    user's to add to, same as the source-run workflow."""
    if os.path.exists(ILLUSTRATIONS_DIR):
        return
    bundled = resource_path("Illustrations")
    if os.path.isdir(bundled):
        shutil.copytree(bundled, ILLUSTRATIONS_DIR)
    else:
        os.makedirs(ILLUSTRATIONS_DIR, exist_ok=True)


def register_scheduled_task():
    """Registers (or replaces) a Windows Scheduled Task that reruns this
    same exe every 15 minutes, indefinitely, only while logged on — the
    setup this project's README always recommended doing by hand."""
    exe = os.path.abspath(sys.executable)
    subprocess.run(["schtasks", "/Create", "/F",
                     "/SC", "MINUTE", "/MO", "15",
                     "/TN", TASK_NAME,
                     "/TR", f'"{exe}"'],
                    check=True, capture_output=True, text=True)


def run_first_time_setup():
    """Tkinter wizard shown once, the first time Birdy.exe runs (i.e. no
    config.ini next to it yet). Asks just what a source-run user would
    otherwise edit at the top of this file — postcode, the title/label
    toggles, and whether to auto-refresh — then gets out of the way; every
    later launch is silent. Settings can be changed later by editing
    config.ini directly, no need to rerun this."""
    import tkinter as tk
    from tkinter import messagebox, simpledialog

    root = tk.Tk()
    root.withdraw()

    messagebox.showinfo(
        "Welcome to Birdy",
        "Birdy turns recent local bird sightings into your desktop "
        "wallpaper. Quick one-time setup, then it runs on its own.")

    postcode = simpledialog.askstring(
        "Birdy setup",
        "Your postcode (used only to find nearby BirdWeather stations —\n"
        "never sent anywhere except the free postcodes.io lookup):",
        initialvalue=_DEFAULTS["postcode"])
    if postcode is None:
        postcode = ""
    postcode = postcode.strip()

    show_title = messagebox.askyesno(
        "Birdy setup",
        "Show a \"Garden Visitors\" title above the collage?")

    show_labels = messagebox.askyesno(
        "Birdy setup",
        "Show each bird's species name underneath it?")

    values = {
        "postcode": postcode,
        "show_title": "true" if show_title else "false",
        "show_labels": "true" if show_labels else "false",
    }
    save_user_config(values)

    auto = messagebox.askyesno(
        "Birdy setup",
        "Refresh your wallpaper automatically every 15 minutes?\n\n"
        "This adds a Windows Scheduled Task named "
        f"\"{TASK_NAME}\" that reruns Birdy while you're logged on. "
        "You can remove it any time from Task Scheduler.")
    if auto:
        try:
            register_scheduled_task()
            messagebox.showinfo("Birdy setup", "Auto-refresh is set up. "
                                 "Setting your first wallpaper now...")
        except Exception as e:
            messagebox.showwarning(
                "Birdy setup",
                f"Couldn't create the scheduled task automatically ({e}).\n"
                "You can still set it up by hand — see the README — or "
                "just rerun Birdy.exe whenever you want a fresh wallpaper.")
    else:
        messagebox.showinfo("Birdy setup", "Setting your first wallpaper now. "
                             "Rerun Birdy.exe any time you want a fresh one — "
                             "see the README if you'd like it automatic.")

    root.destroy()

    global POSTCODE, SHOW_TITLE, SHOW_LABELS
    POSTCODE = postcode or None
    SHOW_TITLE = show_title
    SHOW_LABELS = show_labels


def http_post_json(url, payload, headers=None):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8"))


def http_get_json(url):
    req = urllib.request.Request(url, method="GET")
    req.add_header("User-Agent", "avianvisitors-local-preview/1.0")
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8"))


def lookup_postcode(postcode):
    """Returns (lat, lon, human place name) or raises."""
    url = POSTCODES_API.format(urllib.request.quote(postcode.strip()))
    result = http_get_json(url)
    r = result["result"]
    place = r.get("admin_ward") or r.get("parish") or r.get("admin_district") or postcode
    return r["latitude"], r["longitude"], place


def bounding_box(lat, lon, radius_km):
    """Rough equirectangular bounding box — fine at this scale."""
    lat_delta = radius_km / 111.0
    lon_delta = radius_km / (111.0 * max(0.1, math.cos(math.radians(lat))))
    return {
        "ne": {"lat": lat + lat_delta, "lon": lon + lon_delta},
        "sw": {"lat": lat - lat_delta, "lon": lon - lon_delta},
    }


DETECTIONS_QUERY = """
query recentNearby($ne: InputLocation, $sw: InputLocation, $period: InputDuration, $first: Int) {
  detections(ne: $ne, sw: $sw, period: $period, first: $first) {
    totalCount
    speciesCount
    nodes {
      timestamp
      score
      species {
        commonName
        scientificName
        thumbnailUrl
        imageCredit
        imageLicense
      }
      station {
        name
        location
      }
    }
  }
  stations(ne: $ne, sw: $sw, first: 50) {
    totalCount
    nodes {
      name
      type
      latestDetectionAt
    }
  }
}
"""


def format_period(count, unit):
    return f"{count} {unit}" + ("" if count == 1 else "s")


def fetch_nearby(lat, lon, radius_km, period_count, period_unit="day", first=300):
    bbox = bounding_box(lat, lon, radius_km)
    variables = {
        "ne": bbox["ne"],
        "sw": bbox["sw"],
        "period": {"count": period_count, "unit": period_unit},
        "first": first,
    }
    payload = {"query": DETECTIONS_QUERY, "variables": variables}
    result = http_post_json(BIRDWEATHER_GRAPHQL, payload)
    if "errors" in result:
        raise RuntimeError(f"BirdWeather API returned errors: {result['errors']}")
    return result["data"]


def parse_timestamp(ts):
    if not ts:
        return None
    try:
        return datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except ValueError:
        return None


def count_species(detection_nodes):
    """How many times each species was actually detected — used to size
    tiles by frequency, same idea as the AvianVisitors collage."""
    counts = Counter()
    for node in detection_nodes:
        species = node.get("species") or {}
        name = species.get("commonName")
        if name:
            counts[name] += 1
    return counts


def dedupe_species(detection_nodes):
    """Keep the most recent detection per species."""
    best = {}
    for node in detection_nodes:
        species = node.get("species") or {}
        name = species.get("commonName")
        if not name:
            continue
        ts = parse_timestamp(node.get("timestamp"))
        if name not in best or (ts and best[name]["ts"] and ts > best[name]["ts"]):
            best[name] = {
                "name": name,
                "scientific": species.get("scientificName") or "",
                "thumb": species.get("thumbnailUrl") or "",
                "credit": species.get("imageCredit") or "",
                "license": species.get("imageLicense") or "",
                "station": (node.get("station") or {}).get("name") or "a nearby station",
                "ts": ts,
                "ts_raw": node.get("timestamp"),
                "score": node.get("score"),
            }
    ordered = sorted(
        best.values(),
        key=lambda d: d["ts"] or datetime.min.replace(tzinfo=timezone.utc),
        reverse=True,
    )
    return ordered


def relative_time(ts):
    if not ts:
        return "unknown time"
    now = datetime.now(timezone.utc)
    delta = now - ts
    hours = delta.total_seconds() / 3600
    if hours < 1:
        return "less than an hour ago"
    if hours < 24:
        return f"{int(hours)}h ago"
    days = int(hours / 24)
    return f"{days}d ago"


def _slugify(text):
    return re.sub(r"[^a-z0-9]+", "", text.lower())


def build_illustration_index(illustrations_dir):
    """Maps a normalized species-name slug -> absolute file path."""
    index = {}
    if not illustrations_dir or not os.path.isdir(illustrations_dir):
        return index
    for fname in os.listdir(illustrations_dir):
        base_name, ext = os.path.splitext(fname)
        if ext.lower() not in (".png", ".jpg", ".jpeg", ".webp"):
            continue
        slug = _slugify(base_name)
        index[slug] = os.path.join(illustrations_dir, fname)
    return index


def find_local_illustration(common_name, index):
    if not index:
        return None
    slug = _slugify(common_name)
    if slug in index:
        return index[slug]
    for key, path in index.items():
        if slug in key or key in slug:
            return path
    return None


_MIME_TYPES = {".png": "image/png", ".jpg": "image/jpeg",
               ".jpeg": "image/jpeg", ".webp": "image/webp"}


def embed_image_as_data_uri(file_path):
    """Reads a local image and returns a self-contained data: URI, so the
    HTML never depends on file:// subresource loading (which some webview
    hosts, including Lively, block or mishandle)."""
    ext = os.path.splitext(file_path)[1].lower()
    mime = _MIME_TYPES.get(ext, "application/octet-stream")
    with open(file_path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")
    return f"data:{mime};base64,{b64}"


CARD_TEMPLATE = """
<div class="card">
  <div class="cutout">
    {img_html}
  </div>
  <div class="label">
    <div class="common">{name}</div>
    <div class="sci">{scientific}</div>
    <div class="meta">{station} · {when}</div>
  </div>
</div>
"""

PAGE_TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Garden Visitors — local BirdWeather preview</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="900">
<style>
  :root {{
    --cream: #f4ede0;
    --ink: #2e2620;
    --rust: #b5572a;
    --moss: #5c6b47;
    --line: #ddd0b8;
  }}
  * {{ box-sizing: border-box; }}
  body {{
    margin: 0;
    background: var(--cream);
    color: var(--ink);
    font-family: 'Iowan Old Style', 'Palatino Linotype', Palatino, Georgia, serif;
    padding: 48px 32px 80px;
  }}
  header {{
    max-width: 900px;
    margin: 0 auto 40px;
    text-align: center;
  }}
  h1 {{
    font-size: 2.1rem;
    font-weight: 500;
    letter-spacing: 0.02em;
    margin: 0 0 8px;
  }}
  .sub {{
    color: #6b5f4f;
    font-size: 1rem;
    margin: 0;
  }}
  .stats {{
    display: flex;
    justify-content: center;
    gap: 28px;
    margin-top: 20px;
    font-size: 0.85rem;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--moss);
  }}
  .grid {{
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
    gap: 28px;
    max-width: 1100px;
    margin: 0 auto;
  }}
  .card {{
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
  }}
  .cutout {{
    width: 128px;
    height: 128px;
    border-radius: 50%;
    background: #fff;
    border: 1px solid var(--line);
    overflow: hidden;
    display: flex;
    align-items: center;
    justify-content: center;
    box-shadow: 0 2px 6px rgba(46,38,32,0.12);
  }}
  .cutout img {{
    width: 100%;
    height: 100%;
    object-fit: cover;
  }}
  .cutout .placeholder {{
    font-size: 2rem;
    color: var(--line);
  }}
  .label {{
    margin-top: 10px;
  }}
  .common {{
    font-weight: 600;
    font-size: 0.95rem;
  }}
  .sci {{
    font-style: italic;
    font-size: 0.78rem;
    color: #8a7c65;
  }}
  .meta {{
    font-size: 0.72rem;
    color: var(--rust);
    margin-top: 2px;
  }}
  footer {{
    max-width: 900px;
    margin: 56px auto 0;
    text-align: center;
    font-size: 0.75rem;
    color: #8a7c65;
    line-height: 1.6;
  }}
  footer a {{ color: var(--rust); }}
</style>
</head>
<body>
<header>
  <h1>Garden Visitors</h1>
  <p class="sub">Recent detections within {radius} km of {place}, last {period_label} — via BirdWeather</p>
  <div class="stats">
    <span>{species_count} species</span>
    <span>{station_count} stations nearby</span>
    <span>{detection_count} detections</span>
  </div>
</header>
<div class="grid">
  {cards}
</div>
<footer>
  Detections and species images courtesy of the <a href="https://app.birdweather.com">BirdWeather</a>
  community station network. Individual image credit/license shown where BirdWeather provides it.
  This is an unofficial local preview, unaffiliated with BirdWeather, generated on {generated}.
</footer>
</body>
</html>
"""


def render_html(place, lat, lon, period_label, radius_km, species_list, station_count, detection_count):
    illustration_index = build_illustration_index(ILLUSTRATIONS_DIR)
    local_matches = 0
    cards = []
    for s in species_list[:MAX_SPECIES_CARDS]:
        local_path = find_local_illustration(s["name"], illustration_index)
        if local_path:
            try:
                data_uri = embed_image_as_data_uri(local_path)
                local_matches += 1
                img_html = f'<img src="{data_uri}" alt="{s["name"]}">'
            except Exception as e:
                print(f"Couldn't embed {local_path} ({e}), falling back to thumbnail.")
                img_html = (f'<img src="{s["thumb"]}" alt="{s["name"]}" loading="lazy">'
                             if s["thumb"] else '<span class="placeholder">?</span>')
        elif s["thumb"]:
            img_html = f'<img src="{s["thumb"]}" alt="{s["name"]}" loading="lazy">'
        else:
            img_html = '<span class="placeholder">?</span>'
        cards.append(CARD_TEMPLATE.format(
            img_html=img_html,
            name=s["name"],
            scientific=s["scientific"],
            station=s["station"],
            when=relative_time(s["ts"]),
        ))
    print(f"Using {local_matches} local illustration(s), "
          f"{len(cards) - local_matches} BirdWeather thumbnail(s).")
    html = PAGE_TEMPLATE.format(
        radius=radius_km,
        place=place,
        period_label=period_label,
        species_count=len(species_list),
        station_count=station_count,
        detection_count=detection_count,
        cards="\n".join(cards) if cards else "<p>No detections found nearby in this window.</p>",
        generated=datetime.now().strftime("%Y-%m-%d %H:%M"),
    )
    return html


def get_screen_size():
    if IMAGE_WIDTH and IMAGE_HEIGHT:
        return IMAGE_WIDTH, IMAGE_HEIGHT
    try:
        import ctypes
        user32 = ctypes.windll.user32
        return user32.GetSystemMetrics(0), user32.GetSystemMetrics(1)
    except Exception:
        return 1920, 1080


def load_font(names, size):
    """names is a single font-file stem, or a list tried in order — lets
    callers fall back to another elegant serif if their first choice isn't
    installed, rather than landing on Pillow's tiny default bitmap font."""
    if isinstance(names, str):
        names = [names]
    for name in names:
        path = rf"C:\Windows\Fonts\{name}.ttf"
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                pass
    return ImageFont.load_default()


def fetch_image_bytes(url):
    req = urllib.request.Request(url, method="GET")
    req.add_header("User-Agent", "avianvisitors-local-preview/1.0")
    with urllib.request.urlopen(req, timeout=15) as resp:
        return resp.read()


def make_circle_thumbnail(img, size):
    """Crops to square, resizes, and masks to a circle."""
    w, h = img.size
    side = min(w, h)
    left = (w - side) // 2
    top = (h - side) // 2
    img = img.crop((left, top, left + side, top + side)).resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size, size), fill=255)
    out = Image.new("RGBA", (size, size))
    out.paste(img, (0, 0), mask=mask)
    return out


def cutout_illustration(img, tolerance=32, max_dim=420):
    """Removes a roughly-uniform background (sampled from the corners) and
    returns a transparent-background RGBA cutout, cropped to content.
    Works well for the local illustrations (flat cream background by design);
    not intended for photos with busy backgrounds."""
    img = img.convert("RGB")
    w, h = img.size
    corners = [img.getpixel((0, 0)), img.getpixel((w - 1, 0)),
               img.getpixel((0, h - 1)), img.getpixel((w - 1, h - 1))]
    bg = tuple(sum(c[i] for c in corners) // 4 for i in range(3))
    bg_img = Image.new("RGB", img.size, bg)
    diff = ImageChops.difference(img, bg_img).convert("L")
    mask = diff.point(lambda p: 255 if p > tolerance else 0)
    rgba = img.convert("RGBA")
    rgba.putalpha(mask)
    bbox = rgba.getbbox()
    if bbox:
        rgba = rgba.crop(bbox)
    if max(rgba.size) > max_dim:
        scale = max_dim / max(rgba.size)
        rgba = rgba.resize((max(1, int(rgba.width * scale)),
                             max(1, int(rgba.height * scale))), Image.LANCZOS)
    return rgba


def add_drop_shadow(canvas, cutout, x, y, opacity=32):
    """Soft ambient shadow, scaled to the bird's own size so small and large
    tiles both read as a blurred shadow rather than a crisp offset outline
    (a fixed blur/offset in pixels looks fine on small tiles but turns into
    a hard-edged 'double' silhouette on the larger, count-weighted ones).

    Kept deliberately light: birds are packed edge-to-edge with only a
    sliver of collision margin between them (collision testing is done on
    the bare silhouette, not the shadow-extended one), so a strong shadow
    routinely bleeds onto whichever neighbour got placed next door,
    muddying the seam between them. A lighter, tighter shadow keeps the
    grounding effect without visibly darkening the bird beside it.

    Thin protrusions — tail feathers, wingtips, a leg — get eroded out of
    the mask before blurring, rather than blurred directly: blurring a
    single-pixel-wide sliver just blooms it into a soft blob that reads as
    a stray dark smudge floating near the bird's edge, disconnected from
    the shape that's supposedly casting it. Eroding first drops those
    slivers from the shadow entirely, leaving only the main body mass —
    which is what actually needs to look grounded — to cast a shadow."""
    size = min(cutout.size)
    blur = max(5, size * 0.03)
    offset = (max(2, round(size * 0.01)), max(3, round(size * 0.018)))
    erode = max(3, round(size * 0.012)) | 1  # odd kernel size, MinFilter requires it
    alpha = cutout.split()[-1].filter(ImageFilter.MinFilter(erode))
    shadow_alpha = alpha.point(lambda p: opacity if p > 0 else 0)
    shadow = Image.new("RGBA", cutout.size, (20, 15, 10, 0))
    shadow.putalpha(shadow_alpha)
    shadow = shadow.filter(ImageFilter.GaussianBlur(blur))
    canvas.paste(shadow, (x + offset[0], y + offset[1]), mask=shadow)


def tone_photo_thumbnail(photo, size):
    """Circular-crops a photo AND feathers the edge / desaturates / warms it
    toward the illustration palette, so photo fallbacks don't visually clash
    with the painted cutouts sitting next to them."""
    circle = make_circle_thumbnail(photo, size)
    alpha = circle.split()[-1].filter(ImageFilter.GaussianBlur(2))
    rgb = circle.convert("RGB")
    rgb = ImageEnhance.Color(rgb).enhance(0.5)
    rgb = ImageEnhance.Contrast(rgb).enhance(0.92)
    tint = Image.new("RGB", rgb.size, (224, 201, 168))
    rgb = Image.blend(rgb, tint, 0.22)
    return Image.merge("RGBA", (*rgb.split(), alpha))


def dilate_grid(grid, radius=1):
    """Pads a boolean silhouette grid outward — used only for collision
    checks, not rendering, so birds keep a small visual gap between them
    instead of touching/overlapping right at the pixel edge."""
    h, w = len(grid), len(grid[0])
    out = [[False] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            if grid[y][x]:
                for dy in range(-radius, radius + 1):
                    for dx in range(-radius, radius + 1):
                        ny, nx = y + dy, x + dx
                        if 0 <= ny < h and 0 <= nx < w:
                            out[ny][nx] = True
    return out


def compute_silhouette_grid(cutout, grid_w=32):
    """Downsamples the alpha channel to a small boolean grid — same idea as
    AvianVisitors' offline alpha masks, just computed on the fly here.
    The grid encodes shape only; it's resampled against a tile's actual
    on-canvas bounding box during collision testing, independent of scale."""
    w, h = cutout.size
    grid_h = max(1, round(grid_w * h / w))
    alpha = cutout.split()[-1].resize((grid_w, grid_h), Image.LANCZOS)
    px = alpha.load()
    return [[px[x, y] > 96 for x in range(grid_w)] for y in range(grid_h)]


def _grid_lookup(tile, canvas_x, canvas_y):
    grid = tile["grid"]
    gh, gw = len(grid), len(grid[0])
    fx = (canvas_x - tile["x"]) / tile["w"]
    fy = (canvas_y - tile["y"]) / tile["h"]
    gx, gy = int(fx * gw), int(fy * gh)
    if 0 <= gx < gw and 0 <= gy < gh:
        return grid[gy][gx]
    return False


def tiles_collide(a, b, samples=13):
    ax0, ay0, ax1, ay1 = a["x"], a["y"], a["x"] + a["w"], a["y"] + a["h"]
    bx0, by0, bx1, by1 = b["x"], b["y"], b["x"] + b["w"], b["y"] + b["h"]
    ix0, iy0 = max(ax0, bx0), max(ay0, by0)
    ix1, iy1 = min(ax1, bx1), min(ay1, by1)
    if ix1 <= ix0 or iy1 <= iy0:
        return False  # bounding boxes don't even overlap
    for i in range(samples):
        for j in range(samples):
            px = ix0 + (i + 0.5) / samples * (ix1 - ix0)
            py = iy0 + (j + 0.5) / samples * (iy1 - iy0)
            if _grid_lookup(a, px, py) and _grid_lookup(b, px, py):
                return True
    return False


def pack_flock(tiles, center_x, center_y, canvas_w, canvas_h, top_margin,
                aspect_bias=1.7, max_iterations=10, shrink_factor=0.93):
    """Center-out spiral packing with real silhouette collision (not just
    bounding boxes), horizontally-biased spiral, and shrink+repack if
    anything ends up off-canvas — mirroring the AvianVisitors approach."""
    ordered = sorted(tiles, key=lambda t: t["w"] * t["h"], reverse=True)
    scale = 1.0

    for iteration in range(max_iterations):
        placed = []
        overflowed = False
        for idx, tile in enumerate(ordered):
            w = tile["w"] * scale
            h = tile["h"] * scale
            if idx == 0:
                x, y = center_x - w / 2, center_y - h / 2
                candidate = {**tile, "x": x, "y": y, "w": w, "h": h}
                placed.append(candidate)
                continue

            theta, r = 0.0, 0.0
            max_r = math.hypot(canvas_w, canvas_h) * 0.75
            found = None
            while r < max_r:
                theta += 0.13
                r = 2.6 * theta
                cx = center_x + r * math.cos(theta) * aspect_bias
                cy = center_y + r * math.sin(theta)
                x, y = cx - w / 2, cy - h / 2
                candidate = {**tile, "x": x, "y": y, "w": w, "h": h}
                if not any(tiles_collide(candidate, p) for p in placed):
                    found = candidate
                    break
            if found is None:
                # give up gracefully at the last tried position; this will
                # very likely trigger a shrink+repack below anyway
                found = candidate
                overflowed = True
            placed.append(found)

            if (found["x"] < 0 or found["y"] < top_margin or
                    found["x"] + found["w"] > canvas_w or
                    found["y"] + found["h"] > canvas_h):
                overflowed = True

        if not overflowed:
            print(f"Flock packed cleanly after {iteration + 1} attempt(s).")
            return placed
        scale *= shrink_factor

    print(f"Flock packing hit the iteration limit — using best effort at "
          f"{scale:.0%} scale.")
    return placed


def render_wallpaper_image(species_list, counts, illustration_index, output_path):
    CREAM = (244, 237, 224)
    INK = (46, 38, 32)

    width, height = get_screen_size()
    canvas = Image.new("RGB", (width, height), CREAM)
    draw = ImageDraw.Draw(canvas)

    # Georgia first (a warm serif that suits the illustrated look), falling
    # back to Times New Roman (always present on Windows) before Pillow's
    # plain bitmap default.
    title_font = load_font(["georgiab", "timesbd"], 54)
    name_font = load_font(["georgia", "times"], 18)

    if SHOW_TITLE:
        title = "Garden Visitors"
        tw = draw.textlength(title, font=title_font)
        draw.text(((width - tw) / 2, 60), title, font=title_font, fill=INK)

    birds = species_list[:60]
    n = len(birds)
    if n == 0:
        canvas.save(output_path, "JPEG", quality=90)
        return output_path

    # Prepare each bird: cutout (local art) or toned photo circle
    tiles = []
    for s in birds:
        rnd = random.Random(s["name"])  # stable per species across runs
        local_path = find_local_illustration(s["name"], illustration_index)
        cutout = None
        if local_path:
            try:
                cutout = cutout_illustration(Image.open(local_path))
            except Exception as e:
                print(f"Cutout failed for {s['name']} ({e}), using thumbnail instead.")
        if cutout is None and s["thumb"]:
            try:
                data = fetch_image_bytes(s["thumb"])
                photo = Image.open(BytesIO(data)).convert("RGB")
                cutout = tone_photo_thumbnail(photo, 300)
            except Exception as e:
                print(f"Couldn't fetch thumbnail for {s['name']}: {e}")
        if cutout is None:
            continue

        angle = rnd.uniform(-5, 5)
        cutout = cutout.rotate(angle, expand=True, resample=Image.BICUBIC)
        grid = dilate_grid(compute_silhouette_grid(cutout), radius=1)
        aspect = cutout.width / cutout.height
        count = counts.get(s["name"], 1)
        tiles.append({
            "species": s, "cutout": cutout, "grid": grid,
            "aspect": aspect, "count": count,
        })

    if not tiles:
        canvas.save(output_path, "JPEG", quality=90)
        return output_path

    # --- count-weighted sizing, normalized to a viewport area budget ---
    # (mirrors AvianVisitors: sizes are scaled so total area matches a
    # budget fraction of the viewport, not a fixed per-tile clamp — so a
    # frequently-heard species still visibly outsizes a rare one, regardless
    # of how many species are on screen. log(count+1) rather than a power
    # curve, since raw BirdWeather detection counts are far more skewed
    # than eBird call counts — without it, one very common species can
    # swallow the whole layout.)
    scores = {id(t): math.log(t["count"] + 1) + 0.5 for t in tiles}
    total_score = sum(scores.values())
    budget_fraction = 0.40 + (0.24 - 0.40) * min(1, max(0, (len(tiles) - 10) / 40))
    budget_area = budget_fraction * width * height

    for t in tiles:
        area = (scores[id(t)] / total_score) * budget_area
        h = math.sqrt(area / t["aspect"])
        w = t["aspect"] * h
        min_dim = 70
        if h < min_dim:
            h, w = min_dim, min_dim * t["aspect"]
        t["w"], t["h"] = w, h

    # --- pack ---
    center_x = width / 2
    top_margin = 170 if SHOW_TITLE else 60  # keep clear of the title, if shown
    # Vertical center of the flock is derived from top_margin (rather than a
    # flat fraction of height) so hiding the title reclaims that space
    # instead of leaving it as a dead gap above a collage that stayed put.
    center_y = top_margin + (height - top_margin) * 0.50
    placed = pack_flock(tiles, center_x, center_y, width, height, top_margin)

    # draw back-to-front: place larger/central tiles last so they sit on top
    placed_by_area = sorted(placed, key=lambda t: t["w"] * t["h"])

    for t in placed_by_area:
        cutout = t["cutout"].resize(
            (max(1, round(t["w"])), max(1, round(t["h"]))), Image.LANCZOS
        )
        x, y = round(t["x"]), round(t["y"])
        add_drop_shadow(canvas, cutout, x, y)
        canvas.paste(cutout, (x, y), mask=cutout)
        t["draw_x"], t["draw_y"] = x, y

    # Labels are drawn in their own pass, after every bird is on the canvas —
    # doing it inline in the loop above let a later, larger neighbour paint
    # right over an earlier bird's label, since tiles sit only a sliver
    # apart. This guarantees every label ends up on top, unobscured.
    if SHOW_LABELS:
        for t in placed_by_area:
            name = t["species"]["name"]
            lw = draw.textlength(name, font=name_font)
            draw.text((t["draw_x"] + t["w"] / 2 - lw / 2, t["draw_y"] + t["h"] + 4),
                       name, font=name_font, fill=INK)

    canvas.save(output_path, "JPEG", quality=90)
    return output_path


def set_windows_wallpaper(image_path):
    import ctypes
    SPI_SETDESKWALLPAPER = 20
    SPIF_UPDATEINIFILE = 0x01
    SPIF_SENDCHANGE = 0x02
    abs_path = os.path.abspath(image_path)
    result = ctypes.windll.user32.SystemParametersInfoW(
        SPI_SETDESKWALLPAPER, 0, abs_path, SPIF_UPDATEINIFILE | SPIF_SENDCHANGE
    )
    if not result:
        raise RuntimeError("SystemParametersInfoW reported failure")


def main():
    place = "Spofforth"
    lat, lon = FALLBACK_LAT, FALLBACK_LON

    if POSTCODE:
        try:
            lat, lon, place = lookup_postcode(POSTCODE)
            print(f"Postcode resolved: {place} ({lat:.4f}, {lon:.4f})")
        except Exception as e:
            print(f"Postcode lookup failed ({e}), falling back to configured lat/lon.")

    period_label = format_period(PERIOD_COUNT, PERIOD_UNIT)
    print(f"Querying BirdWeather within {RADIUS_KM} km of ({lat:.4f}, {lon:.4f}), "
          f"last {period_label}...")
    try:
        data = fetch_nearby(lat, lon, RADIUS_KM, PERIOD_COUNT, PERIOD_UNIT)
    except urllib.error.URLError as e:
        print(f"Network error reaching BirdWeather: {e}")
        print("Check your internet connection / firewall and try again.")
        return
    except Exception as e:
        print(f"Failed to fetch from BirdWeather: {e}")
        return

    detections = data["detections"]
    stations = data["stations"]
    species_list = dedupe_species(detections["nodes"])

    print(f"Found {detections['totalCount']} detections, "
          f"{detections['speciesCount']} species, "
          f"{stations['totalCount']} stations nearby.")

    html = render_html(
        place=place,
        lat=lat,
        lon=lon,
        period_label=period_label,
        radius_km=RADIUS_KM,
        species_list=species_list,
        station_count=stations["totalCount"],
        detection_count=detections["totalCount"],
    )

    tmp_file = OUTPUT_FILE + ".tmp"
    try:
        with open(tmp_file, "w", encoding="utf-8") as f:
            f.write(html)
        os.replace(tmp_file, OUTPUT_FILE)
    except PermissionError as e:
        print(f"\nCouldn't write {OUTPUT_FILE}: {e}")
        print("This usually means another program (e.g. Lively Wallpaper, a text "
              "editor, or a browser tab) has the file open and locked. Close it, "
              "or pause Lively Wallpaper, and try again.")
        return

    print(f"Written to {OUTPUT_FILE} — open it in a browser.")

    if SET_DESKTOP_WALLPAPER:
        if not PIL_AVAILABLE:
            print("Pillow isn't installed — run: pip install Pillow")
        else:
            try:
                illustration_index = build_illustration_index(ILLUSTRATIONS_DIR)
                species_counts = count_species(detections["nodes"])
                render_wallpaper_image(species_list, species_counts,
                                        illustration_index, OUTPUT_IMAGE)
                set_windows_wallpaper(OUTPUT_IMAGE)
                print(f"Desktop wallpaper updated directly ({OUTPUT_IMAGE}).")
            except Exception as e:
                print(f"Couldn't set desktop wallpaper: {e}")


if __name__ == "__main__":
    import traceback

    # pythonw.exe (source run) and the packaged --windowed exe both have no
    # console, so sys.stdout/stderr are None rather than writable streams.
    # Redirect to a log file in that case so print() calls don't crash, and
    # so you can still see what happened after the fact.
    if sys.stdout is None or not hasattr(sys.stdout, "write"):
        log_path = os.path.join(app_dir(), "birdweather_local.log")
        log_file = open(log_path, "a", encoding="utf-8")
        sys.stdout = log_file
        sys.stderr = log_file
        print(f"\n--- Run at {datetime.now().strftime('%Y-%m-%d %H:%M:%S')} ---")

    try:
        if FROZEN:
            first_run = not os.path.exists(CONFIG_PATH)
            bootstrap_illustrations()
            if first_run:
                run_first_time_setup()
        main()
    except Exception:
        print("\nSomething went wrong — full details below:\n")
        traceback.print_exc()

    if sys.platform.startswith("win") and sys.stdin is not None and sys.stdin.isatty():
        input("\nPress Enter to close this window...")
