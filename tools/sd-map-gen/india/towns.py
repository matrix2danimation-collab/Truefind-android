import json,glob,sys,math,pickle,re,collections
sys.path.insert(0,'/home/claude/mapwork/scripts')
from place import norm
from shapely.geometry import Point
from shapely.ops import unary_union
from shapely import prepared, STRtree
from bin import read
B=read('/home/claude/truefind-android/app/src/main/assets/sd-map.bin')
st=pickle.load(open('states.pkl','rb'))
india=unary_union(list(st.values())).buffer(0.03); ip=prepared.prep(india)
km=lambda a,b: 111.2*math.hypot((a[0]-b[0])*math.cos(math.radians((a[1]+b[1])/2)),a[1]-b[1])
T=[]  # dict n,lng,lat,pop(thousands),kind,src
for n,x,y,p in B['cities']: T.append(dict(n=n,lng=x,lat=y,pop=p,kind=0,src='web'))
for c in json.load(open('in-cities.json')):
    if c['name'] and ip.contains(Point(c['lon'],c['lat'])): T.append(dict(n=c['name'],lng=c['lon'],lat=c['lat'],pop=max(1,round(c.get('pop',0)/1000)),kind=1 if c.get('fc') in ('PPLA2',) else 0,src='gn',fc=c.get('fc')))
def title(s):
    s=s.strip()
    return s.title() if s.isupper() or s.islower() else s
for f in glob.glob('/home/claude/mapwork/dl-geo/datta-repo/STATES/*/*Hq.geojson'):
    sub='Sub District' in f
    for x in json.load(open(f))['features']:
        p={k.upper():v for k,v in x['properties'].items()}
        g=x.get('geometry'); c=g['coordinates'] if g else (float(p['LONGITUDE']),float(p['LATITUDE']))
        nm=(p.get('LOC_NAME') or p.get('NAME11')) if sub else (p.get('DIST_HQ') or p.get('DIST_HQNM'))
        if not nm or not ip.contains(Point(c[0],c[1])): continue
        T.append(dict(n=title(nm),lng=c[0],lat=c[1],pop=1,kind=0 if sub else 1,src='sub' if sub else 'dhq'))
print(collections.Counter(t['src'] for t in T))
RENAME={'Dehra Dun':'Dehradun','Bhubaneshwar':'Bhubaneswar','Gurgaon':'Gurugram','Allahabad':'Prayagraj','Hoshangabad':'Narmadapuram','Osmanabad':'Dharashiv','Ahmadnagar':'Ahilyanagar','Ahmednagar':'Ahilyanagar','Port Blair':'Sri Vijaya Puram','Mysore':'Mysuru','Calcutta':'Kolkata','Bombay':'Mumbai','Madras':'Chennai','Trivandrum':'Thiruvananthapuram','Pondicherry':'Puducherry','Gauhati':'Guwahati','Baroda':'Vadodara','Poona':'Pune','Simla':'Shimla','Mangalore':'Mangaluru','Belgaum':'Belagavi','Bellary':'Ballari','Gulbarga':'Kalaburagi','Bijapur (Karnataka)':'Vijayapura','Shimoga':'Shivamogga','Tumkur':'Tumakuru','Hubli':'Hubballi','Faizabad':'Ayodhya','Mughal Sarai':'Pt. Deen Dayal Upadhyaya Nagar','Cochin':'Kochi','Calicut':'Kozhikode','Trichur':'Thrissur','Quilon':'Kollam','Alleppey':'Alappuzha','Tuticorin':'Thoothukudi','Trichy':'Tiruchirappalli','Benares':'Varanasi','Kanpur Nagar':'Kanpur','Bhopal':'Bhopal'}
OLDNAMES={}
for t in T:
    nn=RENAME.get(t['n'])
    if nn and nn!=t['n']: OLDNAMES[norm(t['n'])]=nn; t['n']=nn
def clean(n):
    n=re.sub(r'\(.*?\)','',n); n=re.sub(r'\s-\s*[IVX]+\b','',n); n=re.sub(r'\b(sadar|taluk|tehsil|tahsil|block|mandal|circle|subdivision|sub-division|urban|rural|m\.?\s?corp\.?|m\.?\s?cl\.?|cd block)\b\.?','',n,flags=re.I)
    n=n.replace('*','').replace('#','')
    n=re.sub(r'[\s\-]+$','',re.sub(r'\s+',' ',n)).strip(' -,.')
    return n
for t in T:
    if t['src'] in ('sub','dhq'): t['n']=clean(t['n'])
T=[t for t in T if len(t['n'])>=2]
# merge: same normalised name within 12 km → one town (web's name, the most precise place, the biggest pop, the highest kind)
prio={'web':0,'gn':1,'dhq':2,'sub':3}
T.sort(key=lambda t:(prio[t['src']],-t['pop']))
by=collections.defaultdict(list); out=[]; grid={}
import difflib
def grid_near(t):
    gx,gy=round(t['lng']*4),round(t['lat']*4)
    for i in (-1,0,1):
        for j in (-1,0,1): yield from grid.get((gx+i,gy+j),[])
def key(n):
    k=norm(n); k=re.sub(r'\b(city|town|nagar panchayat|np|m corp|m cl|cb|ct)\b','',k); return re.sub(r'\s+',' ',k).strip()
for t in T:
    k=key(t['n']); hit=None
    for o in by[k]:
        if km((o['lng'],o['lat']),(t['lng'],t['lat']))<12: hit=o; break
    if not hit and t['src'] in ('sub','dhq','gn'):
        # the same place spelt another way (Dehra Dun / Dehradun), or a headquarters right inside a town already there
        ks=k.replace(' ','')
        for o in grid_near(t):
            d=km((o['lng'],o['lat']),(t['lng'],t['lat']))
            if d<15 and difflib.SequenceMatcher(None,key(o['n']).replace(' ',''),ks).ratio()>=0.8: hit=o; break
            if t['src']!='gn' and d<3.5 and o['pop']>=20: hit=o; break
    if hit:
        hit['pop']=max(hit['pop'],t['pop']); hit['kind']=max(hit['kind'],t['kind'])
        if t['src']=='gn' and hit['src']=='web': hit['lng'],hit['lat']=t['lng'],t['lat']  # GeoNames' 5 decimals beat the web's 2
        hit.setdefault('also',[]).append(t['src'])
        continue
    t=dict(t); by[k].append(t); out.append(t); grid.setdefault((round(t['lng']*4),round(t['lat']*4)),[]).append(t)
print('towns',len(out),collections.Counter(t['src'] for t in out))
pickle.dump(out,open('towns.pkl','wb')); json.dump(OLDNAMES,open('oldnames.json','w'),indent=0)
print(OLDNAMES)
