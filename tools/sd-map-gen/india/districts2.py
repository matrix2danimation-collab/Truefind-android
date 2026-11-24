import json,sys,math,pickle,re,difflib,collections
sys.path.insert(0,'/home/claude/mapwork/scripts')
from place import norm,placeOf
from shapely.geometry import Point
polys=pickle.load(open('polys.pkl','rb')); states=pickle.load(open('states.pkl','rb')); towns=pickle.load(open('towns.pkl','rb'))
hq=json.load(open('hq.json'))
OLD={'orissa':'odisha','uttaranchal':'uttarakhand','pondicherry':'puducherry','andaman and nicobar':'andaman and nicobar islands'}
HQ=collections.defaultdict(dict)
for s,m in hq.items():
    for d,v in m.items(): HQ[OLD.get(s,s)][d]=v
byst=collections.defaultdict(list)
for d,s,g in polys:
    if d and s: byst[norm(s)].append((d,g))
def skey(s):
    k=norm(s)
    if k.startswith('dadra'): return [x for x in byst if x.startswith('dadra')][0]
    return k
DIR=re.compile(r'\b(north|south|east|west|central|outer|upper|lower|middle|eastern|western|northern|southern|purba|paschim|uttar|dakshin)\b')
dirs=lambda s: sorted(DIR.findall(s))
REN={'ahilyanagar':'ahmednagar','chhatrapati sambhajinagar':'aurangabad','dharashiv':'osmanabad','narmadapuram':'hoshangabad','ayodhya':'faizabad','bagalkote':'bagalkot','ballari':'bellary','belagavi':'belgaum','davanagere':'davangere','kendujhar':'keonjhar','mohali':'sahibzada ajit singh nagar','nawanshahr':'shahid bhagat singh nagar','kumuram bheem asifabad':'komaram bheem','lakhimpur kheri':'kheri','cooch behar':'koch bihar','hooghly':'hugli','howrah':'haora','malda':'maldah','darjeeling':'darjiling','birbhum':'birbhum','kanker':'uttar bastar kanker','dantewada':'dakshin bastar dantewada','kabirdham':'kabeerdham','east champaran':'purbi champaran','west champaran':'pashchim champaran','kutch':'kachchh','kadapa':'y s r','nellore':'sri potti sriramulu nellore','kamrup':'kamrup','sribhumi':'karimganj','raigad':'raigarh','yadgir':'yadgir','hassan':'hassan','kolar':'kolar','haveri':'haveri','dharwad':'dharwad','chamarajanagar':'chamrajnagar','poonch':'punch','wanaparthy':'wanaparthi','alipurduar':'alipurduar','changlang':'changlang','anjaw':'anjaw'}
def inside(g,p,b=0.02): return g.buffer(b).contains(Point(p))
def find_poly(d,st):
    cand=byst.get(skey(st),[]); names={norm(x):(x,g) for x,g in cand}
    k=norm(d); k2=norm(re.sub(r'\(.*?\)','',d))
    ks=[k,k2,REN.get(k2,'')]+[norm(x) for x in re.findall(r'\((.*?)\)',d)]
    for x in ks:
        if x and x in names: return names[x]
    for x in ks:
        if not x: continue
        for m in difflib.get_close_matches(x,list(names),3,0.72):
            if dirs(m)==dirs(x): return names[m]
    for nm in names:   # 'komaram bheem asifabad' ⊂ …
        r=REN.get(k2,k2)
        if r and (r in nm or nm in r) and dirs(nm)==dirs(r) and min(len(r),len(nm))>=5: return names[nm]
    return None
dd=json.load(open('/home/claude/truefind-android/app/src/main/assets/sd-data.json'))['districts']
OUT={};log=[];nopoly=[]
for st,ds in dd.items():
    sg=states.get([k for k in states if norm(k)==skey(st)][0]) if any(norm(k)==skey(st) for k in states) else None
    for d in ds:
        p,how=placeOf(d,st); hp=find_poly(d,st)
        if hp is None:
            ok = sg is not None and inside(sg,p,0.05)
            nopoly.append((d,st,[round(v,3) for v in p],how,'in state' if ok else 'OUTSIDE STATE'))
            OUT[(d,st)]=(p,'keep-nopoly'); continue
        pn,g=hp
        if inside(g,p): OUT[(d,st)]=(p,'keep'); continue
        # the district's own headquarters
        h=HQ.get(skey(st),{}).get(norm(pn)) or HQ.get(skey(st),{}).get(norm(d))
        if h and inside(g,(h[0],h[1])): OUT[(d,st)]=((h[0],h[1]),'datta-hq '+str(h[2])); log.append((d,st,pn,how,'→ hq',h[2],round(h[0],3),round(h[1],3))); continue
        inn=[t for t in towns if inside(g,(t['lng'],t['lat']),0)]
        kk=norm(re.sub(r'\(.*?\)','',d))
        named=[t for t in inn if norm(t['n'])==kk or kk.startswith(norm(t['n'])) and len(norm(t['n']))>3]
        pick=None;why=''
        if named: pick=max(named,key=lambda t:(t['kind'],t['pop'])); why='named'
        elif [t for t in inn if t['kind']>=1]: pick=max([t for t in inn if t['kind']>=1],key=lambda t:t['pop']); why='district-hq town'
        elif inn: pick=max(inn,key=lambda t:t['pop']); why='biggest town'
        if pick: OUT[(d,st)]=((pick['lng'],pick['lat']),why); log.append((d,st,pn,how,'→',why,pick['n'],round(pick['lng'],3),round(pick['lat'],3),'pop',pick['pop'],[t['n'] for t in inn if t['kind']>=1][:4]))
        else:
            rp=g.representative_point().coords[0]; OUT[(d,st)]=(rp,'middle'); log.append((d,st,pn,how,'→ middle',rp))
print('nopoly',len(nopoly)); [print(x) for x in nopoly]
print('changed',len(log)); [print(x) for x in log]
pickle.dump(OUT,open('dplace.pkl','wb'))
