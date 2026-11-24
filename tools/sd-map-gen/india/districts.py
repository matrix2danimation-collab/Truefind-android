import json,sys,math,pickle,re,difflib,collections
sys.path.insert(0,'/home/claude/mapwork/scripts')
from place import norm,placeOf
from shapely.geometry import Point
polys=pickle.load(open('polys.pkl','rb'))
byst=collections.defaultdict(list)
for d,s,g in polys:
    if d and s: byst[norm(s)].append((d,g))
SM={'dadra and nagar haveli and daman and diu':'dadra and nagar haveli and daman and diu'}
def dstate(s):
    k=norm(s)
    if k.startswith('dadra'): return [x for x in byst if x.startswith('dadra')][0]
    return k
REN={'ahilyanagar':'ahmednagar','chhatrapati sambhajinagar':'aurangabad','dharashiv':'osmanabad','narmadapuram':'hoshangabad','ayodhya':'faizabad','prayagraj':'allahabad','kushavati':None}
dd=json.load(open('/home/claude/truefind-android/app/src/main/assets/sd-data.json'))['districts']
res=[];miss=[]
for st,ds in dd.items():
    cand=byst.get(dstate(st),[])
    names={norm(d):(d,g) for d,g in cand}
    for d in ds:
        k=norm(d); k2=norm(re.sub(r'\(.*?\)','',d)); ks=[k,k2,REN.get(k2) or '']+[norm(x) for x in re.findall(r'\((.*?)\)',d)]
        hit=None
        for x in ks:
            if x and x in names: hit=names[x]; break
        if not hit:
            m=difflib.get_close_matches(k2,list(names),1,0.8)
            if m: hit=names[m[0]]
        p,how=placeOf(d,st)
        res.append((d,st,hit[0] if hit else None,p,how,hit[1] if hit else None))
        if not hit: miss.append((d,st))
print(len(res),'matched',len(res)-len(miss),'missing',len(miss)); print(miss)
bad=[]
for d,st,pd,p,how,g in res:
    if g is None: continue
    if not g.buffer(0.01).contains(Point(p)): bad.append((d,st,pd,[round(v,3) for v in p],how, round(g.distance(Point(p))*111)))
print('outside own district:',len(bad))
for b in sorted(bad,key=lambda x:-x[-1]): print(b)
pickle.dump(res,open('dres.pkl','wb'))
