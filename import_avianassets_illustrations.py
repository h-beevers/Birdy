#!/usr/bin/env python3
"""
import_avianassets_illustrations.py

Pulls ready-made bird illustrations straight from jonnywright/AvianAssets
(https://github.com/jonnywright/AvianAssets) into your Birdy Illustrations/
folder — no AI generation, no API key, just a download. AvianAssets ships
~300 UK species as pre-cutout, transparent-background PNGs named after each
bird's scientific name (e.g. "erithacus-rubecula.png" for European Robin),
which Birdy's own cutout_illustration() already handles as-is (real alpha is
trusted over background removal) and find_local_illustration() already
matches on (it checks a species' scientific name as well as its common one).

Two ways to use it:
  - Default: only pulls art for species actually detected near you recently
    (same BirdWeather query as generate_missing_illustrations.py) and only
    where you don't already have local art for that species — a "top up
    what I'm missing" pass.
  - --all: pulls the entire AvianAssets set (still skipping anything you
    already have locally, unless --overwrite) — handy for building out a
    full library before you've had many detections yet. This mode looks up
    each species' English common name via the free GBIF API (same lookup
    rename_latin_illustrations.py uses) so the saved file follows this
    repo's usual "named by common name" convention.

Saved files are named by common name (e.g. "European Robin.png"), matching
the convention the rest of this repo's tooling uses.

Makes NO changes to birdweather_local.py — it's a separate tool you run
standalone, on demand, to keep the art folder stocked.

Attribution: https://github.com/jonnywright/AvianAssets (UK species pack,
generated + cutout by jonnywright). Not covered by this repo's own GPL-3.0
license — see that repo for its own terms before redistributing anything
you pull with this script.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

# Make sibling modules importable regardless of CWD.
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)
import birdweather_local as B  # noqa: E402  (deliberate late import after path fix)
# --all mode only: GBIF lookup, and the same filename sanitizer it already
# applies to that lookup's output before this script existed.
from rename_latin_illustrations import lookup_common_name, safe_filename  # noqa: E402

REPO_OWNER = "jonnywright"
REPO_NAME = "AvianAssets"
REPO_BRANCH = "main"
TREE_URL = (f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}"
            f"/git/trees/{REPO_BRANCH}?recursive=1")
RAW_BASE = f"https://raw.githubusercontent.com/{REPO_OWNER}/{REPO_NAME}/{REPO_BRANCH}/"
SOURCE_URL = f"https://github.com/{REPO_OWNER}/{REPO_NAME}"

USER_AGENT = "Birdy-illustration-importer/1.0"
GBIF_REQUEST_DELAY_SECONDS = 0.2


def http_get(url, timeout=20):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def _scientific_display(slug_base):
    """'acanthis-flammea' -> 'Acanthis flammea'"""
    parts = slug_base.split("-")
    parts[0] = parts[0].capitalize()
    return " ".join(parts)


def fetch_avianassets_index():
    """Maps a scientific-name slug (Birdy's own _slugify) -> {'scientific':
    display name, 'url': raw download URL}, one entry per species (the
    pack's alternate "-2" pose for the same species is skipped — Birdy only
    ever needs one illustration per species)."""
    try:
        raw = http_get(TREE_URL, timeout=30)
    except (urllib.error.URLError, urllib.error.HTTPError) as e:
        raise RuntimeError(f"couldn't list {SOURCE_URL}: {e}")
    tree = json.loads(raw.decode("utf-8"))
    if tree.get("truncated"):
        print("  ! warning: GitHub truncated the file listing; some species may be missed.")

    index = {}
    for entry in tree.get("tree", []):
        path = entry.get("path", "")
        if (entry.get("type") != "blob" or not path.startswith("illustrations/")
                or not path.lower().endswith(".png")):
            continue
        base = os.path.splitext(os.path.basename(path))[0]
        if base.endswith("-2"):
            continue
        slug = B._slugify(base)
        index[slug] = {"scientific": _scientific_display(base), "url": RAW_BASE + path}
    return index


def fetch_detected_species(lat, lon, radius_km, days):
    data = B.fetch_nearby(lat, lon, radius_km, days, "day", first=300)
    nodes = data["detections"]["nodes"]
    species_list = B.dedupe_species(nodes)
    counts = B.count_species(nodes)
    for s in species_list:
        s["count"] = counts.get(s["name"], 1)
    species_list.sort(key=lambda d: d.get("count", 1), reverse=True)
    return species_list


def build_nearby_targets(species, avian_index, ill_index):
    """(common_name, url) pairs for detected species missing local art, plus
    the detected species AvianAssets has no matching illustration for."""
    todo, unmatched = [], []
    for s in species:
        if B.find_local_illustration(s["name"], ill_index, s.get("scientific")):
            continue
        info = avian_index.get(B._slugify(s.get("scientific") or ""))
        if not info:
            unmatched.append(s)
            continue
        todo.append((s["name"], info["url"]))
    return todo, unmatched


def build_all_targets(avian_index, ill_index):
    """(common_name, url) pairs for every species in the pack not already
    covered locally. Looks up each species' common name via GBIF, so it's
    slower than the default "near you" mode — expect roughly 0.2s/species."""
    todo, unresolved = [], []
    items = sorted(avian_index.items())
    for i, (slug, info) in enumerate(items, 1):
        scientific = info["scientific"]
        print(f"  [{i}/{len(items)}] {scientific}...", end=" ", flush=True)
        common = lookup_common_name(scientific)
        time.sleep(GBIF_REQUEST_DELAY_SECONDS)
        if not common:
            print("no common name found, skipping")
            unresolved.append(scientific)
            continue
        if B.find_local_illustration(common, ill_index, scientific):
            print(f"{common} — already have it")
            continue
        # GBIF vernacular names are free text — strip anything that isn't
        # safe to use verbatim as a filename before it reaches disk.
        safe_common = os.path.splitext(safe_filename(common, ".png"))[0]
        print(common if safe_common == common else f"{common} -> {safe_common}")
        todo.append((safe_common, info["url"]))
    return todo, unresolved


def main():
    p = argparse.ArgumentParser(
        description="Import ready-made UK bird illustrations from AvianAssets into Illustrations/.",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    _def = getattr(B, "_DEFAULTS", {})
    p.add_argument("--lat", type=float, default=float(_def.get("fallback_lat", 53.93)))
    p.add_argument("--lon", type=float, default=float(_def.get("fallback_lon", -1.45)))
    p.add_argument("--radius-km", type=float, default=20)
    p.add_argument("--days", type=int, default=30)
    p.add_argument("--illustrations-dir", default=os.path.join(SCRIPT_DIR, "Illustrations"))
    p.add_argument("--all", action="store_true",
                   help="Import every species in the AvianAssets pack, not just ones detected near you.")
    p.add_argument("--limit", type=int, default=None,
                   help="Cap how many illustrations to import this run.")
    p.add_argument("--overwrite", action="store_true",
                   help="Replace an existing local illustration for a species.")
    p.add_argument("--dry-run", action="store_true",
                   help="Show what would be imported, without downloading or writing anything.")
    p.add_argument("--list-unmatched", action="store_true",
                   help="Also list detected species AvianAssets has no illustration for.")
    args = p.parse_args()

    print(f"Fetching the AvianAssets illustration list from {SOURCE_URL}...")
    try:
        avian_index = fetch_avianassets_index()
    except RuntimeError as e:
        print(f"! {e}")
        sys.exit(1)
    print(f"  {len(avian_index)} species available in the pack.")

    ill_index = B.build_illustration_index(args.illustrations_dir)

    if args.all:
        todo, unresolved = build_all_targets(avian_index, ill_index)
        if unresolved:
            print(f"\n{len(unresolved)} species had no GBIF common name and were skipped:")
            for name in unresolved:
                print(f"  - {name}")
    else:
        print(f"Querying BirdWeather within {args.radius_km} km of "
              f"({args.lat:.4f}, {args.lon:.4f}), last {args.days} days...")
        species = fetch_detected_species(args.lat, args.lon, args.radius_km, args.days)
        print(f"  {len(species)} species detected nearby.")
        todo, unmatched = build_nearby_targets(species, avian_index, ill_index)
        if args.list_unmatched and unmatched:
            print(f"\n{len(unmatched)} detected species have no AvianAssets match:")
            for s in unmatched:
                print(f"  - {s['name']} ({s.get('scientific', '')})")

    if args.limit is not None:
        todo = todo[:args.limit]

    if not todo:
        print("\nNothing to import — Illustrations/ is already up to date against this pack.")
        return

    if args.dry_run:
        print(f"\n--- DRY RUN: would import {len(todo)} illustration(s) (no downloads made) ---")
        for common, url in todo:
            print(f"  {common}  <-  {url}")
        print("\nRe-run without --dry-run to actually download.")
        return

    os.makedirs(args.illustrations_dir, exist_ok=True)
    imported = skipped = failed = 0
    for common, url in todo:
        dest = os.path.join(args.illustrations_dir, common + ".png")
        if os.path.exists(dest) and not args.overwrite:
            print(f"  skip {common} (already exists, use --overwrite to replace)")
            skipped += 1
            continue
        try:
            img_bytes = http_get(url, timeout=30)
        except (urllib.error.URLError, urllib.error.HTTPError) as e:
            print(f"  ! failed to download {common}: {e}")
            failed += 1
            continue
        with open(dest, "wb") as f:
            f.write(img_bytes)
        print(f"  + {common} -> {dest}")
        imported += 1
        time.sleep(0.1)  # be polite to raw.githubusercontent.com

    print(f"\nDone. {imported} imported, {skipped} skipped, {failed} failed.")
    print(f"Source: {SOURCE_URL} — see that repo for its own licensing/attribution terms.")


if __name__ == "__main__":
    main()
