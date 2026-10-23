package app.skaner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.skaner.cv.ImageIo
import app.skaner.cv.Processor
import app.skaner.cv.Quad
import app.skaner.data.PageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

private const val DISPLAY_SIDE = 1600

/** S03 — обрезка: 4 угловых маркера поверх затемнённого фото. Координаты хранятся в пикселях оригинала. */
@Composable
fun CropScreen(pageId: String, onNext: () -> Unit, onBack: () -> Unit) {
    val repo = repo()
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<PageEntity?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val corners = remember { mutableStateListOf<Offset>() }
    var rotation by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pageId) {
        val p = repo.page(pageId) ?: return@LaunchedEffect onBack()
        page = p
        rotation = p.rotation
        val q = (Quad.fromJson(p.quad) ?: Quad.full(p.width, p.height)).ordered()
        corners.clear()
        corners.addAll(q.pts.map { Offset(it.first, it.second) })
        bitmap = withContext(Dispatchers.IO) { ImageIo.loadUpright(File(p.originalPath), DISPLAY_SIDE) }
    }

    val quad = if (corners.size == 4) Quad(corners.map { it.x to it.y }) else null
    val valid = quad?.isValid() == true

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.statusBarsPadding().fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
            Text("Границы листа", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
            val p = page
            val b = bitmap
            if (p == null || b == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
                return@BoxWithConstraints
            }
            val density = LocalDensity.current
            val boxW = with(density) { maxWidth.toPx() }
            val boxH = with(density) { maxHeight.toPx() }
            val scale = min(boxW / p.width, boxH / p.height)
            val ox = (boxW - p.width * scale) / 2
            val oy = (boxH - p.height * scale) / 2
            val touchRadius = with(density) { 40.dp.toPx() }
            val handle = with(density) { 11.dp.toPx() }
            val accent = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

            Image(b.asImageBitmap(), "Снимок", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)

            var active by remember { mutableIntStateOf(-1) }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(scale, ox, oy) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                val screen = corners.map { Offset(ox + it.x * scale, oy + it.y * scale) }
                                val nearest = screen.indices.minByOrNull { (screen[it] - pos).getDistance() }
                                active = if (nearest != null && (screen[nearest] - pos).getDistance() <= touchRadius) nearest else -1
                            },
                            onDragEnd = { active = -1 },
                            onDragCancel = { active = -1 },
                            onDrag = { change, drag ->
                                if (active >= 0) {
                                    change.consume()
                                    val c = corners[active] + drag / scale
                                    corners[active] = Offset(
                                        c.x.coerceIn(0f, (p.width - 1).toFloat()),
                                        c.y.coerceIn(0f, (p.height - 1).toFloat()),
                                    )
                                }
                            },
                        )
                    },
            ) {
                if (corners.size != 4) return@Canvas
                val pts = corners.map { Offset(ox + it.x * scale, oy + it.y * scale) }
                val dim = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(ox, oy, ox + p.width * scale, oy + p.height * scale))
                    moveTo(pts[0].x, pts[0].y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(dim, Color.Black.copy(alpha = 0.5f))
                for (i in 0 until 4) drawLine(accent, pts[i], pts[(i + 1) % 4], strokeWidth = 3.dp.toPx())
                pts.forEachIndexed { i, o ->
                    drawCircle(Color.White, if (i == active) handle * 1.4f else handle, o)
                    drawCircle(accent, if (i == active) handle * 1.4f else handle, o, style = Stroke(3.dp.toPx()))
                }
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (!valid && quad != null) {
            Text("Рамка перекручена — разведите углы", color = Color.White, modifier = Modifier.padding(horizontal = 16.dp))
        }

        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(enabled = !busy, onClick = {
                val p = page ?: return@TextButton
                corners.clear(); corners.addAll(Quad.full(p.width, p.height).pts.map { Offset(it.first, it.second) })
            }) { Text("Весь лист", color = Color.White) }
            TextButton(enabled = !busy, onClick = {
                val p = page ?: return@TextButton
                val b = bitmap ?: return@TextButton
                scope.launch {
                    val found = withContext(Dispatchers.Default) { Processor.detect(b) }
                    if (found == null) {
                        error = "Лист не найден — поправьте углы вручную"
                    } else {
                        error = null
                        val k = p.width.toFloat() / b.width
                        corners.clear(); corners.addAll(found.scaled(k).ordered().pts.map { Offset(it.first, it.second) })
                    }
                }
            }) { Text("Авто", color = Color.White) }
            TextButton(enabled = !busy, onClick = { rotation = (rotation + 90) % 360 }) {
                Text(if (rotation == 0) "Повернуть" else "Поворот $rotation°", color = Color.White)
            }
            Button(enabled = valid && !busy, onClick = {
                val p = page ?: return@Button
                val q = quad ?: return@Button
                busy = true
                scope.launch {
                    try {
                        repo.updatePage(p.id, q, rotation, p.filter)
                        onNext()
                    } catch (e: Exception) {
                        error = "Не удалось обработать: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            }) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Далее")
            }
        }
    }
}
