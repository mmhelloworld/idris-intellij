#!/usr/bin/env bash
# Convert a screen recording into an optimized GIF matching docs/images/ style
# (~1000px wide, 12 fps, high-quality palette). Used for the README feature tour.
#
# Recording (macOS, no extra tools): press Cmd-Shift-5, choose "Record Selected
# Portion", drag a tight box around the editor, record the interaction, stop from
# the menu bar. You get a .mov in ~/Desktop. Then convert it here.
#
# Usage:
#   docs/make-gif.sh INPUT.mov OUTPUT.gif [options]
#
# Options (all optional):
#   --width N     output width in px         (default 1000; existing GIFs are 900-1100)
#   --fps N       frames per second          (default 12;  existing GIFs are 10-12.5)
#   --start T     trim: start at T seconds   (e.g. 1.5)
#   --duration T  trim: keep T seconds from --start
#   --speed F     playback speed multiplier  (e.g. 1.5 = 1.5x faster)
#
# Examples:
#   docs/make-gif.sh ~/Desktop/rec.mov docs/images/ffi-completion.gif
#   docs/make-gif.sh ~/Desktop/rec.mov docs/images/ffi-completion.gif --start 1 --speed 1.25
#   docs/make-gif.sh ~/Desktop/rec.mov docs/images/ffi-completion.gif --start 2 --duration 10 --speed 1.25
#
# Needs ffmpeg (brew install ffmpeg). gifsicle is optional: if present, the GIF is
# losslessly shrunk further.
set -euo pipefail

[ $# -ge 2 ] || { sed -n '2,30p' "$0"; exit 1; }
IN="$1"; OUT="$2"; shift 2
[ -f "$IN" ] || { echo "error: input not found: $IN" >&2; exit 1; }

WIDTH=1000; FPS=12; START=""; DURATION=""; SPEED=""
while [ $# -gt 0 ]; do
  case "$1" in
    --width)    WIDTH="$2";    shift 2;;
    --fps)      FPS="$2";      shift 2;;
    --start)    START="$2";    shift 2;;
    --duration) DURATION="$2"; shift 2;;
    --speed)    SPEED="$2";    shift 2;;
    *) echo "unknown option: $1" >&2; exit 1;;
  esac
done

command -v ffmpeg >/dev/null || { echo "error: ffmpeg not found (brew install ffmpeg)" >&2; exit 1; }

# Build the filtergraph: optional speed-up, fps, scale (even width, preserve aspect), lanczos.
vf="fps=${FPS}"
[ -n "$SPEED" ] && vf="setpts=PTS/${SPEED},${vf}"
vf="${vf},scale=${WIDTH}:-2:flags=lanczos"

# Trim args (input-level seeking is fast and frame-accurate enough for screen caps).
trim=()
[ -n "$START" ]    && trim+=(-ss "$START")
[ -n "$DURATION" ] && trim+=(-t "$DURATION")

PALETTE="$(mktemp -t gifpalette).png"
trap 'rm -f "$PALETTE"' EXIT

# Pass 1: per-frame-diff palette for crisp text. Pass 2: apply with light dithering.
ffmpeg -v warning -y "${trim[@]}" -i "$IN" \
  -vf "${vf},palettegen=stats_mode=diff" "$PALETTE"
ffmpeg -v warning -y "${trim[@]}" -i "$IN" -i "$PALETTE" \
  -lavfi "${vf} [x]; [x][1:v] paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle" \
  "$OUT"

if command -v gifsicle >/dev/null; then
  gifsicle -O3 --lossy=40 -b "$OUT"
fi

bytes=$(wc -c < "$OUT" | tr -d ' ')
printf 'wrote %s (%s KB, %spx wide, %s fps)\n' "$OUT" "$((bytes/1024))" "$WIDTH" "$FPS"
