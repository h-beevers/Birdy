# PyInstaller spec for packaged one-file builds (Windows / Linux / macOS).
#
# Build locally (with `pip install pyinstaller` done first):
#   pyinstaller birdy.spec
# Produces dist/Birdy.exe on Windows, or dist/Birdy on Linux/macOS — a
# single file, no Python install required to run it. The GitHub Actions
# workflow (.github/workflows/build-exe.yml) runs this same spec on
# windows-latest, ubuntu-latest, and macos-latest and attaches the
# results to GitHub Releases.
#
# Entry modules pulled in via imports from birdweather_local.py:
#   birdy_wallpaper.py (cross-platform set-wallpaper)
#   birdy_settings_gui.py (--settings GUI)
#
# --windowed / console=False: no console window on normal runs (matches
# the pythonw.exe behaviour of the source-run path — see the sys.stdout
# redirect at the bottom of birdweather_local.py). First-run setup and
# --settings use Tk dialogs instead of console prompts for that reason.

import sys

block_cipher = None

a = Analysis(
    ["birdweather_local.py"],
    pathex=[],
    binaries=[],
    datas=[
        ("Illustrations", "Illustrations"),
        ("packaging", "packaging"),
    ],
    hiddenimports=["birdy_wallpaper", "birdy_settings_gui"],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    cipher=block_cipher,
)
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name="Birdy",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
