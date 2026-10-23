package app.skaner.data

import android.content.Context
import android.net.Uri
import app.skaner.cv.Compressor
import app.skaner.cv.ImageIo
import app.skaner.cv.Processor
import app.skaner.cv.Quad
import app.skaner.pdf.PdfWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Хранение файлов: filesDir/docs/<docId>/<pageId>.{orig,thumb,}.jpg; экспорт — в cache/export. */
class FileStore(context: Context) {
    private val root = File(context.filesDir, "docs")
    val exportDir = File(context.cacheDir, "export")
    val incomingDir = File(context.cacheDir, "incoming")

    fun docDir(docId: String) = File(root, docId).apply { mkdirs() }
    fun original(docId: String, pageId: String) = File(docDir(docId), "$pageId.orig.jpg")
    fun processed(docId: String, pageId: String) = File(docDir(docId), "$pageId.jpg")
    fun thumb(docId: String, pageId: String) = File(docDir(docId), "$pageId.thumb.jpg")
    fun newIncoming() = File(incomingDir.apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
    fun allFiles(): List<File> = root.walkTopDown().filter { it.isFile }.toList()
}

class Repo(private val context: Context, private val dao: ScanDao, private val files: FileStore) {

    /** Тяжёлая обработка по одной странице за раз — иначе несколько 12-Мп кадров не влезут в память. */
    private val cpu = Mutex()

    fun observeDocuments(): Flow<List<DocSummary>> = dao.observeSummaries()
    fun observeDocument(id: String): Flow<DocumentEntity?> = dao.observeDocument(id)
    fun observePages(docId: String): Flow<List<PageEntity>> = dao.observePages(docId)
    fun observePage(id: String): Flow<PageEntity?> = dao.observePage(id)
    suspend fun page(id: String): PageEntity? = dao.getPage(id)

    fun newCaptureFile(): File = files.newIncoming()

    suspend fun createDraft(): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val title = "Скан " + SimpleDateFormat("dd.MM.yyyy HH-mm", Locale("ru")).format(Date(now))
        dao.insertDocument(DocumentEntity(id, title, isDraft = true, createdAt = now, updatedAt = now))
        return id
    }

    suspend fun finalize(docId: String) {
        val doc = dao.getDocument(docId) ?: return
        dao.updateDocument(doc.copy(isDraft = false, updatedAt = System.currentTimeMillis()))
    }

    /**
     * Сохранить снимок как оригинал страницы: повернуть по EXIF, ограничить 4000 px, найти лист.
     * Страница сразу пишется в базу — серия не теряется, если Android выгрузит приложение.
     */
    suspend fun addPage(docId: String, incoming: File, filter: String = Filters.WHITE): String = cpu.withLock {
        withContext(Dispatchers.Default) {
            val pageId = UUID.randomUUID().toString()
            val orig = files.original(docId, pageId)
            val bmp = ImageIo.loadUpright(incoming, 4000)
            try {
                ImageIo.saveJpeg(bmp, orig, 95)
                val quad = Processor.detect(bmp)
                // текст боком или вверх ногами — повернуть сразу; пользователь может поправить кнопкой «Повернуть»
                val rotation = runCatching { Processor.orientation(bmp, quad) }.getOrDefault(0)
                val now = System.currentTimeMillis()
                dao.insertPage(
                    PageEntity(
                        id = pageId, documentId = docId, position = dao.nextPosition(docId),
                        originalPath = orig.absolutePath, processedPath = null, thumbPath = null,
                        quad = quad?.toJson(), rotation = rotation, filter = filter,
                        width = bmp.width, height = bmp.height, createdAt = now, updatedAt = now,
                    ),
                )
                touch(docId)
                pageId
            } finally {
                bmp.recycle()
                incoming.delete()
            }
        }
    }

    suspend fun addPageFromUri(docId: String, uri: Uri): String {
        val tmp = files.newIncoming()
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: error("Не удалось открыть фото")
        }
        return addPage(docId, tmp)
    }

    /** Сохранить рамку/поворот/фильтр и пересчитать страницу от оригинала. */
    suspend fun updatePage(pageId: String, quad: Quad?, rotation: Int, filter: String) {
        val page = dao.getPage(pageId) ?: return
        dao.updatePage(
            page.copy(quad = quad?.toJson(), rotation = rotation, filter = filter, updatedAt = System.currentTimeMillis()),
        )
        render(pageId)
    }

    suspend fun render(pageId: String) = cpu.withLock {
        withContext(Dispatchers.Default) {
            val page = dao.getPage(pageId) ?: return@withContext
            val orig = ImageIo.loadUpright(File(page.originalPath), 4000)
            val out = try {
                Processor.process(orig, Quad.fromJson(page.quad), page.rotation, page.filter)
            } finally {
                orig.recycle()
            }
            try {
                val processed = files.processed(page.documentId, page.id)
                val thumb = files.thumb(page.documentId, page.id)
                ImageIo.saveJpeg(out, processed, 85)
                val t = ImageIo.thumbnail(out)
                ImageIo.saveJpeg(t, thumb, 80)
                if (t !== out) t.recycle()
                dao.updatePage(
                    page.copy(
                        processedPath = processed.absolutePath, thumbPath = thumb.absolutePath,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                touch(page.documentId)
            } finally {
                out.recycle()
            }
        }
    }

    suspend fun deletePage(pageId: String) {
        val page = dao.getPage(pageId) ?: return
        dao.deletePage(pageId)
        listOfNotNull(page.originalPath, page.processedPath, page.thumbPath).forEach { File(it).delete() }
        touch(page.documentId)
    }

    suspend fun deleteDocument(docId: String) {
        dao.deletePagesOf(docId)
        dao.deleteDocument(docId)
        withContext(Dispatchers.IO) { files.docDir(docId).deleteRecursively() }
    }

    /** Переименовать документ. Пустое имя не принимается. */
    suspend fun rename(docId: String, title: String) {
        val t = title.trim().take(100)
        require(t.isNotEmpty()) { "Название не может быть пустым" }
        val doc = dao.getDocument(docId) ?: return
        dao.updateDocument(doc.copy(title = t, updatedAt = System.currentTimeMillis()))
    }

    /**
     * PDF в cache/export; страницы без обработки сначала пересчитываются.
     * compress = true: каждая страница пережимается до ≤ 60% своего размера (сжатие на 40%).
     */
    suspend fun exportPdf(
        docId: String,
        compress: Boolean = true,
        pageSize: PdfWriter.PageSize = PdfWriter.PageSize.A4,
    ): File {
        val doc = dao.getDocument(docId) ?: error("Документ не найден")
        for (p in dao.getPages(docId)) if (p.processedPath == null || !File(p.processedPath).exists()) render(p.id)
        val sources = dao.getPages(docId).map { File(it.processedPath!!) }
        return cpu.withLock {
            withContext(Dispatchers.IO) {
                files.exportDir.mkdirs()
                files.exportDir.listFiles()?.forEach { it.deleteRecursively() }
                val tmp = File(files.exportDir, "pages").apply { mkdirs() }
                val pages = sources.mapIndexed { i, f ->
                    if (compress) {
                        val out = File(tmp, "$i.jpg")
                        val (w, h) = Compressor.compress(f, out, Compressor.TARGET_RATIO)
                        PdfWriter.JpegPage(out, w, h)
                    } else {
                        val (w, h) = ImageIo.size(f)
                        PdfWriter.JpegPage(f, w, h)
                    }
                }
                val out = File(files.exportDir, safeName(doc.title) + ".pdf")
                PdfWriter.write(pages, out, pageSize)
                tmp.deleteRecursively()
                out
            }
        }
    }

    /** При запуске: удалить брошенные черновики старше суток и файлы без записи в базе. */
    suspend fun cleanup() = withContext(Dispatchers.IO) {
        val dayAgo = System.currentTimeMillis() - 24 * 3600 * 1000L
        dao.staleDrafts(dayAgo).forEach { deleteDocument(it.id) }
        val known = dao.allFilePaths().toHashSet()
        files.allFiles().filter { it.absolutePath !in known }.forEach { it.delete() }
        files.incomingDir.listFiles()?.filter { it.lastModified() < dayAgo }?.forEach { it.delete() }
    }

    private suspend fun touch(docId: String) {
        val doc = dao.getDocument(docId) ?: return
        dao.updateDocument(doc.copy(updatedAt = System.currentTimeMillis()))
    }

    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "scan" }
}
