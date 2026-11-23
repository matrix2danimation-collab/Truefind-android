#!/usr/bin/env python3
"""ci/push-test.sh helper: where to tap on the screen (uiautomator dump).

  ui-find.py ui.xml "Answer" ["Riya is calling you"]

Prints "x y" of the button whose text / description is the given one. When it isn't there (the
notification is shown folded, as on Android 16), prints "EXPAND x y" for the Expand toggle of the
notification whose title is the third argument, so the caller can unfold it and look again.
"""
import re
import sys
import xml.etree.ElementTree as ET

def center(b):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", b))
    return (x1 + x2) // 2, (y1 + y2) // 2, y1, y2

try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    sys.exit(1)
want = sys.argv[2].lower()
title = sys.argv[3].lower() if len(sys.argv) > 3 else ""
nodes = list(root.iter("node"))
for n in nodes:
    t = (n.get("text") or "").strip().lower(); d = (n.get("content-desc") or "").strip().lower()
    if want in (t, d) and n.get("bounds"):
        x, y, _, _ = center(n.get("bounds")); print(x, y); sys.exit(0)
if title:
    tops = [center(n.get("bounds"))[2] for n in nodes if (n.get("text") or "").strip().lower() == title and n.get("bounds")]
    exp = [center(n.get("bounds")) for n in nodes if (n.get("content-desc") or "").strip().lower() == "expand" and n.get("bounds")]
    for ty in tops:
        best = sorted(exp, key=lambda e: abs(e[2] - ty))
        if best and abs(best[0][2] - ty) < 260:
            print("EXPAND", best[0][0], best[0][1]); sys.exit(0)
sys.exit(1)
