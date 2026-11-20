// Secret Dating · Online Map data for the phone.
// Reads the web map's data (src/components/secret/mapData.js and src/lib/cityCoords.js) and writes
// app/src/main/assets/sd-map.bin (read by SdMapData.kt): the same outlines at the same levels of detail,
// the same towns, state names, place names and the same district → town tables the web map places people with.
//
//   node gen.mjs [web repo folder] [output file]      (defaults: ../../../truefind and the app's assets)
//   node verify.mjs                                    (reads the file back and checks it against the web)
//
// File layout (big-endian, Java DataInputStream): "TFMAP2", layers, towns, states, place names, then the
// geocoder tables (town coordinates, extra places, district aliases, same-name-other-state places, extra states).
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.resolve(process.argv[2] || path.join(here, "../../../truefind"));
const OUT = path.resolve(process.argv[3] || path.join(here, "../../app/src/main/assets/sd-map.bin"));
const MAPDATA = path.join(WEB, "src/components/secret/mapData.js");
const M = await import(pathToFileURL(MAPDATA).href);
const { CITY_COORDS } = await import(pathToFileURL(path.join(WEB, "src/lib/cityCoords.js")).href);
const src = fs.readFileSync(MAPDATA, "utf8");

// ── the levels of detail: the same tolerance lists as the web (read from its source, so they never drift apart) ──
const lodOf = (name) => {
  const m = src.match(new RegExp(`const ${name} = lods\\((\\w+), \\[([^\\]]*)\\], (\\d+)\\)`));
  if (!m) throw new Error(`${name} not found in mapData.js`);
  return { list: M[m[1]], tols: m[2].split(",").map(Number), min: +m[3] };
};
const lods = ({ list, tols, min }) => tols.map((tol) => ({ tol, rings: list.map((r) => (tol ? M.simplify(r, tol) : r)).filter((r) => r.length > min) }));

// ── the district tables (plain object literals in the web file) ──
const objOf = (name) => {
  const m = src.match(new RegExp(`const ${name} = (\\{[\\s\\S]*?\\n\\});`));
  if (!m) throw new Error(`${name} not found in mapData.js`);
  return Function(`return ${m[1]}`)();
};
const ALIASES = objOf("ALIASES"), EXTRA_PLACES = objOf("EXTRA_PLACES"), STATE_PLACES = objOf("STATE_PLACES"), EXTRA_STATES = objOf("EXTRA_STATES");
export const norm = (t) => String(t || "").toLowerCase().normalize("NFKD").replace(/[̀-ͯ]/g, "").replace(/&/g, " and ").replace(/[^a-z0-9]+/g, " ").trim();
// the web builds Maps from these (a later duplicate wins): the same here
const COORDS = new Map(Object.entries(CITY_COORDS).map(([k, v]) => [norm(k), [v[1], v[0]]]));
const EXTRA = new Map(Object.entries(EXTRA_PLACES).map(([k, v]) => [norm(k), v]));
const AL = new Map(Object.entries(ALIASES).map(([k, v]) => [norm(k), v]));

// ── writer (DataOutputStream format; strings in Java's modified UTF-8) ──
const parts = [];
const int = (v) => { const b = Buffer.alloc(4); b.writeInt32BE(v); parts.push(b); };
const flt = (v) => { const b = Buffer.alloc(4); b.writeFloatBE(v); parts.push(b); };
const utf = (s) => {
  const bytes = [];
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c >= 1 && c <= 0x7f) bytes.push(c);
    else if (c <= 0x7ff) bytes.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
    else bytes.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
  }
  if (bytes.length > 65535) throw new Error("string too long");
  const b = Buffer.alloc(2); b.writeUInt16BE(bytes.length); parts.push(b, Buffer.from(bytes));
};

const layers = [];
const add = (name, tol, rings) => layers.push({ name, tol, rings });
lods(lodOf("WORLD_LOD")).forEach((l, i) => add(`world${i}`, l.tol, l.rings));
lods(lodOf("BORDER_LOD")).forEach((l, i) => add(`border${i}`, l.tol, l.rings));
lods(lodOf("STATE_LOD")).forEach((l, i) => add(`state${i}`, l.tol, l.rings));
lods(lodOf("RIVER_LOD")).forEach((l, i) => add(`river${i}`, l.tol, l.rings));
add("highways", 0, M.HIGHWAYS);
add("roads", 0, M.ROADS);
add("worldLights", 0, [M.WORLD_LIGHTS]);
add("cityGeo", 0, [M.CITY_GEO]);
add("mesh", 0, M.MESH);

utf("TFMAP2");
int(layers.length);
for (const l of layers) {
  flt(l.tol); utf(l.name); int(l.rings.length);
  for (const r of l.rings) { int(r.length); for (const p of r) { flt(p[0]); flt(p[1]); } }
}
// towns, biggest first (the web's order: the order names are given room in)
int(M.CITIES.length);
for (const c of M.CITIES) { utf(c.n); flt(c.g[0]); flt(c.g[1]); int(Math.round(c.pop)); }
// states: name, size, middle
int(M.INDIA_GEO.length);
for (const s of M.INDIA_GEO) { utf(s.n); flt(s.a); flt(s.c[0]); flt(s.c[1]); }
// place names that are not towns: text, where, kind, the zoom they show between
int(M.PLACE_NAMES.length);
for (const p of M.PLACE_NAMES) { utf(p.t); flt(p.g[0]); flt(p.g[1]); utf(p.k); flt(p.z[0]); flt(p.z[1]); }
// geocoder: town coordinates (already normalised names)
int(COORDS.size); for (const [k, v] of COORDS) { utf(k); flt(v[0]); flt(v[1]); }
int(EXTRA.size); for (const [k, v] of EXTRA) { utf(k); flt(v[0]); flt(v[1]); }
int(AL.size); for (const [k, v] of AL) { utf(k); int(v.length); for (const n of v) utf(n); }
const SP = Object.entries(STATE_PLACES); int(SP.length); for (const [k, v] of SP) { utf(k); flt(v[0]); flt(v[1]); }
const ES = Object.entries(EXTRA_STATES); int(ES.length); for (const [k, v] of ES) { utf(k); flt(v[0]); flt(v[1]); }

const buf = Buffer.concat(parts);
fs.writeFileSync(OUT, buf);
console.log(`${OUT}: ${(buf.length / 1024).toFixed(0)} kB · ${layers.map((l) => `${l.name}:${l.rings.length}`).join(" ")} · towns ${M.CITIES.length} · states ${M.INDIA_GEO.length} · names ${M.PLACE_NAMES.length} · coords ${COORDS.size} · extra ${EXTRA.size} · aliases ${AL.size} · state places ${SP.length} · extra states ${ES.length}`);
