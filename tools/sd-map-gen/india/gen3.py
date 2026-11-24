# Secret Dating map, TFMAP3: the web's TFMAP2 data plus all of India in detail (see tools/sd-map-gen/README.md)
import json,sys,math,pickle,struct,re,collections
sys.path.insert(0,'/home/claude/mapwork/scripts')
from bin import read
from place import norm
from shapely.geometry import LineString
from shapely.ops import polylabel
SRC='/home/claude/mapwork/sd-map2.bin'
OUT=sys.argv[1]
B=read(SRC); assert B['magic']=='TFMAP2'
towns=pickle.load(open('towns.pkl','rb')); chains=pickle.load(open('chains.pkl','rb')); dplace=pickle.load(open('dplace.pkl','rb'))
states=pickle.load(open('states.pkl','rb')); old=json.load(open('oldnames.json'))
# ── capitals: the country's (3), states' and union territories' (2) ──
CAP={'New Delhi':3,'Amaravati':2,'Itanagar':2,'Dispur':2,'Patna':2,'Raipur':2,'Panaji':2,'Gandhinagar':2,'Chandigarh':2,'Shimla':2,'Ranchi':2,'Bengaluru':2,'Thiruvananthapuram':2,'Bhopal':2,'Mumbai':2,'Imphal':2,'Shillong':2,'Aizawl':2,'Kohima':2,'Bhubaneswar':2,'Jaipur':2,'Gangtok':2,'Chennai':2,'Hyderabad':2,'Agartala':2,'Lucknow':2,'Dehradun':2,'Kolkata':2,'Sri Vijaya Puram':2,'Daman':2,'Srinagar':2,'Jammu':2,'Leh':2,'Kavaratti':2,'Puducherry':2}
towns=[dict(t) for t in towns]
if not any(t['n']=='Amaravati' for t in towns): towns.append(dict(n='Amaravati',lng=80.515,lat=16.52,pop=1,kind=2,src='cap'))
seen=set()
for t in sorted(towns,key=lambda t:-t['pop']):
    k=CAP.get(t['n'])
    if k and t['n'] not in seen and not (t['n']=='Raipur' and t['lng']<80) and not (t['n']=='Srinagar' and t['lng']>76):
        t['kind']=k; seen.add(t['n'])
print('capitals',len(seen),sorted(set(CAP)-seen))
assert set(CAP)==seen
towns.sort(key=lambda t:(-t['pop'],-t['kind'],t['n']))
# ── layers: the web's, with India's state lines from the 2020s map (Ladakh and J&K apart) and district lines ──
layers=[l for l in B['layers'] if not l[0].startswith('state')]
def lines(ls,tol):
    out=[]
    for l in ls:
        s=LineString(l).simplify(tol,preserve_topology=False) if tol else LineString(l)
        if len(s.coords)<2 or s.length<tol*1.5: continue
        out.append([v for p in s.coords for v in (p[0],p[1])])
    return out
for i,t in enumerate([0.004,0.03,0.07]): layers.append((f'state{i}',t,lines(chains['state'],t)))
for i,t in enumerate([0.006,0.02]): layers.append((f'district{i}',t,lines(chains['district'],t)))
# ── states: names and where they are written (the web's spots; J&K and Ladakh, and the joined DNH & DD, worked out) ──
ST=[]
for n,a,x,y in B['states']:
    if n in ('Dadra & Nagar Haveli','Daman & Diu','Jammu & Kashmir'): continue
    ST.append((n,a,x,y))
def lab(key):
    g=states[key]; g=max(g.geoms,key=lambda p:p.area) if g.geom_type=='MultiPolygon' else g
    p=polylabel(g,0.01); return 481*states[key].area,p.x,p.y
for n,key in (('Jammu & Kashmir','JAMMU AND KASHMIR'),('Ladakh','LADAKH'),('Dadra & Nagar Haveli and Daman & Diu','DADRA & NAGAR HAVELI & DAMAN & DIU')):
    a,x,y=lab(key); ST.append((n,a,x,y)); print(n,round(a),round(x,2),round(y,2))
ST.sort(key=lambda s:s[0])
# ── where each district people can pick sits: its own headquarters, checked against the district's outline ──
SP=dict(((k,(x,y)) for k,x,y in B['statePlaces']))
FIX={('Bengaluru South','Karnataka'):(77.281,12.721),('Mumbai','Maharashtra'):None,('Balodabazar-Bhatapara','Chhattisgarh'):None}
for (d,st),(p,how) in dplace.items():
    if (d,st) in FIX:
        if FIX[(d,st)] is None: p=p if how.startswith('keep') else None
        else: p=FIX[(d,st)]
    if p is None: continue
    SP[f'{norm(d)}|{norm(st)}']=(p[0],p[1])
# the old spellings still find their town (Dehra Dun → Dehradun …)
CO=dict(((k,(x,y)) for k,x,y in B['coords']))
for o,n in old.items():
    t=next((t for t in towns if t['n']==n),None)
    if t and o not in CO: CO[o]=(t['lng'],t['lat'])
ES=dict(((k,(x,y)) for k,x,y in B['extraStates']))
ES.setdefault('dadra and nagar haveli',(73.0,20.27)); ES.setdefault('daman and diu',(72.84,20.41))
# ── write ──
parts=[]
I=lambda v: parts.append(struct.pack('>i',v)); F=lambda v: parts.append(struct.pack('>f',v)); Bt=lambda v: parts.append(struct.pack('>b',v))
def U(s):
    b=bytearray()
    for ch in s:
        c=ord(ch)
        if 1<=c<=0x7f: b.append(c)
        elif c<=0x7ff: b+=bytes([0xc0|(c>>6),0x80|(c&0x3f)])
        else: b+=bytes([0xe0|(c>>12),0x80|((c>>6)&0x3f),0x80|(c&0x3f)])
    parts.append(struct.pack('>H',len(b))+bytes(b))
U('TFMAP3'); I(len(layers))
for n,t,rings in layers:
    F(t); U(n); I(len(rings))
    for r in rings: I(len(r)//2); parts.append(struct.pack('>%df'%len(r),*r))
I(len(towns))
for t in towns: U(t['n']); F(t['lng']); F(t['lat']); I(int(t['pop'])); Bt(t['kind'])
I(len(ST))
for n,a,x,y in ST: U(n); F(a); F(x); F(y)
I(len(B['names']))
for t,x,y,k,z0,z1 in B['names']: U(t); F(x); F(y); U(k); F(z0); F(z1)
def table(m):
    I(len(m))
    for k,(x,y) in m.items(): U(k); F(x); F(y)
table(CO); table(dict(((k,(x,y)) for k,x,y in B['extra'])))
I(len(B['aliases']))
for k,v in B['aliases']:
    U(k); I(len(v))
    for x in v: U(x)
table(SP); table(ES)
buf=b''.join(parts); open(OUT,'wb').write(buf)
print(OUT,len(buf)//1024,'kB · towns',len(towns),collections.Counter(t['kind'] for t in towns),'· states',len(ST),'· state places',len(SP),'· layers',[(n,len(r)) for n,t,r in layers])
