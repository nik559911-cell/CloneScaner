package app.skaner.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Сохранение PDF на телефон. */
object Saver {
    const val FOLDER = "Сканер"

    /** На Android 10+ можно писать в «Загрузки» без разрешений; на 8–9 используем системный диалог. */
    val canSaveDirectly: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Копия в «Загрузки/Сканер». Если имя занято, система добавит «(1)». */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun toDownloads(context: Context, file: File): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Не удалось создать файл в «Загрузках»")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("Не удалось открыть файл для записи")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Запись в место, выбранное пользователем в системном диалоге. */
    suspend fun toUri(context: Context, file: File, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: error("Не удалось открыть файл для записи")
    }

    fun sizeLabel(bytes: Long): String =
        if (bytes >= 1024 * 1024) String.format(java.util.Locale("ru"), "%.1f МБ", bytes / 1048576.0)
        else "${(bytes + 1023) / 1024} КБ"
}
