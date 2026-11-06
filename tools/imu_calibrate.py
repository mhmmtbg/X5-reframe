"""IMU eksenlerini gövde (rig) eksenlerine bağlayan dönüşümü görüntüden ölçer.

Yöntem: klipten ardışık kare çiftleri alınır, iki lens kalibrasyonla küreye (equirect) açılır,
ORB eşleşmelerinden RANSAC ile kare arası dönüş bulunur. Aynı aralıkta jiroskop entegre edilir ve
24 olası işaretli eksen eşlemesinden (ve küçük zaman kaymalarından) görüntüyle en uyumlusu seçilir.
Çıktı yalnızca sayılardır (görüntü yazılmaz).

Kullanım: python imu_calibrate.py clip.insv out.json
"""
import json
import subprocess
import sys
import itertools
import os

import numpy as np
import cv2

sys.path.insert(0, os.path.dirname(__file__))
from insv_imu import read_trailer  # noqa: E402


def rx(a):
    c, s = np.cos(a), np.sin(a); return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def ry(a):
    c, s = np.cos(a), np.sin(a); return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rz(a):
    c, s = np.cos(a), np.sin(a); return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def lenses_from_v3(v3, crop=(5376, 5312)):
    t = [float(x) for x in v3.split('_')]
    n = int(t[0])
    vend = lambda ypr, i: (ry(np.radians(ypr[1]) + np.pi / 2) @ rz(np.radians(ypr[0])) @ rx(np.radians(ypr[2]))) @ (rz(np.pi) if (i == 0 and n == 2) else np.eye(3))
    base = vend([0, 0, 90], 0)
    out = []
    for i in range(n):
        b = 1 + i * 19
        xi, fx, fy, cx, cy = t[b:b + 5]
        ypr = t[b + 5:b + 8]; k = t[b + 11:b + 16]
        fw = t[b + 16] / n
        cx -= fw * i
        src, dst = crop
        s = src / fw
        cx = s * (cx + 0.5) - 0.5 - (src - dst) * 0.5
        cy = s * (cy + 0.5) - 0.5 - (src - dst) * 0.5
        norm = dst - 1.0
        R = base @ vend(ypr, i).T  # rig_from_cam
        out.append(dict(xi=xi, fx=fx * s / norm, fy=fy * s / norm, cx=cx / norm, cy=cy / norm, k=k, R=R))
    return out


def equirect_maps(lenses, W, H, tex):
    lon = (np.arange(W) + 0.5) / W * 2 * np.pi - np.pi
    lat = (np.arange(H) + 0.5) / H * np.pi - np.pi / 2
    lon, lat = np.meshgrid(lon, lat)
    d = np.stack([np.cos(lat) * np.sin(lon), np.sin(lat), np.cos(lat) * np.cos(lon)], -1)
    maps = []
    for L in lenses:
        c = d @ L['R']
        z = c[..., 2]
        x = c[..., 0] / (z + L['xi']); y = c[..., 1] / (z + L['xi'])
        k1, k2, k3, p1, p2 = L['k']
        r2 = x * x + y * y
        rad = 1 + k1 * r2 + k2 * r2 ** 2 + k3 * r2 ** 3
        xd = x * rad + 2 * p1 * x * y + p2 * (r2 + 2 * x * x)
        yd = y * rad + 2 * p2 * x * y + p1 * (r2 + 2 * y * y)
        u = (L['fx'] * xd + L['cx']) * (tex - 1)
        v = (L['fy'] * yd + L['cy']) * (tex - 1)
        maps.append((u.astype(np.float32), v.astype(np.float32), z))
    take0 = maps[0][2] >= maps[1][2]
    return maps, take0, d


def frames(path, track, fps, size):
    cmd = ['ffmpeg', '-v', 'error', '-i', path, '-map', f'0:v:{track}', '-vf', f'fps={fps},scale={size}:{size}',
           '-f', 'rawvideo', '-pix_fmt', 'gray', '-']
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.uint8).reshape(-1, size, size)


def kabsch(a, b):
    """b ≈ R a"""
    H = a.T @ b
    U, _, Vt = np.linalg.svd(H)
    D = np.diag([1, 1, np.sign(np.linalg.det(Vt.T @ U.T))])
    return Vt.T @ D @ U.T


def ransac_rotation(a, b, iters=400, thr=np.radians(0.8)):
    best = None; best_in = 0
    rng = np.random.default_rng(0)
    for _ in range(iters):
        idx = rng.choice(len(a), 3, replace=False)
        R = kabsch(a[idx], b[idx])
        err = np.arccos(np.clip(np.sum((a @ R.T) * b, 1), -1, 1))
        inl = err < thr
        if inl.sum() > best_in:
            best_in = inl.sum(); best = inl
    if best is None or best_in < 12:
        return None, 0
    return kabsch(a[best], b[best]), int(best_in)


def rot_angle(R):
    return np.degrees(np.arccos(np.clip((np.trace(R) - 1) / 2, -1, 1)))


def integrate(t, w, t0, t1):
    """t0..t1 aralığında jiroskop entegrasyonu: IMU çerçevesinde t1 yönelimi = t0 yönelimi · G"""
    sel = (t >= t0) & (t <= t1)
    G = np.eye(3)
    ts = t[sel]; ws = w[sel]
    for i in range(len(ts) - 1):
        dt = ts[i + 1] - ts[i]
        wv = 0.5 * (ws[i] + ws[i + 1]) * dt
        ang = np.linalg.norm(wv)
        if ang < 1e-12:
            continue
        kx = wv / ang
        K = np.array([[0, -kx[2], kx[1]], [kx[2], 0, -kx[0]], [-kx[1], kx[0], 0]])
        G = G @ (np.eye(3) + np.sin(ang) * K + (1 - np.cos(ang)) * K @ K)
    return G


def main(clip, out_path):
    imu = read_trailer(clip)
    lenses = lenses_from_v3(imu['offset_v3'])
    fps, size, W, H = 10, 960, 1024, 512
    f0 = frames(clip, 0, fps, size)
    f1 = frames(clip, 1, fps, size)
    # X5 + Track0Stream10: iz 0 = lens 1
    tracks = {0: f1, 1: f0}
    maps, take0, dirs = equirect_maps(lenses, W, H, size)
    n = min(len(f0), len(f1))
    orb = cv2.ORB_create(3000)
    bf = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=True)
    eq = []
    for i in range(n):
        a = cv2.remap(tracks[0][i], maps[0][0], maps[0][1], cv2.INTER_LINEAR)
        b = cv2.remap(tracks[1][i], maps[1][0], maps[1][1], cv2.INTER_LINEAR)
        eq.append(np.where(take0, a, b))
    # Kutuplar çok bozuk: yalnızca |enlem| < 60° kullan
    lat_ok = np.abs(((np.arange(H) + 0.5) / H * 180 - 90)) < 60
    pairs = []
    step = 2  # 0.2 sn aralık
    for i in range(0, n - step, step):
        k1, d1 = orb.detectAndCompute(eq[i], None)
        k2, d2 = orb.detectAndCompute(eq[i + step], None)
        if d1 is None or d2 is None:
            continue
        m = bf.match(d1, d2)
        pa = []; pb = []
        for mm in m:
            x1, y1 = k1[mm.queryIdx].pt; x2, y2 = k2[mm.trainIdx].pt
            if not (lat_ok[int(y1)] and lat_ok[int(y2)]):
                continue
            pa.append(dirs[int(y1), int(x1)]); pb.append(dirs[int(y2), int(x2)])
        if len(pa) < 20:
            continue
        R, inl = ransac_rotation(np.array(pa), np.array(pb))
        if R is None:
            continue
        t0 = i / fps; t1 = (i + step) / fps
        pairs.append(dict(t0=t0, t1=t1, R=R, inliers=inl))

    # Görüntü: sabit bir nokta, rig çerçevesinde p2 = R_vis p1.
    # Kamera rig çerçevesinde C kadar dönerse p2 = C^T p1 -> C = R_vis^T.
    perms = []
    for p in itertools.permutations(range(3)):
        for sgn in itertools.product([1, -1], repeat=3):
            M = np.zeros((3, 3))
            for r in range(3):
                M[r, p[r]] = sgn[r]
            if np.linalg.det(M) > 0:
                perms.append(M)
    results = []
    for off in np.arange(-0.06, 0.0601, 0.01):
        for M in perms:
            errs = []
            for pr in pairs:
                G = integrate(imu['t'], imu['gyro'], pr['t0'] + off, pr['t1'] + off)
                C = M @ G @ M.T
                errs.append(rot_angle(C @ pr['R']))
            results.append((float(np.median(errs)), float(np.mean(errs)), float(off), M.astype(int).tolist()))
    results.sort(key=lambda r: r[0])
    best = results[0]
    M = np.array(best[3], float)
    g_rig = (M @ imu['acc'].mean(0)).tolist()
    vis_mag = [rot_angle(p['R']) for p in pairs]
    out = dict(
        pairs=len(pairs), visual_rotation_deg_median=float(np.median(vis_mag)) if vis_mag else None,
        best=dict(median_err_deg=best[0], mean_err_deg=best[1], time_offset_s=best[2], rig_from_imu=best[3]),
        runner_up=[dict(median_err_deg=r[0], time_offset_s=r[2], rig_from_imu=r[3]) for r in results[1:6]],
        mean_gravity_rig_g=g_rig, rolling_shutter_ms=imu['rolling_shutter_ms'],
    )
    json.dump(out, open(out_path, 'w'), indent=1)
    print(json.dumps(out, indent=1))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
