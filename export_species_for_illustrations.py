#!/usr/bin/env python3
"""
export_species_for_illustrations.py

Pulls your local BirdWeather species list (same data birdweather_local.py
uses) and writes a plain-text file of "Scientific name|Common name" lines,
ranked by how many times each species has actually been detected nearby.

This is meant to feed straight into AvianVisitors' illustration pipeline:

    python3 export_species_for_illustrations.py --limit 5
    cd AvianVisitors/avian/scripts
    export GEMINI_API_KEY='your-key'
    python3 pregen.py --stdin < /path/to/species_for_illustrations.txt
    python3 cutout.py

Start with a small --limit (5 or so) to sanity-check cost and image quality
before generating your full local list.
"""

import argparse
import json
import math
import urllib.request
from collections import Counter

FALLBACK_LAT = 53.9545
FALLBACK_LON = -1.4491
BIRDWEATHER_GRAPHQL = "https://app.birdweather.com/graphql"

QUERY = """
query recentNearby($ne: InputLocation, $sw: InputLocation, $period: InputDuration, $first: Int) {
  detections(ne: $ne, sw: $sw, period: $period, first: $first) {
    totalCount
    nodes {
      species {
        commonName
        scientificName
      }
    }
  }
}
"""


def bounding_box(lat, lon, radius_km):
    lat_delta = radius_km / 111.0
    lon_delta = radius_km / (111.0 * max(0.1, math.cos(math.radians(lat))))
    return {
        "ne": {"lat": lat + lat_delta, "lon": lon + lon_delta},
        "sw": {"lat": lat - lat_delta, "lon": lon - lon_delta},
    }


def fetch_species_counts(lat, lon, radius_km, days, first=800):
    bbox = bounding_box(lat, lon, radius_km)
    payload = {
        "query": QUERY,
        "variables": {
            "ne": bbox["ne"],
            "sw": bbox["sw"],
            "period": {"count": days, "unit": "day"},
            "first": first,
        },
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(BIRDWEATHER_GRAPHQL, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=20) as resp:
        result = json.loads(resp.read().decode("utf-8"))
    if "errors" in result:
        raise RuntimeError(result["errors"])

    counts = Counter()
    names = {}
    for node in result["data"]["detections"]["nodes"]:
        sp = node.get("species") or {}
        common = sp.get("commonName")
        sci = sp.get("scientificName")
        if not common or not sci:
            continue
        counts[common] += 1
        names[common] = sci
    return counts, names


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lat", type=float, default=FALLBACK_LAT)
    parser.add_argument("--lon", type=float, default=FALLBACK_LON)
    parser.add_argument("--radius-km", type=float, default=20)
    parser.add_argument("--days", type=int, default=30)
    parser.add_argument("--limit", type=int, default=5,
                         help="How many top species to export (start small — this costs money)")
    parser.add_argument("--output", default="species_for_illustrations.txt")
    args = parser.parse_args()

    print(f"Querying BirdWeather within {args.radius_km} km, last {args.days} days...")
    counts, names = fetch_species_counts(args.lat, args.lon, args.radius_km, args.days)

    ranked = counts.most_common(args.limit)
    if not ranked:
        print("No species found — widen --radius-km or --days.")
        return

    with open(args.output, "w", encoding="utf-8") as f:
        for common, n in ranked:
            sci = names[common]
            f.write(f"{sci}|{common}\n")
            print(f"  {n:>4}x  {common}  ({sci})")

    print(f"\nWrote {len(ranked)} species to {args.output}")
    print("Feed it to pregen.py like this:")
    print(f"  python3 pregen.py --stdin < {args.output}")


if __name__ == "__main__":
    main()
