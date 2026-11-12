#!/usr/bin/env python3
"""Classic 1800s bird plates for Birdy, from the Fugleramme project.

[arnegiacomo/fugleramme](https://github.com/arnegiacomo/fugleramme) ships
~800 species of hand-coloured plates (Gould, the von Wright brothers,
Audubon, ...), already cut out and filed by scientific name. The artwork is
CC BY-SA 4.0 (see CLASSIC_PLATES.md).

Birdy does not bundle the plates: the ones a collage needs are downloaded to
a `ClassicPlates/` folder next to Birdy, from the commit pinned in
`classic_pack_manifest.txt`, and reused from there afterwards.

This module has no dependency on birdweather_local, so it can be imported by
it, by the settings GUI and by the tests alike.
"""

from __future__ import annotations

import os
import re
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

MANIFEST_NAME = "classic_pack_manifest.txt"
SOURCE_URL = "https://github.com/arnegiacomo/fugleramme"
RAW_BASE = "https://raw.githubusercontent.com/arnegiacomo/fugleramme/"
BIRDS_PATH = "/assets/artwork/classic/birds/"
USER_AGENT = "birdy-classic-plates/1.0"

_SLUG = re.compile(r"[a-z0-9]+(-[a-z0-9]+)*")
_COMMIT = re.compile(r"[0-9a-f]{40}")

ATTRIBUTION = (
    "Classic bird plates from Fugleramme\n"
    f"{SOURCE_URL}\n\n"
    "Hand-coloured 1800s plates (Gould, von Wright, Audubon and others),\n"
    "digitally restored and cut out for that project.\n"
    "Licence: CC BY-SA 4.0  https://creativecommons.org/licenses/by-sa/4.0/\n"
    "Per-plate sources: assets/artwork/classic/manifest.json in that repo.\n"
)


class Manifest:
    """The pack's species index: which slugs have a plate, and at which commit."""

    def __init__(self, commit="", species=(), aliases=None):
        self.commit = commit
        self.species = frozenset(species)
        self.aliases = dict(aliases or {})

    def __bool__(self):
        return bool(self.commit) and bool(self.species)

    def resolve(self, scientific):
        """Slug of the plate for this scientific name, or None."""
        slug = slug_for(scientific)
        if not slug:
            return None
        if slug in self.species:
            return slug
        target = self.aliases.get(slug)
        return target if target in self.species else None


def slug_for(scientific):
    """'Erithacus rubecula' -> 'erithacus-rubecula', the pack's filenames."""
    if not scientific:
        return None
    slug = re.sub(r"[^a-z0-9]+", "-", str(scientific).lower()).strip("-")
    return slug or None


def parse_manifest(lines):
    commit, species, aliases = "", set(), {}
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("@commit"):
            value = line[len("@commit"):].strip()
            if _COMMIT.fullmatch(value):
                commit = value
            continue
        if "=" in line:
            old, _, new = line.partition("=")
            old, new = old.strip(), new.strip()
            if _SLUG.fullmatch(old) and _SLUG.fullmatch(new):
                aliases[old] = new
            continue
        if _SLUG.fullmatch(line):
            species.add(line)
    return Manifest(commit, species, aliases)


def manifest_path():
    """The index ships inside the exe (packs/), or lives in the source tree."""
    here = os.path.dirname(os.path.abspath(__file__))
    bundled = getattr(sys, "_MEIPASS", here)
    candidates = [
        os.path.join(bundled, "packs", MANIFEST_NAME),
        os.path.join(here, "packs", MANIFEST_NAME),
        os.path.join(here, "android", "app", "src", "main", "assets", MANIFEST_NAME),
    ]
    for path in candidates:
        if os.path.isfile(path):
            return path
    return None


_MANIFEST = None


def load_manifest(path=None):
    """Cached pack index; an empty Manifest if the file is missing."""
    global _MANIFEST
    if path is None and _MANIFEST is not None:
        return _MANIFEST
    target = path or manifest_path()
    manifest = Manifest()
    if target:
        try:
            with open(target, encoding="utf-8") as fh:
                manifest = parse_manifest(fh)
        except OSError:
            pass
    if path is None:
        _MANIFEST = manifest
    return manifest


def url_for(commit, slug):
    if not _COMMIT.fullmatch(commit or "") or not _SLUG.fullmatch(slug or ""):
        raise ValueError(f"refusing to build a plate URL from {commit!r} / {slug!r}")
    return f"{RAW_BASE}{commit}{BIRDS_PATH}{slug}.webp"


def cached_path(cache_dir, slug):
    path = os.path.join(cache_dir, slug + ".webp")
    try:
        return path if os.path.getsize(path) > 0 else None
    except OSError:
        return None


def _http_get(url):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return resp.read()


def ensure(manifest, scientific, cache_dir, fetch=_http_get):
    """Path to this species' plate, downloading it once if needed.

    Returns None when the pack has no plate for it or the download fails."""
    slug = manifest.resolve(scientific)
    if not slug:
        return None
    have = cached_path(cache_dir, slug)
    if have:
        return have
    data = fetch(url_for(manifest.commit, slug))
    # A WebP is a RIFF container; anything else is an error page, not a plate.
    if not data or data[:4] != b"RIFF" or data[8:12] != b"WEBP":
        return None
    os.makedirs(cache_dir, exist_ok=True)
    note = os.path.join(cache_dir, "ATTRIBUTION.txt")
    if not os.path.exists(note):
        with open(note, "w", encoding="utf-8") as fh:
            fh.write(ATTRIBUTION)
    dest = os.path.join(cache_dir, slug + ".webp")
    tmp = dest + ".tmp"
    with open(tmp, "wb") as fh:
        fh.write(data)
    os.replace(tmp, dest)
    return dest


def ensure_many(manifest, scientific_names, cache_dir, fetch=_http_get, workers=6):
    """Fetch several plates at once.

    Returns ({scientific: path}, downloaded, failed). A failed download just
    leaves that species out; it never raises."""
    paths, todo = {}, {}
    for name in scientific_names:
        slug = manifest.resolve(name)
        if not slug:
            continue
        have = cached_path(cache_dir, slug)
        if have:
            paths[name] = have
        else:
            # Keyed by slug: an old and a new name for one bird share a plate,
            # and must not race each other writing the same file.
            todo.setdefault(slug, []).append(name)
    downloaded = failed = 0
    if todo:
        def _one(item):
            slug, names = item
            try:
                return names, ensure(manifest, names[0], cache_dir, fetch)
            except Exception:
                return names, None
        with ThreadPoolExecutor(max_workers=max(1, min(workers, len(todo)))) as pool:
            for names, path in pool.map(_one, todo.items()):
                if path:
                    for name in names:
                        paths[name] = path
                    downloaded += 1
                else:
                    failed += 1
    return paths, downloaded, failed
