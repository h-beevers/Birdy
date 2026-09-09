# Birdy Android (sideload)

Kotlin + Jetpack Compose app under this directory. Package id: `com.henrybeevers.birdy`.

## Data sources

1. **BirdNET-Pi (preferred)** when Settings → BirdNET-Pi base URL is non-blank  
   (see `docs/birdnet-pi-detections-contract.md`)  
   - First: `GET {base}/todays_detections.php?ajax_detections=true&hard_limit=N` (HTML; Com_Name / Sci_Name / Confidence / Date / Time / File_Name)  
   - Optional: `GET {base}/api/v1/detections/recent?limit=N` if a fork/PR exposes JSON  
   - Status: **Using BirdNET-Pi**
2. **BirdWeather + postcodes.io** on Pi failure / empty / off-LAN / blank URL  
   - Status: **Using BirdWeather (fallback)** or **Using BirdWeather**

Hints: `http://192.168.1.159` (LAN) and `https://birds.henrybeevers.org` (may 403 from outside).

## Illustrations

**Birdy's own art** — every file in the repo's top-level `Illustrations/`
folder — is bundled in `app/src/main/assets/illustrations/`, downscaled to fit
512 px and re-encoded as WebP (58 plates, ~0.9 MB). Regenerate after adding or
replacing art:

```bash
pip install pillow
python3 android/tools/sync_illustration_assets.py          # rebuild
python3 android/tools/sync_illustration_assets.py --check  # report drift only
```

**The GB pack** (~300 UK species from
[jonnywright/AvianAssets](https://github.com/jonnywright/AvianAssets), the same
pack `import_avianassets_illustrations.py` pulls on the desktop) is *not*
bundled in the APK. That pack ships no licence of its own and is not covered by
this repo's GPL-3.0, so putting its artwork in a release would be a
redistribution this repo has no permission to make. Instead the app downloads
plates straight from that repo, on the user's own device, into
`filesDir/gb_illustrations`:

- automatically for species that turn up with no bundled plate, while
  **Settings → GB illustration pack → Fetch missing plates** is on (default), or
- in bulk via **Get nearby birds** / **Get all** in the same card.

Each plate is re-encoded to a 512 px WebP on arrival (alpha kept — they are
cutouts), so a full pack costs ~12 MB on disk. **Clear** deletes the lot.

To keep the phone off the GitHub API, the app ships a plain-text index of the
pack's species (`app/src/main/assets/gb_pack_manifest.txt`, one
scientific-name slug per line) and joins the raw URL itself. Species names are
facts, not artwork, so the index carries no licence baggage. Refresh it when
the pack gains species:

```bash
python3 android/tools/sync_gb_pack_manifest.py          # rebuild
python3 android/tools/sync_gb_pack_manifest.py --check  # report drift only
```

Order of preference when drawing a bird: bundled Birdy plate → cached GB plate
→ freshly downloaded GB plate → BirdWeather photo. The status line reports the
split, e.g. `26 illustrated / 2 photo`.

## Collage

Geometry lives in `collage/CollageLayout.kt` — count-weighted tile sizing,
golden-angle (phyllotaxis) packing, then a relaxation pass that pushes
overlapping tiles apart and back inside the canvas. It has no Android types in
it, so `./gradlew testDebugUnitTest` checks the layout, the illustration
matching and the pack index on a plain JVM.

`CollageRenderer` draws each tile in whichever way its art wants:

| Art | Drawn as |
|---|---|
| Transparent cutout (GB pack plate) | as-is, no disc, soft ellipse shadow |
| Opaque plate (Birdy's own art) | letterboxed inside a disc over its own paper colour |
| BirdWeather photo | centre-cropped to fill a disc |

## Build (debug)

Requires JDK 17+ and Android SDK platform 35.

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## Signed release APK + AAB

Do **not** commit keystores or `key.properties`.

1. Place (or symlink) signing material locally, e.g. copy secrets into `android/key.properties` (gitignored):

```properties
storePassword=…
keyPassword=…
keyAlias=birdy-upload
storeFile=/absolute/path/to/birdy-upload.jks
```

2. Build:

```bash
cd android
./gradlew assembleRelease bundleRelease
```

3. Artifacts:

| Artifact | Typical path |
|----------|----------------|
| Signed APK | `app/build/outputs/apk/release/app-release.apk` |
| Signed AAB | `app/build/outputs/bundle/release/app-release.aab` |
| Convenience copies | `android/dist/` (gitignored) and `/workspace/birdy-release/` on the build box |

Verify signing:

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## Releases from CI

`.github/workflows/build-android.yml` runs the unit tests and builds the app on
every push to `main` and every PR that touches `android/`. Pushing a **`v*`**
tag — the same tag the Windows exe releases on, so one tag ships both — or an
**`android-v*`** tag attaches the APKs to that release:

```bash
git tag v1.1.0 && git push origin v1.1.0
```

To add APKs to a release that already exists (or to rebuild them), run the
workflow by hand: **Actions → Build Android APK → Run workflow**, with
`release_tag` set to that release's tag. The APKs are appended to the release's
existing notes and assets rather than replacing them.

- `Birdy-debug.apk` — debug-signed, always attached, sideloads as
  `com.henrybeevers.birdy.debug`.
- `Birdy.apk` — release build signed with the repo's upload key, attached only
  when the `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
  `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` repository secrets are set
  (`base64 -w0 birdy-upload.jks` for the first). Without them the release build
  is unsigned and attached as `Birdy-unsigned.apk`.

The keystore itself never lives in the repo — CI writes `key.properties` from
the secrets and deletes it again in the same job.

## Install release via adb

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
# or from repo-relative:
adb install -r android/dist/birdy-release.apk
```

Uninstall debug first if package id differs (`com.henrybeevers.birdy.debug` vs release).

## OEM note

Lock wallpaper uses `FLAG_LOCK` (API 24+); some OEM skins ignore it. WorkManager ≥ 15 minutes.
