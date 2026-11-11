import re,sys,math
ATT=re.compile(r"re-align attempt \(([^)]*)\): (\d+)% on the scan, median ([\d.]+) cm, correction (-?[\d.]+), (-?[\d.]+), (-?[\d.]+) m (-?\d+) deg(, ambiguous)?.*-> (.*)$")
HELD=re.compile(r"pause ([\d.]+) s: ARCore back ([\d.]+) m and ([\d.]+) deg from the held view: quiet re-align")
BACK=re.compile(r"back on track after ([\d.]+) s: correction (-?[\d.]+), (-?[\d.]+), (-?[\d.]+) m, (-?[\d.]+) deg")
def away(a,b):
    t=abs(a[3]-b[3])%360; t=360-t if t>180 else t
    return math.hypot(a[0]-b[0],a[2]-b[2])+t/30
for path in sys.argv[1:]:
    fix=(0,0,0,0); held=None; prev=None; run=0; cands=[]; seen=set()
    lines=open(path,errors='ignore').read().splitlines()
    print("==",path.split('/')[-3] if 'scan-log' in path else path.split('/')[-1])
    for ln in lines:
        if 'RoomScape' not in ln and 'scan-log' not in path and 'diagnostics' not in path: continue
        if 'tracking null -> PAUSED' in ln or 'session created' in ln: fix=(0,0,0,0); held=None
        if 'restarting AR session' in ln: held=None
        if 'tracking lost mid-scan' in ln: held=None; prev=None; run=0
        m=HELD.search(ln)
        if m: held=(float(m.group(2)),float(m.group(3))); prev=None; run=0; print("  held: ARCore back %.2f m %.1f deg"%held)
        m=BACK.search(ln)
        if m:
            nf=(float(m.group(2)),float(m.group(3)),float(m.group(4)),float(m.group(5)))
            for c in cands: print("    -> resumed at %.2f,%.2f m %.0f deg: candidate %s %s"%(nf[0],nf[2],nf[3],c[0],"SAME as the resume" if away(c[1],nf)<0.2 else "differs (%.2f)"%away(c[1],nf)))
            cands=[]; fix=nf; held=None; prev=None; run=0
        m=ATT.search(ln)
        if m and held and 'implausible' in m.group(9) and not m.group(8):
            F=(float(m.group(4)),float(m.group(5)),float(m.group(6)),float(m.group(7)))
            side=math.hypot(F[0]-fix[0],F[2]-fix[2]); dy=F[3]-fix[3]
            px=abs(side-held[0])+abs(abs(dy)-held[1])/30
            if px<0.35:
                run = run+1 if prev and away(F,prev)<0.08 else 1
                prev=F
                tag=("WOULD RESUME (2 agreeing)" if run>=2 else "1st") if abs(dy)>10 or True else ""
                key=ln[-160:]
                if key in seen: continue
                seen.add(key)
                print("  accept?  %s%% %scm  corr %.2f,%.2f m %+.0f deg (vs fix: %.2f m %+.0f deg; proxy %.2f) %s"%(m.group(2),m.group(3),F[0],F[2],F[3],side,dy,px,tag))
                if run>=2: cands.append((ln[:24].strip(),F))
            else: run=0; prev=None
    for c in cands: print("    -> never resumed after candidate", c[0])
