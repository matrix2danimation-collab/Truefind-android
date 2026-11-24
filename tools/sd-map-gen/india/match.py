import json,sys,re,difflib,collections
sys.path.insert(0,'/home/claude/mapwork/scripts')
from place import norm,placeOf
from shapely.geometry import shape,Point
from shapely import prepared
D=json.load(open('/home/claude/mapwork/dl-geo/datta-repo/INDIA/INDIA_DISTRICTS.geojson'))['features']
polys=[]
for f in D:
    p=f['properties']; g=shape(f['geometry'])
    if not g.is_valid: g=g.buffer(0)
    polys.append({'d':p['district'],'s':p['state'],'g':g,'pg':prepared.prep(g)})
print(collections.Counter(str(x['s']) for x in polys)); print([ (x['d'],x['g'].centroid.coords[0]) for x in polys if not x['s']])
from shapely.ops import unary_union
bys=collections.defaultdict(list)
for x in polys:
    if x['s']: bys[x['s']].append(x['g'])
st={k:unary_union(v) for k,v in bys.items()}
import pickle; pickle.dump(polys and [(x['d'],x['s'],x['g']) for x in polys],open('/home/claude/mapwork/polys.pkl','wb')); pickle.dump(st,open('/home/claude/mapwork/states.pkl','wb'))
from bin import read
B=read('/home/claude/truefind-android/app/src/main/assets/sd-map.bin')
for n,a,lng,lat in B['states']:
    k=n.upper().replace('&','AND')
    g=st.get(k)
    if g is not None: print(n,a,round(g.area,2),round(a/g.area,1),(round(lng,2),round(lat,2)),[round(v,2) for v in g.representative_point().coords[0]])
    else: print('NO',n)
