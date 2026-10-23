package app.skaner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.skaner.cv.Quad
import app.skaner.data.Filters
import kotlinx.coroutines.launch

/** S04 — вид страницы: фильтр и «ещё страница» / «готово». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterScreen(pageId: String, onMorePages: (String) -> Unit, onDone: (String) -> Unit, onBack: () -> Unit) {
    val repo = repo()
    val scope = rememberCoroutineScope()
    val page by repo.observePage(pageId).collectAsStateWithLifecycle(initialValue = null)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // На случай, если страница ещё не обработана (например, после перезапуска).
    LaunchedEffect(page?.processedPath == null) {
        val p = page ?: return@LaunchedEffect
        if (p.processedPath == null && !busy) {
            busy = true
            runCatching { repo.render(p.id) }.onFailure { error = it.message }
            busy = false
        }
    }

    fun finish(next: (String) -> Unit) {
        val p = page ?: return
        scope.launch {
            repo.finalize(p.documentId)
            next(p.documentId)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.statusBarsPadding().fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад к границам") }
            Text("Вид страницы", style = MaterialTheme.typography.titleMedium)
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            val p = page
            FileImage(p?.processedPath, p?.updatedAt, 2000, Modifier.fillMaxSize(), description = "Результат")
            if (busy || p?.processedPath == null) CircularProgressIndicator()
        }

        error?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Filters.all.forEach { f ->
                FilterChip(
                    selected = page?.filter == f,
                    enabled = !busy,
                    onClick = {
                        val p = page ?: return@FilterChip
                        if (p.filter == f) return@FilterChip
                        busy = true
                        scope.launch {
                            try {
                                repo.updatePage(p.id, Quad.fromJson(p.quad), p.rotation, f)
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
