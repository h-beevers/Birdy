# Birdy

A local desktop wallpaper that shows what birds have actually been heard
near you recently — pulled from [BirdWeather](https://app.birdweather.com)'s
public community station network, rendered as an overlapping flock collage
in the style of [AvianVisitors](https://github.com/Twarner491/AvianVisitors),
and set directly as your Windows desktop background.

No microphone, no Raspberry Pi, no hardware required — this queries
BirdWeather's existing station network for your area. It started as a
"phase 0" preview while waiting on hardware for a proper
[BirdNET-Pi](https://github.com/Nachtzuster/BirdNET-Pi) build, and turned
into its own thing.


## What it does

1. Looks up your postcode → lat/lon (via the free
   [postcodes.io](https://postcodes.io) API)
2. Queries BirdWeather's public GraphQL API for recent detections near you
3. Renders a static HTML preview page (handy for just browsing in a tab)
4. Renders a flock-style collage as a JPG and sets it as your actual desktop
   wallpaper — sized to your real screen resolution, no third-party
   wallpaper app involved

## Setup

### The easy way: Birdy.exe (recommended for most people)

No Python install, no editing config files by hand. Grab the latest
`Birdy.exe` from the [Releases page](../../releases), put it in a permanent
folder (e.g. `Documents\Birdy`), and double-click it.

The first launch runs a short one-time setup: it asks for your postcode and
offers to register the 15-minute auto-refresh for you (see
[Running it automatically](#running-it-automatically) — this does that step
for you via `schtasks`, no manual Task Scheduler work needed). It also seeds
an `Illustrations/` folder next to the exe with this repo's bundled bird art,
which you can add your own images to at any time (see below). Every later
double-click (or scheduled run) just refreshes the wallpaper silently — no
window, no prompts.

Settings after first-run live in `config.ini` next to the exe if you want to
change your postcode or search radius later; delete it to rerun the setup
wizard.

New builds are produced automatically by
[the build workflow](.github/workflows/build-exe.yml) whenever a version tag
is pushed, and attached to that release — nothing to build yourself.

### Running from source (for development, or if you'd rather not run a downloaded exe)

```
pip install -r requirements.txt
```

Edit the config block at the top of `birdweather_local.py`:

- `POSTCODE` — your postcode (or `None` to use the fallback lat/lon below it)
- `RADIUS_KM` / `DAYS` — how far and how recent a window to search
- `ILLUSTRATIONS_DIR` — defaults to an `Illustrations/` folder next to the
  script; drop your own bird art in there (see below)

Then run it:

```
python birdweather_local.py
```

## Your own illustrations (optional, but the whole point)

BirdWeather's own species thumbnails are real photos, so they get
desaturated and edge-feathered in the collage to sit alongside painted
artwork without clashing too badly — but they're a fallback, not the goal.

Drop your own illustrations into `Illustrations/`, named loosely after the
species' common name (`Hooded Crow.png`, `hooded_crow.png`, and
`HoodedCrow.jpg` all match "Hooded Crow" — case, spaces, and punctuation are
ignored). The script auto-detects and strips a flat/uniform background from
these (assuming you generate them that way — see prompt notes below), giving
a clean cutout for the flock layout. Anything without a local match falls
back to the BirdWeather photo.

**A prompt approach that worked well** (used with Gemini, reference photo +
explicit plumage description, rather than relying on the photo alone):

> A [common name] rendered as a minimalist Japanese woodblock-print-style
> illustration, in the spirit of Edo-period kachō-e bird prints. [Explicit
> plumage description — colour and pattern per body part, in your own
> words]. Confident ink linework, flat painted colour zones with sharp
> clean edges. The bird floats centered against a plain, flat, warm cream
> background — no branch, no foliage, no ground, no sky, no shadow. Single
> bird, full body visible.

Spelling out the actual plumage in the prompt, rather than trusting the
model to read it off a reference photo, made a real difference to species
accuracy — worth doing for anything with distinctive field marks.

## Running it automatically

**If you're using Birdy.exe**, the first-run setup wizard offers to do all
of this for you — just say yes when it asks. Nothing below is needed unless
you said no then and want it later, or want to change/remove it (Task
Scheduler → look for "Birdy Wallpaper Refresh").

**If you're running from source**, set it up as a Windows Scheduled Task by
hand so your wallpaper refreshes on its own:

- **Trigger:** Daily, repeat every 15 minutes, indefinitely
- **Action:** Program/script → `pythonw.exe` (not `python.exe` — avoids a
  console window popping up every run), Arguments → path to
  `birdweather_local.py`, or just point the action at `run_birdweather.bat`
- **Security options:** "Run only when user is logged on" is simplest and
  sufficient — this only needs to run while you're actually at the desktop

### Windows gotchas that bit us building this

- **`pythonw.exe` has no console**, so `sys.stdout`/`stderr` are `None`
  rather than writable — the script redirects to `birdweather_local.log`
  automatically when it detects this, so `print()` calls don't crash.
  `Birdy.exe` is built the same way (`--windowed`) and hits the same log
  redirect, next to the exe.
- **Controlled Folder Access** (Windows Security → Ransomware protection)
  can silently block writes to new files in Documents-adjacent folders. If
  runs fail with a `PermissionError` on a brand-new `.tmp` file (not an
  existing one), this is almost certainly why — allow `python.exe` **and**
  `pythonw.exe` separately when running from source (Defender treats them as
  different programs even though they live in the same folder), or allow
  `Birdy.exe` if you're using the packaged build.
- **`where python` can lie** if another tool's venv has planted itself
  ahead of your real install on PATH. Use `py -0p` (Python launcher) or
  `(Get-Command python).Source` in PowerShell to confirm which interpreter
  you're actually running, and use its full path in Task Scheduler rather
  than the bare command. (Not a concern for `Birdy.exe` — nothing to
  resolve on PATH.)

## How the collage layout works

Loosely based on the approach described in AvianVisitors' own writeup:
count-weighted tile sizing normalized against a viewport area budget
(`log(detection_count + 1)`, not a fixed clamp — so a frequently-heard
species visibly outsizes a rare one without one very common species
swallowing the whole layout), packed center-out in a spiral with real
per-pixel silhouette collision (not just bounding boxes, so tiles can nest
into each other's negative space), and a shrink-and-repack fallback if
anything lands off-canvas.

## Credits / attribution

- Detections and station data: [BirdWeather](https://app.birdweather.com)
  — huge thanks to their community station network for making this possible
  without any hardware of your own.
- Layout approach inspired by
  [Twarner491/AvianVisitors](https://github.com/Twarner491/AvianVisitors)
  (a fork of [Nachtzuster/BirdNET-Pi](https://github.com/Nachtzuster/BirdNET-Pi)).
  No code or assets from that repo are reused here — this is an independent
  reimplementation of the general layout idea, using your own generated
  artwork or BirdWeather's own thumbnails.
- Postcode lookup: [postcodes.io](https://postcodes.io)

## License

Choose one for your own code — this README doesn't assume anything. If you
keep BirdWeather-sourced thumbnails or any BirdNET-Pi/AvianVisitors-derived
material in the repo, check their respective licenses/attribution
requirements separately.
