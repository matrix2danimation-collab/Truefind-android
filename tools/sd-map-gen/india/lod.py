# Secret Dating map (25 Nov): more levels of detail for India's district and state lines, so the map never draws
# much finer lines than the screen can show. The 24 Nov file (gen3.py) had districts at 0.006° and 0.02° and states at
# 0.004°, 0.03° and 0.07°; between those the map drew the finer level — e.g. all 39,000 district points from a little
# closer than "the north" up to a city. The new levels lie in between and are made from the same source lines
# (chains.pkl from edges.py → chain.py) in exactly gen3.py's way (shapely Douglas–Peucker in degrees, lines shorter
# than 1.5 × the tolerance left out); the old levels come out identical. The app picks a level the same way as before:
# the coarsest one whose tolerance is under one dp on screen (GlobeMap.pick), so lines are never off by a dp or more.
# Everything else in the file is kept byte for byte.
#   python3 lod.py chains.pkl in.bin out.bin
import pickle, struct, sys
from shapely.geometry import LineString

chains_path, src, dst = sys.argv[1], sys.argv[2], sys.argv[3]
DISTRICT = [0.006, 0.009, 0.0135, 0.02, 0.03, 0.045]
STATE = [0.004, 0.006, 0.009, 0.0135, 0.02, 0.03, 0.045, 0.07]

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
old = {n: r for n, t, r in layers}

chains = pickle.load(open(chains_path, 'rb'))
def lines(ls, tol):   # gen3.py's
    out = []
    for l in ls:
        s = LineString(l).simplify(tol, preserve_topology=False) if tol else LineString(l)
        if len(s.coords) < 2 or s.length < tol * 1.5: continue
        out.append([v for q in s.coords for v in (q[0], q[1])])
    return out
f = lambda v: struct.unpack('>f', struct.pack('>f', v))[0]
def made(kind, tols, old_tols):
    out = []
    for i, t in enumerate(tols):
        r = lines(chains[kind], t)
        if t in old_tols:   # an old level: must come out the same as in the file
            o = old[f'{kind}{old_tols.index(t)}']
            assert len(o) == len(r) and all(len(x) == len(y) and all(f(u) == w for u, w in zip(x, y)) for x, y in zip(r, o)), f'{kind} {t} differs'
        out.append((f'{kind}{i}', t, r))
    return out
state = made('state', STATE, [0.004, 0.03, 0.07])
district = made('district', DISTRICT, [0.006, 0.02])
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
