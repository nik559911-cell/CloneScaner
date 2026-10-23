package app.skaner.pdf

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Минимальный PDF 1.4: каждая страница — JPEG как есть (DCTDecode), без перекодирования.
 * Поэтому файл не раздувается, как у android.graphics.pdf.PdfDocument.
 */
object PdfWriter {

    data class JpegPage(val file: File, val widthPx: Int, val heightPx: Int)

    enum class PageSize { A4, FIT }

    private const val A4_W = 595.28
    private const val A4_H = 841.89

    fun write(pages: List<JpegPage>, out: File, pageSize: PageSize = PageSize.A4) {
        require(pages.isNotEmpty()) { "Нет страниц" }
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        CountingStream(BufferedOutputStream(FileOutputStream(tmp))).use { s ->
            val offsets = ArrayList<Long>()
            fun obj(id: Int, body: () -> Unit) {
                while (offsets.size < id) offsets.add(0)
                offsets[id - 1] = s.count
                s.ascii("$id 0 obj\n")
                body()
                s.ascii("\nendobj\n")
            }

            s.ascii("%PDF-1.4\n")
            s.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

            // 1 — каталог, 2 — дерево страниц, далее по 3 объекта на страницу: page, content, image
            val pageIds = pages.indices.map { 3 + it * 3 }
            obj(1) { s.ascii("<< /Type /Catalog /Pages 2 0 R >>") }
            obj(2) {
                s.ascii("<< /Type /Pages /Count ${pages.size} /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] >>")
            }

            pages.forEachIndexed { i, p ->
                val pageId = pageIds[i]
                val contentId = pageId + 1
                val imageId = pageId + 2
                val box = layout(p.widthPx, p.heightPx, pageSize)
                obj(pageId) {
                    s.ascii(
                        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(box.pageW)} ${n(box.pageH)}] " +
                            "/Resources << /XObject << /Im0 $imageId 0 R >> >> /Contents $contentId 0 R >>",
                    )
                }
                val content = "q ${n(box.drawW)} 0 0 ${n(box.drawH)} ${n(box.x)} ${n(box.y)} cm /Im0 Do Q\n"
                    .toByteArray(Charsets.US_ASCII)
                obj(contentId) {
                    s.ascii("<< /Length ${content.size} >>\nstream\n")
                    s.write(content)
                    s.ascii("endstream")
                }
                val len = p.file.length()
                obj(imageId) {
                    s.ascii(
                        "<< /Type /XObject /Subtype /Image /Width ${p.widthPx} /Height ${p.heightPx} " +
                            "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length $len >>\nstream\n",
                    )
                    p.file.inputStream().use { it.copyTo(s) }
                    s.ascii("\nendstream")
                }
            }

            val xref = s.count
            s.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
            offsets.forEach { s.ascii(String.format(Locale.US, "%010d 00000 n \n", it)) }
            s.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        }
        if (out.exists()) out.delete()
        check(tmp.renameTo(out)) { "Не удалось записать PDF" }
    }

    private data class Box(val pageW: Double, val pageH: Double, val drawW: Double, val drawH: Double, val x: Double, val y: Double)

    /** A4 в ориентации картинки, картинка вписана и отцентрована; FIT — страница по размеру картинки при 300 dpi. */
    private fun layout(w: Int, h: Int, size: PageSize): Box {
        if (size == PageSize.FIT) {
            val pw = w * 72.0 / 300.0
            val ph = h * 72.0 / 300.0
            return Box(pw, ph, pw, ph, 0.0, 0.0)
        }
        val landscape = w > h
        val pw = if (landscape) A4_H else A4_W
        val ph = if (landscape) A4_W else A4_H
        val k = minOf(pw / w, ph / h)
        val dw = w * k
        val dh = h * k
        return Box(pw, ph, dw, dh, (pw - dw) / 2, (ph - dh) / 2)
    }

    private fun n(v: Double) = String.format(Locale.US, "%.2f", v)

    private class CountingStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b); count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len); count += len
        }

        override fun flush() = out.flush()
        override fun close() = out.close()

        fun ascii(str: String) = write(str.toByteArray(Charsets.US_ASCII))
    }
}
