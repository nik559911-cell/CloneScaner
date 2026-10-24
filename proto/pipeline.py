"""Прототип ядра сканера: поиск листа, перспектива, фильтры.
Kotlin-код в app/.../cv повторяет эти шаги один в один."""
import cv2
import numpy as np

DETECT_SIDE = 640


def order_quad(pts):
    """Углы по часовой: левый-верх, правый-верх, правый-низ, левый-низ."""
    pts = np.asarray(pts, dtype=np.float32).reshape(4, 2)
    s = pts.sum(1)
    d = pts[:, 1] - pts[:, 0]
    return np.array([pts[s.argmin()], pts[d.argmin()], pts[s.argmax()], pts[d.argmax()]], np.float32)


def _quad_from_contour(c):
    peri = cv2.arcLength(c, True)
    approx = cv2.approxPolyDP(c, 0.02 * peri, True)
    if len(approx) == 4 and cv2.isContourConvex(approx):
        return approx.reshape(4, 2).astype(np.float32)
    hull = cv2.convexHull(c)
    approx = cv2.approxPolyDP(hull, 0.02 * cv2.arcLength(hull, True), True)
    if len(approx) == 4:
        return approx.reshape(4, 2).astype(np.float32)
    return None


def _hugs_frame(q, w, h):
    """Рамка совпадает с самим кадром (все 4 угла у своих углов кадра) — это не лист."""
    m = 0.02 * max(w, h)
    corners = np.array([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]], np.float32)
    return bool((np.linalg.norm(order_quad(q) - corners, axis=1) < m).all())


NBINS = 8


def oriented_edges(gray, s, edge_gray, edge_s):
    """8 карт краёв по направлению градиента (0..180°, по 22.5°), расширенных 5×5.
    Сторона листа поддерживается только краями, идущими вдоль неё; у узора скатерти направления случайные."""
    k5 = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    maps = [np.zeros_like(edge_gray) for _ in range(NBINS)]
    for img, e in ((gray, edge_gray), (s, edge_s)):
        gx = cv2.Sobel(img, cv2.CV_32F, 1, 0, ksize=3)
        gy = cv2.Sobel(img, cv2.CV_32F, 0, 1, ksize=3)
        ang = (np.degrees(np.arctan2(gy, gx)) + 180.0) % 180.0
        b = np.minimum((ang / (180.0 / NBINS)).astype(np.int32), NBINS - 1)
        for k in range(NBINS):
            maps[k] |= np.where((b == k) & (e > 0), 255, 0).astype(np.uint8)
    return [cv2.dilate(m, k5) for m in maps]


def _side_support_oriented(q, maps):
    """(средняя опора, опора самой слабой стороны, число сторон с настоящим краем).
    Край засчитывается, только если его градиент перпендикулярен стороне (±1 корзина из 8).
    Точки на краю кадра: в среднем — слабые 0.35, для «самой слабой стороны» — полноценные
    (лист просто уходит за кадр)."""
    h, w = maps[0].shape
    hit = tot = 0.0
    real_sides = 0
    side_min = 1.0
    for i in range(4):
        a, b = q[i], q[(i + 1) % 4]
        d = b - a
        normal = (np.degrees(np.arctan2(d[0], -d[1])) + 180.0) % 180.0
        kb = int(normal / (180.0 / NBINS)) % NBINS
        bins = (kb - 1) % NBINS, kb, (kb + 1) % NBINS
        n = max(8, int(np.linalg.norm(d) / 3))
        side_hit = 0.0
        side_real = side_border = side_tot = 0
        for t in np.linspace(0.05, 0.95, n):
            x, y = (a + d * t).round().astype(int)
            if 0 <= x < w and 0 <= y < h:
                side_tot += 1
                on_border = x <= 2 or y <= 2 or x >= w - 3 or y >= h - 3
                if any(maps[k][y, x] for k in bins):
                    side_hit += 1.0
                    side_real += 1
                elif on_border:
                    side_hit += 0.35
                    side_border += 1
        hit += side_hit
        tot += side_tot
        if side_tot:
            if side_real / side_tot >= 0.5:
                real_sides += 1
            side_min = min(side_min, (side_real + side_border) / side_tot)
    return hit / max(tot, 1), side_min, real_sides


def _colorful_fraction(q, s):
    """Доля пёстрых пикселей внутри рамки: насыщенность выше медианы внутри на 12+.
    У листа насыщенность ровная (печать, бланк), у скатерти с узором — пятна."""
    inside = np.zeros(s.shape, np.uint8)
    cv2.fillConvexPoly(inside, q.astype(np.int32), 255)
    vals = s[inside > 0]
    if vals.size == 0:
        return 1.0
    return float((vals > np.median(vals) + 12).mean())


def _paper_fraction(q, paper_mask):
    """Доля площади внутри рамки, похожей на бумагу (маска V−S по Отсу)."""
    inside = np.zeros(paper_mask.shape, np.uint8)
    cv2.fillConvexPoly(inside, q.astype(np.int32), 255)
    n = int((inside > 0).sum())
    return float(((paper_mask > 0) & (inside > 0)).sum()) / max(n, 1)


def _side_support(q, edges):
    """(общая опора, число сторон с настоящим краем). Край кадра даёт слабую опору 0.35."""
    hit = tot = 0
    real_sides = 0
    for i in range(4):
        a, b = q[i], q[(i + 1) % 4]
        n = max(8, int(np.linalg.norm(b - a) / 3))
        side_hit = side_real = side_tot = 0
        for t in np.linspace(0.05, 0.95, n):
            x, y = (a + (b - a) * t).round().astype(int)
            if 0 <= x < edges.shape[1] and 0 <= y < edges.shape[0]:
                side_tot += 1
                on_border = x <= 2 or y <= 2 or x >= edges.shape[1] - 3 or y >= edges.shape[0] - 3
                if edges[y, x] > 0:
                    side_hit += 1.0
                    side_real += 1
                elif on_border:
                    side_hit += 0.35
        hit += side_hit
        tot += side_tot
        if side_tot and side_real / side_tot >= 0.5:
            real_sides += 1
    return hit / max(tot, 1), real_sides


def _paper_contrast(q, v, s):
    """Внутри — светлое и бесцветное, в полосе снаружи — иное."""
    h, w = v.shape
    inside = np.zeros((h, w), np.uint8)
    cv2.fillConvexPoly(inside, q.astype(np.int32), 255)
    k = max(5, int(0.04 * max(h, w)) | 1)
    ring = cv2.dilate(inside, np.ones((k, k), np.uint8)) & ~inside
    if ring.sum() == 0:  # лист во весь кадр — снаружи сравнивать не с чем
        return 0.3
    di = float(v[inside > 0].mean()) - float(v[ring > 0].mean())
    ds = float(s[ring > 0].mean()) - float(s[inside > 0].mean())
    return max(0.0, di) / 255 + max(0.0, ds) / 255


def _line_x(l, y):
    (x1, y1, x2, y2) = l
    return x1 + (x2 - x1) * (y - y1) / ((y2 - y1) or 1e-6)


def _intersect(a, b):
    x1, y1, x2, y2 = a
    x3, y3, x4, y4 = b
    d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
    if abs(d) < 1e-6:
        return None
    t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / d
    return np.array([x1 + t * (x2 - x1), y1 + t * (y2 - y1)], np.float32)


def _cluster_lines(ls, w, h):
    """Склеить куски одной прямой: угол отличается < 3°, середина одного лежит в 1% кадра от прямой другого.
    Вес кластера — суммарная длина; прямая кластера — по крайним точкам всех кусков (fitLine)."""
    tol = 0.01 * max(w, h)
    clusters = []  # [вес, угол, список линий]
    for length, l in sorted(ls, key=lambda t: -t[0]):
        x1, y1, x2, y2 = l
        ang = np.degrees(np.arctan2(y2 - y1, x2 - x1)) % 180
        mx, my = (x1 + x2) / 2, (y1 + y2) / 2
        for c in clusters:
            da = abs(ang - c[1]) % 180
            if min(da, 180 - da) > 3:
                continue
            X1, Y1, X2, Y2 = c[2][0]
            dist = abs((Y2 - Y1) * mx - (X2 - X1) * my + X2 * Y1 - Y2 * X1) / max(np.hypot(X2 - X1, Y2 - Y1), 1e-6)
            if dist < tol:
                c[0] += length
                c[2].append(l)
                break
        else:
            clusters.append([length, ang, [l]])
    out = []
    for weight, _, group in sorted(clusters, key=lambda c: -c[0]):
        pts = np.array([[l[0], l[1]] for l in group] + [[l[2], l[3]] for l in group], np.float32)
        vx, vy, x0, y0 = cv2.fitLine(pts, cv2.DIST_L2, 0, 0.01, 0.01).reshape(-1)
        out.append((weight, (float(x0 - vx * 100), float(y0 - vy * 100), float(x0 + vx * 100), float(y0 + vy * 100))))
    return out


def _side_lines(edges, w, h):
    """Прямые-кандидаты для каждой стороны листа (до 4 сильнейших после склейки кусков) + край кадра."""
    lines = cv2.HoughLinesP(edges, 1, np.pi / 180, threshold=40,
                            minLineLength=int(0.08 * min(w, h)), maxLineGap=int(0.03 * max(w, h)))
    groups = {"top": [], "bottom": [], "left": [], "right": []}
    if lines is not None:
        for x1, y1, x2, y2 in lines.reshape(-1, 4):
            ang = abs(np.degrees(np.arctan2(y2 - y1, x2 - x1))) % 180
            length = float(np.hypot(x2 - x1, y2 - y1))
            l = (float(x1), float(y1), float(x2), float(y2))
            if ang < 35 or ang > 145:   # почти горизонтальная
                groups["top" if (y1 + y2) / 2 < h / 2 else "bottom"].append((length, l))
            elif 55 < ang < 125:        # почти вертикальная
                groups["left" if (x1 + x2) / 2 < w / 2 else "right"].append((length, l))
    frame = {"top": (0, 0, w - 1, 0), "bottom": (0, h - 1, w - 1, h - 1),
             "left": (0, 0, 0, h - 1), "right": (w - 1, 0, w - 1, h - 1)}
    out = {}
    for side, ls in groups.items():
        strong = [l for weight, l in _cluster_lines(ls, w, h) if weight >= 0.15 * min(w, h)][:6]
        out[side] = strong + [frame[side]]
    return out


def _hough_quads(edges, w, h):
    sides = _side_lines(edges, w, h)
    res = []
    for t in sides["top"]:
        for b in sides["bottom"]:
            for l in sides["left"]:
                for r in sides["right"]:
                    pts = [_intersect(t, l), _intersect(t, r), _intersect(b, r), _intersect(b, l)]
                    if any(p is None for p in pts):
                        continue
                    q = np.array(pts, np.float32)
                    if (q[:, 0] < -0.05 * w).any() or (q[:, 0] > 1.05 * w).any() or (q[:, 1] < -0.05 * h).any() or (q[:, 1] > 1.05 * h).any():
                        continue
                    q[:, 0] = q[:, 0].clip(0, w - 1)
                    q[:, 1] = q[:, 1].clip(0, h - 1)
                    res.append(q)
    return res


def candidates(img_bgr):
    """Все правдоподобные рамки на уменьшенном кадре с оценками (для отладки и тестов)."""
    h0, w0 = img_bgr.shape[:2]
    scale = DETECT_SIDE / max(h0, w0)
    small = cv2.resize(img_bgr, (round(w0 * scale), round(h0 * scale)), interpolation=cv2.INTER_AREA)
    h, w = small.shape[:2]
    # стираем текст (тёмные тонкие штрихи на светлом): иначе Хаф видит «прямые» сквозь строки
    no_text = cv2.morphologyEx(small, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (11, 11)))
    blur = cv2.medianBlur(no_text, 7)
    gray = cv2.cvtColor(blur, cv2.COLOR_BGR2GRAY)
    hsv = cv2.cvtColor(blur, cv2.COLOR_BGR2HSV)
    s, v = hsv[..., 1], hsv[..., 2]
    k5 = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))

    edge_maps = [cv2.Canny(gray, 50, 150), cv2.Canny(gray, 20, 60), cv2.Canny(s, 20, 60)]
    support_map = cv2.dilate(edge_maps[0] | edge_maps[1] | edge_maps[2], k5)

    paper = cv2.subtract(v, s)  # бумага: яркая и без цвета
    _, mask_paper = cv2.threshold(paper, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    _, mask_gray = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    k9 = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (9, 9))
    masks = [cv2.morphologyEx(cv2.morphologyEx(m, cv2.MORPH_OPEN, k9), cv2.MORPH_CLOSE, k9) for m in (mask_paper, mask_gray)]

    contours = []
    for e in edge_maps:
        contours += cv2.findContours(cv2.morphologyEx(e, cv2.MORPH_CLOSE, k5), cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)[0]
    for m in masks:
        contours += cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)[0]

    min_area = 0.2 * w * h
    quads = []
    for c in contours:
        if cv2.contourArea(cv2.convexHull(c)) < min_area:
            continue
        q = _quad_from_contour(c)
        if q is not None:
            quads.append(q)
    quads += _hough_quads(edge_maps[0] | edge_maps[1] | edge_maps[2], w, h)

    maps = oriented_edges(gray, s, edge_maps[0] | edge_maps[1], edge_maps[2])
    pre = []
    for q in quads:
        q = order_quad(q)
        if not cv2.isContourConvex(q.reshape(4, 1, 2)) or cv2.contourArea(q) < min_area or _hugs_frame(q, w, h):
            continue
        sup, sup_min, real_sides = _side_support_oriented(q, maps)
        if real_sides < 2:
            continue
        pre.append((sup * sup_min, sup, q))
    pre.sort(key=lambda t: -t[0])
    out = []
    for key, sup, q in pre[:40]:  # дорогая оценка — только для лучших по опоре на края
        con = _paper_contrast(q, v, s)
        frac = _paper_fraction(q, masks[0])
        colorful = _colorful_fraction(q, s)
        area = cv2.contourArea(q) / (w * h)
        score = key * (0.8 + con) * frac * np.sqrt(area) * np.exp(-12 * colorful)
        out.append((score, sup, con, area, q / scale))
    out.sort(key=lambda t: -t[0])
    return out


def detect(img_bgr):
    """4 точки листа в координатах исходного кадра или None (тогда — весь кадр)."""
    c = candidates(img_bgr)
    c = [x for x in c if x[2] >= 0.04]  # внутри рамки должно быть светлее и бесцветнее, чем снаружи
    if not c or c[0][1] < 0.45:
        return None
    return order_quad(c[0][4])


def valid_quad(q):
    q = order_quad(q)
    return cv2.isContourConvex(q.reshape(4, 1, 2)) and cv2.contourArea(q) > 1000


def warp(img_bgr, quad, max_side=4000):
    tl, tr, br, bl = order_quad(quad)
    out_w = (np.linalg.norm(tr - tl) + np.linalg.norm(br - bl)) / 2
    out_h = (np.linalg.norm(bl - tl) + np.linalg.norm(br - tr)) / 2
    k = min(1.0, max_side / max(out_w, out_h))
    out_w, out_h = int(round(out_w * k)), int(round(out_h * k))
    dst = np.array([[0, 0], [out_w - 1, 0], [out_w - 1, out_h - 1], [0, out_h - 1]], np.float32)
    m = cv2.getPerspectiveTransform(np.array([tl, tr, br, bl]), dst)
    return cv2.warpPerspective(img_bgr, m, (out_w, out_h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)


def background(channel):
    """Оценка освещения бумаги: уменьшить, закрыть крупным ядром (стираем текст), медиана, увеличить."""
    h, w = channel.shape
    k = 512 / max(h, w)
    small = cv2.resize(channel, (max(1, round(w * k)), max(1, round(h * k))), interpolation=cv2.INTER_AREA)
    small = cv2.morphologyEx(small, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15)))
    small = cv2.medianBlur(small, 21)
    return cv2.resize(small, (w, h), interpolation=cv2.INTER_LINEAR)


def flatten(img_bgr):
    """Деление на фон по каждому каналу: тени и цветной налёт уходят, бумага ~= 1.0."""
    out = []
    for ch in cv2.split(img_bgr):
        bg = background(ch).astype(np.float32)
        out.append(ch.astype(np.float32) / np.maximum(bg, 1.0))
    return cv2.merge(out)  # float32, бумага около 1.0


def _levels_from(img_bgr):
    """Точки белого/чёрного считаются по уменьшенной копии (до 1000 px) — экономит память на телефоне."""
    h, w = img_bgr.shape[:2]
    k = min(1.0, 1000 / max(h, w))
    small = cv2.resize(img_bgr, (max(1, round(w * k)), max(1, round(h * k))), interpolation=cv2.INTER_AREA)
    f = np.clip(flatten(small), 0, 2)
    lum = cv2.cvtColor(f.astype(np.float32), cv2.COLOR_BGR2GRAY)
    lum8 = np.clip(lum * 127.5, 0, 255).astype(np.uint8)  # 0..2 -> 0..255, как в Kotlin
    hist = np.bincount(lum8.ravel(), minlength=256).cumsum()
    def pct(p):
        return int(np.searchsorted(hist, p / 100 * hist[-1])) / 127.5
    white = max(pct(95), 0.5)
    black = min(pct(1), white * 0.6)
    return white, black


# Очистка просвечивающего текста с обратной стороны: светло-серые бесцветные пиксели → белый.
# Яркость 0..255: до SHOW_LO не трогаем, от SHOW_HI — чисто белый, между — плавно (smoothstep).
# Цветное (печати, синие подписи) не трогаем: насыщенность (max−min) ≥ CHROMA_HI.
SHOW_LO, SHOW_HI = 140.0, 217.0
CHROMA_LO, CHROMA_HI = 25.5, 38.25


def remove_show_through(img_u8):
    """img_u8 — трёхканальная uint8. Работает так же, как TextAlign/Processor.kt (поканально, один float-слой)."""
    ch = [c.astype(np.float32) for c in cv2.split(img_u8)]
    lum = (ch[0] + ch[1] + ch[2]) / 3.0
    chroma = np.maximum(np.maximum(ch[0], ch[1]), ch[2]) - np.minimum(np.minimum(ch[0], ch[1]), ch[2])
    t = np.clip((lum - SHOW_LO) / (SHOW_HI - SHOW_LO), 0, 1)
    t = t * t * (3 - 2 * t)
    gray = np.clip((CHROMA_HI - chroma) / (CHROMA_HI - CHROMA_LO), 0, 1)
    a = t * gray
    out = [np.clip(c + a * (255.0 - c) + 0.5, 0, 255).astype(np.uint8) for c in ch]
    return cv2.merge(out)


def filter_white(img_bgr, gray=False):
    white, black = _levels_from(img_bgr)
    chans = []
    for ch in cv2.split(img_bgr):  # по одному каналу: на телефоне в памяти один float-канал
        f = ch.astype(np.float32) / np.maximum(background(ch).astype(np.float32), 1.0)
        f = np.clip((f - black) / (white - black), 0, 1)
        f = np.power(f, 1.25)
        f = np.maximum(f, np.where(f > 0.94, 1.0, 0.0))
        chans.append(np.clip(f * 255, 0, 255).astype(np.uint8))
    out = remove_show_through(cv2.merge(chans))
    if gray:
        out = cv2.cvtColor(out, cv2.COLOR_BGR2GRAY)
    return out


def filter_lighten(img_bgr):
    chans = []
    for ch in cv2.split(img_bgr):
        f = ch.astype(np.float32) / np.maximum(background(ch).astype(np.float32), 1.0)
        chans.append(np.clip(f * 255, 0, 255).astype(np.uint8))
    return cv2.merge(chans)


def filter_bw(img_bgr):
    g = filter_white(img_bgr, gray=True)
    return cv2.adaptiveThreshold(g, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 31, 10)


# ---------- ориентация и наклон текста ----------

def ink_mask(img, side=1000):
    """Маска чернил на уменьшенной копии: адаптивный порог по яркости."""
    g = img if img.ndim == 2 else cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    h, w = g.shape
    k = min(1.0, side / max(h, w))
    g = cv2.resize(g, (max(1, round(w * k)), max(1, round(h * k))), interpolation=cv2.INTER_AREA)
    m = cv2.adaptiveThreshold(g, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, 25, 15)
    # поля и края листа не текст: срезаем по 3% с каждой стороны
    bh, bw = int(0.03 * m.shape[0]), int(0.03 * m.shape[1])
    m[:bh] = 0; m[-bh:] = 0; m[:, :bw] = 0; m[:, -bw:] = 0
    return m


def _rot(m, angle):
    h, w = m.shape
    M = cv2.getRotationMatrix2D((w / 2, h / 2), angle, 1.0)
    return cv2.warpAffine(m, M, (w, h), flags=cv2.INTER_NEAREST, borderValue=0)


def _row_score(m):
    """Чем резче чередуются строки и просветы, тем больше; нормировано на количество чернил."""
    p = m.sum(1, dtype=np.float64)
    tot = p.sum()
    if tot == 0:
        return 0.0
    p = p / tot
    return float(np.square(np.diff(p)).sum() * len(p))


def skew_angle(m, limit=15.0):
    """Угол (в градусах, против часовой — как у getRotationMatrix2D), выпрямляющий строки."""
    best_a, best_s = 0.0, _row_score(m)
    for a in np.arange(-limit, limit + 0.01, 0.5):
        s = _row_score(_rot(m, a))
        if s > best_s:
            best_a, best_s = float(a), s
    for a in np.arange(best_a - 0.5, best_a + 0.51, 0.1):
        s = _row_score(_rot(m, a))
        if s > best_s:
            best_a, best_s = float(a), s
    return best_a, best_s


def _lines(m):
    """Полосы строк по профилю: [(y0, y1)]."""
    p = m.sum(1)
    on = p > 0.15 * p.max() if p.max() > 0 else np.zeros_like(p, bool)
    out, y = [], 0
    while y < len(on):
        if on[y]:
            y0 = y
            while y < len(on) and on[y]:
                y += 1
            if y - y0 >= 3:
                out.append((y0, y))
        y += 1
    return out


def upside_down_votes(m):
    """>0 — текст, скорее всего, вверх ногами.
    В кириллице и латинице над основной высотой строки (заглавные, цифры, б, й, d, f…) чернил больше,
    чем под ней (р, у, д…). Голосуем по строкам и по общей массе.
    Выравнивание по левому краю не используем: на бланках много строк по центру."""
    lines = _lines(m)
    if len(lines) < 3:
        return 0.0
    votes, n, above_t, below_t = 0, 0, 0.0, 0.0
    for y0, y1 in lines:
        hgt = y1 - y0
        a0, a1 = max(0, y0 - hgt // 2), min(m.shape[0], y1 + hgt // 2)
        prof = m[a0:a1].sum(1).astype(np.float64)
        if prof.max() == 0:
            continue
        core = np.where(prof >= 0.5 * prof.max())[0]
        above, below = prof[:core[0]].sum(), prof[core[-1] + 1:].sum()
        if above + below < 0.05 * prof.sum():
            continue
        n += 1
        votes += 1 if below > above else -1
        above_t += above
        below_t += below
    if n < 3:
        return 0.0
    return float(0.5 * votes / n + 0.5 * (below_t - above_t) / max(above_t + below_t, 1))


def orientation(img):
    """Сколько градусов по часовой повернуть страницу (0/90/180/270), чтобы текст стоял прямо.
    Считать по уже обрезанному листу: фон вокруг сбивает подсказки."""
    m = ink_mask(img, 800)
    _, s0 = skew_angle(m, 10)
    _, s90 = skew_angle(np.ascontiguousarray(np.rot90(m, -1)), 10)
    options = (90, 270) if s90 > 1.25 * s0 else (0, 180)
    votes = []
    for o in options:
        mo = np.ascontiguousarray(np.rot90(m, -o // 90))  # np.rot90 с k<0 — по часовой
        a, _ = skew_angle(mo, 10)
        votes.append(upside_down_votes(_rot(mo, a)))
    # меньше голос «вверх ногами» — правильнее; при равенстве — первый вариант
    if votes[1] < votes[0] - 0.1:
        return options[1]
    return options[0]


def deskew(img, limit=15.0):
    """Выпрямить строки. Работает и для текста, идущего вертикально (страница боком):
    повороты вокруг центра перестановочны, поэтому угол, найденный на маске, повёрнутой на 90°,
    годится и для исходной картинки. Значит, выпрямление не зависит от поворота кнопкой."""
    m = ink_mask(img, 1000)
    a0, s0 = skew_angle(m, limit)
    a90, s90 = skew_angle(np.ascontiguousarray(np.rot90(m, -1)), limit)
    a = a90 if s90 > 1.25 * s0 else a0
    if abs(a) < 0.2:
        return img, 0.0
    h, w = img.shape[:2]
    M = cv2.getRotationMatrix2D((w / 2, h / 2), a, 1.0)
    return cv2.warpAffine(img, M, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE), a


def rotate90s(img, cw):
    return {0: img, 90: cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE),
            180: cv2.rotate(img, cv2.ROTATE_180), 270: cv2.rotate(img, cv2.ROTATE_90_COUNTERCLOCKWISE)}[cw % 360]
