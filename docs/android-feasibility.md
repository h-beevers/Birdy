# Android feasibility (Birdy)

Status: **shipped v1 sideload** — Kotlin app under `android/` (package `com.henrybeevers.birdy`).

Shared contract with desktop GraphQL / dedupe / illustration matching:
[`docs/android-shared-contract.md`](android-shared-contract.md).

## What shipped (v1 sideload)

| Feature | Status |
|---|---|
| UK postcode → lat/lon (postcodes.io) | Works |
| BirdWeather public GraphQL detections | Works (same query as desktop) |
| `min_confidence` filter → dedupe → cap 60 | Works (per shared contract) |
| Local flock collage (Kotlin Canvas/Bitmap) | Works (spiral/overlap circles; simpler than desktop Pillow packer) |
| Bundled `Illustrations/` subset + thumb fallback | Works |
| Home wallpaper (`WallpaperManager` / `FLAG_SYSTEM`) | Works |
| Lock wallpaper (`FLAG_LOCK`, API 24+) | Requested; **OEM may ignore** (Samsung/Xiaomi/etc.) |
| WorkManager periodic refresh | Works (user hours; floor 15 min; Doze may delay) |
| First-run + settings (postcode, radius, days/hours, refresh, bg_color, min_confidence, title/labels, home/lock toggles) | Works |
| Optional BirdNET-Pi URL field | Present in settings; **unused in v1** (primary path = BirdWeather) |
| Play Store listing / signing / privacy form | **Not done** — sideload only |

## Build / install

See README **Android (sideload)** section. Short version:

```bash
cd android
# SDK via ANDROID_HOME / local.properties sdk.dir=
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Grant wallpaper permission when prompted. Internet required for BirdWeather + postcodes.io.

## OEM lock-screen caveats

- API 24+ `WallpaperManager.FLAG_LOCK` is used when “Set lock wallpaper” is on.
- **Samsung One UI, Xiaomi MIUI, some ColorOS/OxygenOS** builds often ignore or only partially honour `FLAG_LOCK`; home wallpaper is generally reliable.
- Users on those OEMs may need to set the lock image manually from the saved collage preview (gallery export is a polish gap).

## WorkManager honesty

- Periodic work minimum interval is **15 minutes**; shorter settings are floored.
- Doze, App Standby, and OEM battery savers can delay runs further — not a silent 15-minute daemon promise.
- Default refresh is measured in **hours** (e.g. 6h).

## Remaining Play gaps

1. Upload key / Play App Signing, AAB build, store listing assets.
2. Data safety form: disclose BirdWeather + postcodes.io network; on-device prefs; no ads; uninstall deletes data.
3. Privacy policy URL.
4. Target API / Play policy review (background work, wallpaper).
5. Optional: MediaStore export for OEM lock workaround.
6. Richer collage parity (desktop silhouette packer, drop shadows, label placement search).
7. Wire optional BirdNET-Pi endpoint if desired.
8. Automated CI: draft at `packaging/ci/android-apk.yml` — OAuth lacked `workflow` scope, so Henry (or a PAT with workflow scope) should copy it to `.github/workflows/android-apk.yml` and push.

## Architecture note

Native Kotlin + Jetpack Compose — **no** Chaquopy / embedded Python / Pillow. Desktop collage/UI/a11y code was not modified for this pass.
