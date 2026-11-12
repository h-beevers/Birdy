#!/usr/bin/env python3
"""Regenerate the species index for the Fugleramme "classic" plate pack.

[arnegiacomo/fugleramme](https://github.com/arnegiacomo/fugleramme) ships
~800 species of hand-coloured 1800s bird plates (Gould, the von Wright
brothers, Audubon, ...), already cut out and filed by scientific name.  The
artwork is CC BY-SA 4.0; see CLASSIC_PLATES.md at the repo root.

Birdy does not copy that artwork into this repo (it is ~160 MB).  Both the
Android app and the desktop script download the plates they need, on the
user's own machine, straight from a **pinned commit** of that repo.  This
script writes the plain-text index they use to do it without calling the
GitHub API at run time:

    @commit <sha>            the commit every URL is pinned to
    erithacus-rubecula       one line per species that has a plate
    corvus-monedula=coloeus-monedula
                             an older scientific name BirdNET / BirdWeather
                             still reports, and the name its plate is under

so a plate's URL is a pure string join:

    https://raw.githubusercontent.com/arnegiacomo/fugleramme/<sha>/assets/artwork/classic/birds/<slug>.webp

Pinning matters: the pack is still being curated and renumbers its variants,
so an index built from one commit must not be used against another.

The alias lines come from that repo's `assets/birdnet_aliases.json`, an
unmodified copy of OpenFauna's alias map (CC BY-SA 4.0).

Usage:
    python3 android/tools/sync_classic_pack_manifest.py            # from GitHub
    python3 android/tools/sync_classic_pack_manifest.py --from-dir ../fugleramme
    python3 android/tools/sync_classic_pack_manifest.py --check
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.request

REPO_OWNER = "arnegiacomo"
REPO_NAME = "fugleramme"
REPO_BRANCH = "main"
BIRDS_DIR = "assets/artwork/classic/birds/"
ALIASES_PATH = "assets/birdnet_aliases.json"
API = f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}"
RAW = f"https://raw.githubusercontent.com/{REPO_OWNER}/{REPO_NAME}"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MANIFEST = os.path.join(
    REPO_ROOT, "android", "app", "src", "main", "assets", "classic_pack_manifest.txt"
)
USER_AGENT = "Birdy-classic-pack-manifest/1.0"
# "<slug>-2.webp", "<slug>-3.webp" are alternate plates of the same species.
VARIANT = re.compile(r"-\d+$")
SLUG = re.compile(r"[a-z0-9]+(-[a-z0-9]+)*")


def slugify(scientific: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", scientific.lower()).strip("-")


def _get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read()


def from_github() -> tuple[list[str], dict[str, str], str]:
    sha = json.loads(_get(f"{API}/commits/{REPO_BRANCH}"))["sha"]
    tree = json.loads(_get(f"{API}/git/trees/{sha}?recursive=1"))
    if tree.get("truncated"):
        sys.exit("GitHub truncated the file listing; use --from-dir with a clone.")
    names = [
        os.path.basename(e["path"])
        for e in tree.get("tree", [])
        if e.get("type") == "blob" and e.get("path", "").startswith(BIRDS_DIR)
    ]
    aliases = json.loads(_get(f"{RAW}/{sha}/{ALIASES_PATH}"))
    return names, aliases, sha


def from_dir(path: str) -> tuple[list[str], dict[str, str], str]:
    sha = subprocess.run(
        ["git", "-C", path, "rev-parse", "HEAD"],
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    names = os.listdir(os.path.join(path, BIRDS_DIR))
    with open(os.path.join(path, ALIASES_PATH), encoding="utf-8") as fh:
        aliases = json.load(fh)
    return names, aliases, sha


def build(names: list[str], aliases: dict[str, str]) -> tuple[list[str], list[str]]:
    species = set()
    for name in names:
        base, ext = os.path.splitext(name)
        if ext.lower() != ".webp" or VARIANT.search(base):
            continue
        if SLUG.fullmatch(base):
            species.add(base)
    alias_lines = set()
    for old, new in aliases.items():
        old_slug, new_slug = slugify(old), slugify(new)
        # Only worth a line when it gets an otherwise plate-less name a plate.
        if old_slug and new_slug in species and old_slug not in species:
            alias_lines.add(f"{old_slug}={new_slug}")
    return sorted(species), sorted(alias_lines)


def render(species: list[str], alias_lines: list[str], sha: str) -> str:
    header = [
        '# Fugleramme "classic" plates: species index (scientific-name slugs).',
        f"# Source: https://github.com/{REPO_OWNER}/{REPO_NAME} ({BIRDS_DIR}<slug>.webp)",
        "# Artwork licence: CC BY-SA 4.0 (see CLASSIC_PLATES.md). Not bundled; fetched on demand.",
        "# old=new lines: legacy BirdNET names, from OpenFauna's alias map (CC BY-SA 4.0).",
        "# Regenerate: python3 android/tools/sync_classic_pack_manifest.py",
        f"# {len(species)} species, {len(alias_lines)} aliases",
        f"@commit {sha}",
    ]
    return "\n".join(header + species + alias_lines) + "\n"


def entries(text: str) -> set[str]:
    return {
        line.strip()
        for line in text.splitlines()
        if line.strip() and not line.startswith("#")
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--from-dir", help="read a local clone instead of the GitHub API")
    ap.add_argument(
        "--check", action="store_true",
        help="report drift against the committed index without writing it",
    )
    args = ap.parse_args()

    names, aliases, sha = from_dir(args.from_dir) if args.from_dir else from_github()
    species, alias_lines = build(names, aliases)
    if len(species) < 100:
        sys.exit(f"only {len(species)} species found; refusing to write a broken index.")
    new = render(species, alias_lines, sha)
    old = ""
    if os.path.exists(MANIFEST):
        with open(MANIFEST, encoding="utf-8") as fh:
            old = fh.read()
    added = sorted(entries(new) - entries(old))
    removed = sorted(entries(old) - entries(new))

    if args.check:
        if added or removed:
            for s in added:
                print(f"+ {s}")
            for s in removed:
                print(f"- {s}")
            print(f"{len(added)} added, {len(removed)} removed; rerun without --check.")
            return 1
        print(f"up to date ({len(species)} species)")
        return 0

    os.makedirs(os.path.dirname(MANIFEST), exist_ok=True)
    with open(MANIFEST, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(new)
    print(
        f"wrote {os.path.relpath(MANIFEST, REPO_ROOT)}: {len(species)} species, "
        f"{len(alias_lines)} aliases, pinned to {sha[:12]}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
