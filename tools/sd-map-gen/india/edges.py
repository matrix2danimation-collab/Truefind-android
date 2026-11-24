import json,collections,pickle,sys
f=json.load(open('/home/claude/mapwork/dl-geo/datta-repo/INDIA/INDIA_DISTRICTS.geojson'))['features']
Q=1e4
seg={}  # (a,b) -> list of (state, district idx)
npts=0
for i,x in enumerate(f):
    s=x['properties']['state'] or ('?%d'%i)
    g=x['geometry']; cs=g['coordinates'] if g['type']=='MultiPolygon' else [g['coordinates']]
    for poly in cs:
        for ring in poly:
            prev=None
            for pt in ring:
                q=(round(pt[0]*Q),round(pt[1]*Q)); npts+=1
                if prev is not None and q!=prev:
                    k=(prev,q) if prev<q else (q,prev)
                    v=seg.get(k)
                    if v is None: seg[k]=(s,i)
                    elif v!=(s,i): seg[k]=(v,(s,i))
                prev=q
del f
def own(v):
    if isinstance(v[0],str): return [v]
    return own(v[0])+own(v[1])
st=[];di=[];c=collections.Counter()
for k,v in seg.items():
    o=own(v)
    if len(o)<2: c['single']+=1; continue
    if any(x[0].startswith('?') for x in o): c['sliver']+=1; continue
    if len(set(x[0] for x in o))>1: st.append(k); c['state']+=1
    else: di.append(k); c['district']+=1
print('points',npts,'segments',len(seg),c)
pickle.dump({'state':st,'district':di},open('segs.pkl','wb'))
