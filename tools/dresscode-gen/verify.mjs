// Checks that building the doll from the templates gives exactly the web's own doll SVG.
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import * as W from "./art.out.mjs";
import fs from "fs";
const D = JSON.parse(fs.readFileSync("dresscode.json", "utf8"));
const BY = Object.fromEntries(D.items.map((i) => [i.id, i]));
const shade = W.shade, mk = W.mk;
const colorsOf = (id, c) => { const it = BY[id]; const ix = it.cols[c] ?? it.cols[0]; return mk(D.colors[ix][1]); };
const fillT = (t, c) => t.replaceAll("§m", c.m.slice(1)).replaceAll("§d", c.d.slice(1)).replaceAll("§l", c.l.slice(1)).replaceAll("§x", c.x.slice(1)).replaceAll("§k", c.k.slice(1));
const piece = (w) => { if (!w) return ""; const it = BY[w.id]; if (!it || it.t === undefined) return ""; return fillT(D.tpls[it.t], colorsOf(w.id, w.c ?? 0)); };
const visible = (look) => { const l = { ...look }; if (l.kurta) { delete l.top; delete l.blouse; } if (l.top) delete l.blouse; if (l.saree) delete l.bottom; return l; };
function doll(look, side) {
  const l = visible(look); const p = (s) => piece(l[s]);
  const hw = l.hair && BY[l.hair.id] && BY[l.hair.id].hb !== undefined ? l.hair : null;
  const hc = hw ? colorsOf(hw.id, hw.c ?? 0) : D.baseHair;
  const hb = hw ? fillT(D.tpls[BY[hw.id].hb], hc) : D.defaultHairB, hf = hw ? fillT(D.tpls[BY[hw.id].hf], hc) : D.defaultHairF;
  const back = side === "back";
  const lower = l.saree ? "" : (p("bottom") || D.baseTop.length && D.baseBottom);
  const upper = l.kurta ? p("kurta") : l.top ? p("top") : l.blouse ? p("blouse") : (l.saree ? "" : D.baseTop);
  const sb = (w) => fillT(D.sareeBlouse, mk(colorsOf(w.id, w.c ?? 0).x));
  let s = '<svg viewBox="0 0 300 545">' + D.defs;
  if (!back) s += hb;
  s += back ? D.bodyBack.replaceAll("§h", hc.m.slice(1)) : D.bodyFront;
  s += lower;
  if (l.saree && !l.kurta && !l.top) s += l.blouse ? p("blouse") : sb(l.saree);
  if (l.saree && (l.kurta || l.top)) s += upper;
  s += l.saree ? p("saree") : upper;
  s += p("shoes"); if (!back) s += p("necklace"); s += p("earrings"); s += p("watch") + p("bangles"); if (!back) s += p("ring");
  s += p("dupatta") + p("bag"); if (!back) s += p("glasses"); s += back ? hb : hf; if (!back) s += p("tikka"); s += p("hat") + p("hand");
  return s + "</svg>";
}
const web = (look, side) => renderToStaticMarkup(React.createElement(W.DollSvg, { look, side })).replace(/ role="img" aria-label="Doll"/, "");
const slots = [...new Set(D.items.map((i) => i.slot))];
let bad = 0, n = 0;
const rnd = (a) => a[Math.floor(Math.random() * a.length)];
for (let t = 0; t < 400; t++) {
  const look = {};
  for (const s of slots) if (Math.random() < 0.45) { const it = rnd(D.items.filter((i) => i.slot === s)); look[s] = { id: it.id, c: Math.floor(Math.random() * 6) }; }
  for (const side of ["front", "back"]) { n++; const a = doll(look, side), b = web(look, side); if (a !== b) { bad++; if (bad < 3) { let i = 0; while (a[i] === b[i]) i++; console.log("DIFF", side, JSON.stringify(look).slice(0, 200), "\n mine:", a.slice(i - 80, i + 120), "\n web: ", b.slice(i - 80, i + 120)); } } }
}
console.log("checked", n, "different", bad);
// thumbnails
function thumb(it, c) {
  const w = { id: it.id, c };
  const isHair = it.slot === "hair" && it.hb !== undefined;
  const hc = isHair ? colorsOf(it.id, c) : null;
  let s = `<svg viewBox="${D.thumbBox[it.slot]}" class="w-full h-full" aria-hidden="true">` + D.defs;
  if (isHair) s += fillT(D.tpls[it.hb], hc);
  if (D.thumbBody[it.slot]) s += D.bodyGhost;
  if (it.slot === "saree") s += fillT(D.sareeBlouse, mk(colorsOf(it.id, c).x));
  s += isHair ? fillT(D.tpls[it.hf], hc) : piece(w);
  return s + "</svg>";
}
let tb = 0, tn = 0;
for (const it of D.items) for (let c = 0; c < 6; c++) { tn++; const a = thumb(it, c), b = renderToStaticMarkup(React.createElement(W.ThumbSvg, { item: W.BY_ID[it.id], c })); if (a !== b) { tb++; if (tb < 3) console.log("TDIFF", it.id, c, a.slice(0, 200), "\n", b.slice(0, 200)); } }
console.log("thumbs checked", tn, "different", tb);
