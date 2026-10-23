"""Порт PdfWriter.kt один в один (та же структура объектов и xref) + проверка pypdf."""
import io, os, sys, cv2, numpy as np
from pypdf import PdfReader

A4_W, A4_H = 595.28, 841.89
n = lambda v: f"{v:.2f}"

def layout(w, h):
    land = w > h
    pw, ph = (A4_H, A4_W) if land else (A4_W, A4_H)
    k = min(pw / w, ph / h); dw, dh = w * k, h * k
    return pw, ph, dw, dh, (pw - dw) / 2, (ph - dh) / 2

def write(pages, out):
    s = io.BytesIO(); offsets = []
    def obj(i, body):
        while len(offsets) < i: offsets.append(0)
        offsets[i - 1] = s.tell(); s.write(f"{i} 0 obj\n".encode()); body(); s.write(b"\nendobj\n")
    s.write(b"%PDF-1.4\n"); s.write(bytes([37, 0xE2, 0xE3, 0xCF, 0xD3, 10]))
    ids = [3 + i * 3 for i in range(len(pages))]
    obj(1, lambda: s.write(b"<< /Type /Catalog /Pages 2 0 R >>"))
    obj(2, lambda: s.write(f"<< /Type /Pages /Count {len(pages)} /Kids [{' '.join(f'{i} 0 R' for i in ids)}] >>".encode()))
    for i, (path, w, h) in enumerate(pages):
        pid = ids[i]; cid, iid = pid + 1, pid + 2
        pw, ph, dw, dh, x, y = layout(w, h)
        obj(pid, lambda: s.write(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {n(pw)} {n(ph)}] /Resources << /XObject << /Im0 {iid} 0 R >> >> /Contents {cid} 0 R >>".encode()))
        content = f"q {n(dw)} 0 0 {n(dh)} {n(x)} {n(y)} cm /Im0 Do Q\n".encode()
        def c(): s.write(f"<< /Length {len(content)} >>\nstream\n".encode()); s.write(content); s.write(b"endstream")
        obj(cid, c)
        data = open(path, "rb").read()
        def im(): s.write(f"<< /Type /XObject /Subtype /Image /Width {w} /Height {h} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length {len(data)} >>\nstream\n".encode()); s.write(data); s.write(b"\nendstream")
        obj(iid, im)
    xref = s.tell()
    s.write(f"xref\n0 {len(offsets)+1}\n0000000000 65535 f \n".encode())
    for o in offsets: s.write(f"{o:010d} 00000 n \n".encode())
    s.write(f"trailer\n<< /Size {len(offsets)+1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    open(out, "wb").write(s.getvalue())

d = "/home/claude/skaner/proto"
out1 = cv2.imread(f"{d}/out_shadow_12.jpg")[:, 620:]   # результат фильтра из прототипа
big = cv2.resize(out1, (2480, 3508))
cv2.imwrite(f"{d}/p1.jpg", big, [cv2.IMWRITE_JPEG_QUALITY, 85])
cv2.imwrite(f"{d}/p2.jpg", cv2.rotate(big, cv2.ROTATE_90_CLOCKWISE), [cv2.IMWRITE_JPEG_QUALITY, 85])
pages = [(f"{d}/p1.jpg", 2480, 3508), (f"{d}/p2.jpg", 3508, 2480)] * 25  # 50 страниц
write(pages, f"{d}/test.pdf")
r = PdfReader(f"{d}/test.pdf", strict=True)
print("страниц:", len(r.pages), "| размер:", round(os.path.getsize(f"{d}/test.pdf")/1e6, 1), "МБ на 50 стр.")
print("стр.1 MediaBox:", [float(v) for v in r.pages[0].mediabox], "| стр.2:", [float(v) for v in r.pages[1].mediabox])
img = r.pages[0].images[0]; print("картинка стр.1:", img.image.size, img.image.mode)
# xref-смещения должны указывать ровно на 'N 0 obj'
raw = open(f"{d}/test.pdf","rb").read(); xs = raw.rindex(b"\nxref\n") + 1; entries = raw[xs:].split(b"\n")[3:3+152]
bad = [i for i,e in enumerate(entries,1) if not raw[int(e[:10]):].startswith(f"{i} 0 obj".encode())]
print("неверных смещений xref:", len(bad))
for f in ("p1.jpg","p2.jpg"): os.remove(f"{d}/{f}")
