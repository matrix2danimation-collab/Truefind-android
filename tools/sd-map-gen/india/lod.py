# Secret Dating map: more levels of detail for India's district and state lines (25 Nov), so the map never draws
# much finer lines than the screen can show. The 24 Nov file had districts at 0.006° and 0.02° and states at 0.004°,
# 0.03° and 0.07°; between those, the map drew the finer level (e.g. all 39,000 district points from a little closer
# than all of India up to a city). The new levels lie in between, each made from the next finer level the file
# already has, simplified the same way gen3.py simplifies (shapely, Douglas–Peucker, in degrees). A level's tolerance
# is the most its lines can be off from the source map (the finer level's tolerance plus the extra simplification), so
# the app's rule stays true: a level is used only where that is under one dp on screen.
# Everything else in the file is kept byte for byte.
#   python3 lod.py in.bin out.bin
import struct, sys
from shapely.geometry import LineString

src, dst = sys.argv[1], sys.argv[2]
b = open(src, 'rb').read()
p = 0
def i32():
    global p; v = struct.unpack_from('>i', b, p)[0]; p += 4; return v
def f32():
    global p; v = struct.unpack_from('>f', b, p)[0]; p += 4; return v
def utf():
    global p; n = struct.unpack_from('>H', b, p)[0]; p += 2; v = b[p:p + n].decode('utf8'); p += n; return v

magic = utf(); assert magic == 'TFMAP3', magic
layers = []
for _ in range(i32()):
    tol = f32(); name = utf(); rings = []
    for _ in range(i32()):
        m = i32(); rings.append(list(struct.unpack_from('>%df' % (2 * m), b, p))); p += 8 * m
    layers.append((name, tol, rings))
rest = b[p:]
L = {n: (t, r) for n, t, r in layers}

def simplify(rings, extra, tol):
    out = []
    for r in rings:
        s = LineString(list(zip(r[0::2], r[1::2]))).simplify(extra, preserve_topology=False)
        if len(s.coords) < 2 or s.length < tol * 1.5: continue
        out.append([v for q in s.coords for v in (q[0], q[1])])
    return out

def levels(prefix, plan):
    """plan: list of (tol, source level name or None for kept as is)"""
    old = [(n, t, r) for n, t, r in layers if n.startswith(prefix)]
    made = []
    for tol, base in plan:
        if base is None:
            n, t, r = next(x for x in old if abs(x[1] - tol) < 1e-6); made.append((tol, r))
        else:
            bt, br = L[base]; made.append((tol, simplify(br, tol - bt, tol)))
    return [(f'{prefix}{i}', t, r) for i, (t, r) in enumerate(made)]

district = levels('district', [(0.006, None), (0.009, 'district0'), (0.0135, 'district0'), (0.02, None), (0.03, 'district1'), (0.045, 'district1')])
state = levels('state', [(0.004, None), (0.006, 'state0'), (0.009, 'state0'), (0.0135, 'state0'), (0.02, 'state0'), (0.03, None), (0.045, 'state1'), (0.07, None)])
new = [l for l in layers if not l[0].startswith(('district', 'state'))] + state + district

parts = []
def U(s):
    e = s.encode('utf8'); parts.append(struct.pack('>H', len(e)) + e)
U(magic); parts.append(struct.pack('>i', len(new)))
for n, t, rings in new:
    parts.append(struct.pack('>f', t)); U(n); parts.append(struct.pack('>i', len(rings)))
    for r in rings: parts.append(struct.pack('>i', len(r) // 2) + struct.pack('>%df' % len(r), *r))
out = b''.join(parts) + rest
open(dst, 'wb').write(out)
for n, t, r in state + district: print(f'{n:10s} tol {t:<7} lines {len(r):5d} points {sum(len(x) // 2 for x in r):6d}')
print(dst, len(out) // 1024, 'kB (was', len(b) // 1024, 'kB)')
