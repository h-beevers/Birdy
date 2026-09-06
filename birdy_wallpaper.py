#!/usr/bin/env python3
"""Cross-platform desktop wallpaper setter for Birdy.

Windows uses the existing WinAPI path. Linux tries common desktop helpers
(gsettings for GNOME/Cinnamon/MATE, feh, swaybg). macOS uses osascript /
NSWorkspace via System Events. Callers get a clear error if the desktop
environment is unsupported — collage rendering is unchanged.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys


def set_windows_wallpaper(image_path: str) -> None:
    import ctypes

    SPI_SETDESKWALLPAPER = 20
    SPIF_UPDATEINIFILE = 0x01
    SPIF_SENDCHANGE = 0x02
    abs_path = os.path.abspath(image_path)
    result = ctypes.windll.user32.SystemParametersInfoW(
        SPI_SETDESKWALLPAPER, 0, abs_path, SPIF_UPDATEINIFILE | SPIF_SENDCHANGE
    )
    if not result:
        raise RuntimeError("SystemParametersInfoW reported failure")


def set_macos_wallpaper(image_path: str) -> None:
    abs_path = os.path.abspath(image_path)
    # System Events covers the desktop picture for every Space/desktop.
    script = (
        f'tell application "System Events" to tell every desktop to '
        f'set picture to POSIX file "{abs_path}"'
    )
    try:
        subprocess.run(
            ["osascript", "-e", script],
            check=True,
            capture_output=True,
            text=True,
        )
    except FileNotFoundError as e:
        raise RuntimeError(
            "osascript not found — cannot set wallpaper on this Mac."
        ) from e
    except subprocess.CalledProcessError as e:
        detail = (e.stderr or e.stdout or "").strip() or str(e)
        raise RuntimeError(f"macOS wallpaper set failed: {detail}") from e


def _linux_file_uri(abs_path: str) -> str:
    # gsettings expects a file:// URI; quote spaces for safety.
    return "file://" + abs_path.replace(" ", "%20")


def set_linux_wallpaper(image_path: str) -> None:
    abs_path = os.path.abspath(image_path)
    if not os.path.isfile(abs_path):
        raise RuntimeError(f"Wallpaper file not found: {abs_path}")

    errors: list[str] = []
    uri = _linux_file_uri(abs_path)

    # GNOME / Cinnamon / MATE via gsettings (most Ubuntu desktops).
    if shutil.which("gsettings"):
        schema_keys = [
            ("org.gnome.desktop.background", "picture-uri"),
            ("org.gnome.desktop.background", "picture-uri-dark"),
            ("org.cinnamon.desktop.background", "picture-uri"),
            ("org.mate.background", "picture-filename"),
        ]
        for schema, key in schema_keys:
            value = abs_path if key == "picture-filename" else uri
            try:
                # Skip schemas that aren't installed on this DE.
                subprocess.run(
                    ["gsettings", "list-keys", schema],
                    check=True,
                    capture_output=True,
                    text=True,
                )
            except (subprocess.CalledProcessError, FileNotFoundError):
                continue
            try:
                subprocess.run(
                    ["gsettings", "set", schema, key, value],
                    check=True,
                    capture_output=True,
                    text=True,
                )
                # Also force picture-options so a prior 'none' doesn't hide it.
                if key.startswith("picture-uri"):
                    try:
                        subprocess.run(
                            [
                                "gsettings",
                                "set",
                                schema,
                                "picture-options",
                                "zoom",
                            ],
                            check=False,
                            capture_output=True,
                            text=True,
                        )
                    except Exception:
                        pass
                return
            except subprocess.CalledProcessError as e:
                detail = (e.stderr or e.stdout or "").strip() or str(e)
                errors.append(f"gsettings {schema}.{key}: {detail}")

    # Lightweight X11 fallback.
    if shutil.which("feh"):
        try:
            subprocess.run(
                ["feh", "--bg-fill", abs_path],
                check=True,
                capture_output=True,
                text=True,
            )
            return
        except subprocess.CalledProcessError as e:
            detail = (e.stderr or e.stdout or "").strip() or str(e)
            errors.append(f"feh: {detail}")

    # Sway / wlroots.
    if shutil.which("swaybg"):
        try:
            # Replace any prior swaybg we started; ignore if none.
            subprocess.run(["pkill", "-x", "swaybg"], check=False, capture_output=True)
            subprocess.Popen(
                ["swaybg", "-i", abs_path, "-m", "fill"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
            )
            return
        except OSError as e:
            errors.append(f"swaybg: {e}")

    hint = (
        "Couldn't set the Linux wallpaper automatically. Tried gsettings "
        "(GNOME/Cinnamon/MATE), feh, and swaybg. Install one of those, or "
        f"set the desktop background manually to:\n  {abs_path}"
    )
    if errors:
        hint += "\nDetails:\n  - " + "\n  - ".join(errors)
    raise RuntimeError(hint)


def set_desktop_wallpaper(image_path: str) -> str:
    """Set the desktop wallpaper. Returns a short platform label on success."""
    platform = sys.platform
    if platform.startswith("win"):
        set_windows_wallpaper(image_path)
        return "Windows"
    if platform == "darwin":
        set_macos_wallpaper(image_path)
        return "macOS"
    if platform.startswith("linux"):
        set_linux_wallpaper(image_path)
        return "Linux"
    raise RuntimeError(
        f"Wallpaper setting is not supported on this platform ({platform}). "
        f"The collage was still saved to:\n  {os.path.abspath(image_path)}"
    )


# Back-compat alias used by older call sites / docs.
set_windows_wallpaper_compat = set_windows_wallpaper
