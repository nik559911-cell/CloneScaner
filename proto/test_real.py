"""Реальные фото пользователя (в репозиторий не кладутся — личные данные).
Запуск: REAL_DIR=/путь/к/фото python3 test_real.py
Ожидаемые углы — по фото 3000×4000; допуск в пикселях."""
import os, sys, cv2, numpy as np
sys.path.insert(0, sys.path[0])
import pipeline as P

CASES = {
    # светлый полис на светлой скатерти с кактусами, справа тёмный предмет, сверху край стола.
    # Внешний край белого поля почти не виден; допустима рамка по краю печати.
    "polis.jpg": ([[620, 740], [2520, 720], [2630, 3560], [610, 3600]], 130),
    # лист ЭКГ на той же скатерти, справа второй документ; приложение 0.4.1 брало рамку по скатерти
    "ekg.jpg": ([[245, 936], [1927, 633], [2523, 3087], [653, 3476]], 40),
}

d = os.environ.get("REAL_DIR")
if not d or not os.path.isdir(d):
    print("REAL_DIR не задан — пропуск"); sys.exit(0)
ok = True
for f, (exp, tol) in CASES.items():
    p = os.path.join(d, f)
    if not os.path.exists(p):
        print(f, "нет файла — пропуск"); continue
    q = P.detect(cv2.imread(p))
    if q is None:
        print(f, "НЕ НАЙДЕН"); ok = False; continue
    err = np.abs(P.order_quad(q) - np.float32(exp)).max()
    good = err <= tol
    ok &= good
    print(f"{f}: ошибка углов {err:.0f}px (допуск {tol})", "OK" if good else "ОШИБКА", q.round().astype(int).tolist())
print("ИТОГ:", "OK" if ok else "ЕСТЬ ПРОБЛЕМЫ")
