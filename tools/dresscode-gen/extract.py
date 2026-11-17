# Copies the Dress Code data + art section of the web app's Games.jsx into art.jsx (with exports),
# so gen.mjs can run the web's own drawing code. Usage: python3 extract.py /path/to/truefind/src/pages/Games.jsx
import re, sys
src = open(sys.argv[1]).read().split('\n')
start = next(i for i, l in enumerate(src) if l.startswith('// ───────────── Colours ─────────────'))
end = next(i for i, l in enumerate(src) if l.startswith('const Thumb = React.memo(ThumbSvg);'))
art = '\n'.join(src[start:end + 1])
open('art.jsx', 'w').write('import React from "react";\n' + art + '''
export { COLORS, SLOT_PAL, PATTERNS, TIER, BASES, SINGLES, ITEMS, BY_ID, TABS, G, A, HAIR, DEFAULT_HAIR, BASE_HAIR, BASE_TOP, BASE_BOTTOM,
  Body, GlobalDefs, PatDef, fillOf, mk, shade, isLight, THUMB_BOX, THUMB_BODY, visibleLook, drawPiece, sareeBlouse, DollSvg, ThumbSvg, colorsFor };
''')
