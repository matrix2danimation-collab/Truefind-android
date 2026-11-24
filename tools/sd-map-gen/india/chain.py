import pickle,collections
from shapely.geometry import LineString
S=pickle.load(open('segs.pkl','rb'))
Q=1e4
def chains(segs):
    adj=collections.defaultdict(list)
    for a,b in segs: adj[a].append(b); adj[b].append(a)
    used=set(); out=[]
    def walk(a,b):
        line=[a,b]; used.add((a,b) if a<b else (b,a))
        while len(adj[b])==2:
            n=adj[b][0] if adj[b][0]!=a else adj[b][1]
            k=(b,n) if b<n else (n,b)
            if k in used: break
            used.add(k); line.append(n); a,b=b,n
        return line
    for v in adj:
        if len(adj[v])!=2:
            for w in adj[v]:
                k=(v,w) if v<w else (w,v)
                if k not in used: out.append(walk(v,w))
    for v in adj:   # closed loops (islands of one district inside another)
        for w in adj[v]:
            k=(v,w) if v<w else (w,v)
            if k not in used: out.append(walk(v,w))
    return [[(x/Q,y/Q) for x,y in l] for l in out]
res={}
for k in ('state','district'):
    ls=chains(S[k]); res[k]=ls
    print(k,len(ls),sum(len(l) for l in ls), 'short(<3pts)',sum(1 for l in ls if len(l)<3))
pickle.dump(res,open('chains.pkl','wb'))
for k,tols in (('state',[0.004,0.03,0.07]),('district',[0.006,0.02])):
    for t in tols:
        n=0;r=0
        for l in res[k]:
            s=LineString(l).simplify(t,preserve_topology=False)
            if s.length<t*2: continue
            n+=len(s.coords); r+=1
        print(k,t,r,'rings',n,'pts', round(n*8/1024),'kB')
