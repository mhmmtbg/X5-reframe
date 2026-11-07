"""Sabitleme doğruluğu ölçümü: görüntüden ölçülen kare arası dönüş ile jiroskop modelinin farkı.

Her ardışık kare çifti (30 fps) iki lensten küreye açılır, ORB eşleşmelerinden durağan sahne
noktaları alınır. Jiroskoptan hesaplanan yönelimle her noktanın bir sonraki karedeki yönü tahmin
edilir; tahmin hatası (derece) sabitlenmiş videoda kalan titremenin doğrudan ölçüsüdür.

Denenen model öğeleri:
  - zaman kayması τ (1 ms adım)
  - jiroskop sapması (metadata gyro_calib ya da veriden kestirim)
  - rolling shutter: satır zamanı = kare zamanı + ρ·(satır − 0,5), ρ = metadata'daki okuma süresi;
    okuma ekseni (u/v) ve yönü (±) veriden seçilir.

Kullanım: python stab_eval.py clip.insv out_dir
"""
import json
import os
import subprocess
import sys
import struct

import numpy as np
import cv2
from scipy.spatial.transform import Rotation, Slerp
from scipy.optimize import minimize

sys.path.insert(0, os.path.dirname(__file__))
from insv_imu import read_trailer  # noqa: E402
from imu_calibrate import lenses_from_v3, equirect_maps, ransac_rotation  # noqa: E402

RIG_FROM_IMU = np.array([[0, 0, -1], [1, 0, 0], [0, -1, 0]], float)
SIZE = 640
W, H = 1024, 512


def packet_pts(path, track):
    out = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', f'v:{track}', '-show_entries', 'packet=pts_time',
                          '-of', 'csv=p=0', path], capture_output=True, text=True, check=True).stdout
    return np.array(sorted(float(x) for x in out.split() if x.strip() and x.strip() != 'N/A'))


def decode(path, track):
    cmd = ['ffmpeg', '-v', 'error', '-i', path, '-map', f'0:v:{track}', '-vsync', 'passthrough',
           '-vf', f'scale={SIZE}:{SIZE}', '-f', 'rawvideo', '-pix_fmt', 'gray', '-']
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.uint8).reshape(-1, SIZE, SIZE)


def project(lens, d):
    """rig yönü → lens doku koordinatı (0..1) ve z"""
    c = d @ lens['R']
    z = c[:, 2]
    x = c[:, 0] / (z + lens['xi']); y = c[:, 1] / (z + lens['xi'])
    k1, k2, k3, p1, p2 = lens['k']
    r2 = x * x + y * y
    rad = 1 + k1 * r2 + k2 * r2 ** 2 + k3 * r2 ** 3
    xd = x * rad + 2 * p1 * x * y + p2 * (r2 + 2 * x * x)
    yd = y * rad + 2 * p2 * x * y + p1 * (r2 + 2 * y * y)
    return np.stack([lens['fx'] * xd + lens['cx'], lens['fy'] * yd + lens['cy']], 1), z


def pix_dir(px):
    lon = (px[:, 0] + 0.5) / W * 2 * np.pi - np.pi
    lat = (px[:, 1] + 0.5) / H * np.pi - np.pi / 2
    return np.stack([np.cos(lat) * np.sin(lon), np.sin(lat), np.cos(lat) * np.cos(lon)], 1)


def orientations(t, gyro_rig):
    rv = 0.5 * (gyro_rig[1:] + gyro_rig[:-1]) * np.diff(t)[:, None]
    steps = Rotation.from_rotvec(rv)
    q = np.zeros((len(t), 4)); q[0] = [0, 0, 0, 1]
    cur = Rotation.identity()
    mats = [cur]
    for s in steps:
        cur = cur * s
        mats.append(cur)
    return Slerp(t, Rotation.concatenate(mats))


def main(clip, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    imu = read_trailer(clip)
    meta = imu['meta']
    rs_ms = float(imu['rolling_shutter_ms'] or 0.0)
    calib = None
    if isinstance(meta.get(31), (bytes, bytearray)) and len(meta[31]) >= 48:
        calib = np.array(struct.unpack('<6d', meta[31][:48]))
    lenses = lenses_from_v3(imu['offset_v3'])
    maps, take0, _ = equirect_maps(lenses, W, H, SIZE)
    pts = packet_pts(clip, 0)
    f0 = decode(clip, 0); f1 = decode(clip, 1)
    tracks = {0: f1, 1: f0}  # X5 + Track0Stream10: iz 0 = lens 1
    n = min(len(f0), len(f1), len(pts))
    print(f'frames={n} rs_ms={rs_ms:.3f} gyro_calib={None if calib is None else calib.round(5).tolist()}', flush=True)

    lat_ok = np.abs(((np.arange(H) + 0.5) / H * 180 - 90)) < 60
    orb = cv2.ORB_create(2500)
    bf = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=True)
    prev = None
    A, B, TA, TB = [], [], [], []
    vis_angle = []
    for i in range(n):
        a = cv2.remap(tracks[0][i], maps[0][0], maps[0][1], cv2.INTER_LINEAR)
        b = cv2.remap(tracks[1][i], maps[1][0], maps[1][1], cv2.INTER_LINEAR)
        eq = np.where(take0, a, b)
        eq[~lat_ok, :] = 0
        kp, des = orb.detectAndCompute(eq, None)
        cur = (kp, des, pts[i])
        if prev is not None and des is not None and prev[1] is not None and len(kp) > 50:
            ms = bf.match(prev[1], des)
            if len(ms) > 40:
                pa = np.array([prev[0][m.queryIdx].pt for m in ms]); pb = np.array([kp[m.trainIdx].pt for m in ms])
                da = pix_dir(pa); db = pix_dir(pb)
                R, cnt = ransac_rotation(da, db, iters=300, thr=np.radians(1.5))
                if R is not None:
                    err = np.arccos(np.clip(np.sum((da @ R.T) * db, 1), -1, 1))
                    inl = err < np.radians(1.5)
                    A.append(da[inl]); B.append(db[inl])
                    TA.append(np.full(inl.sum(), prev[2])); TB.append(np.full(inl.sum(), pts[i]))
                    vis_angle.append(np.degrees(np.arccos(np.clip((np.trace(R) - 1) / 2, -1, 1))))
        prev = cur
    A = np.concatenate(A); B = np.concatenate(B); TA = np.concatenate(TA); TB = np.concatenate(TB)
    print(f'pairs={len(vis_angle)} matches={len(A)} median_frame_rotation_deg={np.median(vis_angle):.3f}', flush=True)

    # Her nokta için lens ve doku koordinatı (rolling shutter satırı)
    def lens_uv(D):
        uv0, z0 = project(lenses[0], D); uv1, z1 = project(lenses[1], D)
        use0 = z0 >= z1
        return np.where(use0[:, None], uv0, uv1)
    UVA = lens_uv(A); UVB = lens_uv(B)

    t = imu['t']; g_imu = imu['gyro']

    cache = {}

    def slerp_for(bias):
        key = None if bias is None else tuple(np.round(bias, 7))
        if key not in cache:
            g = g_imu - (0 if bias is None else bias)
            cache[key] = orientations(t, g @ RIG_FROM_IMU.T)
        return cache[key]

    def errors(tau, rho, axis, bias, sub=None):
        sl = slerp_for(bias)
        idx = slice(None) if sub is None else sub
        ta = TA[idx] + tau + rho * (UVA[idx, axis] - 0.5)
        tb = TB[idx] + tau + rho * (UVB[idx, axis] - 0.5)
        lo, hi = t[0], t[-1]
        ta = np.clip(ta, lo, hi); tb = np.clip(tb, lo, hi)
        Ra = sl(ta); Rb = sl(tb)
        pred = (Rb.inv() * Ra).apply(A[idx])
        return np.degrees(np.arccos(np.clip(np.sum(pred * B[idx], 1), -1, 1)))

    def best_tau(rho, axis, bias, lo=-0.02, hi=0.12, step=0.001):
        best = None
        rng = np.random.default_rng(1)
        sub = rng.choice(len(A), min(len(A), 40000), replace=False)
        for tau in np.arange(lo, hi + 1e-9, step):
            m = float(np.median(errors(tau, rho, axis, bias, sub)))
            if best is None or m < best[0]:
                best = (m, float(tau))
        return best

    results = {}

    def report(name, tau, rho, axis, bias):
        e = errors(tau, rho, axis, bias)
        r = dict(tau_s=round(tau, 4), rho_s=rho, axis=axis, median_deg=round(float(np.median(e)), 4),
                 p90_deg=round(float(np.percentile(e, 90)), 4), mean_deg=round(float(e.mean()), 4))
        results[name] = r
        print(f'{name:28s} τ={tau*1000:6.1f}ms ρ={rho*1000:+6.2f}ms ax={axis} median={r["median_deg"]:.4f}° p90={r["p90_deg"]:.4f}°', flush=True)

    report('baseline(τ=40ms)', 0.04, 0.0, 1, None)
    m, tau0 = best_tau(0.0, 1, None)
    report('tau_fine', tau0, 0.0, 1, None)
    if calib is not None:
        for nm, bias in [('calib_rad', calib[:3]), ('calib_deg', np.radians(calib[:3]))]:
            m, tb = best_tau(0.0, 1, bias)
            report(nm, tb, 0.0, 1, bias)
    rho = rs_ms / 1000.0
    best_rs = None
    for axis in (0, 1):
        for sgn in (1, -1):
            m, tr = best_tau(sgn * rho, axis, None)
            report(f'rs_ax{axis}_{"+" if sgn > 0 else "-"}', tr, sgn * rho, axis, None)
            if best_rs is None or m < best_rs[0]:
                best_rs = (m, tr, sgn * rho, axis)
    # Serbest sapma kestirimi (en iyi RS modeliyle)
    _, tr, rr, ax = best_rs
    rng = np.random.default_rng(2)
    sub = rng.choice(len(A), min(len(A), 15000), replace=False)
    f = lambda b: float(np.mean(np.minimum(errors(tr, rr, ax, np.array(b) * 1e-3, sub), 1.0)))
    opt = minimize(f, np.zeros(3), method='Nelder-Mead', options=dict(xatol=0.05, fatol=1e-5, maxiter=120))
    bias_fit = opt.x * 1e-3
    m, tf = best_tau(rr, ax, bias_fit)
    report('rs+bias_fit', tf, rr, ax, bias_fit)
    results['bias_fit_rad_s'] = bias_fit.round(6).tolist()
    results['gyro_calib'] = None if calib is None else calib.tolist()
    results['rs_ms'] = rs_ms
    json.dump(results, open(os.path.join(out_dir, 'stab.json'), 'w'), indent=1)
    print(json.dumps(results), flush=True)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
