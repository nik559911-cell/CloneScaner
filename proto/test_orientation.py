"""Ориентация и наклон: страница пользователя (кириллица, бланк) и синтетика (латиница)."""
import sys, cv2, numpy as np
sys.path.insert(0, sys.path[0])
import pipeline as P

D = '/tmp/claude-0/-home-claude/cdf02360-79ed-57d6-8927-9e932f92db87/scratchpad/real/'
pages = {}
for name, path in (('стр.2 пользователя', D + 'p1_0.jpg'),):
    im = cv2.imread(path)
    if im is not None:
        pages[name] = im
exec(open(sys.path[0] + '/test_pipeline.py').read().split('page = make_page()')[0].split('rng = ')[0])
syn = np.full((1754, 1240, 3), 255, np.uint8)
words = ["Akt", "priema-peredachi", "No 15", "ot 23.10.2026", "Komandirovka", "Moskva", "gostinica", "Itogo",
         "Podpis", "Bukhgalter", "summa", "dogovor", "Ivanov", "rubley", "ploshchad", "zakazchik"]
r = np.random.default_rng(3)
for y in range(150, 1600, 52):
    x = 100
    while x < 1000:
        w_ = words[int(r.integers(0, len(words)))]
        cv2.putText(syn, w_, (x, y), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (20, 20, 20), 2)
        x += 25 * len(w_) + 20
pages['синтетика (латиница)'] = syn

ok = True
for name, im in pages.items():
    base = P.skew_angle(P.ink_mask(im))[0]
    for cw in (0, 90, 180, 270):
        o = P.orientation(P.rotate90s(im, (-cw) % 360))
        good = o == cw
        ok &= good
        print(f'{name}: повернута на {cw:3d}° → ответ {o:3d}°', 'OK' if good else 'ОШИБКА')
    for a in (-9, -4, 3, 12):
        h, w = im.shape[:2]
        M = cv2.getRotationMatrix2D((w / 2, h / 2), a, 1)
        t = cv2.warpAffine(im, M, (w, h), borderMode=cv2.BORDER_REPLICATE)
        est = P.skew_angle(P.ink_mask(t))[0]
        good = abs(est - (base - a)) <= 0.5
        ok &= good
        print(f'{name}: наклон {a:+d}° → найден {est:+.1f}° (ожидалось {base - a:+.1f}°)', 'OK' if good else 'ОШИБКА')
print('ИТОГ:', 'OK' if ok else 'ЕСТЬ ПРОБЛЕМЫ')

# выпрямление не зависит от поворота на 90°: deskew → поворот == поворот → deskew
ok2 = True
for name, im in pages.items():
    h, w = im.shape[:2]
    M = cv2.getRotationMatrix2D((w / 2, h / 2), 5, 1)
    tilted = cv2.warpAffine(im, M, (w, h), borderMode=cv2.BORDER_REPLICATE)
    angles = []
    for cw in (0, 90, 180, 270):
        _, a = P.deskew(P.rotate90s(tilted, cw))
        angles.append(round(a, 1))
    same = max(angles) - min(angles) <= 0.3
    ok2 &= same
    print(f'{name}: угол выпрямления при повороте 0/90/180/270: {angles}', 'OK' if same else 'ОШИБКА')
print('ИТОГ выпрямления при повороте:', 'OK' if ok2 else 'ЕСТЬ ПРОБЛЕМЫ')
