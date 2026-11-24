#!/usr/bin/env bash
# Secret Dating · Online Map smoothness on the test phone (made-up people, no server, no sign-in): how long each
# frame takes while the map is dragged, at four zooms (all of India · the north · Uttar Pradesh · a city), and
# while it sits still. The emulator draws with a software GPU, so its times are far slower than a phone's: they
# are for comparing two versions of the app on the same kind of machine, not for promising a phone's numbers.
# Output: mapperf.txt (one line per scene), <scene>.png (what the map looked like), <scene>-framestats.txt.
APK="$1"; OUT="$2"; PKG=app.truefind.android
mkdir -p "$OUT"
ADB="$(command -v adb)"
adb() { timeout 90 "$ADB" "$@"; }
timeout 240 "$ADB" install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell settings put global hide_error_dialogs 1
read -r W H <<< "$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')"; W=${W:-1080}; H=${H:-2400}
# like a normal phone: animations on (the emulator starts with them off, which the map reads as "reduce motion") and
# the full look (tf_lite 0), not the light one for phones with little memory / few cores
for k in animator_duration_scale transition_animation_scale window_animation_scale; do adb shell settings put global $k 1; done
LOOK="${MAPPERF_LOOK:-0}"
waitlog() { local end=$(( $(date +%s) + $2 )); while [ "$(date +%s)" -lt $end ]; do adb logcat -d -s TFDBG:I | grep -q "$1" && return 0; sleep 1; done; return 1; }

# per-frame times from "dumpsys gfxinfo … framestats": total, the app's own thread (input + layout + drawing the
# picture list) and the drawing thread (turning it into pixels)
summary() {
  python3 - "$1" "$2" <<'PY' >> "$OUT/mapperf.txt"
import sys, statistics as st
name, path = sys.argv[1], sys.argv[2]
rows, head = [], None
for line in open(path, errors="ignore"):
    line = line.strip()
    if line.startswith("Flags,"): head = line.rstrip(",").split(","); continue
    if head and line and line[0].isdigit():
        v = line.rstrip(",").split(",")
        if len(v) >= len(head): rows.append(dict(zip(head, map(int, v[:len(head)]))))
ok = [r for r in rows if r.get("Flags", 1) == 0 and r.get("FrameCompleted", 0) > 0]
def ms(a, b): return [(r[b] - r[a]) / 1e6 for r in ok if r.get(a, 0) > 0 and r.get(b, 0) >= r.get(a, 0)]
def pct(x, p): return sorted(x)[min(len(x) - 1, int(len(x) * p))] if x else 0.0
tot = ms("IntendedVsync", "FrameCompleted"); ui = ms("HandleInputStart", "SyncQueued"); rt = ms("SyncStart", "FrameCompleted")
if not ui: ui = ms("AnimationStart", "SyncQueued")
print(f"{name:14s} frames {len(ok):4d} | total p50 {pct(tot,.5):6.1f} p90 {pct(tot,.9):6.1f} p99 {pct(tot,.99):6.1f} ms | "
      f"app thread p50 {pct(ui,.5):6.1f} p90 {pct(ui,.9):6.1f} | draw thread p50 {pct(rt,.5):6.1f} p90 {pct(rt,.9):6.1f} | "
      f"over 33 ms {sum(t > 33.3 for t in tot) * 100 // max(1, len(tot)):3d}%")
PY
}

# scene <name> <tf_open>: the full-screen map at a zoom, 10 drags across it, then 6 s without touching it
scene() {
  adb shell am force-stop $PKG; sleep 1; adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity --es tf_preview secret-map --ez tf_premium true --es tf_open "$2" --es tf_lite "$LOOK" >> "$OUT/start.txt" 2>&1
  waitlog "preview open" 45 || echo "($1: no 'preview open')" >> "$OUT/start.txt"
  sleep 9   # the first flight to the place ends
  adb shell dumpsys gfxinfo $PKG reset > /dev/null
  for i in 1 2 3 4 5; do
    adb shell input swipe $((W * 25 / 100)) $((H * 55 / 100)) $((W * 70 / 100)) $((H * 50 / 100)) 650
    adb shell input swipe $((W * 70 / 100)) $((H * 45 / 100)) $((W * 30 / 100)) $((H * 52 / 100)) 650
  done
  adb shell dumpsys gfxinfo $PKG framestats > "$OUT/$1-framestats.txt"
  summary "$1-drag" "$OUT/$1-framestats.txt"
  # (framestats keeps the last 120 frames; the counts below cover every frame of the drags)
  { echo -n "               all drag frames: "; grep -E "Total frames rendered|Janky frames:|50th percentile|90th percentile|99th percentile" "$OUT/$1-framestats.txt" | head -5 | sed -E 's/^ +//' | tr '\n' ' '; echo; } >> "$OUT/mapperf.txt"
  timeout 40 "$ADB" exec-out screencap -p > "$OUT/$1.png"
  adb shell dumpsys gfxinfo $PKG reset > /dev/null
  sleep 6
  adb shell dumpsys gfxinfo $PKG framestats > "$OUT/$1-still-framestats.txt"
  summary "$1-still" "$OUT/$1-still-framestats.txt"
}

echo "scene          (times per frame on the emulator; lower is better)" > "$OUT/mapperf.txt"
scene india full-india
scene north full-north
scene up full-up
scene city full-close
adb logcat -d -s AndroidRuntime:E | grep -A30 "Process: $PKG" > "$OUT/crash.txt"; [ -s "$OUT/crash.txt" ] || rm -f "$OUT/crash.txt"
cat "$OUT/mapperf.txt"
ls -la "$OUT"
