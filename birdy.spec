# PyInstaller spec for the packaged Windows build.
#
# Build locally (on Windows, with `pip install pyinstaller` done first):
#   pyinstaller birdy.spec
# Produces dist/Birdy.exe — a single file, no Python install required to run
# it. The GitHub Actions workflow (.github/workflows/build-exe.yml) runs
# this same spec on a windows-latest runner and attaches the result to
# GitHub Releases, which is the path most users should just download from.
#
# --windowed: no console window on normal runs (matches the pythonw.exe
# behaviour of the source-run path — see the sys.stdout redirect at the
# bottom of birdweather_local.py). First-run setup uses Tk dialogs instead
# of console prompts for exactly this reason.

import sys

block_cipher = None

a = Analysis(
    ["birdweather_local.py"],
    pathex=[],
    binaries=[],
    datas=[("Illustrations", "Illustrations")],
    hiddenimports=[],
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
