// Runs the web's own Dress Code art (Games.jsx) and writes templates for the phone:
// every item's SVG with its colours left as placeholders (#§m #§d #§l #§x #§k), so the app only swaps colours in.
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import * as A0 from "./art.out.mjs";
import fs from "fs";
const { COLORS, PATTERNS, TIER, ITEMS, TABS, G, A, HAIR, DEFAULT_HAIR, BASE_HAIR, BASE_TOP, BASE_BOTTOM, Body, GlobalDefs, PatDef, fillOf, THUMB_BOX, THUMB_BODY } = A0;
const h = React.createElement;
const SYM = { m: "#§m", d: "#§d", l: "#§l", x: "#§x", k: "#§k" };
const inner = (el) => { if (!el) return ""; const s = renderToStaticMarkup(h("svg", null, el)); return s.replace(/^<svg>/, "").replace(/<\/svg>$/, ""); };
const tpls = []; const tplIx = new Map();
const add = (s) => { if (!tplIx.has(s)) { tplIx.set(s, tpls.length); tpls.push(s); } return tplIx.get(s); };
const items = ITEMS.map((it) => {
  let el = null;
  if (it.pat) {
    const draw = G[it.slot === "saree" ? "saree" : it.base];
    el = draw ? h("g", null, h("defs", null, h(PatDef, { pat: it.pat, c: SYM })), draw(fillOf(it, SYM), SYM, it)) : null;
  } else if (A[it.id]) el = h("g", null, A[it.id](SYM));
  const o = { id: it.id, slot: it.slot, base: it.base, pat: it.pat, name: it.name, baseName: it.baseName, tier: it.tier, price: it.price, tags: it.tags, cols: it.cols };
  if (el) o.t = add(inner(el));
  if (it.slot === "hair" && HAIR[it.id]) { const hh = HAIR[it.id](SYM); o.hb = add(inner(hh.b)); o.hf = add(inner(hh.f)); }
  return o;
});
const dh = DEFAULT_HAIR(BASE_HAIR);
const out = {
  colors: COLORS, patterns: PATTERNS, tierLabels: Object.fromEntries(Object.entries(TIER).map(([k, v]) => [k, v.label])),
  tabs: TABS, items, tpls,
  defs: inner(h(GlobalDefs)),
  baseTop: inner(BASE_TOP), baseBottom: inner(BASE_BOTTOM),
  bodyFront: inner(h(Body, {})), bodyBack: inner(h(Body, { back: true, hairCol: "#§h" })), bodyGhost: inner(h(Body, { ghost: true })),
  defaultHairB: inner(dh.b), defaultHairF: inner(dh.f), baseHair: BASE_HAIR,
  sareeBlouse: inner(G.bshort(SYM.m, SYM, { tier: "n" })),
  thumbBox: THUMB_BOX, thumbBody: THUMB_BODY,
};
fs.writeFileSync("dresscode.json", JSON.stringify(out));
console.log("items", items.length, "templates", tpls.length, "bytes", JSON.stringify(out).length, "no-art", items.filter((i) => i.t === undefined && i.hb === undefined).map((i) => i.id).join(","));
