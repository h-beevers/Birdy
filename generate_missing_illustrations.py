#!/usr/bin/env python3
"""
generate_missing_illustrations.py

Top up your Birdy Illustrations/ folder automatically. For every bird being
detected near you that doesn't already have a local illustration, this script:

  1. Pulls the live species list from BirdWeather (reusing birdweather_local's
     own query, so it matches exactly what your wallpaper will show).
  2. Diffs it against Illustrations/ using Birdy's own build_illustration_index
     / _slugify matching, so "missing" == "what the wallpaper would fall back".
  3. Builds a factual prompt + sends up to N of your EXISTING illustrations as
     style references, so the new art matches your set (same art style, same
     flat cream background Birdy's cutout_illustration() expects).
  4. Calls OpenRouter's unified image API to generate a PNG.
  5. Verifies it two ways: a cheap PIL corner-colour check (background must be
     flat cream, or Birdy's cutout will fail) + a vision-model pass ("is this a
     recognisable {species} in the right style?").
  6. Saves PASS images straight into Illustrations/ (named by common name, which
     Birdy matches). FAIL images go to Pending/ with a .json sidecar + reason,
     so nothing bad lands in your live folder.
  7. Writes a single-file review contact sheet (illustration_review.html) and a
     JSONL cost log, so you can eyeball a whole batch in one glance.

It makes NO changes to birdweather_local.py — it's a separate tool you run
standalone (on demand, or on a schedule) to keep the art folder stocked, so the
wallpaper never falls back to a photo.

Costs are enforced by a hard --max-cost budget (OpenRouter returns the exact
USD cost per image in the response). Defaults are tuned to stay far under a
100-image, pennies-per-image ceiling.

Requires: an OpenRouter API key (env OPENROUTER_API_KEY or --key).
Optional: Pillow (for the background-colour sanity check).
"""

import argparse
import base64
import io
import json
import math
import os
import re
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

# Make the sibling birdweather_local importable regardless of CWD.
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)
import birdweather_local as B  # noqa: E402  (deliberate late import after path fix)

try:
    from PIL import Image
    PIL_OK = True
except Exception:
    PIL_OK = False

OPENROUTER_IMAGES = "https://openrouter.ai/api/v1/images"
OPENROUTER_CHAT = "https://openrouter.ai/api/v1/chat/completions"

# Birdy's cutout_illustration() samples the four corners and removes a
# roughly-uniform background — your existing art uses a flat cream ground.
CREAM = (244, 237, 224)
CREAM_HEX = "#F4EDE0"

DEFAULT_IMAGE_MODEL = "google/gemini-3.1-flash-image"
DEFAULT_VERIFY_MODEL = "google/gemini-2.5-flash"

WIKI_SEARCH = "https://en.wikipedia.org/w/api.php"
WIKI_SUMMARY = "https://en.wikipedia.org/api/rest_v1/page/summary/"

# --------------------------------------------------------------------------- #
# HTTP helpers
# --------------------------------------------------------------------------- #
def http_json(url, payload, headers, timeout=180, max_retries=3):
    data = json.dumps(payload).encode("utf-8")
    last_err = None
    for attempt in range(max_retries):
        req = urllib.request.Request(url, data=data, method="POST")
        for k, v in headers.items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")
            last_err = f"{e.code} {body}"
            # 429 / 5xx: back off and retry. 4xx (bad request/auth) won't fix itself.
            if e.code in (429, 500, 502, 503, 504):
                wait = 2 ** attempt + 1
                print(f"    ! HTTP {e.code}, retrying in {wait}s...")
                time.sleep(wait)
                continue
            raise RuntimeError(f"HTTP {e.code}: {body}")
        except (urllib.error.URLError, TimeoutError) as e:
            last_err = str(e)
            wait = 2 ** attempt + 1
            print(f"    ! network error ({e}), retrying in {wait}s...")
            time.sleep(wait)
    raise RuntimeError(f"request failed after {max_retries} tries: {last_err}")


def fetch_wikipedia_description(common_name):
    """Best-effort factual plumage/habitat blurb to anchor accuracy. Returns
    '' on any failure — the model still knows common garden birds by name."""
    try:
        q = urllib.parse.quote(f"{common_name} bird")
        search_url = f"{WIKI_SEARCH}?action=query&list=search&srsearch={q}&format=json&srlimit=1"
        req = urllib.request.Request(search_url, headers={"User-Agent": "Birdy-illu-gen/1.0"})
        with urllib.request.urlopen(req, timeout=15) as r:
            search = json.loads(r.read().decode("utf-8"))
        hits = search.get("query", {}).get("search", [])
        if not hits:
            return ""
        title = hits[0]["title"]
        sum_url = WIKI_SUMMARY + urllib.parse.quote(title)
        req2 = urllib.request.Request(sum_url, headers={"User-Agent": "Birdy-illu-gen/1.0"})
        with urllib.request.urlopen(req2, timeout=15) as r2:
            summary = json.loads(r2.read().decode("utf-8"))
        extract = summary.get("extract", "")
        # Keep it short — just the identifying opener.
        extract = extract.split(". ")[0]
        return extract.strip()
    except Exception as e:
        print(f"    ! wikipedia lookup failed ({e}), using name only")
        return ""


# --------------------------------------------------------------------------- #
# Species discovery (reuse Birdy's own query)
# --------------------------------------------------------------------------- #
def fetch_detected_species(lat, lon, radius_km, days):
    data = B.fetch_nearby(lat, lon, radius_km, days, "day", first=300)
    nodes = data["detections"]["nodes"]
    species_list = B.dedupe_species(nodes)
    counts = B.count_species(nodes)
    for s in species_list:
        s["count"] = counts.get(s["name"], 1)
    # Most-detected first, so we top up the birds you actually see.
    species_list.sort(key=lambda d: d.get("count", 1), reverse=True)
    return species_list


def diff_missing(species_list, illustrations_dir):
    index = B.build_illustration_index(illustrations_dir)
    missing = []
    for s in species_list:
        slug = B._slugify(s["name"])
        if slug not in index:
            missing.append(s)
    return missing


# --------------------------------------------------------------------------- #
# Prompt + image generation
# --------------------------------------------------------------------------- #
def build_prompt(species, description):
    common = species["name"]
    scientific = species.get("scientific") or ""
    prompt = (
        f"A painted illustration of a {common}"
        + (f" ({scientific})" if scientific else "")
        + ", rendered in the same flat, clean, storybook field-guide art "
          "style as the reference images provided. "
    )
    if description:
        prompt += f"Accurate to the real species: {description} "
    prompt += (
        f"The bird should be a full-body portrait, centred and facing left, on a "
        f"perfectly flat, solid, uniform cream-coloured background (hex {CREAM_HEX}), "
        f"with no gradients, shadows, scenery, vignette, or text. Match the "
        f"reference artworks' linework, palette, and level of detail so the new "
        f"image sits seamlessly alongside them in a collage."
    )
    return prompt


def file_to_data_uri(path):
    ext = os.path.splitext(path)[1].lower()
    mime = {".png": "image/png", ".jpg": "image/jpeg",
            ".jpeg": "image/jpeg", ".webp": "image/webp"}.get(ext, "image/png")
    with open(path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")
    return f"data:{mime};base64,{b64}"


def pick_style_refs(illustrations_dir, n, exclude_names):
    """Up to n existing illustrations as style anchors (skip any we're about
    to generate / that failed)."""
    if n <= 0 or not os.path.isdir(illustrations_dir):
        return []
    refs = []
    for fname in sorted(os.listdir(illustrations_dir)):
        if len(refs) >= n:
            break
        base = os.path.splitext(fname)[0]
        if base in exclude_names:
            continue
        if os.path.splitext(fname)[1].lower() not in (".png", ".jpg", ".jpeg", ".webp"):
            continue
        refs.append(os.path.join(illustrations_dir, fname))
    return refs


def generate_image(prompt, ref_paths, model, api_key, aspect_ratio="1:1"):
    refs = [{"type": "image_url", "image_url": {"url": file_to_data_uri(p)}}
            for p in ref_paths]
    payload = {
        "model": model,
        "prompt": prompt,
        "aspect_ratio": aspect_ratio,
        "output_format": "png",
    }
    if refs:
        payload["input_references"] = refs
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    result = http_json(OPENROUTER_IMAGES, payload, headers)
    if "error" in result:
        raise RuntimeError(f"image API error: {result['error']}")
    item = result.get("data", [{}])[0]
    b64 = item.get("b64_json")
    url = item.get("url")
    media_type = item.get("media_type", "image/png")
    if b64:
        img_bytes = base64.b64decode(b64)
    elif url:
        req = urllib.request.Request(url, headers={"User-Agent": "Birdy-illu-gen/1.0"})
        with urllib.request.urlopen(req, timeout=30) as r:
            img_bytes = r.read()
    else:
        raise RuntimeError("image API returned neither b64_json nor url")
    cost = (result.get("usage") or {}).get("cost", 0.0) or 0.0
    return img_bytes, media_type, cost


# --------------------------------------------------------------------------- #
# Verification
# --------------------------------------------------------------------------- #
def background_is_flat_cream(img_bytes, tol=45):
    """Cheap structural gate: Birdy's cutout_illustration() samples the four
    corners and removes a uniform background. If the model ignored the cream
    instruction and drew a scene/photo, the cutout would mangle it."""
    if not PIL_OK:
        return True  # can't check without Pillow; let vision pass decide
    try:
        img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
        w, h = img.size
        pts = [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1),
               (w // 2, 0), (0, h // 2)]
        avg = [0, 0, 0]
        for (x, y) in pts:
            px = img.getpixel((x, y))
            for i in range(3):
                avg[i] += px[i]
        avg = [c / len(pts) for c in avg]
        dist = math.sqrt(sum((avg[i] - CREAM[i]) ** 2 for i in range(3)))
        if dist > tol:
            return False
        # Also reject "all cream" (empty image) — there must be non-cream pixels.
        extrema = img.getextrema()
        if all(lo == hi for (lo, hi) in extrema):
            return False
        return True
    except Exception:
        return True


def verify_image(img_bytes, species, verify_model, api_key):
    common = species["name"]
    scientific = species.get("scientific") or ""
    text = (
        f"You are checking AI-generated bird artwork for a desktop wallpaper app. "
        f"The image should depict a {common}"
        + (f" ({scientific})" if scientific else "")
        + " as a clean painted illustration on a flat cream background, in a "
          "storybook field-guide style. Is the bird clearly and recognisably the "
          "correct species (correct major plumage colours, body shape, and "
          "distinguishing features), and is the background a flat cream colour "
          "rather than a photo or scene? Answer with exactly one word — PASS or "
          "FAIL — on the first line, then a brief reason on the second line."
    )
    mime = "image/png"
    b64 = base64.b64encode(img_bytes).decode("ascii")
    payload = {
        "model": verify_model,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "text", "text": text},
                {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{b64}"}},
            ],
        }],
        "max_tokens": 200,
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    result = http_json(OPENROUTER_CHAT, payload, headers, timeout=120)
    if "error" in result:
        raise RuntimeError(f"verify API error: {result['error']}")
    content = result["choices"][0]["message"]["content"]
    cost = (result.get("usage") or {}).get("cost", 0.0) or 0.0
    first_line = content.strip().splitlines()[0].upper()
    passed = "PASS" in first_line and "FAIL" not in first_line
    return passed, content.strip(), cost


# --------------------------------------------------------------------------- #
# Output: save, quarantine, contact sheet
# --------------------------------------------------------------------------- #
def save_png(img_bytes, path):
    with open(path, "wb") as f:
        f.write(img_bytes)


def build_contact_sheet(records, out_html, illustrations_dir, pending_dir):
    rows = []
    base = os.path.dirname(out_html)
    for rec in records:
        # Prefer a relative path so the sheet is portable, but fall back to an
        # absolute file:// URL when the image sits on a different drive (relpath
        # raises ValueError across drive mounts, e.g. Pending/ on E:, sheet on C:).
        try:
            rel = os.path.relpath(rec["path"], base)
            src = rel.replace("\\", "/")
        except ValueError:
            src = "file:///" + rec["path"].replace("\\", "/")
        badge = "PASS" if rec["passed"] else "FAIL"
        color = "#3a7d34" if rec["passed"] else "#b5572a"
        rows.append(f"""
        <div class="card">
          <div class="badge" style="background:{color}">{badge}</div>
          <img src="{src}" alt="{rec['name']}">
          <div class="name">{rec['name']}</div>
          <div class="sci">{rec.get('scientific','')}</div>
          <div class="meta">${rec['gen_cost']:.4f} gen · ${rec['verify_cost']:.4f} verify</div>
          <div class="reason">{rec.get('reason','')}</div>
        </div>""")
    html = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>Birdy illustration review</title>
<style>
  body {{ font-family: Georgia, 'Times New Roman', serif; background:#f4ede0; color:#2e2620; padding:32px; }}
  h1 {{ font-weight:500; }}
  .grid {{ display:grid; grid-template-columns:repeat(auto-fill,minmax(180px,1fr)); gap:18px; margin-top:24px; }}
  .card {{ background:#fff; border:1px solid #ddd0b8; border-radius:10px; padding:10px; position:relative; }}
  .card img {{ width:100%; height:160px; object-fit:contain; background:#f4ede0; border-radius:6px; }}
  .badge {{ position:absolute; top:8px; right:8px; color:#fff; font-size:0.7rem; padding:2px 8px; border-radius:10px; }}
  .name {{ font-weight:600; margin-top:6px; }}
  .sci {{ font-style:italic; font-size:0.78rem; color:#8a7c65; }}
  .meta {{ font-size:0.7rem; color:#6b5f4f; margin-top:2px; }}
  .reason {{ font-size:0.7rem; color:#5c6b47; margin-top:4px; max-height:60px; overflow:auto; }}
</style></head><body>
<h1>Birdy illustration review &mdash; {datetime.now().strftime('%Y-%m-%d %H:%M')}</h1>
<p>{len(records)} generated &middot; {sum(1 for r in records if r['passed'])} passed &middot;
   {sum(1 for r in records if not r['passed'])} quarantined to Pending/</p>
<div class="grid">{''.join(rows)}</div>
</body></html>"""
    with open(out_html, "w", encoding="utf-8") as f:
        f.write(html)


# --------------------------------------------------------------------------- #
# Release / rebuild (no API calls)
# --------------------------------------------------------------------------- #
def _do_release(base_name, pending_dir, ill_dir, args):
    """Move one quarantined image (named <base_name>.png + .json) into
    Illustrations/. Returns (ok, message)."""
    src_path = os.path.join(pending_dir, base_name + ".png")
    if not os.path.isfile(src_path):
        return False, f"Couldn't find a quarantined image for '{base_name}'."
    sidecar = os.path.join(pending_dir, base_name + ".json")
    meta = {}
    if os.path.isfile(sidecar):
        try:
            meta = json.load(open(sidecar, encoding="utf-8"))
        except Exception:
            pass
    common = meta.get("species") or base_name
    dest = os.path.join(ill_dir, common + ".png")
    if os.path.exists(dest) and not args.overwrite:
        return False, (f"Refusing: {dest} already exists "
                       f"(use --overwrite to replace).")
    shutil.move(src_path, dest)
    if os.path.isfile(sidecar):
        os.remove(sidecar)
    _log(os.path.join(SCRIPT_DIR, "illustration_generation_log.jsonl"),
         {"species": common, "scientific": meta.get("scientific", ""),
          "status": "released", "ts": _now(),
          "gen_cost": meta.get("gen_cost", 0.0),
          "verify_cost": meta.get("verify_cost", 0.0)})
    return True, (f"Released '{common}' -> Illustrations/{common}.png "
                  f"(Birdy will now match it on the next refresh).")


def release_pending(args):
    """Force-move a quarantined image into Illustrations/ so Birdy matches it.
    The file is already named <Common Name>.png, which is exactly what
    find_local_illustration() keys on, so no rename logic is needed."""
    pending_dir = args.pending_dir
    ill_dir = args.illustrations_dir
    os.makedirs(ill_dir, exist_ok=True)
    src = args.release
    base = os.path.splitext(os.path.basename(src))[0] if os.path.isfile(src) else src
    ok, msg = _do_release(base, pending_dir, ill_dir, args)
    print(msg)
    if ok:
        rebuild_review_sheet(args)


def release_all(args):
    """Release every quarantined image in Pending/ into Illustrations/ in one
    go (handy once you've eyeballed them all and decided they're good enough).
    Respects --overwrite for any name collision."""
    pending_dir = args.pending_dir
    ill_dir = args.illustrations_dir
    os.makedirs(ill_dir, exist_ok=True)
    if not os.path.isdir(pending_dir):
        print(f"No Pending/ folder at {pending_dir}.")
        return
    pngs = sorted(f for f in os.listdir(pending_dir)
                  if os.path.splitext(f)[1].lower() == ".png")
    if not pngs:
        print("Nothing quarantined — Pending/ is empty.")
        return
    released = skipped = 0
    for f in pngs:
        ok, msg = _do_release(os.path.splitext(f)[0], pending_dir, ill_dir, args)
        print(msg)
        released += 1 if ok else 0
        skipped += 0 if ok else 1
    print(f"\nReleased {released}, skipped {skipped}.")
    rebuild_review_sheet(args)


def rebuild_review_sheet(args):
    """Regenerate illustration_review.html from whatever is still in Pending/,
    with no network calls — handy after releasing a few by hand."""
    pending_dir = args.pending_dir
    ill_dir = args.illustrations_dir
    out = os.path.join(SCRIPT_DIR, "illustration_review.html")
    records = []
    if os.path.isdir(pending_dir):
        for fname in sorted(os.listdir(pending_dir)):
            if os.path.splitext(fname)[1].lower() != ".png":
                continue
            base = os.path.splitext(fname)[0]
            sidecar = os.path.join(pending_dir, base + ".json")
            meta = {}
            if os.path.isfile(sidecar):
                try:
                    meta = json.load(open(sidecar, encoding="utf-8"))
                except Exception:
                    pass
            records.append({
                "name": meta.get("species", base),
                "scientific": meta.get("scientific", ""),
                "path": os.path.join(pending_dir, fname),
                "passed": False,
                "reason": meta.get("reason", ""),
                "gen_cost": meta.get("gen_cost", 0.0),
                "verify_cost": meta.get("verify_cost", 0.0),
            })
    build_contact_sheet(records, out, ill_dir, pending_dir)
    n_ill = len([f for f in os.listdir(ill_dir)
                 if os.path.splitext(f)[1].lower() in (".png", ".jpg", ".jpeg", ".webp")]) \
        if os.path.isdir(ill_dir) else 0
    print(f"Review sheet rebuilt: {out} "
          f"({len(records)} still quarantined, {n_ill} in Illustrations/)")


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #
def main():
    p = argparse.ArgumentParser(
        description="Auto-generate missing Birdy bird illustrations via OpenRouter.",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    _def = getattr(B, "_DEFAULTS", {})
    p.add_argument("--lat", type=float, default=float(_def.get("fallback_lat", 53.93)))
    p.add_argument("--lon", type=float, default=float(_def.get("fallback_lon", -1.45)))
    p.add_argument("--radius-km", type=float, default=20)
    p.add_argument("--days", type=int, default=30)
    p.add_argument("--limit", type=int, default=100,
                   help="Max birds to generate this run (default 100).")
    p.add_argument("--illustrations-dir", default=os.path.join(SCRIPT_DIR, "Illustrations"))
    p.add_argument("--pending-dir", default=os.path.join(SCRIPT_DIR, "Pending"))
    p.add_argument("--style-refs", type=int, default=4,
                   help="How many existing illustrations to send as style references.")
    p.add_argument("--image-model", default=DEFAULT_IMAGE_MODEL)
    p.add_argument("--verify-model", default=DEFAULT_VERIFY_MODEL)
    p.add_argument("--no-verify", action="store_true", help="Skip the vision verify pass.")
    p.add_argument("--no-wiki", action="store_true", help="Skip Wikipedia description lookup.")
    p.add_argument("--max-cost", type=float, default=5.0,
                   help="Hard stop once cumulative spend (USD) reaches this.")
    p.add_argument("--key", default=os.environ.get("OPENROUTER_API_KEY"),
                   help="OpenRouter API key (or set OPENROUTER_API_KEY).")
    p.add_argument("--dry-run", action="store_true",
                   help="Print the species-to-generate + prompts, no API calls / no saves.")
    p.add_argument("--release", metavar="NAME_OR_PATH", default=None,
                   help="Force-release a quarantined image into Illustrations/ "
                        "(by species name or path), overriding the verdict.")
    p.add_argument("--release-all", action="store_true",
                   help="Release every quarantined image in Pending/ into "
                        "Illustrations/ in one go.")
    p.add_argument("--rebuild-sheet", action="store_true",
                   help="Rebuild illustration_review.html from the Pending/ folder only.")
    p.add_argument("--overwrite", action="store_true",
                   help="Overwrite an existing Illustrations/ file on release.")
    args = p.parse_args()

    # Non-API modes: release a quarantined image, release all, or rebuild sheet.
    if args.release:
        release_pending(args)
        return
    if args.release_all:
        release_all(args)
        return
    if args.rebuild_sheet:
        rebuild_review_sheet(args)
        return

    if not args.dry_run and not args.key:
        p.error("OpenRouter API key required (--key or OPENROUTER_API_KEY env).")

    print(f"Querying BirdWeather within {args.radius_km} km of "
          f"({args.lat:.4f}, {args.lon:.4f}), last {args.days} days...")
    species = fetch_detected_species(args.lat, args.lon, args.radius_km, args.days)
    print(f"  {len(species)} species detected nearby.")

    missing = diff_missing(species, args.illustrations_dir)
    print(f"  {len(missing)} have no local illustration yet.")
    if not missing:
        print("Nothing to generate — your Illustrations/ folder is fully stocked!")
        return

    missing = missing[:args.limit]

    if args.dry_run:
        print(f"\n--- DRY RUN: would generate {len(missing)} illustration(s) "
              f"(no API calls made) ---")
        for s in missing:
            desc = "" if args.no_wiki else fetch_wikipedia_description(s["name"])
            prompt = build_prompt(s, desc)
            print(f"\n* {s['name']} ({s.get('scientific','')})  [{s.get('count',1)} detections]")
            print(f"  file -> {os.path.join(args.illustrations_dir, s['name'] + '.png')}")
            print(f"  prompt: {prompt[:240]}{'...' if len(prompt) > 240 else ''}")
        print(f"\nDry run complete. Re-run without --dry-run (and with --key) to generate.")
        return

    os.makedirs(args.pending_dir, exist_ok=True)
    log_path = os.path.join(SCRIPT_DIR, "illustration_generation_log.jsonl")
    contact_path = os.path.join(SCRIPT_DIR, "illustration_review.html")

    total_spend = 0.0
    records = []
    generated = 0

    for s in missing:
        if total_spend >= args.max_cost:
            print(f"\nReached --max-cost ${args.max_cost:.2f}; stopping. "
                  f"{len(missing) - generated} birds left for next run.")
            break

        common = s["name"]
        print(f"\n[{generated + 1}/{len(missing)}] {common} "
              f"({s.get('scientific','')}) — {s.get('count',1)} detections")

        desc = "" if args.no_wiki else fetch_wikipedia_description(common)
        prompt = build_prompt(s, desc)
        refs = pick_style_refs(args.illustrations_dir, args.style_refs,
                               exclude_names={common})
        print(f"    prompt: {prompt[:120]}...")
        print(f"    style refs: {len(refs)}")

        try:
            img_bytes, media_type, gen_cost = generate_image(
                prompt, refs, args.image_model, args.key)
        except Exception as e:
            print(f"    ! generation failed: {e}")
            _log(log_path, {**s, "status": "error", "error": str(e),
                            "ts": _now(), "gen_cost": 0.0, "verify_cost": 0.0})
            continue

        verify_cost = 0.0
        reason = ""
        passed = True

        if not background_is_flat_cream(img_bytes):
            passed = False
            reason = "Background is not a flat cream colour — Birdy's cutout would fail."
        elif not args.no_verify:
            try:
                vpass, vreason, verify_cost = verify_image(
                    img_bytes, s, args.verify_model, args.key)
                passed = vpass
                reason = vreason
            except Exception as e:
                print(f"    ! verify failed ({e}); keeping image (unverified).")
                reason = f"verify error: {e}"
        else:
            reason = "verify skipped"

        total_spend += gen_cost + verify_cost

        if passed:
            dest = os.path.join(args.illustrations_dir, common + ".png")
            save_png(img_bytes, dest)
            print(f"    PASS — saved to Illustrations/{common}.png "
                  f"(${gen_cost:.4f} gen, ${verify_cost:.4f} verify)")
        else:
            dest = os.path.join(args.pending_dir, common + ".png")
            save_png(img_bytes, dest)
            sidecar = {"species": common, "scientific": s.get("scientific", ""),
                       "prompt": prompt, "reason": reason,
                       "gen_cost": gen_cost, "verify_cost": verify_cost,
                       "ts": _now()}
            with open(os.path.join(args.pending_dir, common + ".json"), "w",
                      encoding="utf-8") as f:
                json.dump(sidecar, f, indent=2)
            print(f"    FAIL — quarantined to Pending/{common}.png "
                  f"(reason: {reason.splitlines()[0] if reason else ''})")

        records.append({
            "name": common, "scientific": s.get("scientific", ""),
            "path": dest, "passed": passed, "reason": reason,
            "gen_cost": gen_cost, "verify_cost": verify_cost,
        })
        _log(log_path, {**s, "status": "pass" if passed else "fail",
                        "reason": reason, "gen_cost": gen_cost,
                        "verify_cost": verify_cost, "ts": _now()})
        generated += 1
        time.sleep(0.5)  # be polite to the API

    build_contact_sheet(records, contact_path, args.illustrations_dir, args.pending_dir)
    print(f"\nDone. {generated} generated, {sum(1 for r in records if r['passed'])} passed, "
          f"{sum(1 for r in records if not r['passed'])} quarantined.")
    print(f"Total spend this run: ${total_spend:.4f}")
    print(f"Review sheet: {contact_path}")
    print(f"Cost log:     {log_path}")


def _now():
    return datetime.now(timezone.utc).isoformat()


def _log(path, entry):
    with open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(entry) + "\n")


if __name__ == "__main__":
    main()
