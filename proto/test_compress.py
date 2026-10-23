"""Сжатие страниц как в Compressor.kt: качество 85→55, затем масштаб 0.85/0.7/0.6; цель ≤ 60% размера.
Запуск: python3 test_compress.py страница1.jpg страница2.jpg …"""
import sys, cv2, numpy as np

SCALES = (1.0, 0.85, 0.7, 0.6)
QUALITIES = (85, 80, 75, 70, 65, 60, 55)


def compress(path, ratio=0.6):
    data = open(path, 'rb').read()
    target = len(data) * ratio
    full = cv2.imread(path)
    best = None
    for s in SCALES:
        img = full if s == 1.0 else cv2.resize(full, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
        for q in QUALITIES:
            b = cv2.imencode('.jpg', img, [cv2.IMWRITE_JPEG_QUALITY, q])[1].tobytes()
            if best is None or len(b) < len(best[0]):
                best = (b, s, q)
            if len(b) <= target:
                return len(data), len(b), s, q
    return len(data), len(best[0]), best[1], best[2]


tot0 = tot = 0
for p in sys.argv[1:]:
    a, b, s, q = compress(p)
    tot0 += a; tot += b
    print(f'{p.split("/")[-1]}: {a // 1024} → {b // 1024} КБ ({100 * b / a:.0f}%), масштаб {s}, качество {q}')
if tot0:
    print(f'ИТОГО: {tot0 // 1024} → {tot // 1024} КБ, уменьшение на {100 - 100 * tot / tot0:.0f}%')
