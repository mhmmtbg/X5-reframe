"""Dikiş kalitesi analizi: iki lensin örtüşme bandında renk farkı ve hizalama hatası.

Uygulamadaki shader'ın aynısını numpy ile uygular; şu değişkenleri dener:
  - dikiş mesafesi (sonsuz, 5 .. 0,5 m) ve öteleme işareti
  - lensler arası renk/pozlama eşitleme (kanal başına kazanç)
  - geçiş bandı genişliği
Çıktı: out/seam.json (sayılar) ve out/seam_*.jpg (yalnızca dikiş çevresindeki dar şeritler).
Kullanım: python seam_analyze.py clip.insv out/
"""
import json
import os
import subprocess
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from insv_imu import read_trailer, proto  # noqa: E402
from imu_calibrate import rx, ry, rz  # noqa: E402


def lenses(v3, crop):
    t = [float(x) for x in v3.split('_')]
    n = int(t[0])

    def vend(ypr, i):
        m = ry(np.radians(ypr[1]) + np.pi / 2) @ rz(np.radians(ypr[0])) @ rx(np.radians(ypr[2]))
        return m @ (rz(np.pi) if (i == 0 and n == 2) else np.eye(3))

    base = vend([0, 0, 90], 0)
    out = []
    for i in range(n):
        b = 1 + i * 19
        xi, fx, fy, cx, cy = t[b:b + 5]
        ypr = t[b + 5:b + 8]; tr = np.array(t[b + 8:b + 11]); k = t[b + 11:b + 16]
        fw = t[b + 16] / n; fh = t[b + 17]
        cx -= fw * i
        calib = fw
        if crop:
            src_w, src_h, dst_w, dst_h, ox, oy = crop
            s = src_w / fw
            cx = s * (cx + 0.5) - 0.5 - (src_w - dst_w) * 0.5 - ox
            cy = s * (cy + 0.5) - 0.5 - (src_h - dst_h) * 0.5 - oy
            fx *= s; fy *= s; calib = dst_w
        norm = calib - 1.0
        out.append(dict(xi=xi, fx=fx / norm, fy=fy / norm, cx=cx / norm, cy=cy / norm, k=k,
                        R=base @ vend(ypr, i).T, T=base @ tr))
    return out


def project(L, c):
    z = c[..., 2]
    n = np.linalg.norm(c, axis=-1)
    c = c / n[..., None]
    z = c[..., 2]
    x = c[..., 0] / (z + L['xi']); y = c[..., 1] / (z + L['xi'])
    k1, k2, k3, p1, p2 = L['k']
    r2 = x * x + y * y
    rad = 1 + k1 * r2 + k2 * r2 ** 2 + k3 * r2 ** 3
    xd = x * rad + 2 * p1 * x * y + p2 * (r2 + 2 * x * x)
    yd = y * rad + 2 * p2 * x * y + p1 * (r2 + 2 * y * y)
    return L['fx'] * xd + L['cx'], L['fy'] * yd + L['cy'], z


def sample(img, u, v):
    h, w = img.shape[:2]
    x = np.clip(u * (w - 1), 0, w - 1); y = np.clip(v * (h - 1), 0, h - 1)
    x0 = np.floor(x).astype(int); y0 = np.floor(y).astype(int)
    x1 = np.minimum(x0 + 1, w - 1); y1 = np.minimum(y0 + 1, h - 1)
    fx = (x - x0)[..., None]; fy = (y - y0)[..., None]
    a = img[y0, x0] * (1 - fx) + img[y0, x1] * fx
    b = img[y1, x0] * (1 - fx) + img[y1, x1] * fx
    return a * (1 - fy) + b * fy


def frame(path, track, sec, size):
    cmd = ['ffmpeg', '-v', 'error', '-ss', str(sec), '-i', path, '-map', f'0:v:{track}', '-frames:v', '1',
           '-vf', f'scale={size}:{size}', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-']
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.uint8).reshape(size, size, 3).astype(np.float32)


def main(clip, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    d = read_trailer(clip)
    meta = d['meta']
    crop = None
    if 27 in meta:
        c = proto(meta[27])
        vals = [c.get(i, 0) for i in range(1, 7)]
        if all(vals[:4]):
            crop = vals
    Ls = lenses(d['offset_v3'], crop)
    camera = meta.get(2, b'').decode(errors='ignore')
    order = meta.get(80, 0)
    reversed_tracks = order == 1
    probe = json.loads(subprocess.run(['ffprobe', '-v', 'error', '-show_streams', '-show_format', '-of', 'json', clip],
                                      capture_output=True).stdout)
    dur = float(probe['format']['duration'])
    vids = [s for s in probe['streams'] if s['codec_type'] == 'video']
    size = 1440
    # Equirect şeritleri: her iki dikiş çevresi (boylam ±90°), ±14° genişlik
    W, H = 2048, 1024
    lon = (np.arange(W) + 0.5) / W * 2 * np.pi - np.pi
    lat = (np.arange(H) + 0.5) / H * np.pi - np.pi / 2
    LON, LAT = np.meshgrid(lon, lat)
    dirs = np.stack([np.cos(LAT) * np.sin(LON), np.sin(LAT), np.cos(LAT) * np.cos(LON)], -1)

    # Dikiş: c0.z == c1.z olan yerler (genelde boylam ±90°). Şeritleri bunun çevresinden al.
    c0z = (dirs @ Ls[0]['R'])[..., 2]; c1z = (dirs @ Ls[1]['R'])[..., 2]
    seam_dist = np.abs(c0z - c1z)
    band = seam_dist < 0.35  # ~±10°
    lat_ok = np.abs(LAT) < np.radians(70)

    results = dict(camera=camera, duration=dur, tracks=[(v['width'], v['height'], v.get('codec_name')) for v in vids],
                   crop=crop, translation=[L['T'].tolist() for L in Ls], frames=[])
    times = [dur * f for f in (0.2, 0.5, 0.8)]
    agg = {}
    for ti, sec in enumerate(times):
        t0 = frame(clip, 0, sec, size); t1 = frame(clip, 1, sec, size)
        imgs = {0: t1, 1: t0} if reversed_tracks else {0: t0, 1: t1}
        fr = dict(t=sec)
        for sgn in (1, -1):
            for dist in (0, 5, 3, 2, 1.5, 1, 0.7, 0.5):
                inv = 0 if dist == 0 else 1.0 / dist
                cols = []; zs = []; valid = []
                for i, L in enumerate(Ls):
                    p = dirs[band & lat_ok] - sgn * L['T'] * inv
                    u, v, z = project(L, p @ L['R'])
                    cols.append(sample(imgs[i], u, v)); zs.append(z)
                    valid.append((z > -0.17) & (u > 0) & (u < 1) & (v > 0) & (v < 1))
                both = valid[0] & valid[1]
                a, b = cols[0][both], cols[1][both]
                # Renk eşitleme: kanal başına kazanç (b'yi a'ya)
                gain = (a.mean(0) + 1) / (b.mean(0) + 1)
                # Hizalama hatası: parlaklık normalize edildikten sonra gradyan benzeri yerel fark
                err_raw = float(np.abs(a - b).mean())
                err_gain = float(np.abs(a - b * gain).mean())
                key = f"s{sgn}_d{dist}"
                fr[key] = dict(err_raw=err_raw, err_gain=err_gain, gain=gain.tolist(), n=int(both.sum()))
                agg.setdefault(key, []).append(err_gain)
        results['frames'].append(fr)

        # Görsel: mevcut uygulama (sonsuz, kazançsız, ±5°) ve en iyi ayar için dikiş şeritleri
        if ti == 1:
            best = min(agg, key=lambda k: np.mean(agg[k]))
            for name, (key, use_gain, width) in {'current': ('s1_d0', False, 0.09), 'best': (best, True, 0.25)}.items():
                sgn = int(key.split('_')[0][1:]); dist = float(key.split('_d')[1])
                inv = 0 if dist == 0 else 1.0 / dist
                out = np.zeros((H, W, 3), np.float32)
                cs = []; zz = []
                for i, L in enumerate(Ls):
                    p = dirs - sgn * L['T'] * inv
                    c = p @ L['R']
                    u, v, z = project(L, c)
                    cs.append(sample(imgs[i], u, v)); zz.append(z)
                g = fr[key]['gain'] if use_gain else [1, 1, 1]
                cs[1] = cs[1] * np.array(g)
                diff = zz[0] - zz[1]
                w0 = np.clip((diff + width) / (2 * width), 0, 1)
                w0 = w0 * w0 * (3 - 2 * w0)
                w0[zz[0] < -0.17] = 0; w0[zz[1] < -0.17] = 1
                out = cs[0] * w0[..., None] + cs[1] * (1 - w0[..., None])
                # Yalnızca dikiş çevresindeki şeritler (her iki dikiş)
                cols_idx = np.where((seam_dist < 0.5).any(0))[0]
                strips = []
                for center in (W // 4, 3 * W // 4):
                    near = cols_idx[np.abs(cols_idx - center) < W // 8]
                    if len(near):
                        x0 = max(0, near.min() - 10); x1 = min(W, near.max() + 10)
                        strips.append(out[H // 6: 5 * H // 6, x0:x1])
                if strips:
                    hmax = max(s.shape[0] for s in strips)
                    canvas = np.concatenate([np.pad(s, ((0, hmax - s.shape[0]), (0, 8), (0, 0))) for s in strips], 1)
                    Image.fromarray(canvas.clip(0, 255).astype(np.uint8)).save(os.path.join(out_dir, f'seam_{name}.jpg'), quality=85)

    # ---- Kayma ölçümü: dikiş üzerinde iki lensin görüntüsünü yerel olarak karşılaştır ----
    # Dikiş boyunca (enlem -60..60) küçük pencerelerde, lens1 örneğini lens0'a göre kaydırarak
    # en iyi eşleşen kaymayı (derece) bul. Sabit kayma = kalibrasyon/eksen hatası;
    # enleme göre değişen kayma = bozulma modeli hatası; sıfıra yakın = sorun renk/bulanıklıkta.
    sec = dur * 0.5
    t0 = frame(clip, 0, sec, size); t1 = frame(clip, 1, sec, size)
    imgs = {0: t1, 1: t0} if reversed_tracks else {0: t0, 1: t1}
    step = np.radians(0.1)
    shifts = []
    for side in (1, -1):  # iki dikiş: boylam +90 ve -90 civarı
        for latd in range(-60, 61, 10):
            la = np.radians(latd)
            # Bu enlemde dikişin boylamını bul (c0.z == c1.z)
            lons = np.radians(np.linspace(side * 60, side * 120, 601))
            dd = np.stack([np.cos(la) * np.sin(lons), np.full_like(lons, np.sin(la)), np.cos(la) * np.cos(lons)], -1)
            z0 = (dd @ Ls[0]['R'])[:, 2]; z1 = (dd @ Ls[1]['R'])[:, 2]
            lo = lons[np.argmin(np.abs(z0 - z1))]
            # 4°x4° pencere, 0,1° adım
            g = np.radians(np.linspace(-2, 2, 41))
            GL, GA = np.meshgrid(lo + g, la + g)
            def patch(i, dlon=0.0, dlat=0.0):
                L_, A_ = GL + dlon, GA + dlat
                d = np.stack([np.cos(A_) * np.sin(L_), np.sin(A_), np.cos(A_) * np.cos(L_)], -1)
                u, v, _ = project(Ls[i], d @ Ls[i]['R'])
                p = sample(imgs[i], u, v).mean(-1)
                return (p - p.mean()) / (p.std() + 1e-6), float(p.std())
            a, sa = patch(0)
            if sa < 6:  # dokusuz bölge (gökyüzü vb.), ölçüm güvenilmez
                continue
            best = (1e9, 0, 0)
            for dl in np.arange(-1.5, 1.51, 0.1):
                for da in np.arange(-1.5, 1.51, 0.1):
                    b, _ = patch(1, np.radians(dl), np.radians(da))
                    e = float(np.mean((a - b) ** 2))
                    if e < best[0]:
                        best = (e, dl, da)
            b0, _ = patch(1)
            shifts.append(dict(side=side, lat=latd, lon=round(float(np.degrees(lo)), 2), dlon=round(best[1], 2), dlat=round(best[2], 2),
                               err0=round(float(np.mean((a - b0) ** 2)), 3), err=round(best[0], 3)))
    results['shifts'] = shifts

    # Görsel: dikiş üzerinde iki lensin %50 karışımı (kayma varsa çift görüntü), dar şeritler
    for side, name in ((1, 'right'), (-1, 'left')):
        lo = np.radians(side * 90)
        lonv = lo + np.radians(np.linspace(-8, 8, 160))
        latv = np.radians(np.linspace(-60, 60, 600))
        LL, AA = np.meshgrid(lonv, latv)
        d = np.stack([np.cos(AA) * np.sin(LL), np.sin(AA), np.cos(AA) * np.cos(LL)], -1)
        cs = []
        for i in (0, 1):
            u, v, _ = project(Ls[i], d @ Ls[i]['R'])
            cs.append(sample(imgs[i], u, v))
        ghost = 0.5 * cs[0] + 0.5 * cs[1]
        canvas = np.concatenate([cs[0], np.zeros((600, 4, 3)), cs[1], np.zeros((600, 4, 3)), ghost], 1)
        Image.fromarray(canvas.clip(0, 255).astype(np.uint8)).save(os.path.join(out_dir, f'ghost_{name}.jpg'), quality=88)

    results['summary'] = sorted(((float(np.mean(v)), k) for k, v in agg.items()))[:8]
    results['current_err'] = float(np.mean([f['s1_d0']['err_raw'] for f in results['frames']]))
    json.dump(results, open(os.path.join(out_dir, 'seam.json'), 'w'), indent=1)
    print(json.dumps({k: results[k] for k in ('camera', 'tracks', 'crop', 'summary', 'current_err')}, indent=1))
    for sh in results['shifts']:
        print(sh)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
