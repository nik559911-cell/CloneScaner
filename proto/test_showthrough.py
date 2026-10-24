"""Просвечивающий текст с обратной стороны: зеркальный бледный серый текст поверх страницы.
После «Белого фона» просвет должен исчезнуть, настоящий текст и синяя подпись — остаться."""
import sys, os, cv2, numpy as np
sys.path.insert(0, sys.path[0])
import pipeline as P

W, H = 1240, 1754
rng = np.random.default_rng(5)
front = np.full((H, W, 3), 255, np.uint8)
back = np.full((H, W, 3), 255, np.uint8)
for i, y in enumerate(range(150, 1500, 48)):
    cv2.putText(front, "Pervyi shag podderzhaniya poryadka 5S", (90, y), cv2.FONT_HERSHEY_SIMPLEX, 0.95, (20, 20, 20), 2)
    cv2.putText(back, "Obratnaya storona lista: drugoi tekst 12345", (80, y + 20), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 0), 3)
cv2.line(front, (700, 1600), (1100, 1560), (170, 70, 20), 4)          # синяя подпись (BGR)
cv2.circle(front, (300, 1620), 70, (40, 40, 200), 5)                   # красная печать
back = cv2.flip(back, 1)                                               # зеркально
back = cv2.GaussianBlur(back, (0, 0), 2.5)                            # сквозь бумагу — размыто
ink_back = 1.0 - back.astype(np.float32) / 255
page = front.astype(np.float32) * (1 - 0.22 * ink_back)              # просвет ~22% от чёрного
# фото: тёплый неравномерный свет и шум
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
light = (0.85 - 0.25 * (((xx - 600) / 900) ** 2 + ((yy - 800) / 1200) ** 2))[..., None]
photo = np.clip(page * light * np.array([0.82, 0.92, 1.0], np.float32) + rng.normal(0, 3, page.shape), 0, 255).astype(np.uint8)

out = P.filter_white(photo)
lum = cv2.cvtColor(out, cv2.COLOR_BGR2GRAY)
front_ink = (cv2.cvtColor(front, cv2.COLOR_BGR2GRAY) < 128)
front_near = cv2.dilate(front_ink.astype(np.uint8), np.ones((9, 9), np.uint8)) > 0
back_only = (ink_back.mean(2) > 0.3) & ~front_near
show = (lum[back_only] < 235).mean()
text = np.percentile(lum[front_ink], 50)
blue = (front[..., 0] == 170) & (front[..., 2] == 20)
b, g, r = [np.median(out[..., i][blue]) for i in range(3)]
stamp = (front[..., 2] == 200) & (front[..., 0] == 40)
sb, sg, sr = [np.median(out[..., i][stamp]) for i in range(3)]

saved = P.remove_show_through
P.remove_show_through = lambda x: x
before = cv2.cvtColor(P.filter_white(photo), cv2.COLOR_BGR2GRAY)
P.remove_show_through = saved
show_before = (before[back_only] < 235).mean()

print(f"просвет виден: было {show_before:.1%}, стало {show:.1%}")
print(f"настоящий текст (медиана): {text:.0f}")
print(f"синяя подпись BGR: {b:.0f},{g:.0f},{r:.0f}   красная печать BGR: {sb:.0f},{sg:.0f},{sr:.0f}")
ok = show < 0.02 and show_before > 0.3 and text < 90 and b - r > 60 and sr - sb > 60
cv2.imwrite(os.path.join(sys.path[0], "out_showthrough.jpg"), np.hstack([cv2.resize(before, (620, 877)), cv2.resize(lum, (620, 877))]))
print("ИТОГ:", "OK" if ok else "ЕСТЬ ПРОБЛЕМЫ")
