# Android feasibility (Birdy)

Status: **consider / defer** — no Android app in this pass.

## What Birdy is today

Birdy is a **desktop** tool:

1. Resolve a UK postcode → lat/lon (postcodes.io).
2. Query BirdWeather’s public GraphQL API for nearby detections.
3. Render a flock collage locally with **Pillow**.
4. Set that JPEG as the **OS desktop wallpaper** (Windows WinAPI; Linux gsettings/feh/swaybg; macOS osascript).

Config lives in `config.ini`; optional Tk settings GUI (`--settings`); auto-refresh via schtasks / systemd user timer / launchd.

## What Android would require

| Desktop piece | Android equivalent |
|---|---|
| Set desktop wallpaper | `WallpaperManager` (home and/or lock screen); user must grant permission |
| Periodic refresh (schtasks / systemd / launchd) | `WorkManager` or a **foreground service** with a persistent notification (background limits since Android 8+/12+) |
| Pillow collage | Keep Python via Chaquopy / BeeWare / Termux-style, **or** reimplement render in Kotlin/Canvas — large rewrite |
| Tk settings GUI | Native Compose/XML settings activity |
| One-file PyInstaller exe | Play-distributed **APK/AAB** with signing, store policy, privacy labels |

BirdWeather GraphQL itself is fine on mobile (HTTPS, no key). The hard parts are wallpaper UX, background work, and shipping a native or embedded-Python binary.

## Size / effort (rough)

| Approach | APK size (order of magnitude) | Effort | Notes |
|---|---|---|---|
| Minimal “save collage to gallery” companion (no live wallpaper) | 5–15 MB if Kotlin + download pre-rendered image from a desktop/server; 30–80 MB if embedding a Python runtime | Small–medium | Does **not** replace desktop Birdy |
| Full port: local Pillow (Chaquopy) + WallpaperManager + WorkManager | **~40–120 MB** (Python + Pillow + libs) | Large (weeks+) | Fragile Play background policy; OEM wallpaper quirks |
| Full native reimplement (Kotlin render) | **~5–20 MB** | Large (rewrite collage/a11y/illustration matching) | Best long-term mobile quality; duplicates desktop logic |

## Pros / cons vs desktop-only (+ optional gallery export)

**Pros of Android app**

- Wallpaper on the device people actually look at all day.
- Push-ish refresh without a PC left on.

**Cons**

- Background execution is hostile; “every 15 minutes forever” is not a reliable product promise without a foreground service.
- Packaging Python+Pillow into an APK is heavy and awkward to update.
- Rewriting the collage stack risks diverging from desktop (Jack’s UI/collage/a11y ownership).
- Store compliance, privacy policy, and UK-postcode-centric UX may not map cleanly to a global Play audience.

**Desktop-only + “save collage image”**

- Ship today on Win/Linux/macOS with one codebase.
- Users who want mobile can sideload the JPEG to the gallery / use an existing wallpaper app.
- Zero APK maintenance.

## Verdict

**Defer a full Android app.** Birdy’s value is local Pillow rendering + OS wallpaper automation; Android needs a different architecture (permissions, background limits, distribution) that is not a thin wrap of the current script.

**Recommend instead (if mobile demand appears):**

1. Keep investing in desktop packaging (done: Linux/macOS binaries, systemd/launchd templates, settings GUI).
2. Optional tiny feature later: explicit “Export collage” / share JPEG (gallery-friendly) — still desktop-side, no APK.
3. Revisit Android only with a **native** prototype and a clear UX that accepts manual or infrequent wallpaper updates — not a silent 15-minute daemon.

No APK is planned in the current packaging work.
