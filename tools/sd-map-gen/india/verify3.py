import os,sys,json,pickle,math
os.environ['SDBIN']=sys.argv[1]
sys.path.insert(0,'/home/claude/mapwork/scripts')
import place
from place import placeOf,norm
from shapely.geometry import Point
dplace=pickle.load(open('/home/claude/mapwork/dplace.pkl','rb'))
assert place.D['rest']==b'', 'bytes left over'
dd=json.load(open('/home/claude/truefind-android/app/src/main/assets/sd-data.json'))['districts']
km=lambda a,b: 111.2*math.hypot((a[0]-b[0])*math.cos(math.radians((a[1]+b[1])/2)),a[1]-b[1])
n=0;moved=[];none=0
old=__import__('subprocess')
for st,ds in dd.items():
    p,how=placeOf('',st)
    if p is None: none+=1; print('state has no place',st)
    for d in ds:
        n+=1; p,how=placeOf(d,st)
        if p is None: none+=1; print('NONE',d,st); continue
        e=dplace[(d,st)][0]
        if km(p,e)>0.5 and not (d,st) in [('Mumbai','Maharashtra'),('Balodabazar-Bhatapara','Chhattisgarh'),('Bengaluru South','Karnataka')]: moved.append((d,st,p,e,how))
print(n,'districts; null',none,'; differ from the checked place',len(moved)); print(moved[:20])
for c,s in [('Delhi',''),('Rohtak','haryana'),('Gautam Buddh Nagar (Noida)','Uttar Pradesh'),('Mau','Uttar Pradesh'),('Dehra Dun','Uttarakhand'),('Port Blair','Andaman and Nicobar Islands'),('Leh','Ladakh'),('Daman','Daman and Diu'),('Gurgaon','Haryana'),('Nowhere','Nowhere')]: print(c,s,placeOf(c,s))
