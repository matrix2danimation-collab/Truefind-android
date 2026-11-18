# Dress Code art for the phone

The app draws Dress Code with the web's own art. `gen.mjs` runs the web code (Games.jsx) and writes
`app/src/main/assets/games/dresscode.json`: every item's SVG with its colours left as placeholders
(`#§m #§d #§l #§x #§k`), plus the body, hair, defs and the item list. The app only swaps the colours in.

    npm i react@18.3.1 react-dom@18.3.1 esbuild@0.24.0
    python3 extract.py ../../../truefind/src/pages/Games.jsx
    npx esbuild art.jsx --format=esm --jsx=transform --outfile=art.out.mjs
    node gen.mjs && node verify.mjs      # verify: the app's way of building gives the web's exact SVG
    cp dresscode.json ../../app/src/main/assets/games/

Run it again whenever the web's Dress Code art or items change.
