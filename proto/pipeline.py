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


def _best_quad(binary, min_area):
    contours, _ = cv2.findContours(binary, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    best, best_area = None, min_area
    for c in contours:
        peri = cv2.arcLength(c, True)
        approx = cv2.approxPolyDP(c, 0.02 * peri, True)
        if len(approx) != 4 or not cv2.isContourConvex(approx):
            hull = cv2.convexHull(c)
            approx = cv2.approxPolyDP(hull, 0.02 * cv2.arcLength(hull, True), True)
            if len(approx) != 4:
                continue
        area = cv2.contourArea(approx)
        if area > best_area:
            best, best_area = approx, area
    return best


def detect(img_bgr):
    """4 точки листа в координатах исходного кадра или None."""
    h, w = img_bgr.shape[:2]
    scale = DETECT_SIDE / max(h, w)
    small = cv2.resize(img_bgr, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA)
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (5, 5), 0)
    min_area = 0.2 * small.shape[0] * small.shape[1]
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))

    edges = cv2.Canny(gray, 50, 150)
    edges = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, kernel)
    quad = _best_quad(edges, min_area)
    if quad is None:  # запасной путь: светлый лист на тёмном фоне
        _, th = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        th = cv2.morphologyEx(th, cv2.MORPH_OPEN, kernel)
        quad = _best_quad(th, min_area)
    if quad is None:
        return None
    return order_quad(quad.reshape(4, 2) / scale)


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


def filter_white(img_bgr, gray=False):
    white, black = _levels_from(img_bgr)
    chans = []
    for ch in cv2.split(img_bgr):  # по одному каналу: на телефоне в памяти один float-канал
        f = ch.astype(np.float32) / np.maximum(background(ch).astype(np.float32), 1.0)
        f = np.clip((f - black) / (white - black), 0, 1)
        f = np.power(f, 1.25)
        f = np.maximum(f, np.where(f > 0.94, 1.0, 0.0))
        chans.append(np.clip(f * 255, 0, 255).astype(np.uint8))
    out = cv2.merge(chans)
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
