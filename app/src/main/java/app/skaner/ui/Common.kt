package app.skaner.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.core.content.FileProvider
import app.skaner.App
import app.skaner.cv.ImageIo
import app.skaner.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun repo(): Repo = (LocalContext.current.applicationContext as App).repo

fun Context.app(): App = applicationContext as App

/** Картинка из файла, загружается не в главном потоке; key меняется — перечитываем. */
@Composable
fun FileImage(path: String?, key: Any?, maxSide: Int, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit, description: String? = null) {
    val bmp by produceState<Bitmap?>(null, path, key) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching { ImageIo.loadUpright(File(path), maxSide) }.getOrNull()
        }
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), description, modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

fun sharePdf(context: Context, file: File) = shareFile(context, file, "application/pdf", "Отправить PDF")

fun shareFile(context: Context, file: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

val filterNames = mapOf(
    "white" to "Белый фон",
    "original" to "Как есть",
    "lighten" to "Светлее",
    "gray" to "Серый",
    "bw" to "Ч/Б",
)

/** Открыть сохранённый PDF во внешнем просмотрщике (если он есть). */
fun openPdf(context: Context, uri: android.net.Uri) {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(view)
    } catch (_: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "Нет приложения для просмотра PDF", android.widget.Toast.LENGTH_SHORT).show()
    }
}
