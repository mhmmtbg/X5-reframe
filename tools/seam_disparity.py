"""Dinamik dikiş modeli doğrulaması.

Dikiş düzlemi (rig z=0) boyunca 48 bölmede, her bölge için iki lensin birbirine doğru/uzağa
ne kadar kaydırılması gerektiğini (açısal kayma, derece) bulur. Model:
  lens i yönü = R_i^T * normalize(d + s_i * tan(delta/2) * w * ez),  s0=+1, s1=-1
w: dikişe yakınlık ağırlığı. Uygulamadaki GPU tahmincisiyle aynı maliyet (ortalaması çıkarılmış
mutlak fark) kullanılır. Önce/sonra dikiş uyumu (korelasyon) raporlanır.
Kullanım: python seam_disparity.py clip.insv out_dir
"""
import json, os, sys, subprocess
import numpy as np
from PIL import Image
sys.path.insert(0, os.path.dirname(__file__))
from insv_imu import read_trailer, proto  # noqa
from seam_analyze import lenses, project, sample, frame  # noqa

BINS = 48
DELTAS = np.radians(np.linspace(-2.0, 6.0, 33))


def dirs_dir(phi, off):
    """Dikiş düzleminde phi açısı, düzlemden off (rad) uzaklıkta birim yön (rig)."""
    return np.stack([np.cos(off) * np.cos(phi), np.cos(off) * np.sin(phi), np.sin(off)], -1)


def lens_dir(d, i, delta, w):
    s = 1 if i == 0 else -1
    v = d.copy()
    v[..., 2] += s * np.tan(delta / 2) * w
    return v / np.linalg.norm(v, axis=-1, keepdims=True)


def main(clip, out):
    os.makedirs(out, exist_ok=True)
    d = read_trailer(clip); meta = d['meta']
    c = proto(meta[27]) if 27 in meta else {}
    crop = [c.get(i, 0) for i in range(1, 7)] if c else None
    Ls = lenses(d['offset_v3'], crop if crop and all(crop[:4]) else None)
    rev = meta.get(80, 0) == 1
    dur = float(json.loads(subprocess.run(['ffprobe', '-v', 'error', '-show_format', '-of', 'json', clip], capture_output=True).stdout)['format']['duration'])
    size = 1440
    # Hangi eksen dikiş normali? Lens eksenlerinin rig'deki yönü:
    ax = [(L['R'] @ np.array([0, 0, 1.0])).round(3).tolist() for L in Ls]
    report = dict(lens_axes_rig=ax, frames=[])
    # Pencere: bölme merkezinden ±3.75° boyunca (dikiş yönünde), ±4° dikişe dik
    along = np.radians(np.linspace(-3.5, 3.5, 8))
    across = np.radians(np.linspace(-4, 4, 9))
    for f in (0.2, 0.5, 0.8):
        a = frame(clip, 0, dur * f, size); b = frame(clip, 1, dur * f, size)
        imgs = {0: b, 1: a} if rev else {0: a, 1: b}
        best_d = np.zeros(BINS); before = []; after = []; conf = np.zeros(BINS)
        for k in range(BINS):
            phi0 = (k + 0.5) / BINS * 2 * np.pi - np.pi
            P, O = np.meshgrid(phi0 + along, across)
            base = dirs_dir(P, O)
            costs = []
            for dl in DELTAS:
                ps = []
                for i in (0, 1):
                    v = lens_dir(base, i, dl, 1.0)
                    u, vv, _ = project(Ls[i], v @ Ls[i]['R'])
                    ps.append(sample(imgs[i], u, vv).mean(-1))
                p0 = ps[0] - ps[0].mean(); p1 = ps[1] - ps[1].mean()
                costs.append(float(np.abs(p0 - p1).mean()))
                if dl == DELTAS[8]:  # delta = 0
                    tex = float(ps[0].std())
                    c0 = float((p0 * p1).mean() / (p0.std() * p1.std() + 1e-6))
            costs = np.array(costs)
            j = int(np.argmin(costs))
            best_d[k] = np.degrees(DELTAS[j])
            conf[k] = (np.median(costs) - costs[j]) / (np.median(costs) + 1e-6)
            if tex > 6:
                ps = []
                for i in (0, 1):
                    v = lens_dir(base, i, DELTAS[j], 1.0)
                    u, vv, _ = project(Ls[i], v @ Ls[i]['R'])
                    ps.append(sample(imgs[i], u, vv).mean(-1))
                p0 = ps[0] - ps[0].mean(); p1 = ps[1] - ps[1].mean()
                before.append(c0); after.append(float((p0 * p1).mean() / (p0.std() * p1.std() + 1e-6)))
        report['frames'].append(dict(t=dur * f, delta_deg=best_d.round(2).tolist(), conf=conf.round(2).tolist(),
                                     corr_before=float(np.mean(before)), corr_after=float(np.mean(after)), textured=len(before)))
        print(f"t={dur*f:.1f} corr {np.mean(before):.3f} -> {np.mean(after):.3f} ({len(before)} bölge)")
        print("  delta:", best_d.round(1).tolist())
    json.dump(report, open(os.path.join(out, 'disparity.json'), 'w'), indent=1)
    print("lens axes rig:", ax)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
