import re,sys,glob
ST=re.compile(r"state: (\w+), view (\w+)[^,]*, fusion ([^,]*),.*ARCore features (-?\d+)")
def tsec(ln):
    m=re.match(r"\s*([\d.]+)s\s",ln)
    if m: return float(m.group(1))
    m=re.search(r"(\d\d):(\d\d):(\d\d)\.(\d+) I/RoomScape",ln)
    if m: return int(m.group(1))*3600+int(m.group(2))*60+int(m.group(3))+float("0."+m.group(4))
    return None
def streams(path):
    cur=[];last=-1
    for ln in open(path,errors='ignore'):
        if 'RoomScape' not in ln and not re.match(r"\s*[\d.]+s\s",ln): continue
        t=tsec(ln)
        if t is None: continue
        if t<last-5 or 'session created' in ln:
            if cur: yield cur
            cur=[]
        last=t
        m=ST.search(ln)
        if m: cur.append(('s',t,m.group(1)=='TRACKING' and m.group(2)=='live' and m.group(3)=='on',int(m.group(4))))
        elif 'tracking lost mid-scan' in ln: cur.append(('L',t))
    if cur: yield cur
rules={}
for thr in (5,10,15,20):
    for k in (2,3,4):
        rules['<%d for %ds'%(thr,k)]=(lambda thr,k: lambda h: len(h)>=k and all(x<thr for x in h[-k:]))(thr,k)
rules['<10 for 2s after >=60 within 8s']=lambda h: len(h)>=3 and h[-1]<10 and h[-2]<10 and max(h[-10:])>=60
rules['<15 for 3s after >=60 within 10s']=lambda h: len(h)>=4 and all(x<15 for x in h[-3:]) and max(h[-12:])>=60
files=sys.argv[1:]
data=[]
for f in files:
    for s in streams(f):
        if sum(1 for e in s if e[0]=='s' and e[3]>=0)>20: data.append(s)
print(len(data),"streams,",sum(sum(1 for e in s if e[0]=='L') for s in data),"losses,",sum(sum(1 for e in s if e[0]=='s') for s in data)//60,"min")
# features before losses
for name,r in rules.items():
    warns=[];losses=[];tot=0
    for s in data:
        h=[];lastw=-1e9
        L=[e[1] for e in s if e[0]=='L']; losses+=[(id(s),x) for x in L]
        for e in s:
            if e[0]!='s': continue
            if not e[2] or e[3]<0: h=[]; continue
            tot+=1; h.append(e[3])
            if r(h) and e[1]-lastw>10: warns.append((id(s),e[1],L)); lastw=e[1]
    hits=[w for w in warns if any(w[1]+1<=x<=w[1]+20 for x in w[2])]
    caught=[l for l in losses if any(w[0]==l[0] and l[1]-20<=w[1]<=l[1]-1 for w in warns)]
    leads=[min(x-w[1] for x in w[2] if w[1]+1<=x<=w[1]+20) for w in hits]
    leads.sort()
    print("%-36s warnings %3d, %3d before a loss, %3d false (%.1f/min); losses caught %d/%d; lead median %s s"%(name,len(warns),len(hits),len(warns)-len(hits),(len(warns)-len(hits))/(tot/60),len(caught),len(losses),"%.0f"%leads[len(leads)//2] if leads else "-"))
