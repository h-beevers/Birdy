# Android shared contract (Birdy ↔ Jill v1)

Docs-only extract from desktop `birdweather_local.py` (+ `rename_latin_illustrations.safe_filename`) at `main` / `0a28184`. Reimplement these behaviours in Kotlin; do **not** change desktop collage/UI here.

Function pointers are Python names.

BirdNET-Pi preferred local detections source: see [`birdnet-pi-detections-contract.md`](./birdnet-pi-detections-contract.md).

---

## 1. BirdWeather GraphQL

### Endpoint / transport

| | |
|---|---|
| URL | `https://app.birdweather.com/graphql` (`BIRDWEATHER_GRAPHQL`) |
| Method | `POST` (`http_post_json`) |
| Body | `{"query": <DETECTIONS_QUERY>, "variables": {...}}` |
| Header | `Content-Type: application/json` |
| Timeout | 20s |
| Auth | none |

Errors: if response JSON has `"errors"`, raise / fail (`fetch_nearby`).

### Exact operation (`DETECTIONS_QUERY`)

```graphql
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
```

### Variables (`fetch_nearby`)

```json
{
  "ne": {"lat": <float>, "lon": <float>},
  "sw": {"lat": <float>, "lon": <float>},
  "period": {"count": <int>, "unit": "day" | "hour"},
  "first": 300
}
```

- Default period: `days` from config → `PERIOD_COUNT` + `PERIOD_UNIT = "day"`.
- If config `hours` is set (non-blank), override: `PERIOD_COUNT = hours`, `PERIOD_UNIT = "hour"`.
- `first` default **300**.

### Postcode → lat/lon → bbox

1. **Postcode** (`lookup_postcode`): `GET https://api.postcodes.io/postcodes/{urlencoded}` (`POSTCODES_API`), UA `avianvisitors-local-preview/1.0`.
   - Read `result.latitude`, `result.longitude`.
   - Place label: `admin_ward` → else `parish` → else `admin_district` → else raw postcode.
2. On failure / no postcode: use `fallback_lat` / `fallback_lon` (defaults `53.93`, `-1.45`).
3. **BBox** (`bounding_box(lat, lon, radius_km)`), equirectangular:
   - `lat_delta = radius_km / 111.0`
   - `lon_delta = radius_km / (111.0 * max(0.1, cos(radians(lat))))`
   - `ne = {lat + lat_delta, lon + lon_delta}`, `sw = {lat - lat_delta, lon - lon_delta}`
4. Default `radius_km = 20`.

### Response fields **actually used**

| Path | Used for |
|---|---|
| `detections.nodes[].timestamp` | parse ISO (`parse_timestamp`); dedupe “most recent”; UI relative time |
| `detections.nodes[].score` | `min_confidence` filter (0–1 float) |
| `species.commonName` | species key / display; skip node if missing |
| `species.scientificName` | labels + illustration latin lookup |
| `species.thumbnailUrl` | fallback art URL |
| `species.imageCredit` / `imageLicense` | stored on species dict (`credit`/`license`); not rendered on cards today |
| `station.name` | per-bird station label (default `"a nearby station"` if absent) |
| `station.location` | **queried, unused** |
| `detections.totalCount` / `speciesCount` | summary counts (pre-filter totals printed; post-filter count when filtering) |
| `stations.totalCount` | summary only |
| `stations.nodes[]` (`name`/`type`/`latestDetectionAt`) | **queried, unused** |

**No station id** is requested or used.

Timestamps: ISO-8601; `Z` → `+00:00` then parse; invalid → `null`.

### `min_confidence` (`filter_detections_by_confidence`)

- Config `min_confidence` clamped to **[0, 1]**; default **0** = keep all.
- Applied in `main` **before** `dedupe_species` on raw `detections.nodes`.
- Drop node if `score` is present and `float(score) < min_confidence`.
- Keep if `score` is `null` / missing / non-numeric (so threshold never empties flock solely for absent scores).
- `min_confidence <= 0` is a no-op.

---

## 2. Dedupe / species-cap → collage birds

Pipeline (`main`):

```
nodes = data.detections.nodes
nodes = filter_detections_by_confidence(nodes, MIN_CONFIDENCE)   # optional
species_list = dedupe_species(nodes)
# later: species_list[:MAX_SPECIES_CARDS]  or  [:60] for wallpaper
```

### `dedupe_species(detection_nodes)`

- Key = `species.commonName` (skip if empty).
- Keep **one row per common name**: the node with the **newest** parsed `timestamp` (if both have `ts` and new > old, replace).
- Output map fields per species:

```
name, scientific, thumb, credit, license, station, ts, ts_raw, score
```

- Sort result by `ts` **descending** (`null` ts sorts as oldest).

### Caps / ordering into UI

| Consumer | Cap | Constant / code |
|---|---|---|
| HTML cards | **40** | `MAX_SPECIES_CARDS`; `species_list[:MAX_SPECIES_CARDS]` in `render_html` |
| Wallpaper collage | **60** | hard-coded `species_list[:60]` in collage path |

Order after dedupe = newest detection first; cap takes the **prefix** of that list.

### Frequency sizing (`count_species`)

- Counts occurrences of each `commonName` on the **post-confidence** `nodes` (not the deduped list).
- Used to size collage tiles; Jill can ignore until collage port.

**Confidence timing:** filter raw detections → then dedupe → then cap. Do **not** filter on the deduped species `score` alone (that score is only from the winning/most-recent detection).

---

## 3. Illustration filename matching

### Writing names (`rename_latin_illustrations.safe_filename`)

Used when **saving** pack downloads (`install_avianassets_pack`), not as the matcher itself:

```python
# keep letters, digits, spaces; collapse spaces; append ext
safe_filename(name, ext)  # e.g. "Hooded Crow" + ".png" -> "Hooded Crow.png"
```

Kotlin: strip chars outside `[A-Za-z0-9 ]`, trim, collapse whitespace, append extension.

### Index (`build_illustration_index`)

- Scan illustrations dir; keep extensions **`.png` `.jpg` `.jpeg` `.webp`** (case-insensitive).
- Key = `_slugify(basename)` = lowercase, strip everything except `[a-z0-9]` (spaces/punct gone).
- Also store `tokens = frozenset(_tokenize(basename))` where `_tokenize` splits on `[^a-z0-9]+`.
- Same slug twice → **higher mtime wins**.

### Lookup (`find_local_illustration(common, index, scientific=None)`)

1. `_illustration_lookup(common)` and, if scientific set, `_illustration_lookup(scientific)`.
2. Each lookup: **exact slug hit first**; else fuzzy (`_fuzzy_score`): one token set must be ⊆ the other; score `(shared_count, -extra_count)`; tie-break higher **mtime**, then path string.
3. Among common-hit and latin-hit candidates, **higher mtime wins**.
4. Return path or `null`.

### Fallback

If no local file (or local load fails) → use `thumbnailUrl` HTTP image; if that missing → placeholder.

### Matching notes for Kotlin

- Matching ignores spaces/case/punct via slugify; `"Hooded Crow.png"`, `"hooded_crow.png"`, `"Corvus cornix.png"` can all hit the same species.
- Prefer reusing `_slugify` / word-boundary fuzzy rules above rather than only `safe_filename` equality.
- Local art beats remote thumb; between two local files for the same species, **mtime** decides.

---

## Quick Kotlin checklist

1. Postcode.io → lat/lon (or fallback) → bbox → GraphQL `recentNearby` as above.
2. Filter nodes by `score >= min_confidence` (keep null scores).
3. Dedupe by `commonName`, newest `timestamp`; sort newest first.
4. Take first 40 (list) / 60 (collage) species.
5. Resolve art: local index (slug + fuzzy + mtime) → else `thumbnailUrl`.

Source anchors: `lookup_postcode`, `bounding_box`, `DETECTIONS_QUERY`, `fetch_nearby`, `filter_detections_by_confidence`, `dedupe_species`, `count_species`, `MAX_SPECIES_CARDS`, `build_illustration_index`, `_slugify`, `_tokenize`, `_fuzzy_score`, `_illustration_lookup`, `find_local_illustration`, `safe_filename`.
