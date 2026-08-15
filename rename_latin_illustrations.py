#!/usr/bin/env python3
"""
rename_latin_illustrations.py

AvianVisitors' pregen pipeline can leave illustration files named after the
species' Latin/scientific name (e.g. "Corvus_cornix.png"). Birdy only
matches Illustrations/ files against a species' *common* name (see
find_local_illustration() in birdweather_local.py), so Latin-named files
are silently ignored and Birdy falls back to the BirdWeather photo instead.

This script looks up each Latin name via the free GBIF species API (no API
key needed), finds its English common/vernacular name, and copies the file
into your Birdy Illustrations/ folder under that common name — leaving your
original AvianVisitors folder untouched.

Usage:
    python3 rename_latin_illustrations.py <source_dir> <dest_illustrations_dir>

Example:
    python3 rename_latin_illustrations.py ^
        "E:\\Documents\\Homemade Apps\\AvianVisitors-avian-visitors\\AvianVisitors-avian-visitors\\avian\\assets\\illustrations" ^
        "E:\\Documents\\Homemade Apps\\Birdy\\Illustrations"

Add --dry-run to preview the renames without copying anything.
"""

import argparse
import json
import os
import re
import shutil
import time
import urllib.error
import urllib.parse
import urllib.request

GBIF_MATCH_URL = "https://api.gbif.org/v1/species/match"
GBIF_VERNACULAR_URL = "https://api.gbif.org/v1/species/{key}/vernacularNames"
IMAGE_EXTS = (".png", ".jpg", ".jpeg", ".webp")
REQUEST_DELAY_SECONDS = 0.2


def scientific_name_from_filename(fname):
    """'Corvus_cornix.png' / 'Corvus-cornix.png' / 'Corvus cornix.png' -> 'Corvus cornix'"""
    base, _ext = os.path.splitext(fname)
    name = re.sub(r"[_\-]+", " ", base).strip()
    name = re.sub(r"\s+", " ", name)
    return name


def gbif_get(url, params=None):
    if params:
        url = f"{url}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"User-Agent": "Birdy-illustration-renamer/1.0"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


def lookup_common_name(scientific_name):
    """Returns an English common name for a scientific name via GBIF, or None."""
    try:
        match = gbif_get(GBIF_MATCH_URL, {"name": scientific_name, "strict": "false"})
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as e:
        print(f"    ! GBIF match request failed: {e}")
        return None

    usage_key = match.get("usageKey")
    if not usage_key:
        return None

    try:
        vernaculars = gbif_get(GBIF_VERNACULAR_URL.format(key=usage_key))
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as e:
        print(f"    ! GBIF vernacular request failed: {e}")
        return None

    results = vernaculars.get("results", [])
    english = [r for r in results if r.get("language") == "eng" and r.get("vernacularName")]
    if not english:
        return None

    preferred = [r for r in english if r.get("preferred")]
    chosen = preferred[0] if preferred else english[0]
    return chosen["vernacularName"]


def safe_filename(name, ext):
    cleaned = re.sub(r"[^A-Za-z0-9 ]+", "", name).strip()
    cleaned = re.sub(r"\s+", " ", cleaned)
    return f"{cleaned}{ext}"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source_dir", help="Folder of Latin-named AvianVisitors illustrations")
    parser.add_argument("dest_dir", help="Your Birdy Illustrations/ folder")
    parser.add_argument("--dry-run", action="store_true", help="Preview matches without copying files")
    parser.add_argument("--overwrite", action="store_true", help="Overwrite existing files in dest_dir")
    args = parser.parse_args()

    if not os.path.isdir(args.source_dir):
        parser.error(f"Source folder not found: {args.source_dir}")

    if not args.dry_run:
        os.makedirs(args.dest_dir, exist_ok=True)

    files = sorted(
        f for f in os.listdir(args.source_dir)
        if os.path.splitext(f)[1].lower() in IMAGE_EXTS
    )
    if not files:
        print(f"No image files found in {args.source_dir}")
        return

    matched, skipped = 0, 0
    unmatched = []

    for fname in files:
        sci_name = scientific_name_from_filename(fname)
        print(f"{fname}  ->  looking up '{sci_name}'...")
        common = lookup_common_name(sci_name)
        time.sleep(REQUEST_DELAY_SECONDS)

        if not common:
            print("    ! no common name found, skipping")
            unmatched.append(fname)
            skipped += 1
            continue

        ext = os.path.splitext(fname)[1].lower()
        dest_name = safe_filename(common, ext)
        dest_path = os.path.join(args.dest_dir, dest_name)
        print(f"    -> {dest_name}")

        if args.dry_run:
            matched += 1
            continue

        if os.path.exists(dest_path) and not args.overwrite:
            print("    ! destination already exists, skipping (use --overwrite to replace)")
            skipped += 1
            continue

        shutil.copy2(os.path.join(args.source_dir, fname), dest_path)
        matched += 1

    print(f"\n{matched} matched, {skipped} skipped/unmatched out of {len(files)} files.")
    if unmatched:
        print("Unmatched files (rename these manually to their common name):")
        for f in unmatched:
            print(f"  - {f}")


if __name__ == "__main__":
    main()
