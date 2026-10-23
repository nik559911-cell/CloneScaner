package app.skaner.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.skaner.cv.ImageIo
import app.skaner.cv.Quad
import app.skaner.data.Filters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * S04 — готовая страница: поворот по кругу, фильтр, «ещё страница» / «готово».
 * Поворот сразу показывается на превью, а через паузу страница пересчитывается от оригинала
 * в полном качестве (несколько нажатий подряд — один пересчёт).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterScreen(pageId: String, onMorePages: (String) -> Unit, onDone: (String) -> Unit, onBack: () -> Unit) {
    val repo = repo()
    val scope = rememberCoroutineScope()
    val page by repo.observePage(pageId).collectAsStateWithLifecycle(initialValue = null)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** Поворот, выбранный кнопкой, но ещё не применённый к файлу; -1 — нет. */
    var pendingRotation by remember { mutableIntStateOf(-1) }

    // На случай, если страница ещё не обработана (например, после перезапуска).
    LaunchedEffect(page?.processedPath == null) {
        val p = page ?: return@LaunchedEffect
        if (p.processedPath == null && !busy) {
            busy = true
            runCatching { repo.render(p.id) }.onFailure { error = it.message }
            busy = false
        }
    }

    // Отложенный пересчёт после поворота: ждём, пока перестанут нажимать.
    LaunchedEffect(pendingRotation) {
        if (pendingRotation < 0) return@LaunchedEffect
        delay(700)
        val p = page ?: return@LaunchedEffect
        busy = true
        try {
            repo.updatePage(p.id, Quad.fromJson(p.quad), pendingRotation, p.filter)
            error = null
        } catch (e: Exception) {
            error = e.message
        } finally {
            pendingRotation = -1
            busy = false
        }
    }

    // Превью — готовый файл, повёрнутый в памяти на разницу между выбранным и применённым поворотом.
    val preview by produceState<Bitmap?>(null, page?.processedPath, page?.updatedAt) {
        val path = page?.processedPath
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching { ImageIo.loadUpright(File(path), 2000) }.getOrNull()
        }
    }
    val delta = page?.let { p -> if (pendingRotation < 0) 0 else ((pendingRotation - p.rotation) % 360 + 360) % 360 } ?: 0
    val shown = remember(preview, delta) {
        val b = preview
        if (b == null || delta == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(delta.toFloat()) }, true)
    }

    fun finish(next: (String) -> Unit) {
        val p = page ?: return
        scope.launch {
            busy = true
            try {
                // поворот, который ещё не успел примениться, — применить перед выходом
                if (pendingRotation >= 0) {
                    repo.updatePage(p.id, Quad.fromJson(p.quad), pendingRotation, p.filter)
                    pendingRotation = -1
                }
                repo.finalize(p.documentId)
                next(p.documentId)
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.statusBarsPadding().fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад к границам") }
            Text("Готовая страница", style = MaterialTheme.typography.titleMedium)
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            val b = shown
            if (b != null) {
                Image(b.asImageBitmap(), "Результат", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            }
            if (busy || page?.processedPath == null) CircularProgressIndicator()
        }

        error?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                enabled = page?.processedPath != null && !busy,
                onClick = {
                    val p = page ?: return@FilledTonalButton
                    val from = if (pendingRotation >= 0) pendingRotation else p.rotation
                    pendingRotation = (from + 90) % 360
                },
            ) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Повернуть")
            }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Filters.all.forEach { f ->
                    FilterChip(
                        selected = page?.filter == f,
                        enabled = !busy,
                        onClick = {
                            val p = page ?: return@FilterChip
                            if (p.filter == f) return@FilterChip
                            val rot = if (pendingRotation >= 0) pendingRotation else p.rotation
                            busy = true
                            scope.launch {
                                try {
                                    repo.updatePage(p.id, Quad.fromJson(p.quad), rot, f)
                                    pendingRotation = -1
                                    error = null
                                } catch (e: Exception) {
                                    error = e.message
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        label = { Text(filterNames[f] ?: f) },
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(enabled = !busy && page != null, onClick = { finish(onMorePages) }, modifier = Modifier.weight(1f)) {
                Text("Ещё страница")
            }
            Button(enabled = !busy && page?.processedPath != null, onClick = { finish(onDone) }, modifier = Modifier.weight(1f)) {
                Text("Готово")
            }
        }
    }
}
