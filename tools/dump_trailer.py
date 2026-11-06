"""Insta360 .insv trailer bölümünü dosyanın sonundan çıkarır.

Trailer sonu (bilinen düzen): ... [32 bayt dolgu][u32 trailer boyutu][u32 sürüm][32 bayt ASCII imza]
Boyut alanı güvenilmezse son 16 MB'ı yedek olarak yazar.
Kullanım: python dump_trailer.py <insv> <çıktı_klasörü>
"""
import json
import os
import struct
import sys

MAGIC = b"8db42d694ccc418790edff439fe026bf"


def main(path: str, out_dir: str) -> None:
    os.makedirs(out_dir, exist_ok=True)
    size = os.path.getsize(path)
    info = {"file_size": size}
    with open(path, "rb") as f:
        f.seek(size - 72)
        tail = f.read(72)
        info["tail72_hex"] = tail.hex()
        info["magic_ok"] = tail[-32:] == MAGIC
        trailer_size, version = struct.unpack("<II", tail[32:40])
        info["trailer_size_field"] = trailer_size
        info["version_field"] = version
        n = trailer_size if info["magic_ok"] and 0 < trailer_size < 200 * 1024 * 1024 else 16 * 1024 * 1024
        n = min(n, size)
        f.seek(size - n)
        data = f.read(n)
    info["dumped_bytes"] = len(data)
    with open(os.path.join(out_dir, "trailer.bin"), "wb") as w:
        w.write(data)
    with open(os.path.join(out_dir, "trailer_info.json"), "w") as w:
        json.dump(info, w, indent=2)
    print(json.dumps(info, indent=2))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
