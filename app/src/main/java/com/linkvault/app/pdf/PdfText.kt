package com.linkvault.app.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One word with its position on the page. */
data class PdfWord(val text: String, val x: Float, val endX: Float)

/** One line of text as it sits on the page. */
data class PdfLine(val words: List<PdfWord>, val y: Float, val size: Float, val bold: Boolean) {
    val text: String get() = words.joinToString(" ") { it.text }
}

/** A paragraph rebuilt from consecutive lines. */
data class PdfPara(val text: String, val bold: Boolean, val size: Float)

/** Collects lines (with word positions, size and weight) instead of producing plain text. */
private class LineCollector : PDFTextStripper() {
    val lines = ArrayList<PdfLine>()
    private val words = ArrayList<PdfWord>()
    private var y = 0f
    private var size = 0f
    private var boldCount = 0
    private var wordCount = 0

    init { sortByPosition = true }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        if (text.isBlank() || textPositions.isEmpty()) return
        val first = textPositions.first()
        val last = textPositions.last()
        if (words.isEmpty()) { y = first.yDirAdj; size = first.fontSizeInPt }
        val fontName = try { first.font?.name.orEmpty() } catch (e: Exception) { "" }
        if (fontName.contains("bold", true) || fontName.contains("black", true) || fontName.contains("heavy", true)) boldCount++
        wordCount++
        words += PdfWord(text.trim(), first.xDirAdj, last.xDirAdj + last.widthDirAdj)
    }

    override fun writeLineSeparator() = flush()
    override fun writePageEnd() = flush()

    private fun flush() {
        if (words.isNotEmpty()) lines += PdfLine(ArrayList(words), y, size, wordCount > 0 && boldCount * 2 > wordCount)
        words.clear(); boldCount = 0; wordCount = 0
    }

    override fun writeString(text: String) { /* handled with positions */ }
    override fun writeWordSeparator() { /* words are kept separate */ }
}

object PdfText {
    /** Lines per page (index 0 = page 1). */
    fun lines(job: PdfJob, doc: PDDocument, onPage: (Int) -> Unit = {}): List<List<PdfLine>> {
        val total = doc.numberOfPages
        val result = ArrayList<List<PdfLine>>()
        for (p in 1..total) {
            job.check()
            onPage(p)
            val s = LineCollector()
            s.startPage = p
            s.endPage = p
            s.getText(doc)
            result += s.lines.toList()
        }
        return result
    }

    fun hasText(pages: List<List<PdfLine>>) = pages.any { page -> page.any { it.text.isNotBlank() } }

    const val NO_TEXT = "This PDF has no selectable text, so it looks like a scan. Converting it needs text recognition (OCR), which is planned for the PDF Editor update."

    /** Joins wrapped lines into paragraphs; a bigger gap or a short sentence-ending line starts a new one. */
    fun paragraphs(lines: List<PdfLine>): List<PdfPara> {
        val out = ArrayList<PdfPara>()
        if (lines.isEmpty()) return out
        val maxWidth = lines.maxOf { l -> (l.words.last().endX - l.words.first().x) }.coerceAtLeast(1f)
        var cur = StringBuilder(lines[0].text)
        var bold = lines[0].bold
        var size = lines[0].size
        var prev = lines[0]
        for (i in 1 until lines.size) {
            val l = lines[i]
            val gap = l.y - prev.y
            val prevWidth = prev.words.last().endX - prev.words.first().x
            val sizeChanged = kotlin.math.abs(l.size - prev.size) > 0.8f || l.bold != prev.bold
            val endsSentence = prev.text.trimEnd().lastOrNull() in listOf('.', '!', '?', ':')
            val newPara = gap > prev.size * 1.55f || sizeChanged || (endsSentence && prevWidth < maxWidth * 0.75f) || gap < 0f
            if (newPara) {
                out += PdfPara(cur.toString(), bold, size)
                cur = StringBuilder(l.text); bold = l.bold; size = l.size
            } else cur.append(' ').append(l.text)
            prev = l
        }
        out += PdfPara(cur.toString(), bold, size)
        return out
    }

    private fun xml(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) when {
            c == '&' -> sb.append("&amp;")
            c == '<' -> sb.append("&lt;")
            c == '>' -> sb.append("&gt;")
            c == '"' -> sb.append("&quot;")
            c.code >= 0x20 || c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
        }
        return sb.toString()
    }

    private fun zip(out: File, entries: List<Pair<String, String>>) {
        ZipOutputStream(FileOutputStream(out)).use { z ->
            entries.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        }
    }

    // ---------- PDF -> Word ----------
    fun toDocx(job: PdfJob, src: File, password: String, baseName: String): File {
        val out = job.out("$baseName.docx")
        val pages = PdfEngine.open(src, password).use { doc ->
            lines(job, doc) { p -> job.progress(p / (doc.numberOfPages + 1f), "Reading page $p of ${doc.numberOfPages}") }
        }
        if (!hasText(pages)) throw PdfProblem(NO_TEXT)
        job.progress(0.95f, "Building the Word file")
        val body = StringBuilder()
        pages.forEachIndexed { i, lines ->
            paragraphs(lines).forEach { p ->
                val half = (p.size * 2).toInt().coerceIn(16, 80)
                body.append("<w:p><w:r><w:rPr>")
                if (p.bold) body.append("<w:b/>")
                body.append("<w:sz w:val=\"$half\"/></w:rPr><w:t xml:space=\"preserve\">").append(xml(p.text)).append("</w:t></w:r></w:p>")
            }
            if (i < pages.lastIndex) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
        }
        zip(out, listOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="709" w:footer="709" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        ))
        return out
    }

    // ---------- PDF -> HTML ----------
    fun toHtml(job: PdfJob, src: File, password: String, baseName: String): File {
        val out = job.out("$baseName.html")
        val pages = PdfEngine.open(src, password).use { doc ->
            lines(job, doc) { p -> job.progress(p / (doc.numberOfPages + 1f), "Reading page $p of ${doc.numberOfPages}") }
        }
        if (!hasText(pages)) throw PdfProblem(NO_TEXT)
        val sizes = pages.flatMap { pg -> pg.map { it.size } }.sorted()
        val body = if (sizes.isEmpty()) 12f else sizes[sizes.size / 2]
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>")
            .append(xml(baseName)).append("</title><style>body{font-family:system-ui,sans-serif;line-height:1.55;max-width:760px;margin:0 auto;padding:24px;color:#1a1a1a}section{padding:8px 0 24px;border-bottom:1px solid #ddd}.pg{font-size:12px;color:#888;letter-spacing:.08em;text-transform:uppercase}h1,h2,h3{line-height:1.25}</style></head><body>")
        pages.forEachIndexed { i, lines ->
            sb.append("<section><div class=\"pg\">Page ${i + 1}</div>")
            paragraphs(lines).forEach { p ->
                val tag = when { p.size >= body * 1.7f -> "h1"; p.size >= body * 1.35f -> "h2"; p.size >= body * 1.15f && p.bold -> "h3"; else -> "p" }
                sb.append("<$tag>").append(if (p.bold && tag == "p") "<strong>${xml(p.text)}</strong>" else xml(p.text)).append("</$tag>")
            }
            sb.append("</section>")
        }
        sb.append("</body></html>")
        out.writeText(sb.toString())
        return out
    }

    // ---------- PDF -> Excel ----------
    fun toXlsx(job: PdfJob, src: File, password: String, baseName: String): File {
        val out = job.out("$baseName.xlsx")
        val pages = PdfEngine.open(src, password).use { doc ->
            lines(job, doc) { p -> job.progress(p / (doc.numberOfPages + 1f), "Reading page $p of ${doc.numberOfPages}") }
        }
        if (!hasText(pages)) throw PdfProblem(NO_TEXT)
        val rows = ArrayList<List<String>>()
        pages.forEach { lines ->
            lines.forEach { l ->
                // Words close together belong to one cell; a wide gap starts the next column.
                val cells = ArrayList<StringBuilder>()
                var prevEnd = Float.NaN
                l.words.forEach { w ->
                    if (prevEnd.isNaN() || w.x - prevEnd > l.size * 1.0f) cells += StringBuilder(w.text) else cells.last().append(' ').append(w.text)
                    prevEnd = w.endX
                }
                rows += cells.map { it.toString() }
            }
        }
        val sheet = StringBuilder()
        rows.forEachIndexed { r, cells ->
            sheet.append("<row r=\"${r + 1}\">")
            cells.forEachIndexed { c, v ->
                val ref = colName(c) + (r + 1)
                val clean = v.replace(",", "").trim()
                if (Regex("""^-?(0|[1-9]\d*)(\.\d+)?$""").matches(clean) && clean.length < 16)
                    sheet.append("<c r=\"$ref\"><v>$clean</v></c>")
                else sheet.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(v)}</t></is></c>")
            }
            sheet.append("</row>")
        }
        zip(out, listOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$sheet</sheetData></worksheet>"""
        ))
        return out
    }

    private fun colName(index: Int): String {
        var n = index; val sb = StringBuilder()
        do { sb.insert(0, ('A' + n % 26)); n = n / 26 - 1 } while (n >= 0)
        return sb.toString()
    }
}
