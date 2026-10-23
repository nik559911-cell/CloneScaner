package app.skaner.cv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageIo {

    /** Размер картинки без декодирования пикселей. */
    fun size(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return o.outWidth to o.outHeight
    }

    /**
     * Загрузить JPEG, повернуть по EXIF и уменьшить так, чтобы длинная сторона была ≤ maxSide.
     * Сначала грубое уменьшение через inSampleSize, чтобы не держать в памяти 50 Мп.
     */
    fun loadUpright(file: File, maxSide: Int): Bitmap {
        val (w, h) = size(file)
        require(w > 0 && h > 0) { "Не удалось прочитать изображение" }
        var sample = 1
        while (max(w, h) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: error("Не удалось декодировать изображение")

        val orientation = try {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val longSide = max(bmp.width, bmp.height)
        if (longSide > maxSide) {
            val k = maxSide.toFloat() / longSide
            m.postScale(k, k)
        }
        if (!m.isIdentity) {
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out !== bmp) bmp.recycle()
            bmp = out
        }
        return bmp
    }

    /** Запись через временный файл: при сбое не остаётся половины JPEG. */
    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "Не удалось сохранить JPEG" }
        }
        if (file.exists()) file.delete()
        check(tmp.renameTo(file)) { "Не удалось записать ${file.name}" }
    }

    fun thumbnail(bitmap: Bitmap, side: Int = 360): Bitmap {
        val k = side.toFloat() / max(bitmap.width, bitmap.height)
        if (k >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * k).roundToInt().coerceAtLeast(1),
            (bitmap.height * k).roundToInt().coerceAtLeast(1), true,
        )
    }
}
