#!/usr/bin/env python3
"""Frame times from `dumpsys gfxinfo <pkg> framestats` dumps (several dumps of one test may be joined in one file;
   a frame that shows up in two dumps counts once). For each test: the frames drawn while a finger moved the map
   (they carry an input event) and the others, and where the time of a frame went:
     record  = the app's own drawing code (onDraw of every view) · issue = the drawing thread turning the picture
     into GPU work (paths are cut into pixels here) · gpu = until the GPU finished the frame.
   usage: fstats.py <name> <file> [<name> <file> …]   (prints one block per file)"""
import sys

def frames(path):
    head, out, seen = None, [], set()
    for line in open(path, errors="ignore"):
        line = line.strip()
        if line.startswith("Flags,"):
            head = line.rstrip(",").split(","); continue
        if head and line and line[0].isdigit():
            v = line.rstrip(",").split(",")
            if len(v) < len(head): continue
            r = dict(zip(head, map(int, v[:len(head)])))
            key = (r.get("IntendedVsync"), r.get("FrameTimelineVsyncId"))
            if key in seen: continue
            seen.add(key)
            if r.get("Flags", 1) == 0 and r.get("FrameCompleted", 0) > 0: out.append(r)
    return out

def pct(x, p): return sorted(x)[min(len(x) - 1, int(len(x) * p))] if x else 0.0
def span(r, a, b):
    if r.get(a, 0) > 0 and r.get(b, 0) >= r.get(a, 0): return (r[b] - r[a]) / 1e6
    return None
def col(rs, a, b): return [s for s in (span(r, a, b) for r in rs) if s is not None]

def block(name, rs):
    if not rs: return f"{name:14s} no frames"
    tot = col(rs, "IntendedVsync", "FrameCompleted")
    rec = col(rs, "DrawStart", "SyncQueued")
    iss = col(rs, "IssueDrawCommandsStart", "SwapBuffers")
    gpu = col(rs, "SwapBuffers", "FrameCompleted")
    slow = sum(t > 33.4 for t in tot) * 100 // max(1, len(tot))
    return (f"{name:14s} frames {len(rs):4d} | whole frame p50 {pct(tot,.5):6.1f} p90 {pct(tot,.9):6.1f} p99 {pct(tot,.99):6.1f} ms | over 33 ms {slow:3d}% | "
            f"record p50 {pct(rec,.5):5.1f} p90 {pct(rec,.9):5.1f} | issue p50 {pct(iss,.5):5.1f} p90 {pct(iss,.9):5.1f} | gpu p50 {pct(gpu,.5):5.1f} p90 {pct(gpu,.9):5.1f}")

args = sys.argv[1:]
for i in range(0, len(args) - 1, 2):
    name, path = args[i], args[i + 1]
    rs = frames(path)
    touch = [r for r in rs if r.get("InputEventId", 0) != 0]
    other = [r for r in rs if r.get("InputEventId", 0) == 0]
    if touch: print(block(name + " finger", touch))
    print(block(name + (" other" if touch else ""), other))
