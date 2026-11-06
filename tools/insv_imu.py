"""Insta360 .insv trailer'ından ham IMU'yu ve zaman/ölçek bilgisini okur (uygulamadaki Kotlin kodunun eşi)."""
import struct
import numpy as np

MAGIC = b"8db42d694ccc418790edff439fe026bf"


def _varint(b, i):
    r = s = 0
    while True:
        x = b[i]; i += 1; r |= (x & 0x7f) << s; s += 7
        if x < 0x80:
            return r, i


def proto(b):
    i = 0; out = {}
    while i < len(b):
        key, i = _varint(b, i); f, w = key >> 3, key & 7
        if w == 0: v, i = _varint(b, i)
        elif w == 1: v = struct.unpack('<d', b[i:i + 8])[0]; i += 8
        elif w == 5: v = b[i:i + 4]; i += 4
        elif w == 2:
            L, i = _varint(b, i); v = b[i:i + L]; i += L
        else: raise ValueError(w)
        out.setdefault(f, v)
    return out


def read_trailer(path_or_bytes):
    if isinstance(path_or_bytes, (bytes, bytearray)):
        t = bytes(path_or_bytes)
    else:
        with open(path_or_bytes, 'rb') as f:
            f.seek(-72, 2); foot = f.read(72)
            assert foot[-32:] == MAGIC
            size = struct.unpack('<I', foot[32:36])[0]
            f.seek(-size, 2); t = f.read(size)
    n = len(t)
    size = struct.unpack('<I', t[n - 78 + 2:n - 72])[0]
    tab = t[n - 78 - size:n - 78]
    ents = {}
    for i in range(0, len(tab), 10):
        id_, fmt, s, o = struct.unpack('<BBII', tab[i:i + 10])
        if id_: ents[id_] = (fmt, s, o)
    rec = lambda i: t[ents[i][2]:ents[i][2] + ents[i][1]]
    meta = proto(rec(1))
    cfg = proto(meta[65]) if 65 in meta else {}
    acc_range = cfg.get(1, 16); gyro_range = cfg.get(2, 2000)
    raw = np.frombuffer(rec(3), dtype=np.dtype([('t', '<u8'), ('v', '<u2', 6)]))
    ts = raw['t'].astype(np.float64)
    vals = raw['v'].astype(np.float64) - 32768.0
    acc = vals[:, 0:3] / (32768.0 / acc_range)          # g
    gyro = vals[:, 3:6] / (32768.0 / gyro_range)        # deg/s
    fft = float(meta.get(24, 0)); gts = float(meta.get(28, 0.0))
    t_sec = (ts - fft) / 1e6 - gts / 1000.0
    return dict(t=t_sec, acc=acc, gyro=np.radians(gyro), meta=meta,
                rolling_shutter_ms=meta.get(25), offset_v3=meta.get(54, b'').decode())
