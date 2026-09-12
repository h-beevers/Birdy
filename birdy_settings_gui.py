#!/usr/bin/env python3
"""Reopenable Birdy settings GUI (Tk).

Edit config.ini without hand-editing. Open via:
  Birdy.exe --settings
  python birdweather_local.py --settings

Keeps collage / first-run wizard code out of this module — imports only
config helpers and optional actions from birdweather_local.
"""

from __future__ import annotations

import os
import sys
import tkinter as tk
from tkinter import messagebox, ttk


def _bool_str(value: bool) -> str:
    return "true" if value else "false"


def open_settings_gui(parent=None):
    """Show the settings window. Blocks until closed. Returns True if saved."""
    # Late import so `python birdy_settings_gui.py` and frozen --settings
    # both resolve the same app helpers without circular import at module load
    # of birdweather_local (this module is only imported on --settings).
    import birdweather_local as app

    cfg = app.load_user_config()
    saved = {"ok": False}

    root = parent
    owns_root = parent is None
    if owns_root:
        root = tk.Tk()
        root.title("Birdy settings")
        root.resizable(False, False)

    frame = ttk.Frame(root, padding=12)
    frame.grid(row=0, column=0, sticky="nsew")

    ttk.Label(frame, text="Postcode").grid(row=0, column=0, sticky="w", pady=2)
    postcode_var = tk.StringVar(value=cfg.get("postcode", ""))
    ttk.Entry(frame, textvariable=postcode_var, width=28).grid(
        row=0, column=1, sticky="ew", pady=2
    )

    ttk.Label(frame, text="Radius (km)").grid(row=1, column=0, sticky="w", pady=2)
    radius_var = tk.StringVar(value=cfg.get("radius_km", "20"))
    ttk.Entry(frame, textvariable=radius_var, width=28).grid(
        row=1, column=1, sticky="ew", pady=2
    )

    ttk.Label(frame, text="Days").grid(row=2, column=0, sticky="w", pady=2)
    days_var = tk.StringVar(value=cfg.get("days", "1"))
    ttk.Entry(frame, textvariable=days_var, width=28).grid(
        row=2, column=1, sticky="ew", pady=2
    )

    ttk.Label(frame, text="Hours (optional)").grid(row=3, column=0, sticky="w", pady=2)
    hours_var = tk.StringVar(value=cfg.get("hours", ""))
    ttk.Entry(frame, textvariable=hours_var, width=28).grid(
        row=3, column=1, sticky="ew", pady=2
    )
    ttk.Label(
        frame,
        text="Leave hours blank to use days. Set hours (e.g. 6) to override.",
        wraplength=360,
    ).grid(row=4, column=0, columnspan=2, sticky="w", pady=(0, 6))

    show_title_var = tk.BooleanVar(
        value=str(cfg.get("show_title", "true")).lower() in ("1", "true", "yes", "on")
    )
    ttk.Checkbutton(frame, text="Show title", variable=show_title_var).grid(
        row=5, column=0, columnspan=2, sticky="w", pady=2
    )

    ttk.Label(frame, text="Title text").grid(row=6, column=0, sticky="w", pady=2)
    title_var = tk.StringVar(value=cfg.get("title_text", "Garden Visitors"))
    ttk.Entry(frame, textvariable=title_var, width=28).grid(
        row=6, column=1, sticky="ew", pady=2
    )

    show_labels_var = tk.BooleanVar(
        value=str(cfg.get("show_labels", "false")).lower()
        in ("1", "true", "yes", "on")
    )
    ttk.Checkbutton(frame, text="Show labels", variable=show_labels_var).grid(
        row=7, column=0, columnspan=2, sticky="w", pady=2
    )

    ttk.Label(frame, text="Label style").grid(row=8, column=0, sticky="w", pady=2)
    label_style_var = tk.StringVar(
        value=(cfg.get("label_style") or "common").strip().lower()
    )
    style_box = ttk.Combobox(
        frame,
        textvariable=label_style_var,
        values=list(app.LABEL_STYLES),
        state="readonly",
        width=25,
    )
    style_box.grid(row=8, column=1, sticky="ew", pady=2)

    # Friendly preset names for the combobox (aliases like "blue" still parse).
    bg_presets = ("cream", "pastel_blue", "pastel_green")
    ttk.Label(frame, text="Background colour").grid(
        row=9, column=0, sticky="w", pady=2
    )
    bg_var = tk.StringVar(
        value=(cfg.get("bg_color") or app._DEFAULTS["bg_color"]).strip()
    )
    bg_box = ttk.Combobox(
        frame,
        textvariable=bg_var,
        values=list(bg_presets),
        width=25,
    )
    bg_box.grid(row=9, column=1, sticky="ew", pady=2)
    ttk.Label(
        frame,
        text="Preset (cream / pastel_blue / pastel_green) or hex (#rrggbb) / r,g,b.",
        wraplength=360,
    ).grid(row=10, column=0, columnspan=2, sticky="w", pady=(0, 6))

    ttk.Label(frame, text="Min confidence (0–1)").grid(
        row=11, column=0, sticky="w", pady=2
    )
    min_conf_var = tk.StringVar(
        value=(cfg.get("min_confidence") or app._DEFAULTS["min_confidence"]).strip()
    )
    ttk.Entry(frame, textvariable=min_conf_var, width=28).grid(
        row=11, column=1, sticky="ew", pady=2
    )

    open_html_var = tk.BooleanVar(
        value=str(cfg.get("open_html", "false")).lower()
        in ("1", "true", "yes", "on")
    )
    ttk.Checkbutton(
        frame, text="Open HTML after run", variable=open_html_var
    ).grid(row=12, column=0, columnspan=2, sticky="w", pady=2)

    def _normalize_bg_color(raw: str) -> str:
        """Store a preset name when possible, else validated #rrggbb."""
        bg_raw = (raw or "").strip() or app._DEFAULTS["bg_color"]
        key = bg_raw.lower().replace(" ", "_").replace("-", "_")
        if key in app._BG_COLOR_PRESETS:
            return key
        parsed = app.parse_bg_color(bg_raw, (244, 237, 224))
        s = bg_raw.strip()
        valid = False
        if s.startswith("#"):
            h = s[1:]
            valid = len(h) in (3, 6) and all(
                c in "0123456789abcdefABCDEF" for c in h
            )
        elif "," in s:
            parts = [p.strip() for p in s.split(",")]
            if len(parts) == 3:
                try:
                    for p in parts:
                        int(float(p))
                    valid = True
                except ValueError:
                    valid = False
        if not valid:
            raise ValueError(
                "Background colour must be a preset "
                "(cream, pastel_blue, pastel_green), #rrggbb / #rgb, or r,g,b."
            )
        return app.rgb_to_hex(parsed)

    def collect_values():
        radius = radius_var.get().strip() or "20"
        days = days_var.get().strip() or "1"
        try:
            int(radius)
            int(days)
        except ValueError as e:
            raise ValueError("radius_km and days must be whole numbers.") from e
        hours = hours_var.get().strip()
        if hours:
            try:
                hours_int = int(hours)
            except ValueError as e:
                raise ValueError("hours must be a whole number, or blank.") from e
            if hours_int <= 0:
                hours = ""
        style = (label_style_var.get() or "common").strip().lower()
        if style not in app.LABEL_STYLES:
            style = "common"
        bg_color = _normalize_bg_color(bg_var.get())
        mc_raw = min_conf_var.get().strip() or app._DEFAULTS["min_confidence"]
        try:
            mc = float(mc_raw)
        except ValueError as e:
            raise ValueError(
                "min_confidence must be a number between 0 and 1."
            ) from e
        if not 0.0 <= mc <= 1.0:
            raise ValueError("min_confidence must be between 0 and 1.")
        return {
            "postcode": postcode_var.get().strip(),
            "radius_km": radius,
            "days": days,
            "hours": hours,
            "show_title": _bool_str(show_title_var.get()),
            "title_text": title_var.get().strip() or app._DEFAULTS["title_text"],
            "show_labels": _bool_str(show_labels_var.get()),
            "label_style": style,
            "bg_color": bg_color,
            "min_confidence": "{:g}".format(mc),
            "open_html": _bool_str(open_html_var.get()),
        }

    def on_save(refresh_after=False):
        try:
            values = collect_values()
        except ValueError as e:
            messagebox.showerror("Birdy settings", str(e), parent=root)
            return
        app.save_user_config(values)
        saved["ok"] = True
        if refresh_after:
            try:
                # Reload module-level settings then run one wallpaper pass.
                app.load_runtime_config()
                app.main()
                messagebox.showinfo(
                    "Birdy settings",
                    "Settings saved and wallpaper refreshed.",
                    parent=root,
                )
            except Exception as e:
                messagebox.showwarning(
                    "Birdy settings",
                    f"Settings saved, but wallpaper refresh failed:\n{e}",
                    parent=root,
                )
        else:
            messagebox.showinfo(
                "Birdy settings",
                f"Saved to:\n{app.CONFIG_PATH}",
                parent=root,
            )

    def on_avianassets():
        try:
            values = collect_values()
        except ValueError as e:
            messagebox.showerror("Birdy settings", str(e), parent=root)
            return
        app.save_user_config(values)
        app.load_runtime_config()
        postcode = values.get("postcode") or ""
        lat, lon = app.FALLBACK_LAT, app.FALLBACK_LON
        if postcode:
            try:
                lat, lon, _place = app.lookup_postcode(postcode)
            except Exception:
                pass
        try:
            added, already = app.install_avianassets_pack(
                lat, lon, app.RADIUS_KM, 30, app.ILLUSTRATIONS_DIR
            )
            messagebox.showinfo(
                "Birdy settings",
                f"Added {added} illustration(s) from AvianAssets "
                f"({already} already covered).",
                parent=root,
            )
        except Exception as e:
            messagebox.showwarning(
                "Birdy settings",
                f"Couldn't download illustrations right now ({e}).",
                parent=root,
            )

    def on_schedule():
        platform = sys.platform
        if platform.startswith("win"):
            try:
                app.register_scheduled_task()
                messagebox.showinfo(
                    "Birdy settings",
                    f'Windows task "{app.TASK_NAME}" created/updated '
                    "(every 15 minutes while logged on).",
                    parent=root,
                )
            except Exception as e:
                messagebox.showwarning(
                    "Birdy settings",
                    f"Couldn't create the scheduled task ({e}).\n"
                    "See README / Task Scheduler.",
                    parent=root,
                )
            return

        packaging_dir = os.path.join(app.app_dir(), "packaging")
        if not os.path.isdir(packaging_dir):
            # Frozen builds may not ship packaging/; point at the repo docs.
            packaging_dir = os.path.join(
                os.path.dirname(os.path.abspath(__file__)), "packaging"
            )
        if platform == "darwin":
            msg = (
                "macOS auto-refresh uses a launchd agent.\n\n"
                "Copy packaging/com.birdy.wallpaper.plist, set the ProgramArguments "
                "path to your Birdy binary or python + script, then:\n"
                "  launchctl load ~/Library/LaunchAgents/com.birdy.wallpaper.plist\n\n"
                f"Templates live under:\n{packaging_dir}"
            )
        elif platform.startswith("linux"):
            msg = (
                "Linux auto-refresh uses a systemd --user timer.\n\n"
                "Copy packaging/birdy-wallpaper.service and "
                "birdy-wallpaper.timer into ~/.config/systemd/user/, "
                "edit the ExecStart path, then:\n"
                "  systemctl --user daemon-reload\n"
                "  systemctl --user enable --now birdy-wallpaper.timer\n\n"
                f"Templates live under:\n{packaging_dir}"
            )
        else:
            msg = f"Auto-refresh scheduling isn't automated on {platform}."
        messagebox.showinfo("Birdy settings", msg, parent=root)

    btns = ttk.Frame(frame)
    btns.grid(row=13, column=0, columnspan=2, sticky="ew", pady=(10, 0))
    ttk.Button(btns, text="Save", command=lambda: on_save(False)).grid(
        row=0, column=0, padx=2
    )
    ttk.Button(
        btns, text="Save & refresh wallpaper", command=lambda: on_save(True)
    ).grid(row=0, column=1, padx=2)
    ttk.Button(btns, text="Install AvianAssets…", command=on_avianassets).grid(
        row=1, column=0, padx=2, pady=4
    )
    ttk.Button(btns, text="Schedule refresh…", command=on_schedule).grid(
        row=1, column=1, padx=2, pady=4
    )
    ttk.Button(btns, text="Close", command=root.destroy).grid(
        row=2, column=0, columnspan=2, pady=(6, 0)
    )

    if owns_root:
        root.mainloop()
    return saved["ok"]


def main():
    open_settings_gui()


if __name__ == "__main__":
    main()
