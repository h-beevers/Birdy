#!/usr/bin/env python3
"""Regenerate the Android app's GB illustration-pack species index.

The Android app does **not** bundle jonnywright/AvianAssets artwork — that pack
has no licence file of its own, so redistributing it inside an APK would be a
redistribution this repo has no permission to make.  Instead the app downloads
the plates it needs straight from that repo, on the user's own device, on
demand.

To do that without hitting the GitHub API from a phone (rate-limited, and one
extra failure mode on a bad network), the app ships a plain-text *index* of the
pack's species: one scientific-name slug per line, exactly the basename the
pack uses, so a plate's URL is a pure string join:

    https://raw.githubusercontent.com/jonnywright/AvianAssets/main/illustrations/<slug>.png

Species names are facts, not artwork, so the index carries no licence baggage.

Usage:  python3 android/tools/sync_gb_pack_manifest.py [--check]
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.request

REPO_OWNER = "jonnywright"
REPO_NAME = "AvianAssets"
REPO_BRANCH = "main"
TREE_URL = (
    f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}"
    f"/git/trees/{REPO_BRANCH}?recursive=1"
)
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MANIFEST = os.path.join(
    REPO_ROOT, "android", "app", "src", "main", "assets", "gb_pack_manifest.txt"
)
USER_AGENT = "Birdy-gb-pack-manifest/1.0"


def fetch_slugs() -> tuple[list[str], str]:
    """Every species slug in the pack, plus the commit the listing came from.

    The pack ships an alternate ``-2`` pose for some species; Birdy only ever
    needs one plate per species, so those are dropped.
    """
    req = urllib.request.Request(TREE_URL, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as resp:
        tree = json.loads(resp.read().decode("utf-8"))
    if tree.get("truncated"):
        sys.exit("GitHub truncated the file listing — refusing a partial index.")
    slugs = set()
    for entry in tree.get("tree", []):
        path = entry.get("path", "")
        if entry.get("type") != "blob":
            continue
        if not path.startswith("illustrations/") or not path.lower().endswith(".png"):
            continue
        base = os.path.splitext(os.path.basename(path))[0]
        if base.endswith("-2"):
            continue
        slugs.add(base)
    return sorted(slugs), tree.get("sha", "unknown")


def render(slugs: list[str], sha: str) -> str:
    header = [
        "# AvianAssets GB illustration pack — species index (scientific-name slugs).",
        f"# Source: https://github.com/{REPO_OWNER}/{REPO_NAME} (illustrations/<slug>.png)",
        "# Regenerate: python3 android/tools/sync_gb_pack_manifest.py",
        f"# Pinned commit: {sha} ({len(slugs)} species)",
    ]
    return "\n".join(header + slugs) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--check",
        action="store_true",
        help="report drift against the committed index without writing it",
    )
    args = ap.parse_args()

    slugs, sha = fetch_slugs()
    new = render(slugs, sha)
    old = ""
    if os.path.exists(MANIFEST):
        with open(MANIFEST, encoding="utf-8") as fh:
            old = fh.read()

    def species(text: str) -> set[str]:
        return {
            line.strip()
            for line in text.splitlines()
            if line.strip() and not line.startswith("#")
        }

    added = sorted(species(new) - species(old))
    removed = sorted(species(old) - species(new))

    if args.check:
        if added or removed:
            for s in added:
                print(f"+ {s}")
            for s in removed:
                print(f"- {s}")
            print(f"{len(added)} added, {len(removed)} removed — rerun without --check.")
            return 1
        print(f"up to date ({len(species(new))} species)")
        return 0

    os.makedirs(os.path.dirname(MANIFEST), exist_ok=True)
    with open(MANIFEST, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(new)
    print(
        f"wrote {os.path.relpath(MANIFEST, REPO_ROOT)}: "
        f"{len(species(new))} species (+{len(added)} / -{len(removed)})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
