package app.skaner.cv

import org.json.JSONArray
import kotlin.math.abs

/** Четыре угла листа в координатах изображения: левый-верх, правый-верх, правый-низ, левый-низ. */
data class Quad(val pts: List<Pair<Float, Float>>) {
    init {
        require(pts.size == 4)
    }

    fun ordered(): Quad {
        val tl = pts.minBy { it.first + it.second }
        val br = pts.maxBy { it.first + it.second }
        val tr = pts.minBy { it.second - it.first }
        val bl = pts.maxBy { it.second - it.first }
        return Quad(listOf(tl, tr, br, bl))
    }

    /** Выпуклый, без самопересечений и не крошечный — иначе выпрямлять нельзя. */
    fun isValid(minArea: Float = 1000f): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val c = pts[(i + 2) % 4]
            val cross = (b.first - a.first) * (c.second - b.second) - (b.second - a.second) * (c.first - b.first)
            if (abs(cross) < 1e-3f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return area() >= minArea
    }

    fun area(): Float {
        var s = 0f
        for (i in 0 until 4) {
            val (x1, y1) = pts[i]
            val (x2, y2) = pts[(i + 1) % 4]
            s += x1 * y2 - x2 * y1
        }
        return abs(s) / 2f
    }

    fun scaled(k: Float) = Quad(pts.map { it.first * k to it.second * k })

    fun toJson(): String = JSONArray().apply {
        pts.forEach { put(JSONArray().put(it.first.toDouble()).put(it.second.toDouble())) }
    }.toString()

    companion object {
        fun full(w: Int, h: Int) = Quad(
            listOf(0f to 0f, (w - 1).toFloat() to 0f, (w - 1).toFloat() to (h - 1).toFloat(), 0f to (h - 1).toFloat()),
        )

        fun fromJson(s: String?): Quad? {
            if (s.isNullOrBlank()) return null
            return try {
                val a = JSONArray(s)
                Quad((0 until 4).map { i ->
                    val p = a.getJSONArray(i)
                    p.getDouble(0).toFloat() to p.getDouble(1).toFloat()
                })
            } catch (_: Exception) {
                null
            }
        }
    }
}
