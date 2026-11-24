import sys,json
b=open(sys.argv[1],'rb').read();p=0;n=len(b)
def varint(buf,i):
    r=0;s=0
    while True:
        c=buf[i];i+=1;r|=(c&0x7f)<<s;s+=7
        if c<0x80: return r,i
def zz(v): return (v>>1)^-(v&1)
out=[];lon=0;lat=0
while p<n:
    ln,p=varint(b,p); m=b[p:p+ln]; p+=ln
    c={};i=0
    while i<len(m):
        k,i=varint(m,i); tag=k>>3; wt=k&7
        if wt==2:
            l,i=varint(m,i); v=m[i:i+l].decode('utf8','replace'); i+=l
            c[{2:'name',3:'country',4:'alt',5:'muni',6:'muniSub',7:'fc',8:'admin'}.get(tag,str(tag))]=v
        else:
            v,i=varint(m,i)
            if tag==1: c['id']=zz(v)
            elif tag==9: c['pop']=v
            elif tag==10: lon+=zz(v); c['lon']=lon/1e5
            elif tag==11: lat+=zz(v); c['lat']=lat/1e5
    if 'lon' not in c: c['lon']=lon/1e5
    if 'lat' not in c: c['lat']=lat/1e5
    if c.get('country')=='IN': out.append(c)
json.dump(out,open(sys.argv[2],'w'),ensure_ascii=False)
print(len(out))
import collections
print(collections.Counter(x.get('fc') for x in out))
print(collections.Counter('100k' if x.get('pop',0)>=100000 else '20k' if x.get('pop',0)>=20000 else '5k' if x.get('pop',0)>=5000 else '1k' for x in out))
print([ (x['name'],x['lon'],x['lat'],x.get('pop'),x.get('admin'),x.get('fc')) for x in out if x['name'] in ('Mumbai','Shimla','Leh','Kohima','Gangtok')])
