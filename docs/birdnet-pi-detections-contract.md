# BirdNET-Pi detections contract (Android preferred local source)

Docs-only. Honest facts from upstream **Nachtzuster/BirdNET-Pi** `main` (verified via `gh`), plus Henry’s host probe notes. For Jill’s Android Birdy preferred source when a Pi is configured. Shared collage/species shape stays in [`android-shared-contract.md`](./android-shared-contract.md) — this doc only covers **how to fetch** detections from BirdNET-Pi and map into that shape.

Do **not** treat open upstream PRs as available on stock installs unless Henry’s host actually serves them.

---

## Henry’s hosts / reachability

| Base | Notes |
|---|---|
| `https://birds.henrybeevers.org` | Intended public / WAN hostname |
| `http://192.168.1.159` | LAN IP of the Pi |

**Public probe (2026-09-06):** from the public internet, `https://birds.henrybeevers.org` (root, `/api/v1/detections/recent?limit=1`, and `todays_detections.php?ajax_detections=true&hard_limit=1`) returned **HTTP 403 Access Denied** — auth and/or LAN gate. Android on the home LAN (or with whatever auth Henry uses) can reach the Pi; stock public access cannot.

### Config

- Key: `birdnet_pi_base_url`
- Value: origin only, **no trailing slash** (e.g. `http://192.168.1.159` or `https://birds.henrybeevers.org`)
- All paths below are appended to this base

---

## What is stable JSON on Nachtzuster `main` today

### Image provider — `GET /api/v1/image/{Sci_Name}`

Routed by `scripts/api.php` on `main`. That file’s only `/api/v1/...` route today is this image endpoint (everything else → 404).

| | |
|---|---|
| Path | `/api/v1/image/{Sci_Name}` |
| `{Sci_Name}` | URL-encoded scientific name; spaces allowed (e.g. `Parus%20major`) |
| Method | `GET` only (other methods → 405) |
| Success body | JSON `{ "status", "message", "data" }` where `data` is the image-provider payload |
| Missing image | HTTP 404 text error |

Use as an **optional remote thumb** when no local illustration asset matches (see shared contract §3).

---

## Detections on stock `main`: mostly HTML AJAX, not REST JSON

There is **no** detections list JSON API on Nachtzuster `main` today.

### Primary fallback — `GET /todays_detections.php?ajax_detections=true`

Returns **HTML table rows** (AJAX fragment), not a JSON array. SQLite select used by that path:

`Date`, `Time`, `Com_Name`, `Sci_Name`, `Confidence`, `File_Name`

| Query param | Role |
|---|---|
| `ajax_detections=true` | Required to enter the AJAX HTML path |
| `hard_limit=N` | `LIMIT N` (newest first by `Time DESC`, today’s date localtime) — prefer this for Android |
| `display_limit=N` | Pagination window used by the web UI (“load more”); offset style — avoid unless matching UI behaviour |
| `searchterm` | Optional filter over Com/Sci/Confidence/File_Name/Time |
| `mobile` | Tweaks mobile HTML layout |
| `kiosk` | Kiosk HTML variant |

**Confidence:** DB float **0–1**; the HTML UI shows `round(Confidence, 2) * 100` as percent.

**Audio path pattern** (from the PHP that builds the row):

```
/By_Date/{YYYY-MM-DD}/{Com_Name_with_underscores}/{File_Name}
```

(`Com_Name` spaces → `_`; apostrophes stripped for the path segment.)

Android v1 only needs to parse species fields from the HTML (`Com_Name`, `Sci_Name`, `Confidence`, `Time`); audio paths are optional.

### Small JSON chart helper (not a detection list)

```
GET /todays_detections.php?comname={CommonName}&days=N
```

→ JSON **array** of `{ "date": "YYYY-MM-DD", "count": <int> }` (daily counts for one common name). Default `days` in PHP is 30. **Not** a recent-detections list — do not use it as the collage source.

---

## Proposed REST (not on stock `main` yet)

**[Nachtzuster/BirdNET-Pi PR #575](https://github.com/Nachtzuster/BirdNET-Pi/pull/575)** (title includes analytics / API work) proposes among other routes:

```
GET /api/v1/detections/recent?limit=N
```

Optional `days` in the PR patch: if `days > 0`, filter `Date >= now - days`; else today (localtime). Cap `limit` at 100; default 20.

Proposed JSON array elements (from the PR patch — **not merged**):

```json
{
  "species": "<Com_Name>",
  "sci_name": "<Sci_Name>",
  "confidence": 0.1234,
  "date": "YYYY-MM-DD",
  "time": "HH:MM:SS"
}
```

Also proposed (same PR): `/api/v1/detections/timeline`, plus several `/api/v1/analytics/...` helpers. **Status:** PR is **OPEN / not merged to `main`** as of the verification used for this doc. Do **not** assume stock Nachtzuster installs expose these.

**Henry may run a fork or cherry-pick.** Always **probe** his configured base first; if `GET {base}/api/v1/detections/recent?limit=N` returns **200** with JSON, prefer it.

---

## Android v1 recommended flow

1. Read `birdnet_pi_base_url` (no trailing slash). If empty → BirdWeather path only (shared contract).
2. **Probe preferred JSON:**  
   `GET {base}/api/v1/detections/recent?limit=N`  
   - If **200** and parseable JSON array → use it (map fields below).  
   - If **404 / non-JSON / network error** → fall through.
3. **Fallback HTML AJAX:**  
   `GET {base}/todays_detections.php?ajax_detections=true&hard_limit=N`  
   Parse table rows for `Com_Name`, `Sci_Name`, `Confidence`, `Time` (and `Date` if present).
4. **Map into the same collage species shape** as [`android-shared-contract.md`](./android-shared-contract.md) / Android `SpeciesDetection`:

| Collage / `SpeciesDetection` | BirdNET-Pi source |
|---|---|
| `name` (common) | `species` (PR JSON) or `Com_Name` (HTML/SQLite) |
| `scientific` | `sci_name` or `Sci_Name` |
| `score` | `confidence` / `Confidence` (keep **0–1** float; do not store the UI percent) |
| `timestampIso` / time | Combine `date`+`time` when available, or today’s date + `Time` from AJAX |
| `thumb` | Leave empty unless using image API / local asset |
| `station` | e.g. `"BirdNET-Pi"` or hostname — Pi has no BirdWeather station object |
| `credit` / `license` | Optional; image API `data` may carry provider metadata |

Then apply the **same** confidence filter → dedupe-by-common-name (newest) → cap (40/60) → illustration matching as in the shared contract.

5. **Illustrations:** reuse shared filename / slug matching against bundled assets. Optional remote thumb:  
   `GET {base}/api/v1/image/{urlencode(Sci_Name)}` → use a URL from `data` if present when no local asset.

### Practical notes

- Cleartext LAN (`http://192.168.1.159`) needs Android cleartext / network-security allowance for that host.
- Expect **403** on the public hostname without Henry’s auth/LAN path; surface a settings error rather than silently falling back unless BirdWeather is also configured.
- `hard_limit` is “today only” on stock PHP; multi-day history needs the PR JSON (`days`) or another source.

---

## Quick checklist

1. Config `birdnet_pi_base_url` (no trailing slash).
2. Probe `/api/v1/detections/recent?limit=N` — use if 200 JSON (may be Henry-only until PR #575 merges).
3. Else parse `/todays_detections.php?ajax_detections=true&hard_limit=N` HTML for Com/Sci/Confidence/Time.
4. Map to shared species shape (`name` / `scientific` / `score`); filter → dedupe → cap.
5. Art: local matcher → else optional `/api/v1/image/{Sci_Name}`.

Upstream anchors: Nachtzuster/BirdNET-Pi `scripts/api.php`, `scripts/todays_detections.php` on `main`; PR #575 for proposed `/api/v1/detections/*`.
