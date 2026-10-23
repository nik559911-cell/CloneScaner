package app.skaner.cv

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Выравнивание текста. Повторяет proto/pipeline.py (ink_mask, skew_angle, upside_down_votes,
 * orientation, deskew) шаг в шаг. Угол — в градусах против часовой, как у getRotationMatrix2D.
 */
object TextAlign {

    /** Маска чернил на уменьшенной копии; поля по 3% срезаны (края листа — не текст). */
    fun inkMask(rgb: Mat, side: Int): Mat {
        val g = Mat()
        if (rgb.channels() == 1) rgb.copyTo(g) else Imgproc.cvtColor(rgb, g, Imgproc.COLOR_RGB2GRAY)
        val k = min(1.0, side.toDouble() / max(g.cols(), g.rows()))
        val small = Mat()
        Imgproc.resize(g, small, Size(max(1, (g.cols() * k).roundToInt()).toDouble(), max(1, (g.rows() * k).roundToInt()).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        g.release()
        val m = Mat()
        Imgproc.adaptiveThreshold(small, m, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 25, 15.0)
        small.release()
        val bh = (0.03 * m.rows()).toInt()
        val bw = (0.03 * m.cols()).toInt()
        if (bh > 0) {
            m.submat(0, bh, 0, m.cols()).setTo(Scalar(0.0))
            m.submat(m.rows() - bh, m.rows(), 0, m.cols()).setTo(Scalar(0.0))
        }
        if (bw > 0) {
            m.submat(0, m.rows(), 0, bw).setTo(Scalar(0.0))
            m.submat(0, m.rows(), m.cols() - bw, m.cols()).setTo(Scalar(0.0))
        }
        return m
    }

    private fun rot(m: Mat, angle: Double): Mat {
        val r = Imgproc.getRotationMatrix2D(Point(m.cols() / 2.0, m.rows() / 2.0), angle, 1.0)
        val out = Mat()
        Imgproc.warpAffine(m, out, r, m.size(), Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0))
        r.release()
        return out
    }

    private fun rotateCw(m: Mat, deg: Int): Mat {
        val out = Mat()
        when (((deg % 360) + 360) % 360) {
            90 -> Core.rotate(m, out, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(m, out, Core.ROTATE_180)
            270 -> Core.rotate(m, out, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> m.copyTo(out)
        }
        return out
    }

    private fun rowSums(m: Mat): DoubleArray {
        val r = Mat()
        Core.reduce(m, r, 1, Core.REDUCE_SUM, CvType.CV_64F)
        val out = DoubleArray(r.rows())
        r.get(0, 0, out)
        r.release()
        return out
    }

    /** Чем резче чередуются строки и просветы, тем больше; нормировано на количество чернил. */
    private fun rowScore(m: Mat): Double {
        val p = rowSums(m)
        val tot = p.sum()
        if (tot == 0.0) return 0.0
        var s = 0.0
        for (i in 1 until p.size) {
            val d = (p[i] - p[i - 1]) / tot
            s += d * d
        }
        return s * p.size
    }

    private fun scoreAt(m: Mat, a: Double): Double {
        val r = rot(m, a)
        val s = rowScore(r)
        r.release()
        return s
    }

    /** (угол, оценка): поворот, выпрямляющий строки. Сначала шаг 0.5°, затем 0.1° вокруг лучшего. */
    fun skewAngle(m: Mat, limit: Double): Pair<Double, Double> {
        var bestA = 0.0
        var bestS = rowScore(m)
        var a = -limit
        while (a <= limit + 0.01) {
            val s = scoreAt(m, a)
            if (s > bestS) { bestA = a; bestS = s }
            a += 0.5
        }
        val center = bestA
        a = center - 0.5
        while (a <= center + 0.51) {
            val s = scoreAt(m, a)
            if (s > bestS) { bestA = a; bestS = s }
            a += 0.1
        }
        return bestA to bestS
    }

    private fun lines(m: Mat): List<IntRange> {
        val p = rowSums(m)
        val mx = p.maxOrNull() ?: 0.0
        if (mx <= 0) return emptyList()
        val out = ArrayList<IntRange>()
        var y = 0
        while (y < p.size) {
            if (p[y] > 0.15 * mx) {
                val y0 = y
                while (y < p.size && p[y] > 0.15 * mx) y++
                if (y - y0 >= 3) out.add(y0 until y)
            }
            y++
        }
        return out
    }

    /**
     * > 0 — текст, скорее всего, вверх ногами. Над основной высотой строки (заглавные, цифры, б, й, d, f…)
     * чернил больше, чем под ней (р, у, д…). Голосуем по строкам и по общей массе.
     */
    fun upsideDownVotes(m: Mat): Double {
        val ls = lines(m)
        if (ls.size < 3) return 0.0
        val all = rowSums(m)
        var votes = 0
        var n = 0
        var aboveT = 0.0
        var belowT = 0.0
        for (l in ls) {
            val hgt = l.last + 1 - l.first
            val a0 = max(0, l.first - hgt / 2)
            val a1 = min(m.rows(), l.last + 1 + hgt / 2)
            val prof = all.copyOfRange(a0, a1)
            val mx = prof.maxOrNull() ?: 0.0
            if (mx == 0.0) continue
            val core = prof.indices.filter { prof[it] >= 0.5 * mx }
            if (core.isEmpty()) continue
            var above = 0.0
            for (i in 0 until core.first()) above += prof[i]
            var below = 0.0
            for (i in core.last() + 1 until prof.size) below += prof[i]
            if (above + below < 0.05 * prof.sum()) continue
            n++
            votes += if (below > above) 1 else -1
            aboveT += above
            belowT += below
        }
        if (n < 3) return 0.0
        return 0.5 * votes / n + 0.5 * (belowT - aboveT) / max(aboveT + belowT, 1.0)
    }

    /** Сколько градусов по часовой повернуть обрезанный лист (0/90/180/270), чтобы текст стоял прямо. */
    fun orientation(rgb: Mat): Int {
        val m = inkMask(rgb, 800)
        val (_, s0) = skewAngle(m, 10.0)
        val m90 = rotateCw(m, 90)
        val (_, s90) = skewAngle(m90, 10.0)
        m90.release()
        val options = if (s90 > 1.25 * s0) intArrayOf(90, 270) else intArrayOf(0, 180)
        val votes = options.map { o ->
            val mo = rotateCw(m, o)
            val (a, _) = skewAngle(mo, 10.0)
            val r = rot(mo, a)
            mo.release()
            upsideDownVotes(r).also { r.release() }
        }
        m.release()
        return if (votes[1] < votes[0] - 0.1) options[1] else options[0]
    }

    /** Выпрямить строки (до ±limit°); края заполняются соседними пикселями. Исходный Mat освобождается. */
    fun deskew(img: Mat, limit: Double = 15.0): Mat {
        val m = inkMask(img, 1000)
        val (a, _) = skewAngle(m, limit)
        m.release()
        if (abs(a) < 0.2) return img
        val r = Imgproc.getRotationMatrix2D(Point(img.cols() / 2.0, img.rows() / 2.0), a, 1.0)
        val out = Mat()
        Imgproc.warpAffine(img, out, r, img.size(), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        r.release()
        img.release()
        return out
    }
}
