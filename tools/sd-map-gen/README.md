# Secret Dating map data for the phone

The app's Online Map draws the web map's own data. `gen.mjs` reads the web files
(`src/components/secret/mapData.js`, `src/lib/cityCoords.js`) and writes `app/src/main/assets/sd-map.bin`:
the outlines at the same levels of detail, the towns (biggest first), states, place names, the network between
India's big cities, and the tables that put each person at the district or town they chose.

    node gen.mjs ../../../truefind        # path to the web repo
    node verify.mjs ../../../truefind     # reads the file back: every layer and all 786 districts must match the web

Run it again whenever the web map's data changes.
