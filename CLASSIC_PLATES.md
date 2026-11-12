# Classic plates (Fugleramme)

Birdy can draw birds from the "classic" artwork of
[arnegiacomo/fugleramme](https://github.com/arnegiacomo/fugleramme): about 800
species of hand-coloured 1800s plates (John Gould, the von Wright brothers,
Audubon, Dresser, Keulemans and others), digitally restored and cut out for
that project.

## Licence and credit

- The plates are offered by Fugleramme under
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). The
  underlying works are public domain or CC-licensed scans; the per-work
  sources are in that repo's
  [`assets/artwork/classic/ATTRIBUTION.md`](https://github.com/arnegiacomo/fugleramme/blob/main/assets/artwork/classic/ATTRIBUTION.md),
  and `manifest.json` beside it links every file to the plate it was cut from.
- Birdy **does not bundle** the artwork (it is about 160 MB). The plates a
  collage needs are downloaded on your own machine or phone, from a pinned
  commit of that repo, and kept locally: `ClassicPlates/` next to Birdy on the
  desktop (with an `ATTRIBUTION.txt`), app storage on Android.
- A wallpaper that uses classic plates carries a small credit line on Android.
  If you share a collage made with them, credit Fugleramme and keep the
  CC BY-SA 4.0 licence.
- The way Birdy tones each plate's scrap of scan paper to the canvas follows
  Fugleramme's own renderer (`render/paper.py`, MIT licence, © Arne Giacomo
  Munthe-Kaas); Birdy's version is an independent reimplementation.

## The species index

`android/app/src/main/assets/classic_pack_manifest.txt` lists which species
have a plate and the commit the downloads are pinned to. It holds names only,
no artwork. Its `old=new` lines map scientific names BirdNET and BirdWeather
still report (for example *Corvus monedula*) onto the current name the plate
is filed under; they are derived from
[OpenFauna](https://github.com/tphakala/openfauna)'s alias map (CC BY-SA 4.0)
as shipped in Fugleramme.

Refresh it when the pack gains species:

```bash
python3 android/tools/sync_classic_pack_manifest.py          # rebuild
python3 android/tools/sync_classic_pack_manifest.py --check  # report drift only
```

## Choosing the artwork

| | Desktop (`config.ini` or `--settings`) | Android (Settings → Artwork) |
|---|---|---|
| Classic plates first | `art_style = classic` | Classic plates |
| Your / Birdy's illustrations first (default) | `art_style = birdy` | Birdy illustrations |
| Never download classic plates | `classic_plates = false` | untick "Download plates as new birds turn up" |

Whichever style comes second still fills the gaps, so a bird only falls back
to a BirdWeather photo when no illustration exists for it anywhere.
