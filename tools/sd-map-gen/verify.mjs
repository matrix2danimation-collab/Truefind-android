// Reads sd-map.bin back and checks it against the web map: every layer, and where every district people can pick
// (the app's own list, assets/sd-data.json) lands — with the data as the phone reads it (32-bit numbers).
//   node verify.mjs [web repo folder] [sd-map.bin]
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.resolve(process.argv[2] || path.join(here, "../../../truefind"));
const BIN = path.resolve(process.argv[3] || path.join(here, "../../app/src/main/assets/sd-map.bin"));
const M = await import(pathToFileURL(path.join(WEB, "src/components/secret/mapData.js")).href);
const b = fs.readFileSync(BIN);
let o = 0;
const int = () => { const v = b.readInt32BE(o); o += 4; return v; };
const flt = () => { const v = b.readFloatBE(o); o += 4; return v; };
const utf = () => { const n = b.readUInt16BE(o); o += 2; const s = b.subarray(o, o + n).toString("utf8"); o += n; return s; };

if (utf() !== "TFMAP2") throw new Error("not TFMAP2");
const layers = {};
for (let i = int(); i > 0; i--) {
  const tol = flt(), name = utf(), rings = [];
  for (let n = int(); n > 0; n--) { const r = []; for (let m = int(); m > 0; m--) r.push([flt(), flt()]); rings.push(r); }
  layers[name] = { tol, rings };
}
const towns = []; for (let n = int(); n > 0; n--) towns.push({ n: utf(), g: [flt(), flt()], pop: int() });
const states = []; for (let n = int(); n > 0; n--) states.push({ n: utf(), a: flt(), c: [flt(), flt()] });
const names = []; for (let n = int(); n > 0; n--) names.push({ t: utf(), g: [flt(), flt()], k: utf(), z: [flt(), flt()] });
const map = () => { const m = new Map(); for (let n = int(); n > 0; n--) m.set(utf(), [flt(), flt()]); return m; };
const COORDS = map(), EXTRA = map();
const AL = new Map(); for (let n = int(); n > 0; n--) { const k = utf(), v = []; for (let m = int(); m > 0; m--) v.push(utf()); AL.set(k, v); }
const SP = map(), ES = map();
if (o !== b.length) throw new Error(`${b.length - o} bytes left over`);

let bad = 0;
const near = (a, c) => Math.abs(a - c) < 1e-4;
const sameRings = (A, B, label) => {
  if (A.length !== B.length) { console.log(`${label}: ${A.length} rings, web ${B.length}`); bad++; return; }
  for (let i = 0; i < A.length; i++) {
    if (A[i].length !== B[i].length || A[i].some((p, j) => !near(p[0], B[i][j][0]) || !near(p[1], B[i][j][1]))) { console.log(`${label}: ring ${i} differs`); bad++; return; }
  }
};
// the web picks a level by zoom: ask it for each level and compare
const D2R = Math.PI / 180;
const levels = (prefix, fn, px) => {
  const ls = Object.keys(layers).filter((k) => k.startsWith(prefix) && /\d$/.test(k)).sort();
  for (const k of ls) { const t = layers[k].tol; const R = t ? (px / (D2R * t)) * 0.999 : 1e9; sameRings(layers[k].rings, fn(R), k); }
};
levels("world", M.worldRings, 1.6); levels("border", M.borderRings, 0.9); levels("state", M.stateRings, 1); levels("river", M.riverRings, 1.2);
sameRings(layers.highways.rings, M.HIGHWAYS, "highways"); sameRings(layers.roads.rings, M.ROADS, "roads");
sameRings(layers.worldLights.rings, [M.WORLD_LIGHTS], "worldLights");
sameRings([layers.cityGeo.rings[0]], [M.CITY_GEO], "cityGeo"); sameRings(layers.mesh.rings, M.MESH, "mesh");
if (towns.length !== M.CITIES.length || towns.some((t, i) => t.n !== M.CITIES[i].n || t.pop !== Math.round(M.CITIES[i].pop))) { console.log("towns differ"); bad++; }
if (states.length !== M.INDIA_GEO.length || states.some((s, i) => s.n !== M.INDIA_GEO[i].n)) { console.log("states differ"); bad++; }
if (names.length !== M.PLACE_NAMES.length || names.some((p, i) => p.t !== M.PLACE_NAMES[i].t || p.k !== M.PLACE_NAMES[i].k)) { console.log("place names differ"); bad++; }

// ── placeOf, written the way the phone does it (SdPlaces.kt), on the data as read back ──
const norm = (t) => String(t || "").toLowerCase().normalize("NFKD").replace(/[̀-ͯ]/g, "").replace(/&/g, " and ").replace(/[^a-z0-9]+/g, " ").trim();
const TOWNS = new Map(); for (const c of towns) { const k = norm(c.n); if (!TOWNS.has(k)) TOWNS.set(k, []); TOWNS.get(k).push(c); }
const STATES = new Map(states.map((s) => [norm(s.n), { mid: s.c, r: Math.max(3, 2.2 * Math.sqrt(s.a / 481 / Math.PI)) }]));
const stateOf = (st) => { const k = norm(st); return STATES.get(k) || (ES.has(k) ? { mid: ES.get(k), r: 3 } : null); };
const apart = (a, c) => Math.hypot((a[0] - c[0]) * Math.cos(((a[1] + c[1]) / 2) * D2R), a[1] - c[1]);
const DIRS = /\b(north|south|east|west|central|outer|upper|lower|middle|eastern|western|northern|southern|purba|paschim|uttar|dakshin)\b/g;
const placeOf = (city, state) => {
  const S = stateOf(state), mid = S ? S.mid : null, rad = S ? S.r : 9;
  let out = SP.get(`${norm(city)}|${norm(state)}`) || null;
  const raw = String(city || "").trim();
  if (!out && raw) {
    const nm0 = [raw, ...raw.split(/[()/,]|\s-\s|-/).map((x) => x.trim()).filter(Boolean)];
    const more = []; for (const n of nm0) { const a = AL.get(norm(n)); if (a) more.push(...a); }
    for (const nm of [...nm0, ...more, norm(raw).replace(DIRS, " ").replace(/\s+/g, " ").trim()]) {
      const k = norm(nm); if (!k) continue;
      const e = EXTRA.get(k); if (e) { out = e; break; }
      const c = COORDS.get(k); if (c && (!mid || apart(c, mid) < rad)) { out = c; break; }
      const ts = TOWNS.get(k);
      if (ts) { const best = mid ? ts.reduce((x, y) => (apart(y.g, mid) < apart(x.g, mid) ? y : x)) : ts[0]; if (!mid || apart(best.g, mid) < rad) { out = best.g; break; } }
    }
  }
  return out || mid || null;
};
const D = JSON.parse(fs.readFileSync(path.join(here, "../../app/src/main/assets/sd-data.json"), "utf8")).districts;
let n = 0, diff = 0;
const check = (city, state) => {
  n++;
  const a = placeOf(city, state), w = M.placeOf(city, state);
  const ok = (!a && !w) || (a && w && Math.abs(a[0] - w[0]) < 1e-3 && Math.abs(a[1] - w[1]) < 1e-3);
  if (!ok) { diff++; if (diff < 12) console.log(`placeOf(${city}, ${state}): phone ${a}, web ${w}`); }
};
for (const [st, ds] of Object.entries(D)) { check("", st); for (const d of ds) check(d, st); }
for (const [c, s] of [["Delhi", ""], ["", ""], ["Rohtak", "haryana"], ["Gautam Buddh Nagar (Noida)", "Uttar Pradesh"], ["Mohali (SAS Nagar)", "Punjab"], ["Bengaluru Urban", "Karnataka"], ["Kamrup Metro", "Assam"], ["Bilaspur", "Himachal Pradesh"], ["Bilaspur", "Chhattisgarh"], ["Pratapgarh", "Rajasthan"], ["Pratapgarh", "Uttar Pradesh"], ["Lakhimpur Kheri", "Uttar Pradesh"], ["Nowhere", "Nowhere"]]) check(c, s);
console.log(`layers ${Object.keys(layers).length} · towns ${towns.length} · placeOf: ${n} places, ${diff} differ from the web · ${bad} layer problems`);
if (bad || diff) process.exit(1);
// for checking the phone's own code (SdPlaces.kt) by hand: every district → [lng, lat], in the temp folder
fs.writeFileSync(path.join(os.tmpdir(), "sd-expected-places.txt"), Object.entries(D).flatMap(([st, ds]) => ["", ...ds].map((d) => { const p = placeOf(d, st); return `${d}|${st}|${p ? p[0].toFixed(4) + "," + p[1].toFixed(4) : "null"}`; })).join("\n") + "\n");
