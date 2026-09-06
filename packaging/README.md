# Packaging helpers

Templates and shortcuts for installing / auto-refreshing Birdy outside Windows Task Scheduler.

| File | Platform | Purpose |
|---|---|---|
| `birdy-wallpaper.service` + `birdy-wallpaper.timer` | Linux (systemd --user) | 15-minute wallpaper refresh |
| `com.birdy.wallpaper.plist` | macOS (launchd) | 15-minute wallpaper refresh |
| `open_settings.sh` / `open_settings.bat` | all | Launch the reopenable settings GUI (`--settings`) |

Edit paths in the service/plist before enabling. CI builds one-file binaries for Windows, Linux, and macOS via `.github/workflows/build-exe.yml`.

Wallpaper setting itself lives in `birdy_wallpaper.py` (WinAPI / gsettings|feh|swaybg / osascript).

## CI note

Proposed multi-OS workflow: [`ci/build-packaged.yml`](ci/build-packaged.yml). Copy to `.github/workflows/build-exe.yml` (requires a GitHub token with the `workflow` scope). Until then, Releases still get the Windows exe from the existing workflow.
