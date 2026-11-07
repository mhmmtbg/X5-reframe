"""Lens 1'in göreli dönüşüne küçük bir düzeltme (3 eksen) arar: dikiş boyunca iki lensin
görüntü uyumunu (normalize çapraz korelasyon) en üst düzeye çıkarır. Birden çok kare kullanır.
Kullanım: python seam_fit.py clip.insv out.json
"""
import json, os, sys
import numpy as np
from scipy.optimize import minimize
sys.path.insert(0, os.path.dirname(__file__))
from insv_imu import read_trailer, proto  # noqa
from seam_analyze import lenses, project, sample, frame  # noqa
from imu_calibrate import rx, ry, rz  # noqa


def main(clip, out):
    d = read_trailer(clip); meta = d['meta']
    c = proto(meta[27]) if 27 in meta else {}
    crop = [c.get(i, 0) for i in range(1, 7)] if c else None
    Ls = lenses(d['offset_v3'], crop if crop and all(crop[:4]) else None)
    rev = meta.get(80, 0) == 1
    import subprocess
    dur = float(json.loads(subprocess.run(['ffprobe', '-v', 'error', '-show_format', '-of', 'json', clip], capture_output=True).stdout)['format']['duration'])
    size = 1440
    frames = []
    for f in (0.15, 0.35, 0.55, 0.75, 0.9):
        a = frame(clip, 0, dur * f, size); b = frame(clip, 1, dur * f, size)
        frames.append({0: b, 1: a} if rev else {0: a, 1: b})
    # Dikiş boyunca örnek pencereler (8x8 deg, 0.25 deg adım)
    g = np.radians(np.arange(-3, 3.01, 0.25))
    wins = []
    for side in (1, -1):
        for latd in range(-60, 61, 6):
            la = np.radians(latd); lo = np.radians(side * 90)
            GL, GA = np.meshgrid(lo + g, la + g)
            wins.append(np.stack([np.cos(GA) * np.sin(GL), np.sin(GA), np.cos(GA) * np.cos(GL)], -1))
    W = np.stack(wins)  # (n, h, w, 3)
    # Lens 0 örnekleri sabit
    p0 = []
    for fr in frames:
        u, v, _ = project(Ls[0], W @ Ls[0]['R']); p0.append(sample(fr[0], u, v).mean(-1))
    # Dokusuz pencereleri ele
    keep = [[float(p.std()) > 6 for p in pp] for pp in p0]

    R1 = Ls[1]['R'].copy()

    def cost(x):
        D = rx(np.radians(x[0])) @ ry(np.radians(x[1])) @ rz(np.radians(x[2]))
        L1 = dict(Ls[1]); L1['R'] = D @ R1
        tot = 0; n = 0
        for fi, fr in enumerate(frames):
            u, v, _ = project(L1, W @ L1['R']); p1 = sample(fr[1], u, v).mean(-1)
            for k in range(len(W)):
                if not keep[fi][k]:
                    continue
                a = p0[fi][k]; b = p1[k]
                a = (a - a.mean()) / (a.std() + 1e-6); b = (b - b.mean()) / (b.std() + 1e-6)
                tot += float((a * b).mean()); n += 1
        return -tot / max(n, 1)

    c0 = cost([0, 0, 0])
    best = None
    for start in ([0, 0, 0], [1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, 0, 1], [0, -1, 0], [0, 0, -1]):
        r = minimize(cost, start, method='Nelder-Mead', options=dict(xatol=0.02, fatol=1e-4, maxiter=300))
        if best is None or r.fun < best.fun:
            best = r
    res = dict(corr_before=-c0, corr_after=-float(best.fun), delta_deg_xyz=[round(float(v), 3) for v in best.x],
               offset_v3_ypr=[[float(x) for x in d['offset_v3'].split('_')[1 + i * 19 + 5:1 + i * 19 + 8]] for i in range(2)],
               windows_used=int(sum(sum(k) for k in keep)))
    json.dump(res, open(out, 'w'), indent=1)
    print(json.dumps(res, indent=1))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
