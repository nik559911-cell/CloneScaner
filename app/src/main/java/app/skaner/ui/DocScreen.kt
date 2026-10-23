package app.skaner.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.skaner.data.Saver
import java.io.File
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.skaner.data.PageEntity
import kotlinx.coroutines.launch

/** S05 — документ: страницы, добавить, удалить, поделиться PDF (S07). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DocScreen(docId: String, addPage: () -> Unit, openPage: (String) -> Unit, onBack: () -> Unit) {
    val repo = repo()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc by repo.observeDocument(docId).collectAsStateWithLifecycle(initialValue = null)
    val pages by repo.observePages(docId).collectAsStateWithLifecycle(initialValue = emptyList())
    val snackbar = remember { SnackbarHostState() }
    var exporting by remember { mutableStateOf(false) }
    var confirmDeleteDoc by remember { mutableStateOf(false) }
    var pageToDelete by remember { mutableStateOf<PageEntity?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<File?>(null) }

    // Android 8–9: «Загрузки» без разрешения недоступны — сохраняем через системный диалог
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val file = pendingSave
        pendingSave = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                Saver.toUri(context, file, uri)
                snackbar.showSnackbar("PDF сохранён (${Saver.sizeLabel(file.length())})")
            } catch (e: Exception) {
                snackbar.showSnackbar("Не удалось сохранить: ${e.message}")
            }
        }
    }

    fun export(then: suspend (File) -> Unit) {
        exporting = true
        scope.launch {
            try {
                then(repo.exportPdf(docId))
            } catch (e: Exception) {
                snackbar.showSnackbar("Не удалось собрать PDF: ${e.message}")
            } finally {
                exporting = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = {
                    Text(
                        doc?.title ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = doc != null) { renaming = true },
                    )
                },
                actions = {
                    IconButton(enabled = doc != null, onClick = { renaming = true }) { Icon(Icons.Filled.Edit, "Переименовать") }
                    IconButton(onClick = { confirmDeleteDoc = true }) { Icon(Icons.Filled.Delete, "Удалить документ") }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    enabled = pages.isNotEmpty() && !exporting,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        export { file ->
                            if (Saver.canSaveDirectly) {
                                val uri = Saver.toDownloads(context, file)
                                val r = snackbar.showSnackbar(
                                    "Сохранено в «Загрузки/${Saver.FOLDER}» (${Saver.sizeLabel(file.length())})",
                                    actionLabel = "Открыть",
                                    duration = SnackbarDuration.Long,
                                )
                                if (r == SnackbarResult.ActionPerformed) openPdf(context, uri)
                            } else {
                                pendingSave = file
                                saveAs.launch(file.name)
                            }
                        }
                    },
                ) { Text("Сохранить") }
                Button(
                    enabled = pages.isNotEmpty() && !exporting,
                    modifier = Modifier.weight(1f),
                    onClick = { export { file -> sharePdf(context, file) } },
                ) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Отправить")
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = addPage,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Страница") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.71f)
                                .combinedClickable(onClick = { openPage(p.id) }, onLongClick = { pageToDelete = p }),
                        ) {
                            FileImage(p.thumbPath, p.updatedAt, 400, Modifier.fillMaxSize(), ContentScale.Fit, "Страница ${i + 1}")
                        }
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (pages.isNotEmpty()) {
                Text(
                    "Нажмите на страницу, чтобы поправить, удерживайте — чтобы удалить",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                )
            }
            if (exporting) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Собираю PDF…", modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }

    if (renaming) {
        val current = doc?.title ?: ""
        var text by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Название скана") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.text.length <= 100) text = it },
                    singleLine = true,
                    isError = text.text.isBlank(),
                    supportingText = { Text("Так будет называться и PDF-файл") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            },
            confirmButton = {
                TextButton(enabled = text.text.isNotBlank(), onClick = {
                    renaming = false
                    scope.launch {
                        runCatching { repo.rename(docId, text.text) }
                            .onFailure { snackbar.showSnackbar(it.message ?: "Не удалось переименовать") }
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Отмена") } },
        )
    }

    if (confirmDeleteDoc) {
        AlertDialog(
            onDismissRequest = { confirmDeleteDoc = false },
            title = { Text("Удалить документ?") },
            text = { Text("Все страницы будут удалены без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteDoc = false
                    scope.launch {
                        repo.deleteDocument(docId)
                        onBack()
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteDoc = false }) { Text("Отмена") } },
        )
    }

    pageToDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pageToDelete = null },
            title = { Text("Удалить страницу?") },
            confirmButton = {
                TextButton(onClick = {
                    pageToDelete = null
                    scope.launch { repo.deletePage(p.id) }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { pageToDelete = null }) { Text("Отмена") } },
        )
    }
}
