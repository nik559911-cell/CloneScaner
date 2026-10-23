package app.skaner.cv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * Сжатие страницы под целевой размер. Подобрано на страницах пользователя (proto/test_compress.py):
 * сначала снижаем качество JPEG при полном разрешении, затем уменьшаем разрешение (0.85, 0.7, 0.6)
 * и снова подбираем качество. Для текста на белом фоне разница на глаз не видна, файл −40%.
 */
object Compressor {
    const val TARGET_RATIO = 0.6
    private val SCALES = doubleArrayOf(1.0, 0.85, 0.7, 0.6)
    private val QUALITIES = intArrayOf(85, 80, 75, 70, 65, 60, 55)

    /** Пишет сжатый JPEG в [out]; возвращает его размер в пикселях. */
    fun compress(src: File, out: File, ratio: Double): Pair<Int, Int> {
        val target = (src.length() * ratio).toLong()
        val full = BitmapFactory.decodeFile(src.absolutePath) ?: error("Не удалось прочитать страницу")
        var best: ByteArray? = null
        var bestW = full.width
        var bestH = full.height
        try {
            for (scale in SCALES) {
                val bmp = if (scale == 1.0) full else Bitmap.createScaledBitmap(
                    full, (full.width * scale).roundToInt(), (full.height * scale).roundToInt(), true,
                )
                try {
                    for (q in QUALITIES) {
                        val bytes = encode(bmp, q)
                        if (best == null || bytes.size < best.size) {
                            best = bytes; bestW = bmp.width; bestH = bmp.height
                        }
                        if (bytes.size <= target) {
                            out.writeBytes(bytes)
                            return bmp.width to bmp.height
                        }
                    }
                } finally {
                    if (bmp !== full) bmp.recycle()
                }
            }
            // цель недостижима — берём самый маленький из полученных вариантов
            out.writeBytes(best!!)
            return bestW to bestH
        } finally {
            full.recycle()
        }
    }

    private fun encode(bmp: Bitmap, q: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
        return bos.toByteArray()
    }
}
