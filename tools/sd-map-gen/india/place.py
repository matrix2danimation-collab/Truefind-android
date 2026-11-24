import math,re,unicodedata,json,sys
sys.path.insert(0,'/home/claude/mapwork/scripts')
from bin import read
import os; D=read(os.environ.get('SDBIN','/home/claude/mapwork/sd-map2.bin'))
def norm(t):
    t=unicodedata.normalize('NFKD',(t or '').lower()); t=re.sub('[̀-ͯ]','',t).replace('&',' and '); return re.sub('[^a-z0-9]+',' ',t).strip()
COORDS={k:(x,y) for k,x,y in D['coords']}; EXTRA={k:(x,y) for k,x,y in D['extra']}; AL=dict(D['aliases']); SP={k:(x,y) for k,x,y in D['statePlaces']}; ES={k:(x,y) for k,x,y in D['extraStates']}
TOWNS={}
for c in D['cities']: TOWNS.setdefault(norm(c[0]),[]).append(c)
ST={norm(s[0]):((s[2],s[3]),max(3,2.2*math.sqrt(s[1]/481/math.pi))) for s in D['states']}
def stateOf(s):
    k=norm(s); 
    if k in ST: return ST[k]
    if k in ES: return (ES[k],3)
    return None
apart=lambda a,c: math.hypot((a[0]-c[0])*math.cos((a[1]+c[1])/2*math.pi/180),a[1]-c[1])
DIRS=re.compile(r'\b(north|south|east|west|central|outer|upper|lower|middle|eastern|western|northern|southern|purba|paschim|uttar|dakshin)\b')
def placeOf(city,state):
    S=stateOf(state); mid=S[0] if S else None; rad=S[1] if S else 9
    out=SP.get(norm(city)+'|'+norm(state)); how='sp' if out else None
    raw=(city or '').strip()
    if not out and raw:
        n0=[raw]+[x.strip() for x in re.split(r'[()/,]|\s-\s|-',raw) if x.strip()]
        more=[]
        for n in n0: more+=AL.get(norm(n),[])
        for nm in n0+more+[re.sub(r'\s+',' ',DIRS.sub(' ',norm(raw))).strip()]:
            k=norm(nm)
            if not k: continue
            if k in EXTRA: out=EXTRA[k]; how='extra:'+k; break
            c=COORDS.get(k)
            if c and (not mid or apart(c,mid)<rad): out=c; how='coords:'+k; break
            ts=TOWNS.get(k)
            if ts:
                best=min(ts,key=lambda t: apart((t[1],t[2]),mid)) if mid else ts[0]
                if not mid or apart((best[1],best[2]),mid)<rad: out=(best[1],best[2]); how='town:'+best[0]; break
    return (out or mid), (how or ('MID' if mid else 'NONE'))
if __name__=='__main__':
    dd=json.load(open('/home/claude/truefind-android/app/src/main/assets/sd-data.json'))['districts']
    n=0;mids=[]
    for st,ds in dd.items():
        for d in ds:
            n+=1; p,how=placeOf(d,st)
            if how in ('MID','NONE'): mids.append((d,st))
    print(n,'districts;',len(mids),'fall to state middle'); print(mids[:80])
