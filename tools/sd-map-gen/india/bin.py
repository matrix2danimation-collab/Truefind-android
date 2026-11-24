import struct
class R:
    def __init__(s,path): s.b=open(path,'rb').read(); s.p=0
    def i32(s): v=struct.unpack_from('>i',s.b,s.p)[0]; s.p+=4; return v
    def f32(s): v=struct.unpack_from('>f',s.b,s.p)[0]; s.p+=4; return v
    def utf(s): n=struct.unpack_from('>H',s.b,s.p)[0]; s.p+=2; v=s.b[s.p:s.p+n].decode('utf8','replace'); s.p+=n; return v
def read(path):
    r=R(path); D={}
    D['magic']=r.utf(); L=[]
    for _ in range(r.i32()):
        tol=r.f32(); name=r.utf(); rings=[]
        for _ in range(r.i32()):
            m=r.i32(); rings.append(list(struct.unpack_from('>%df'%(2*m),r.b,r.p))); r.p+=8*m
        L.append((name,tol,rings))
    D['layers']=L
    v3=D['magic']=='TFMAP3'
    def city():
        c=(r.utf(),r.f32(),r.f32(),r.i32())
        if v3: r.p+=1
        return c
    D['cities']=[city() for _ in range(r.i32())]
    D['states']=[(r.utf(),r.f32(),r.f32(),r.f32()) for _ in range(r.i32())]
    D['names']=[(r.utf(),r.f32(),r.f32(),r.utf(),r.f32(),r.f32()) for _ in range(r.i32())]
    def table(): return [(r.utf(),r.f32(),r.f32()) for _ in range(r.i32())]
    D['coords']=table(); D['extra']=table()
    D['aliases']=[]
    for _ in range(r.i32()):
        k=r.utf(); D['aliases'].append((k,[r.utf() for _ in range(r.i32())]))
    D['statePlaces']=table(); D['extraStates']=table()
    D['rest']=r.b[r.p:]
    return D
