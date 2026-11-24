package com.linkvault.app.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile

/** Output files plus an optional note about what could not be carried over. */
data class PdfResult(val files: List<File>, val note: String? = null)

/**
 * Word, Excel and PowerPoint files are read straight from their XML and drawn onto PDF pages with
 * Android's own PDF writer, so nothing leaves the phone. Text, tables and (for slides) pictures come
 * across; fonts, charts, shapes and complex layouts are simplified.
 */
object OfficeToPdf {
    private const val NEED_NEW_FORMAT = "Only the newer file types (.docx, .xlsx, .pptx) can be converted. Open the file in its app and save it in the new format first."

    // ---------- page helper ----------
    private class Pages(val w: Int, val h: Int, val margin: Float) {
        val doc = PdfDocument()
        private var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = margin
        var count = 0
        val bottom get() = h - margin

        fun newPage() {
            page?.let { doc.finishPage(it) }
            count++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(w, h, count).create())
            page = p; canvas = p.canvas; y = margin
        }

        fun ensure() { if (page == null) newPage() }

        /** Draws a (possibly tall) layout, continuing on new pages line by line. */
        fun drawLayout(layout: StaticLayout, x: Float) {
            ensure()
            var line = 0
            while (line < layout.lineCount) {
                val avail = bottom - y
                var last = line
                while (last < layout.lineCount && layout.getLineBottom(last) - layout.getLineTop(line) <= avail) last++
                if (last == line) {
                    if (y > margin + 1f) { newPage(); continue } else last = line + 1
                }
                val top = layout.getLineTop(line)
                val bot = layout.getLineBottom(last - 1)
                val c = canvas!!
                c.save()
                c.translate(x, y - top)
                c.clipRect(0f, top.toFloat(), layout.width.toFloat(), bot.toFloat())
                layout.draw(c)
                c.restore()
                y += (bot - top)
                line = last
                if (line < layout.lineCount) newPage()
            }
        }

        fun finish(file: File) {
            if (count == 0) newPage()
            page?.let { doc.finishPage(it) }
            FileOutputStream(file).use { doc.writeTo(it) }
            doc.close()
        }
    }

    private fun parser(input: InputStream): XmlPullParser =
        Xml.newPullParser().apply { setInput(input, "UTF-8") }

    private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

    private fun openZip(file: File): ZipFile = try { ZipFile(file) } catch (e: Exception) { throw PdfProblem(NEED_NEW_FORMAT) }

    private fun ZipFile.read(name: String): InputStream? = getEntry(name)?.let { getInputStream(it) }

    // =====================================================================
    //  Word
    // =====================================================================
    private sealed class Block
    private class ParaBlock(val text: SpannableStringBuilder, val align: Layout.Alignment) : Block()
    private class TableBlock(val rows: List<List<String>>) : Block()
    private object PageBreak : Block()

    fun docxToPdf(job: PdfJob, src: File, baseName: String): PdfResult {
        val zip = openZip(src)
        zip.use { z ->
            val input = z.read("word/document.xml") ?: throw PdfProblem(NEED_NEW_FORMAT)
            job.progress(0.1f, "Reading the document")
            val blocks = ArrayList<Block>()
            val p = parser(input)

            var tblDepth = 0
            var rows = ArrayList<List<String>>()
            var row = ArrayList<String>()
            var cell = StringBuilder()
            var sb = SpannableStringBuilder()
            var align = Layout.Alignment.ALIGN_NORMAL
            var style = ""
            var bullet = false
            var breakAfter = false
            var inT = false
            var bold = false; var italic = false; var underline = false
            var size: Float? = null
            var color: Int? = null
            var images = 0

            fun styleSize(): Float = when {
                style.equals("Title", true) -> 26f
                style.startsWith("Heading1", true) -> 18f
                style.startsWith("Heading2", true) -> 15f
                style.startsWith("Heading3", true) -> 13f
                style.startsWith("Heading", true) -> 12f
                else -> 11f
            }
            fun styleBold() = style.startsWith("Heading", true) || style.equals("Title", true)

            fun addText(t: String) {
                if (bullet && sb.isEmpty()) sb.append("•  ")
                val start = sb.length
                sb.append(t)
                val end = sb.length
                if (end == start) return
                val b = bold || styleBold()
                val face = when { b && italic -> Typeface.BOLD_ITALIC; b -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
                if (face != Typeface.NORMAL) sb.setSpan(StyleSpan(face), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (underline) sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val s = size ?: styleSize()
                if (s != 11f) sb.setSpan(RelativeSizeSpan(s / 11f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                color?.let { sb.setSpan(ForegroundColorSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }

            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                job.check()
                when (ev) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "w:tbl" -> { tblDepth++; if (tblDepth == 1) rows = ArrayList() }
                        "w:tr" -> if (tblDepth == 1) row = ArrayList()
                        "w:tc" -> if (tblDepth == 1) cell = StringBuilder()
                        "w:p" -> { sb = SpannableStringBuilder(); align = Layout.Alignment.ALIGN_NORMAL; style = ""; bullet = false; breakAfter = false }
                        "w:pStyle" -> style = p.attr("w:val").orEmpty()
                        "w:jc" -> align = when (p.attr("w:val")) {
                            "center" -> Layout.Alignment.ALIGN_CENTER
                            "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
                            else -> Layout.Alignment.ALIGN_NORMAL
                        }
                        "w:numPr" -> bullet = true
                        "w:r" -> { bold = false; italic = false; underline = false; size = null; color = null }
                        "w:b" -> bold = p.attr("w:val").let { it != "0" && it != "false" }
                        "w:i" -> italic = p.attr("w:val").let { it != "0" && it != "false" }
                        "w:u" -> underline = p.attr("w:val") != "none"
                        "w:sz" -> size = p.attr("w:val")?.toFloatOrNull()?.div(2f)
                        "w:color" -> color = p.attr("w:val")?.takeIf { it.length == 6 }?.let { runCatching { Color.parseColor("#$it") }.getOrNull() }
                        "w:t" -> inT = true
                        "w:tab" -> addText("    ")
                        "w:br" -> if (p.attr("w:type") == "page") breakAfter = true else addText("\n")
                        "w:drawing", "w:pict" -> images++
                    }
                    XmlPullParser.TEXT -> if (inT) addText(p.text)
                    XmlPullParser.END_TAG -> when (p.name) {
                        "w:t" -> inT = false
                        "w:p" -> {
                            if (tblDepth > 0) { if (cell.isNotEmpty()) cell.append('\n'); cell.append(sb.toString()) }
                            else {
                                if (sb.isEmpty()) sb.append(" ")
                                blocks += ParaBlock(sb, align)
                                if (breakAfter) blocks += PageBreak
                            }
                        }
                        "w:tc" -> if (tblDepth == 1) row.add(cell.toString().trim())
                        "w:tr" -> if (tblDepth == 1) rows.add(row)
                        "w:tbl" -> { tblDepth--; if (tblDepth == 0 && rows.isNotEmpty()) blocks += TableBlock(rows) }
                    }
                }
                ev = p.next()
            }

            if (blocks.none { it is ParaBlock && it.text.isNotBlank() || it is TableBlock }) throw PdfProblem("This document has no text to convert.")

            val pages = Pages(595, 842, 56f)
            val width = (pages.w - 2 * pages.margin).toInt()
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK; typeface = Typeface.SANS_SERIF }
            val tablePaint = TextPaint(paint).apply { textSize = 9.5f }
            val grid = Paint().apply { color = Color.rgb(150, 150, 150); this.style = Paint.Style.STROKE; strokeWidth = 0.6f }
            val total = blocks.size.coerceAtLeast(1)
            blocks.forEachIndexed { i, b ->
                job.check()
                if (i % 20 == 0) job.progress(0.2f + 0.75f * i / total, "Laying out pages")
                when (b) {
                    is PageBreak -> pages.newPage()
                    is ParaBlock -> {
                        val layout = StaticLayout.Builder.obtain(b.text, 0, b.text.length, paint, width)
                            .setAlignment(b.align).setLineSpacing(0f, 1.2f).build()
                        pages.drawLayout(layout, pages.margin)
                        pages.y += 6f
                    }
                    is TableBlock -> {
                        pages.ensure()
                        val cols = b.rows.maxOf { it.size }.coerceAtLeast(1)
                        val colW = width / cols
                        for (r in b.rows) {
                            val layouts = (0 until cols).map { c ->
                                val t = r.getOrNull(c).orEmpty().ifEmpty { " " }
                                StaticLayout.Builder.obtain(t, 0, t.length, tablePaint, colW - 8).build()
                            }
                            val rowH = (layouts.maxOf { it.height } + 8f).coerceAtMost(pages.bottom - pages.margin)
                            if (pages.y + rowH > pages.bottom) pages.newPage()
                            val c = pages.canvas!!
                            layouts.forEachIndexed { ci, l ->
                                val x = pages.margin + ci * colW
                                c.drawRect(x, pages.y, x + colW, pages.y + rowH, grid)
                                c.save(); c.translate(x + 4f, pages.y + 4f); l.draw(c); c.restore()
                            }
                            pages.y += rowH
                        }
                        pages.y += 8f
                    }
                }
            }
            val out = job.out("$baseName.pdf")
            pages.finish(out)
            return PdfResult(listOf(out), if (images > 0) "Pictures inside the document were left out; text and tables were kept." else null)
        }
    }

    // =====================================================================
    //  Excel
    // =====================================================================
    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break }
        return n - 1
    }

    private fun cleanNumber(raw: String): String {
        val d = raw.toDoubleOrNull() ?: return raw
        if (d == Math.floor(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return java.math.BigDecimal(d).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
    }

    private fun sharedStrings(z: ZipFile): List<String> {
        val input = z.read("xl/sharedStrings.xml") ?: return emptyList()
        val list = ArrayList<String>()
        val p = parser(input)
        var cur: StringBuilder? = null
        var inT = false
        var phonetic = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) { "si" -> cur = StringBuilder(); "t" -> inT = true; "rPh" -> phonetic++ }
                XmlPullParser.TEXT -> if (inT && phonetic == 0) cur?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) { "t" -> inT = false; "rPh" -> phonetic--; "si" -> { list += cur?.toString().orEmpty(); cur = null } }
            }
            ev = p.next()
        }
        return list
    }

    private fun sheetList(z: ZipFile): List<Pair<String, String>> {
        val rels = HashMap<String, String>()
        z.read("xl/_rels/workbook.xml.rels")?.let { s ->
            val p = parser(s)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "Relationship") rels[p.attr("Id").orEmpty()] = p.attr("Target").orEmpty()
                ev = p.next()
            }
        }
        val sheets = ArrayList<Pair<String, String>>()
        val wb = z.read("xl/workbook.xml") ?: throw PdfProblem(NEED_NEW_FORMAT)
        val p = parser(wb)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "sheet") {
                val target = rels[p.attr("r:id").orEmpty()]
                if (target != null) sheets += (p.attr("name") ?: "Sheet") to (if (target.startsWith("/")) target.removePrefix("/") else "xl/$target")
            }
            ev = p.next()
        }
        return sheets
    }

    fun xlsxToPdf(job: PdfJob, src: File, baseName: String): PdfResult {
        val zip = openZip(src)
        zip.use { z ->
            job.progress(0.05f, "Reading the workbook")
            val shared = sharedStrings(z)
            val sheets = sheetList(z)
            if (sheets.isEmpty()) throw PdfProblem(NEED_NEW_FORMAT)
            val pages = Pages(842, 595, 28f)
            val availW = pages.w - 2 * pages.margin
            val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.BLACK; typeface = Typeface.SANS_SERIF }
            val head = TextPaint(text).apply { textSize = 12f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
            val line = Paint().apply { color = Color.rgb(200, 200, 200); strokeWidth = 0.5f }
            val rowH = 15f
            var truncated = false
            var printed = 0

            sheets.forEachIndexed { si, (name, path) ->
                job.check()
                job.progress(0.1f + 0.85f * si / sheets.size, "Sheet ${si + 1} of ${sheets.size}: $name")
                val input = z.read(path) ?: return@forEachIndexed
                val grid = java.util.TreeMap<Int, HashMap<Int, String>>()
                var maxCol = -1
                val p = parser(input)
                var ref = ""; var type = ""; var value: StringBuilder? = null; var inline = StringBuilder(); var inV = false; var inT = false
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    when (ev) {
                        XmlPullParser.START_TAG -> when (p.name) {
                            "c" -> { ref = p.attr("r").orEmpty(); type = p.attr("t").orEmpty(); value = StringBuilder(); inline = StringBuilder() }
                            "v" -> inV = true
                            "t" -> inT = true
                        }
                        XmlPullParser.TEXT -> { if (inV) value?.append(p.text); if (inT) inline.append(p.text) }
                        XmlPullParser.END_TAG -> when (p.name) {
                            "v" -> inV = false
                            "t" -> inT = false
                            "c" -> {
                                val col = colIndex(ref)
                                val rowNo = ref.dropWhile { it in 'A'..'Z' }.toIntOrNull() ?: 0
                                val raw = value?.toString().orEmpty()
                                val shown = when (type) {
                                    "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                                    "inlineStr" -> inline.toString()
                                    "b" -> if (raw == "1") "TRUE" else "FALSE"
                                    "str", "e" -> raw
                                    else -> cleanNumber(raw)
                                }
                                if (col >= 0 && rowNo > 0 && shown.isNotEmpty()) {
                                    if (rowNo <= 3000) { grid.getOrPut(rowNo) { HashMap() }[col] = shown; if (col > maxCol) maxCol = col } else truncated = true
                                }
                            }
                        }
                    }
                    ev = p.next()
                }
                if (grid.isEmpty()) return@forEachIndexed
                val numbers = Regex("^-?[0-9.,]+%?$")
                val widths = FloatArray(maxCol + 1) { 28f }
                grid.entries.take(400).forEach { (_, cells) -> cells.forEach { (c, t) -> widths[c] = maxOf(widths[c], minOf(170f, text.measureText(t) + 10f)) } }

                // Columns are grouped so each group fits the page width; every group prints down all rows.
                val groups = ArrayList<IntRange>()
                var start = 0; var acc = 0f
                for (c in 0..maxCol) {
                    if (acc + widths[c] > availW && c > start) { groups += start until c; start = c; acc = 0f }
                    acc += widths[c]
                }
                groups += start..maxCol

                groups.forEach { g ->
                    pages.newPage()
                    printed++
                    var canvas = pages.canvas!!
                    canvas.drawText(if (groups.size > 1) "$name (columns ${colLabel(g.first)}–${colLabel(g.last)})" else name, pages.margin, pages.y + 12f, head)
                    pages.y += 24f
                    for ((_, cells) in grid) {
                        if (pages.y + rowH > pages.bottom) { pages.newPage(); canvas = pages.canvas!! }
                        var x = pages.margin
                        for (c in g) {
                            val w = minOf(widths[c], availW)
                            cells[c]?.let { t ->
                                val shown = TextUtils.ellipsize(t, text, w - 6f, TextUtils.TruncateAt.END).toString()
                                if (numbers.matches(t)) { text.textAlign = Paint.Align.RIGHT; canvas.drawText(shown, x + w - 3f, pages.y + 11f, text); text.textAlign = Paint.Align.LEFT }
                                else canvas.drawText(shown, x + 3f, pages.y + 11f, text)
                            }
                            x += w
                        }
                        canvas.drawLine(pages.margin, pages.y + rowH, x, pages.y + rowH, line)
                        pages.y += rowH
                    }
                }
            }
            if (printed == 0) throw PdfProblem("This workbook has no data to convert.")
            val out = job.out("$baseName.pdf")
            pages.finish(out)
            return PdfResult(listOf(out), if (truncated) "Only the first 3000 rows of a sheet were converted." else null)
        }
    }

    private fun colLabel(index: Int): String {
        var n = index; val sb = StringBuilder()
        do { sb.insert(0, ('A' + n % 26)); n = n / 26 - 1 } while (n >= 0)
        return sb.toString()
    }

    // =====================================================================
    //  PowerPoint
    // =====================================================================
    private class SRun(val text: String, val size: Float?, val bold: Boolean, val italic: Boolean)
    private class SPara(val runs: MutableList<SRun> = ArrayList(), var align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, var bullet: Boolean = false)
    private class Shape(val kind: Int) {   // 0 text, 1 picture, 2 table
        var x = -1L; var y = -1L; var w = -1L; var h = -1L
        var embed: String? = null
        val paras = ArrayList<SPara>()
        val placed get() = w > 0 && h > 0
    }

    private fun relTargets(z: ZipFile, path: String): Map<String, String> {
        val map = HashMap<String, String>()
        val s = z.read(path) ?: return map
        val p = parser(s)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "Relationship") map[p.attr("Id").orEmpty()] = p.attr("Target").orEmpty()
            ev = p.next()
        }
        return map
    }

    private fun resolve(base: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = ArrayList(base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() })
        target.split('/').forEach { seg ->
            when (seg) {
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                ".", "" -> {}
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    fun pptxToPdf(job: PdfJob, src: File, baseName: String): PdfResult {
        val zip = openZip(src)
        zip.use { z ->
            val presStream = z.read("ppt/presentation.xml") ?: throw PdfProblem(NEED_NEW_FORMAT)
            var cx = 12192000L; var cy = 6858000L
            val ids = ArrayList<String>()
            run {
                val p = parser(presStream)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) when (p.name) {
                        "p:sldSz" -> { cx = p.attr("cx")?.toLongOrNull() ?: cx; cy = p.attr("cy")?.toLongOrNull() ?: cy }
                        "p:sldId" -> p.attr("r:id")?.let { ids += it }
                    }
                    ev = p.next()
                }
            }
            val rels = relTargets(z, "ppt/_rels/presentation.xml.rels")
            var slidePaths = ids.mapNotNull { id -> rels[id]?.let { resolve("ppt/presentation.xml", it) } }
            if (slidePaths.isEmpty()) {
                slidePaths = z.entries().toList().map { it.name }
                    .filter { Regex("""ppt/slides/slide\d+\.xml""").matches(it) }
                    .sortedBy { Regex("""\d+""").find(it.substringAfterLast('/'))!!.value.toInt() }
            }
            if (slidePaths.isEmpty()) throw PdfProblem("This presentation has no slides.")

            val pw = (cx / 12700f).coerceIn(200f, 2000f)
            val ph = (cy / 12700f).coerceIn(150f, 2000f)
            val emu = pw / cx
            val pdf = PdfDocument()
            var skippedPictures = 0

            slidePaths.forEachIndexed { index, path ->
                job.check()
                job.progress(index / slidePaths.size.toFloat(), "Slide ${index + 1} of ${slidePaths.size}")
                val shapes = ArrayList<Shape>()
                z.read(path)?.let { input ->
                    val p = parser(input)
                    var shape: Shape? = null
                    var para: SPara? = null
                    var rSize: Float? = null; var rBold = false; var rItalic = false
                    var inT = false
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        when (ev) {
                            XmlPullParser.START_TAG -> when (p.name) {
                                "p:sp" -> shape = Shape(0)
                                "p:pic" -> shape = Shape(1)
                                "p:graphicFrame" -> shape = Shape(2)
                                "a:off" -> shape?.let { it.x = p.attr("x")?.toLongOrNull() ?: -1; it.y = p.attr("y")?.toLongOrNull() ?: -1 }
                                "a:ext" -> if (p.attr("cx") != null) shape?.let { it.w = p.attr("cx")?.toLongOrNull() ?: -1; it.h = p.attr("cy")?.toLongOrNull() ?: -1 }
                                "a:blip" -> shape?.embed = p.attr("r:embed")
                                "a:p" -> para = SPara()
                                "a:pPr" -> para?.align = when (p.attr("algn")) { "ctr" -> Layout.Alignment.ALIGN_CENTER; "r" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
                                "a:buChar", "a:buAutoNum" -> para?.bullet = true
                                "a:rPr" -> { rSize = p.attr("sz")?.toFloatOrNull()?.div(100f); rBold = p.attr("b") == "1"; rItalic = p.attr("i") == "1" }
                                "a:t" -> inT = true
                                "a:br" -> para?.runs?.add(SRun("\n", null, false, false))
                            }
                            XmlPullParser.TEXT -> if (inT) para?.runs?.add(SRun(p.text, rSize, rBold, rItalic))
                            XmlPullParser.END_TAG -> when (p.name) {
                                "a:t" -> inT = false
                                "a:p" -> { val pa = para; val s = shape; if (pa != null && s != null && pa.runs.isNotEmpty()) s.paras += pa; para = null; rSize = null; rBold = false; rItalic = false }
                                "p:sp", "p:pic", "p:graphicFrame" -> { shape?.let { shapes += it }; shape = null }
                            }
                        }
                        ev = p.next()
                    }
                }

                val slideRels = relTargets(z, path.substringBeforeLast('/') + "/_rels/" + path.substringAfterLast('/') + ".rels")
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw.toInt(), ph.toInt(), index + 1).create())
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                var unplaced = 0
                shapes.forEach { s ->
                    val rect: RectF
                    var defaultSize = 18f
                    if (s.placed && s.x >= 0 && s.y >= 0) {
                        rect = RectF(s.x * emu, s.y * emu, (s.x + s.w) * emu, (s.y + s.h) * emu)
                    } else {
                        if (s.kind != 0 && s.kind != 2) return@forEach
                        rect = if (unplaced == 0) RectF(pw * 0.06f, ph * 0.06f, pw * 0.94f, ph * 0.26f) else RectF(pw * 0.06f, ph * 0.3f, pw * 0.94f, ph * 0.92f)
                        if (unplaced == 0) defaultSize = 32f
                        unplaced++
                    }
                    if (s.kind == 1) {
                        val target = s.embed?.let { slideRels[it] }?.let { resolve(path, it) }
                        val bmp = target?.let { t -> z.read(t)?.use { decodeSampled(it.readBytes(), 1600) } }
                        if (bmp != null) { canvas.drawBitmap(bmp, null, rect, Paint(Paint.FILTER_BITMAP_FLAG)); bmp.recycle() } else skippedPictures++
                        return@forEach
                    }
                    if (s.paras.isEmpty()) return@forEach
                    drawSlideText(canvas, s.paras, rect, defaultSize)
                }
                pdf.finishPage(page)
            }
            val out = job.out("$baseName.pdf")
            FileOutputStream(out).use { pdf.writeTo(it) }
            pdf.close()
            return PdfResult(listOf(out), "Text and pictures were kept. Backgrounds, shapes, charts and animations are not included." +
                if (skippedPictures > 0) " $skippedPictures picture(s) in unsupported formats were skipped." else "")
        }
    }

    private fun decodeSampled(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        if (b.outWidth <= 0) return null
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun drawSlideText(canvas: Canvas, paras: List<SPara>, rect: RectF, defaultSize: Float) {
        val base = 18f
        fun build(): SpannableStringBuilder {
            val sb = SpannableStringBuilder()
            paras.forEachIndexed { i, pa ->
                if (i > 0) sb.append('\n')
                if (pa.bullet) sb.append("•  ")
                pa.runs.forEach { r ->
                    val st = sb.length
                    sb.append(r.text)
                    val en = sb.length
                    if (en == st) return@forEach
                    val face = when { r.bold && r.italic -> Typeface.BOLD_ITALIC; r.bold -> Typeface.BOLD; r.italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
                    if (face != Typeface.NORMAL) sb.setSpan(StyleSpan(face), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val s = r.size ?: defaultSize
                    if (s != base) sb.setSpan(RelativeSizeSpan(s / base), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            return sb
        }
        val text = build()
        val width = rect.width().toInt().coerceAtLeast(20)
        fun layout(scale: Float): StaticLayout {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = base * scale; color = Color.BLACK; typeface = Typeface.SANS_SERIF }
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(paras.first().align).setLineSpacing(0f, 1.05f).build()
        }
        var l = layout(1f)
        if (l.height > rect.height() * 1.05f) l = layout((rect.height() / l.height).coerceAtLeast(0.5f))   // shrink to fit, like PowerPoint's autofit
        canvas.save()
        canvas.translate(rect.left, rect.top)
        l.draw(canvas)
        canvas.restore()
    }
}
