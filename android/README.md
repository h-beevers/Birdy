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

## Install release via adb

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
# or from repo-relative:
adb install -r android/dist/birdy-release.apk
```

Uninstall debug first if package id differs (`com.henrybeevers.birdy.debug` vs release).

## OEM note

Lock wallpaper uses `FLAG_LOCK` (API 24+); some OEM skins ignore it. WorkManager ≥ 15 minutes.
