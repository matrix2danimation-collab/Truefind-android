# Secret Dating map data for the phone

The app's Online Map draws the web map's own data. `gen.mjs` reads the web files
(`src/components/secret/mapData.js`, `src/lib/cityCoords.js`) and writes `app/src/main/assets/sd-map.bin`:
the outlines at the same levels of detail, the towns (biggest first), states, place names, the network between
India's big cities, and the tables that put each person at the district or town they chose.

    node gen.mjs ../../../truefind        # path to the web repo
    node verify.mjs ../../../truefind     # reads the file back: every layer and all 786 districts must match the web

Run it again whenever the web map's data changes.

## All of India (24 Nov) — `india/`, file version TFMAP3

On top of the web's data (`gen.mjs` → TFMAP2), `india/gen3.py` adds all of India in detail and writes TFMAP3:

- **towns** (~7,100): the web's towns + GeoNames places of 1,000+ people (npm `all-the-cities`, MIT, read by
  `atc.py`) + every district and sub-district headquarters of
  [datta07/INDIAN-SHAPEFILES](https://github.com/datta07/INDIAN-SHAPEFILES) (MIT), the same place spelt two ways
  kept once; each town has a kind: 0 town, 1 district headquarters, 2 state / UT capital, 3 New Delhi
- **state lines** from that repo's district map (Ladakh apart from Jammu & Kashmir, DNH & DD joined) and **district
  lines** (`edges.py` → `chain.py`: the edges two districts share)
- **states**: today's 36 (28 states, 8 union territories)
- **where each of the 786 districts people can pick sits**: checked against the district's own outline
  (`districts2.py`); a place outside its district (another town of the same name, a mistyped entry) is moved to the
  district's headquarters. `verify3.py` reads the file back and checks every district.

Order: `match.py` (district outlines) → `towns.py` → `districts2.py` → `edges.py` → `chain.py` → `gen3.py out.bin` → `verify3.py out.bin`.
The scripts expect the downloads in `/home/claude/mapwork` (paths at their top).

## In-between levels for district and state lines (25 Nov trial) — `india/lod.py`, not in the app

`lod.py chains.pkl in.bin out.bin` adds levels between the existing ones (districts 0.009°, 0.0135°, 0.03°, 0.045°;
states 0.006°–0.02° and 0.045°), made from the same source lines in exactly gen3.py's way (the old levels come out
identical). Tried on 25 Nov: about 10–15 % less drawing work when zoomed in (emulator), district lines a little
smoother (under 1 dp). Not used yet — only with the owner's yes. `chains.pkl` comes from `edges.py` → `chain.py`
(the source geojson: datta07/INDIAN-SHAPEFILES `INDIA/INDIA_DISTRICTS.geojson`).

