# SPDX-License-Identifier: GPL-3.0-or-later
import sys, numpy as np
def rd(p):
    f=open(p,'rb'); t=f.readline().strip(); w,h=map(int,f.readline().split()); s=float(f.readline())
    a=np.frombuffer(f.read(),dtype='<f4' if s<0 else '>f4').reshape(h,w,3 if t==b'PF' else 1); return a
a=rd(sys.argv[1]); b=rd(sys.argv[2]); d=np.abs(a.astype(np.float64)-b)
mse=(d**2).mean(); psnr=float('inf') if mse==0 else 10*np.log10(1.0/mse)
print(f"max {d.max():.3e} mean {d.mean():.3e} psnr {psnr:.1f} px>1/255 {100*(d.max(axis=2)>1/255).mean():.3f}%")
