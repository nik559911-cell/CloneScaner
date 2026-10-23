package app.skaner.cv

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Поиск листа. Повторяет proto/pipeline.py (candidates/detect) шаг в шаг:
 * 1) стереть текст морфологическим закрытием, иначе Хаф видит «прямые» сквозь строки;
 * 2) кандидаты: замкнутые контуры по краям, маски «бумаги» и сочетания прямых Хафа по сторонам
 *    (куски одной прямой склеиваются; до 6 прямых на сторону + край кадра, если лист уходит за кадр);
 * 3) оценка: опора сторон на края, идущие ВДОЛЬ стороны (узор скатерти даёт края случайных направлений),
 *    × опора самой слабой стороны × контраст с фоном × доля «бумаги» внутри × √площади × штраф за пёстрость
 *    внутри (у листа насыщенность ровная, у скатерти с рисунком — пятна).
 */
object Detector {
    private const val SIDE = 640.0
    private const val NBINS = 8

    private class Cand(val q: Array<DoubleArray>, val key: Double, val support: Double)

    fun detect(rgb: Mat): Quad? {
        val w0 = rgb.cols()
        val h0 = rgb.rows()
        val scale = SIDE / max(w0, h0)
        val small = Mat()
        Imgproc.resize(rgb, small, Size((w0 * scale).roundToInt().toDouble(), (h0 * scale).roundToInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val w = small.cols()
        val h = small.rows()

        val noText = Mat()
        Imgproc.morphologyEx(small, noText, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(11.0, 11.0)))
        val blur = Mat()
        Imgproc.medianBlur(noText, blur, 7)
        val gray = Mat()
        Imgproc.cvtColor(blur, gray, Imgproc.COLOR_RGB2GRAY)
        val hsv = Mat()
        Imgproc.cvtColor(blur, hsv, Imgproc.COLOR_RGB2HSV)
        val ch = ArrayList<Mat>()
        Core.split(hsv, ch)
        val s = ch[1]
        val v = ch[2]
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))

        val e1 = Mat(); Imgproc.Canny(gray, e1, 50.0, 150.0)
        val e2 = Mat(); Imgproc.Canny(gray, e2, 20.0, 60.0)
        val e3 = Mat(); Imgproc.Canny(s, e3, 20.0, 60.0)
        val eGray = Mat(); Core.bitwise_or(e1, e2, eGray)
        val union = Mat(); Core.bitwise_or(eGray, e3, union)

        val paper = Mat()
        Core.subtract(v, s, paper) // бумага: яркая и без цвета
        val k9 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        val masks = listOf(paper, gray).map { src ->
            val m = Mat()
            Imgproc.threshold(src, m, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(m, m, Imgproc.MORPH_OPEN, k9)
            Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, k9)
            m
        }

        val minArea = 0.2 * w * h
        val quads = ArrayList<Array<DoubleArray>>()
        fun fromContours(bin: Mat, mode: Int) {
            val contours = ArrayList<MatOfPoint>()
            val hier = Mat()
            Imgproc.findContours(bin, contours, hier, mode, Imgproc.CHAIN_APPROX_SIMPLE)
            hier.release()
            for (c in contours) {
                if (hullArea(c) >= minArea) quadFromContour(c)?.let { quads.add(it) }
                c.release()
            }
        }
        for (e in listOf(e1, e2, e3)) {
            val closed = Mat()
            Imgproc.morphologyEx(e, closed, Imgproc.MORPH_CLOSE, k5)
            fromContours(closed, Imgproc.RETR_LIST)
            closed.release()
        }
        for (m in masks) fromContours(m, Imgproc.RETR_EXTERNAL)
        quads.addAll(houghQuads(union, w, h))

        val maps = orientedEdges(gray, s, eGray, e3, w, h)
        val pre = ArrayList<Cand>()
        for (raw in quads) {
            val q = order(raw)
            if (!convex(q) || area(q) < minArea || hugsFrame(q, w, h)) continue
            val sup = sideSupport(q, maps, w, h)
            if (sup.realSides < 2) continue
            pre.add(Cand(q, sup.avg * sup.min, sup.avg))
        }
        pre.sortByDescending { it.key }

        val sBytes = ByteArray(w * h).also { s.get(0, 0, it) }
        val paperBytes = ByteArray(w * h).also { masks[0].get(0, 0, it) }

        var best: Array<DoubleArray>? = null
        var bestScore = -1.0
        var bestSupport = 0.0
        for (c in pre.take(40)) { // дорогая оценка — только для лучших по опоре на края
            val con = paperContrast(c.q, v, s, w, h)
            if (con < 0.04) continue // внутри должно быть светлее и бесцветнее, чем снаружи
            val (frac, colorful) = insideStats(c.q, sBytes, paperBytes, w, h)
            val score = c.key * (0.8 + con) * frac * sqrt(area(c.q) / (w * h)) * exp(-12 * colorful)
            if (score > bestScore) {
                bestScore = score
                best = c.q
                bestSupport = c.support
            }
        }

        (listOf(small, noText, blur, gray, hsv, k5, k9, e1, e2, e3, eGray, union, paper) + ch + masks).forEach { it.release() }

        if (best == null || bestSupport < 0.45) return null
        return Quad(best.map { (it[0] / scale).toFloat() to (it[1] / scale).toFloat() }).ordered()
    }

    // ---------- края по направлению ----------

    /**
     * 8 карт краёв по направлению градиента (0..180°, по 22.5°), расширенных 5×5, как массивы байт.
     * Сторона листа поддерживается только краями, идущими вдоль неё.
     */
    private fun orientedEdges(gray: Mat, s: Mat, edgeGray: Mat, edgeS: Mat, w: Int, h: Int): Array<ByteArray> {
        val raw = Array(NBINS) { ByteArray(w * h) }
        for ((img, edges) in listOf(gray to edgeGray, s to edgeS)) {
            val gx = Mat(); Imgproc.Sobel(img, gx, CvType.CV_32F, 1, 0, 3)
            val gy = Mat(); Imgproc.Sobel(img, gy, CvType.CV_32F, 0, 1, 3)
            val ang = Mat()
            Core.phase(gx, gy, ang, true) // 0..360°
            val a = FloatArray(w * h).also { ang.get(0, 0, it) }
            val e = ByteArray(w * h).also { edges.get(0, 0, it) }
            for (i in 0 until w * h) {
                if (e[i].toInt() == 0) continue
                val deg = ((a[i] % 180f) + 180f) % 180f
                val b = min((deg / (180f / NBINS)).toInt(), NBINS - 1)
                raw[b][i] = 255.toByte()
            }
            listOf(gx, gy, ang).forEach { it.release() }
        }
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val out = Array(NBINS) { k ->
            val m = Mat(h, w, CvType.CV_8U)
            m.put(0, 0, raw[k])
            val d = Mat()
            Imgproc.dilate(m, d, k5)
            val bytes = ByteArray(w * h).also { d.get(0, 0, it) }
            m.release(); d.release()
            bytes
        }
        k5.release()
        return out
    }

    private class Support(val avg: Double, val min: Double, val realSides: Int)

    /**
     * Средняя опора, опора самой слабой стороны и число сторон с настоящим краем.
     * Край засчитывается, только если его градиент перпендикулярен стороне (±1 корзина из 8).
     * Точки на краю кадра: в среднем — слабые 0.35, для самой слабой стороны — полноценные (лист уходит за кадр).
     */
    private fun sideSupport(q: Array<DoubleArray>, maps: Array<ByteArray>, w: Int, h: Int): Support {
        var hit = 0.0
        var tot = 0
        var realSides = 0
        var sideMin = 1.0
        for (i in 0 until 4) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            val dx = b[0] - a[0]
            val dy = b[1] - a[1]
            val normal = ((Math.toDegrees(atan2(dx, -dy)) + 180.0) % 180.0)
            val kb = (normal / (180.0 / NBINS)).toInt() % NBINS
            val bins = intArrayOf((kb - 1 + NBINS) % NBINS, kb, (kb + 1) % NBINS)
            val n = max(8, (hypot(dx, dy) / 3).toInt())
            var sideHit = 0.0
            var sideReal = 0
            var sideBorder = 0
            var sideTot = 0
            for (j in 0 until n) {
                val t = 0.05 + 0.9 * j / (n - 1)
                val x = (a[0] + dx * t).roundToInt()
                val y = (a[1] + dy * t).roundToInt()
                if (x !in 0 until w || y !in 0 until h) continue
                sideTot++
                val idx = y * w + x
                val onBorder = x <= 2 || y <= 2 || x >= w - 3 || y >= h - 3
                if (bins.any { maps[it][idx].toInt() != 0 }) {
                    sideHit += 1.0; sideReal++
                } else if (onBorder) {
                    sideHit += 0.35; sideBorder++
                }
            }
            hit += sideHit
            tot += sideTot
            if (sideTot > 0) {
                if (sideReal.toDouble() / sideTot >= 0.5) realSides++
                sideMin = min(sideMin, (sideReal + sideBorder).toDouble() / sideTot)
            }
        }
        return Support(hit / max(tot, 1), sideMin, realSides)
    }

    /**
     * (доля «бумаги» внутри рамки, доля пёстрых пикселей внутри рамки).
     * Пёстрый — насыщенность выше медианы внутри рамки на 12+.
     */
    private fun insideStats(q: Array<DoubleArray>, s: ByteArray, paper: ByteArray, w: Int, h: Int): Pair<Double, Double> {
        val inside = Mat.zeros(h, w, CvType.CV_8U)
        Imgproc.fillConvexPoly(inside, MatOfPoint(*q.map { Point(it[0].roundToInt().toDouble(), it[1].roundToInt().toDouble()) }.toTypedArray()), Scalar(255.0))
        val m = ByteArray(w * h).also { inside.get(0, 0, it) }
        inside.release()
        val hist = IntArray(256)
        var n = 0
        var paperN = 0
        for (i in 0 until w * h) {
            if (m[i].toInt() == 0) continue
            n++
            hist[s[i].toInt() and 0xFF]++
            if (paper[i].toInt() != 0) paperN++
        }
        if (n == 0) return 0.0 to 1.0
        // медиана как у numpy: при чётном числе — среднее двух средних значений
        fun nth(k: Int): Int {
            var acc = 0
            for (i in 0 until 256) {
                acc += hist[i]
                if (acc > k) return i
            }
            return 255
        }
        val median = (nth((n - 1) / 2) + nth(n / 2)) / 2.0
        var colorful = 0
        for (i in 0 until 256) if (i > median + 12) colorful += hist[i]
        return paperN.toDouble() / n to colorful.toDouble() / n
    }

    private fun paperContrast(q: Array<DoubleArray>, v: Mat, s: Mat, w: Int, h: Int): Double {
        val inside = Mat.zeros(h, w, CvType.CV_8U)
        Imgproc.fillConvexPoly(inside, MatOfPoint(*q.map { Point(it[0].roundToInt().toDouble(), it[1].roundToInt().toDouble()) }.toTypedArray()), Scalar(255.0))
        val k = max(5, (0.04 * max(h, w)).toInt() or 1)
        val dil = Mat()
        val kernel = Mat.ones(k, k, CvType.CV_8U)
        Imgproc.dilate(inside, dil, kernel)
        val notInside = Mat()
        Core.bitwise_not(inside, notInside)
        val ring = Mat()
        Core.bitwise_and(dil, notInside, ring)
        val result = if (Core.countNonZero(ring) == 0) {
            0.3 // лист во весь кадр — снаружи сравнивать не с чем
        } else {
            val di = Core.mean(v, inside).`val`[0] - Core.mean(v, ring).`val`[0]
            val ds = Core.mean(s, ring).`val`[0] - Core.mean(s, inside).`val`[0]
            max(0.0, di) / 255 + max(0.0, ds) / 255
        }
        listOf(inside, dil, kernel, notInside, ring).forEach { it.release() }
        return result
    }

    // ---------- геометрия ----------

    /** Углы по часовой: левый-верх, правый-верх, правый-низ, левый-низ. */
    private fun order(p: Array<DoubleArray>): Array<DoubleArray> {
        val tl = p.minBy { it[0] + it[1] }
        val br = p.maxBy { it[0] + it[1] }
        val tr = p.minBy { it[1] - it[0] }
        val bl = p.maxBy { it[1] - it[0] }
        return arrayOf(tl, tr, br, bl)
    }

    private fun area(q: Array<DoubleArray>): Double {
        var s = 0.0
        for (i in 0 until 4) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            s += a[0] * b[1] - b[0] * a[1]
        }
        return abs(s) / 2
    }

    private fun convex(q: Array<DoubleArray>): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            val c = q[(i + 2) % 4]
            val cross = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
            if (abs(cross) < 1e-6) return false
            val sg = if (cross > 0) 1 else -1
            if (sign == 0) sign = sg else if (sg != sign) return false
        }
        return true
    }

    /** Рамка совпадает с самим кадром — это не лист. */
    private fun hugsFrame(q: Array<DoubleArray>, w: Int, h: Int): Boolean {
        val m = 0.02 * max(w, h)
        val corners = arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(w - 1.0, 0.0), doubleArrayOf(w - 1.0, h - 1.0), doubleArrayOf(0.0, h - 1.0))
        return (0 until 4).all { hypot(q[it][0] - corners[it][0], q[it][1] - corners[it][1]) < m }
    }

    private fun hullArea(c: MatOfPoint): Double {
        val idx = MatOfInt()
        Imgproc.convexHull(c, idx)
        val src = c.toArray()
        val hull = MatOfPoint2f(*idx.toArray().map { Point(src[it].x, src[it].y) }.toTypedArray())
        val a = Imgproc.contourArea(hull)
        idx.release(); hull.release()
        return a
    }

    private fun approx4(points: Array<Point>): Array<Point>? {
        if (points.size < 4) return null
        val curve = MatOfPoint2f(*points)
        val out = MatOfPoint2f()
        Imgproc.approxPolyDP(curve, out, 0.02 * Imgproc.arcLength(curve, true), true)
        val res = out.toArray()
        curve.release(); out.release()
        return if (res.size == 4) res else null
    }

    private fun quadFromContour(c: MatOfPoint): Array<DoubleArray>? {
        val pts = c.toArray()
        var a = approx4(pts)
        if (a == null || !Imgproc.isContourConvex(MatOfPoint(*a))) {
            val idx = MatOfInt()
            Imgproc.convexHull(c, idx)
            val hull = idx.toArray().map { pts[it] }.toTypedArray()
            idx.release()
            a = approx4(hull) ?: return null
        }
        return a.map { doubleArrayOf(it.x, it.y) }.toTypedArray()
    }

    // ---------- прямые Хафа ----------

    private fun intersect(a: DoubleArray, b: DoubleArray): DoubleArray? {
        val (x1, y1, x2, y2) = a
        val (x3, y3, x4, y4) = b
        val d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (abs(d) < 1e-6) return null
        val t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / d
        return doubleArrayOf(x1 + t * (x2 - x1), y1 + t * (y2 - y1))
    }

    private class Cluster(var weight: Double, val angle: Double, val lines: MutableList<DoubleArray>)

    /**
     * Склеить куски одной прямой: угол отличается < 3°, середина куска лежит в 1% кадра от прямой кластера.
     * Вес — суммарная длина; прямая — по всем концам кусков (fitLine).
     */
    private fun clusterLines(ls: List<Pair<Double, DoubleArray>>, w: Int, h: Int): List<Pair<Double, DoubleArray>> {
        val tol = 0.01 * max(w, h)
        val clusters = ArrayList<Cluster>()
        for ((length, l) in ls.sortedByDescending { it.first }) {
            val (x1, y1, x2, y2) = l
            val ang = ((Math.toDegrees(atan2(y2 - y1, x2 - x1)) % 180) + 180) % 180
            val mx = (x1 + x2) / 2
            val my = (y1 + y2) / 2
            val target = clusters.firstOrNull { c ->
                val da = abs(ang - c.angle) % 180
                if (min(da, 180 - da) > 3) return@firstOrNull false
                val (cx1, cy1, cx2, cy2) = c.lines[0]
                val dist = abs((cy2 - cy1) * mx - (cx2 - cx1) * my + cx2 * cy1 - cy2 * cx1) / max(hypot(cx2 - cx1, cy2 - cy1), 1e-6)
                dist < tol
            }
            if (target != null) {
                target.weight += length
                target.lines.add(l)
            } else {
                clusters.add(Cluster(length, ang, mutableListOf(l)))
            }
        }
        return clusters.sortedByDescending { it.weight }.map { c ->
            val pts = MatOfPoint2f(*(c.lines.map { Point(it[0], it[1]) } + c.lines.map { Point(it[2], it[3]) }).toTypedArray())
            val line = Mat()
            Imgproc.fitLine(pts, line, Imgproc.DIST_L2, 0.0, 0.01, 0.01)
            val f = FloatArray(4).also { line.get(0, 0, it) }
            pts.release(); line.release()
            val (vx, vy, x0, y0) = f.map { it.toDouble() }
            c.weight to doubleArrayOf(x0 - vx * 100, y0 - vy * 100, x0 + vx * 100, y0 + vy * 100)
        }
    }

    private fun houghQuads(edges: Mat, w: Int, h: Int): List<Array<DoubleArray>> {
        val lines = Mat()
        Imgproc.HoughLinesP(edges, lines, 1.0, PI / 180, 40, (0.08 * min(w, h)).toInt().toDouble(), (0.03 * max(w, h)).toInt().toDouble())
        val groups: Map<String, ArrayList<Pair<Double, DoubleArray>>> = listOf("top", "bottom", "left", "right")
            .associateWith { ArrayList<Pair<Double, DoubleArray>>() }
        for (i in 0 until lines.rows()) {
            // обычно N×1 с 4 каналами; на всякий случай поддерживаем и N×4 с одним каналом
            val l: DoubleArray = if (lines.channels() >= 4) {
                lines.get(i, 0) ?: continue
            } else if (lines.cols() >= 4) {
                DoubleArray(4) { j -> lines.get(i, j)?.get(0) ?: 0.0 }
            } else {
                continue
            }
            if (l.size < 4) continue
            val (x1, y1, x2, y2) = l
            val ang = abs(Math.toDegrees(atan2(y2 - y1, x2 - x1))) % 180
            val len = hypot(x2 - x1, y2 - y1)
            val line = doubleArrayOf(x1, y1, x2, y2)
            if (ang < 35 || ang > 145) {
                groups.getValue(if ((y1 + y2) / 2 < h / 2.0) "top" else "bottom").add(len to line)
            } else if (ang > 55 && ang < 125) {
                groups.getValue(if ((x1 + x2) / 2 < w / 2.0) "left" else "right").add(len to line)
            }
        }
        lines.release()
        val frame = mapOf(
            "top" to doubleArrayOf(0.0, 0.0, w - 1.0, 0.0), "bottom" to doubleArrayOf(0.0, h - 1.0, w - 1.0, h - 1.0),
            "left" to doubleArrayOf(0.0, 0.0, 0.0, h - 1.0), "right" to doubleArrayOf(w - 1.0, 0.0, w - 1.0, h - 1.0),
        )
        val minWeight = 0.15 * min(w, h)
        val sides = groups.mapValues { (side, ls) ->
            clusterLines(ls, w, h).filter { it.first >= minWeight }.take(6).map { it.second } + frame.getValue(side)
        }
        val res = ArrayList<Array<DoubleArray>>()
        for (t in sides.getValue("top")) for (b in sides.getValue("bottom")) for (l in sides.getValue("left")) for (r in sides.getValue("right")) {
            val pts = listOf(intersect(t, l), intersect(t, r), intersect(b, r), intersect(b, l))
            if (pts.any { it == null }) continue
            val q = pts.map { it!! }
            if (q.any { it[0] < -0.05 * w || it[0] > 1.05 * w || it[1] < -0.05 * h || it[1] > 1.05 * h }) continue
            res.add(q.map { doubleArrayOf(it[0].coerceIn(0.0, w - 1.0), it[1].coerceIn(0.0, h - 1.0)) }.toTypedArray())
        }
        return res
    }
}
