# Birdy

A local desktop wallpaper that shows what birds have actually been heard
near you recently — pulled from [BirdWeather](https://app.birdweather.com)'s
public community station network, rendered as an overlapping flock collage
in the style of [AvianVisitors](https://github.com/Twarner491/AvianVisitors),
and set directly as your Windows desktop background.

[BirdNET-Pi](https://github.com/Nachtzuster/BirdNET-Pi)/AvianVisitors-style
setups are genuinely great, but they need a Raspberry Pi and a mic pointed
out a window. Birdy exists so anyone on a Windows desktop can get the same
"what's been heard nearby, as art on my screen" result with none of that —
no hardware, no soldering, no always-on device — by leaning on BirdWeather's
existing public station network instead of running your own. It started as
a "phase 0" preview while waiting on hardware for a proper BirdNET-Pi build,
and turned into its own thing worth sharing on its own.

<!-- TODO: drop a real screenshot of the rendered wallpaper here, e.g.
     ![Birdy wallpaper example](docs/example-wallpaper.jpg) -->

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

The first launch runs a short one-time setup: it asks for your postcode,
whether to show the "Garden Visitors" title above the collage, whether to
show each bird's species name underneath it, and offers to register the
15-minute auto-refresh for you (see
[Running it automatically](#running-it-automatically) — this does that step
for you via `schtasks`, no manual Task Scheduler work needed). It also seeds
an `Illustrations/` folder next to the exe with this repo's bundled bird art,
which you can add your own images to at any time (see below). Every later
double-click (or scheduled run) just refreshes the wallpaper silently — no
window, no prompts.

Settings after first-run live in `config.ini` next to the exe — edit it
directly with any text editor to change your postcode, search radius, or the
title/label toggles later (keys: `postcode`, `radius_km`, `days`,
`show_title`, `show_labels` — the last two take `true`/`false`); delete the
whole file to rerun the setup wizard instead.

New builds are produced automatically by
[the build workflow](.github/workflows/build-exe.yml) whenever a version tag
is pushed, and attached to that release — nothing to build yourself.

### Updating Birdy.exe

Grab the new `Birdy.exe` from the [Releases page](../../releases) and drop
it into the **same folder**, overwriting the old one — that's it, no
uninstall step. This is safe because everything Birdy remembers about your
setup lives in separate files next to the exe, not inside it:

- `config.ini` (postcode/radius/days, title/label toggles) is untouched.
- Your `Illustrations/` folder is untouched — nothing gets overwritten or
  merged into it.
- The Scheduled Task keeps working with zero changes, since it points at
  the exe's file path rather than its contents (as long as the new download
  keeps the filename `Birdy.exe` in that same folder — don't rename it or
  save it somewhere else).
- No setup wizard reappears — that only runs once, the very first time,
  triggered by `config.ini` not existing yet.

The one thing an update *won't* do automatically: if a newer version of
this repo has added or changed one of the bundled default illustrations,
your existing `Illustrations/` folder won't pick that up on its own — it's
only ever seeded once, on that very first run, specifically so an update
never overwrites illustrations you've added or swapped in yourself. If you
want whatever's newly added to the repo's own art, see the download
instructions below.

Only avoid overwriting the exe file in the exact moment it's actively
running (it's a short-lived process — runs, sets the wallpaper, exits — so
this is a narrow window). Windows will refuse the file replace if you catch
it mid-run; just wait a few seconds and try again.

### Running from source (for development, or if you'd rather not run a downloaded exe)

```
pip install -r requirements.txt
```

Edit the config block at the top of `birdweather_local.py`:

- `POSTCODE` — your postcode (or `None` to use the fallback lat/lon below it)
- `RADIUS_KM` / `DAYS` — how far and how recent a window to search
- `ILLUSTRATIONS_DIR` — defaults to an `Illustrations/` folder next to the
  script; drop your own bird art in there (see below)
- `SHOW_TITLE` / `SHOW_LABELS` — toggle the "Garden Visitors" title and
  each bird's species name; both configurable without editing code in
  `Birdy.exe` via `config.ini` instead (see above)

Then run it:

```
python birdweather_local.py
```

**Updating**: `git pull` (or re-download the ZIP and overwrite the files),
then `pip install -r requirements.txt` again in case dependencies changed.
Your config edits at the top of `birdweather_local.py` will get clobbered
by a `git pull` only if you edited a line that also changed upstream —
otherwise they're untouched. Your `Illustrations/` folder is never touched
by an update either way.

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

### Getting this repo's bundled illustrations

`Birdy.exe` seeds your `Illustrations/` folder from this repo's own art
automatically, but only once, on the very first run — so if new bird art
gets added to the repo later (or you deleted one by mistake and want it
back), it won't show up in an already-existing folder on its own. To pull
specific images in manually:

1. Open this repo's [`Illustrations/`
   folder](Illustrations) on GitHub in a browser.
2. Click into the bird you want, then use the **⋯** menu (or the download
   icon) on the file view → **Download raw file**.
3. Drop the downloaded image into your own `Illustrations/` folder next to
   `Birdy.exe` (or next to `birdweather_local.py` if running from source).

If you want the whole set at once rather than picking individual files:
**Code → Download ZIP** from the top of the repo page, then copy the
`Illustrations/` folder out of the extracted ZIP into your own — safe to
overwrite, since it's the same art you'd get from a fresh install anyway.

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

## How the collage layout works

Loosely based on the approach described in AvianVisitors' own writeup:
count-weighted tile sizing normalized against a viewport area budget
(`log(detection_count + 1)`, not a fixed clamp — so a frequently-heard
species visibly outsizes a rare one without one very common species
swallowing the whole layout), packed center-out in a spiral with real
per-pixel silhouette collision (not just bounding boxes, so tiles can nest
into each other's negative space), and a shrink-and-repack fallback if
anything lands off-canvas.

## Troubleshooting

Nothing here needs doing up front — only look if you actually hit one of
these.

**Runs fail with a `PermissionError` on a brand-new `.tmp` file** (not an
existing one): this is almost always Windows Security's *Controlled Folder
Access* (Windows Security → Virus & threat protection → Ransomware
protection), which can silently block a program from writing new files in
Documents-adjacent folders. Fix: add an allowed app there — `Birdy.exe`
itself if you're using the packaged build, or **both** `python.exe` *and*
`pythonw.exe` separately if running from source (Defender treats them as
different programs even though they live in the same folder). It's off by
default on most installs, so most people will never see this.

**Nothing happens / no log file appears when running from source via
`pythonw.exe`**: it has no console, so `sys.stdout`/`stderr` are `None`
rather than writable streams — the script detects this and redirects
`print()` output to `birdweather_local.log` next to it instead of crashing.
Check there first. (`Birdy.exe` is built the same way and behaves
identically, logging beside itself.)

**Task Scheduler runs a different Python than the one you tested with (from
source only)**: `where python` can lie if another tool's venv has planted
itself ahead of your real install on PATH. Use `py -0p` (Python launcher) or
`(Get-Command python).Source` in PowerShell to confirm which interpreter
you're actually running, and point Task Scheduler at its full path rather
than the bare command. Not a concern for `Birdy.exe` — there's no PATH
resolution involved.

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

## Feedback

Early days — if you try it and hit something odd, or want a feature that's
not here yet, [open an issue](../../issues). Bug reports, feature requests,
and "here's a bird it misidentified/uglified" screenshots are all welcome.

## License

[GNU GPLv3](LICENSE) — code and bundled illustrations alike. In short:
anyone can use, modify, and redistribute Birdy (commercial use included),
but any distributed modified version must also be released under GPL-3.0
with source available. This is a deliberate choice, not a default — it
keeps Birdy itself, and anything forked from it, from ending up closed off
or resold as a closed product downstream.

This only covers code and illustrations original to this repo. BirdWeather
thumbnail fallback photos are separately credited/licensed per-image by
BirdWeather itself (not covered by Birdy's GPL license), and this repo
doesn't reuse code or assets from BirdNET-Pi/AvianVisitors at all — see
Credits above.
