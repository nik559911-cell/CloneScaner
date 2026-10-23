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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Поиск листа. Повторяет proto/pipeline.py (candidates/detect) шаг в шаг:
 * 1) стереть текст морфологическим закрытием, иначе Хаф видит «прямые» сквозь строки;
 * 2) кандидаты: замкнутые контуры по краям (яркость, насыщенность), маски «бумаги» и сочетания
 *    прямых Хафа по сторонам; край кадра — запасная сторона, если лист уходит за кадр;
 * 3) оценка: опора сторон на настоящие края × «бумажность» (внутри светлее и бесцветнее, чем снаружи) × √площади.
 */
object Detector {
    private const val SIDE = 640.0

    private class Cand(val q: Array<DoubleArray>, val support: Double)

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
        val union = Mat()
        Core.bitwise_or(e1, e2, union)
        Core.bitwise_or(union, e3, union)
        val supportMat = Mat()
        Imgproc.dilate(union, supportMat, k5)
        val support = ByteArray(w * h)
        supportMat.get(0, 0, support)

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
                val hullArea = hullArea(c)
                if (hullArea >= minArea) quadFromContour(c)?.let { quads.add(it) }
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

        val pre = ArrayList<Cand>()
        for (raw in quads) {
            val q = order(raw)
            if (!convex(q) || area(q) < minArea || hugsFrame(q, w, h)) continue
            val (sup, realSides) = sideSupport(q, support, w, h)
            if (realSides < 2) continue
            pre.add(Cand(q, sup))
        }
        pre.sortByDescending { it.support }

        var best: Array<DoubleArray>? = null
        var bestScore = -1.0
        var bestSupport = 0.0
        for (c in pre.take(15)) { // дорогая оценка — только для лучших по опоре на края
            val con = paperContrast(c.q, v, s, w, h)
            if (con < 0.04) continue // внутри должно быть светлее и бесцветнее, чем снаружи
            val score = c.support * (0.4 + con) * sqrt(area(c.q) / (w * h))
            if (score > bestScore) {
                bestScore = score
                best = c.q
                bestSupport = c.support
            }
        }

        (listOf(small, noText, blur, gray, hsv, k5, k9, e1, e2, e3, union, supportMat, paper) + ch + masks).forEach { it.release() }

        if (best == null || bestSupport < 0.45) return null
        return Quad(best.map { (it[0] / scale).toFloat() to (it[1] / scale).toFloat() }).ordered()
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

    /** (общая опора, число сторон с настоящим краем). Край кадра — слабая опора 0.35. */
    private fun sideSupport(q: Array<DoubleArray>, edges: ByteArray, w: Int, h: Int): Pair<Double, Int> {
        var hit = 0.0
        var tot = 0
        var realSides = 0
        for (i in 0 until 4) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            val len = hypot(b[0] - a[0], b[1] - a[1])
            val n = max(8, (len / 3).toInt())
            var sideHit = 0.0
            var sideReal = 0
            var sideTot = 0
            for (j in 0 until n) {
                val t = 0.05 + 0.9 * j / (n - 1)
                val x = (a[0] + (b[0] - a[0]) * t).roundToInt()
                val y = (a[1] + (b[1] - a[1]) * t).roundToInt()
                if (x !in 0 until w || y !in 0 until h) continue
                sideTot++
                val onBorder = x <= 2 || y <= 2 || x >= w - 3 || y >= h - 3
                if (edges[y * w + x].toInt() != 0) {
                    sideHit += 1.0; sideReal++
                } else if (onBorder) {
                    sideHit += 0.35
                }
            }
            hit += sideHit
            tot += sideTot
            if (sideTot > 0 && sideReal.toDouble() / sideTot >= 0.5) realSides++
        }
        return hit / max(tot, 1) to realSides
    }

    private fun paperContrast(q: Array<DoubleArray>, v: Mat, s: Mat, w: Int, h: Int): Double {
        val inside = Mat.zeros(h, w, CvType.CV_8U)
        Imgproc.fillConvexPoly(inside, MatOfPoint(*q.map { Point(it[0].roundToInt().toDouble(), it[1].roundToInt().toDouble()) }.toTypedArray()), Scalar(255.0))
        val k = max(5, (0.04 * max(h, w)).toInt() or 1)
        val dil = Mat()
        Imgproc.dilate(inside, dil, Mat.ones(k, k, CvType.CV_8U))
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
        listOf(inside, dil, notInside, ring).forEach { it.release() }
        return result
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

    private fun houghQuads(edges: Mat, w: Int, h: Int): List<Array<DoubleArray>> {
        val lines = Mat()
        Imgproc.HoughLinesP(edges, lines, 1.0, PI / 180, 40, (0.15 * min(w, h)).toInt().toDouble(), (0.03 * max(w, h)).toInt().toDouble())
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
        val sides = groups.mapValues { (side, ls) ->
            ls.sortByDescending { it.first }
            val picked = ArrayList<DoubleArray>()
            for ((_, l) in ls) { // не брать почти одинаковые прямые
                val mx = (l[0] + l[2]) / 2
                val my = (l[1] + l[3]) / 2
                if (picked.all { p -> hypot(mx - (p[0] + p[2]) / 2, my - (p[1] + p[3]) / 2) > 0.03 * max(w, h) }) picked.add(l)
                if (picked.size == 3) break
            }
            picked + frame.getValue(side)
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
