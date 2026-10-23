"""Синтетические «фото» документа: перекос, тень, тёплый свет, шум. Проверяем детектор и фильтр."""
import sys
import cv2
import numpy as np
sys.path.insert(0, sys.path[0])
import pipeline as P

rng = np.random.default_rng(1)
W, H = 1240, 1754  # A4 при 150 dpi


def make_page():
    page = np.full((H, W, 3), 255, np.uint8)
    for i, y in enumerate(range(160, 1500, 46)):
        x2 = 1100 if i % 5 else 700
        cv2.putText(page, "Akt No 15 ot 23.10.2026 komandirovka 12345", (100, y), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (25, 25, 25), 2)
    cv2.circle(page, (950, 1600), 110, (40, 40, 200), 6)  # красная печать (BGR)
    cv2.putText(page, "PECHAT", (860, 1610), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (40, 40, 200), 3)
    cv2.line(page, (150, 1620), (500, 1590), (160, 60, 20), 3)  # синяя подпись
    return page


def photograph(page, quad, light_kind):
    ph, pw = 3000, 2250
    table = (rng.normal(70, 12, (ph, pw, 3))).clip(0, 255).astype(np.uint8)
    table = cv2.GaussianBlur(table, (9, 9), 0)
    src = np.array([[0, 0], [W - 1, 0], [W - 1, H - 1], [0, H - 1]], np.float32)
    m = cv2.getPerspectiveTransform(src, np.float32(quad))
    warped = cv2.warpPerspective(page, m, (pw, ph))
    mask = cv2.warpPerspective(np.full((H, W), 255, np.uint8), m, (pw, ph))
    img = np.where(mask[..., None] > 0, warped, table).astype(np.float32)
    yy, xx = np.mgrid[0:ph, 0:pw].astype(np.float32)
    if light_kind == "shadow":   # тень от руки/телефона по диагонали
        light = 0.55 + 0.45 * np.clip((xx + yy - 1800) / 1500, 0, 1)
    else:                        # неравномерный свет к краям
        light = 0.95 - 0.35 * (((xx - 1100) / 1500) ** 2 + ((yy - 1400) / 1800) ** 2)
    tint = np.array([0.78, 0.9, 1.0], np.float32)  # тёплая лампа: серо-жёлтая бумага
    img = img * light[..., None] * tint
    img += rng.normal(0, 4, img.shape)
    img = cv2.GaussianBlur(img.clip(0, 255).astype(np.uint8), (3, 3), 0)
    return img


def score(name, out, page):
    paper = (page == 255).all(2)
    paper = cv2.erode(paper.astype(np.uint8), np.ones((9, 9), np.uint8)) > 0
    o = cv2.resize(out, (W, H), interpolation=cv2.INTER_AREA)
    lum = cv2.cvtColor(o, cv2.COLOR_BGR2GRAY)
    white_share = (lum[paper] >= 245).mean()
    text = (page[..., 0] < 60) & (page[..., 2] < 60)
    text_lum = np.median(lum[text])
    stamp = (page[..., 2] > 150) & (page[..., 0] < 80)
    b, g, r = [np.median(o[..., i][stamp]) for i in range(3)]
    print(f"{name:22s} бумага>=245: {white_share:6.1%}  текст(медиана): {text_lum:5.0f}  печать BGR: {b:.0f},{g:.0f},{r:.0f}")
    return white_share, text_lum, r - b


page = make_page()
cases = {
    "перекос+тень": [[420, 380], [1900, 520], [1980, 2700], [260, 2560]],
    "сильный перекос+свет": [[600, 300], [1700, 420], [2100, 2850], [150, 2650]],
}
ok = True
for name, quad in cases.items():
    for light in ("shadow", "vignette"):
        photo = photograph(page, quad, light)
        q = P.detect(photo)
        if q is None:
            print(name, light, "НЕ НАЙДЕН"); ok = False; continue
        err = np.abs(P.order_quad(q) - P.order_quad(quad)).max()
        flat = P.warp(photo, q)
        out = P.filter_white(flat)
        ws, tl, red = score(f"{name}/{light}", out, page)
        print(f"{'':22s} ошибка углов: {err:.1f}px из 3000; выход {flat.shape[1]}x{flat.shape[0]}")
        ok &= err < 25 and ws > 0.97 and tl < 90 and red > 60
        cv2.imwrite(f"/home/claude/skaner/proto/out_{light}_{len(name)}.jpg", np.hstack([cv2.resize(cv2.cvtColor(cv2.cvtColor(flat, cv2.COLOR_BGR2RGB), cv2.COLOR_RGB2BGR), (620, 877)), cv2.resize(out, (620, 877))]))
        cv2.imwrite(f"/home/claude/skaner/proto/photo_{light}_{len(name)}.jpg", cv2.resize(photo, (563, 750)))

# фото без листа: детектор не должен выдумывать рамку
noise = (rng.normal(120, 30, (2000, 1500, 3))).clip(0, 255).astype(np.uint8)
print("шум без листа ->", P.detect(cv2.GaussianBlur(noise, (15, 15), 0)))
# неверная рамка
print("самопересечение валидно?", P.valid_quad(np.float32([[0, 0], [100, 100], [100, 0], [0, 100]])) if False else P.valid_quad(np.float32([[0,0],[500,0],[500,500],[0,500]])))
print("ИТОГ:", "OK" if ok else "ЕСТЬ ПРОБЛЕМЫ")
