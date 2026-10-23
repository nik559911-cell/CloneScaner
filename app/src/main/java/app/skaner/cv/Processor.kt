package app.skaner.cv

import android.graphics.Bitmap
import app.skaner.data.Filters
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ядро сканера. Повторяет проверенный прототип proto/pipeline.py шаг в шаг:
 * поиск листа (Detector) → перспектива → выпрямление строк (TextAlign) → поворот кнопкой → фильтр.
 * Все Mat здесь в RGB (у прототипа BGR — для поканальных операций это не важно,
 * для яркости используется COLOR_RGB2GRAY).
 */
object Processor {
    /** Длинная сторона результата: A4 при 300 dpi. */
    const val OUTPUT_MAX_SIDE = 3508

    // ---------- поиск листа ----------

    fun detect(bitmap: Bitmap): Quad? {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        return try {
            detect(rgb)
        } finally {
            rgb.release()
        }
    }

    fun detect(rgb: Mat): Quad? = Detector.detect(rgb)

    // ---------- перспектива и поворот ----------

    fun warp(rgb: Mat, quad: Quad, maxSide: Int = OUTPUT_MAX_SIDE): Mat {
        val q = quad.ordered().pts
        fun d(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot((a.first - b.first).toDouble(), (a.second - b.second).toDouble())
        var outW = (d(q[1], q[0]) + d(q[2], q[3])) / 2
        var outH = (d(q[3], q[0]) + d(q[2], q[1])) / 2
        val k = min(1.0, maxSide / max(outW, outH))
        outW = (outW * k).coerceAtLeast(2.0)
        outH = (outH * k).coerceAtLeast(2.0)
        val w = outW.roundToInt()
        val h = outH.roundToInt()
        val src = MatOfPoint2f(*q.map { Point(it.first.toDouble(), it.second.toDouble()) }.toTypedArray())
        val dst = MatOfPoint2f(
            Point(0.0, 0.0), Point(w - 1.0, 0.0), Point(w - 1.0, h - 1.0), Point(0.0, h - 1.0),
        )
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val out = Mat()
        Imgproc.warpPerspective(rgb, out, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        listOf(src, dst, m).forEach { it.release() }
        return out
    }

    private fun limit(rgb: Mat, maxSide: Int): Mat {
        val longSide = max(rgb.cols(), rgb.rows())
        if (longSide <= maxSide) return rgb
        val k = maxSide.toDouble() / longSide
        val out = Mat()
        Imgproc.resize(rgb, out, Size((rgb.cols() * k).roundToInt().toDouble(), (rgb.rows() * k).roundToInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        rgb.release()
        return out
    }

    private fun rotate(m: Mat, degrees: Int): Mat {
        val code = when (((degrees % 360) + 360) % 360) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return m
        }
        val out = Mat()
        Core.rotate(m, out, code)
        m.release()
        return out
    }

    // ---------- фильтры ----------

    /** Освещение бумаги: уменьшить, закрыть крупным ядром (стираем штрихи), медиана, увеличить. */
    private fun background(channel: Mat): Mat {
        val w = channel.cols()
        val h = channel.rows()
        val k = 512.0 / max(w, h)
        val small = Mat()
        Imgproc.resize(channel, small, Size(max(1, (w * k).roundToInt()).toDouble(), max(1, (h * k).roundToInt()).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0))
        Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.medianBlur(small, small, 21)
        val bg = Mat()
        Imgproc.resize(small, bg, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release()
        kernel.release()
        return bg
    }

    /** Канал, делённый на своё освещение: бумага ≈ 1.0 (CV_32F). */
    private fun flattenChannel(channel: Mat): Mat {
        val bg = background(channel)
        val f = Mat()
        channel.convertTo(f, CvType.CV_32F)
        val bgf = Mat()
        bg.convertTo(bgf, CvType.CV_32F)
        bg.release()
        Core.max(bgf, Scalar(1.0), bgf)
        Core.divide(f, bgf, f)
        bgf.release()
        return f
    }

    /** Точки белого и чёрного по уменьшенной копии (до 1000 px) — экономит память. */
    private fun levels(rgb: Mat): Pair<Double, Double> {
        val k = min(1.0, 1000.0 / max(rgb.cols(), rgb.rows()))
        val small = Mat()
        Imgproc.resize(rgb, small, Size(max(1, (rgb.cols() * k).roundToInt()).toDouble(), max(1, (rgb.rows() * k).roundToInt()).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val chans = ArrayList<Mat>()
        Core.split(small, chans)
        small.release()
        val flat = chans.map { ch -> flattenChannel(ch).also { ch.release() } }
        val merged = Mat()
        Core.merge(flat, merged)
        flat.forEach { it.release() }
        Core.min(merged, Scalar(2.0, 2.0, 2.0), merged)
        Core.max(merged, Scalar(0.0, 0.0, 0.0), merged)
        val lum = Mat()
        Imgproc.cvtColor(merged, lum, Imgproc.COLOR_RGB2GRAY)
        merged.release()
        val lum8 = Mat()
        lum.convertTo(lum8, CvType.CV_8U, 127.5) // 0..2 → 0..255
        lum.release()
        val bytes = ByteArray((lum8.total() * lum8.channels()).toInt())
        lum8.get(0, 0, bytes)
        lum8.release()
        val hist = IntArray(256)
        for (b in bytes) hist[b.toInt() and 0xFF]++
        fun pct(p: Double): Double {
            val target = p / 100.0 * bytes.size
            var acc = 0
            for (i in 0 until 256) {
                acc += hist[i]
                if (acc >= target) return i / 127.5
            }
            return 255 / 127.5
        }
        val white = max(pct(95.0), 0.5)
        val black = min(pct(1.0), white * 0.6)
        return white to black
    }

    /** «Белый фон без серости»: деление на освещение + уровни, бумага → 255, цвет печатей сохраняется. */
    private fun filterWhite(rgb: Mat): Mat {
        val (white, black) = levels(rgb)
        val scale = 1.0 / (white - black)
        val chans = ArrayList<Mat>()
        Core.split(rgb, chans)
        val out = chans.map { ch ->
            val f = flattenChannel(ch)
            ch.release()
            Core.subtract(f, Scalar(black), f)
            Core.multiply(f, Scalar(scale), f)
            Core.min(f, Scalar(1.0), f)
            Core.max(f, Scalar(0.0), f)
            Core.pow(f, 1.25, f)
            val snap = Mat()
            Imgproc.threshold(f, snap, 0.94, 1.0, Imgproc.THRESH_BINARY) // почти белое → чисто белое
            Core.max(f, snap, f)
            snap.release()
            val u8 = Mat()
            f.convertTo(u8, CvType.CV_8U, 255.0)
            f.release()
            u8
        }
        val merged = Mat()
        Core.merge(out, merged)
        out.forEach { it.release() }
        return merged
    }

    private fun filterLighten(rgb: Mat): Mat {
        val chans = ArrayList<Mat>()
        Core.split(rgb, chans)
        val out = chans.map { ch ->
            val f = flattenChannel(ch)
            ch.release()
            val u8 = Mat()
            f.convertTo(u8, CvType.CV_8U, 255.0)
            f.release()
            u8
        }
        val merged = Mat()
        Core.merge(out, merged)
        out.forEach { it.release() }
        return merged
    }

    private fun toGray(rgb: Mat): Mat {
        val g = Mat()
        Imgproc.cvtColor(rgb, g, Imgproc.COLOR_RGB2GRAY)
        return g
    }

    private fun applyFilter(rgb: Mat, filter: String): Mat = when (filter) {
        Filters.ORIGINAL -> rgb.clone()
        Filters.LIGHTEN -> filterLighten(rgb)
        Filters.GRAY -> filterWhite(rgb).let { w -> toGray(w).also { w.release() } }
        Filters.BW -> {
            val w = filterWhite(rgb)
            val g = toGray(w)
            w.release()
            val bw = Mat()
            Imgproc.adaptiveThreshold(g, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 31, 10.0)
            g.release()
            bw
        }
        else -> filterWhite(rgb)
    }

    /** Полный конвейер для одной страницы. quad — в координатах [original]; null — весь кадр. */
    fun process(original: Bitmap, quad: Quad?, rotation: Int, filter: String): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(original, rgba)
        var rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()

        if (quad != null && quad.ordered().isValid()) {
            val warped = warp(rgb, quad)
            rgb.release()
            rgb = warped
        }
        rgb = limit(rgb, OUTPUT_MAX_SIDE)
        rgb = TextAlign.deskew(rgb) // строки по горизонту (до ±15°); от поворота на 90° не зависит
        rgb = rotate(rgb, rotation)  // поворот кнопкой на экране страницы

        val result = applyFilter(rgb, filter)
        rgb.release()
        val bmp = Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(result, bmp)
        result.release()
        return bmp
    }
}
