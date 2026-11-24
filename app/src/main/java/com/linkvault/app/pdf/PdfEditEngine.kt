package com.linkvault.app.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDSimpleFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Reads page text once and keeps the document open, so tapping text stays quick on big files. */
class LayoutSession(file: File) : AutoCloseable {
    private val doc: PDDocument = PdfEngine.open(file)
    private val cache = HashMap<Int, PageLayout>()
    val pageCount: Int get() = doc.numberOfPages

    @Synchronized
    fun page(index: Int): PageLayout = cache.getOrPut(index) { PdfEditEngine.computeLayout(doc, index) }

    @Synchronized
    override fun close() { try { doc.close() } catch (_: Exception) {} }
}

class ReplaceReport(val done: Int, val blocked: Int, val reason: String?)

/** Page geometry: maps "page as displayed, origin bottom-left" into the page's own coordinate space. */
class PageGeom(page: PDPage) {
    val box: PDRectangle = page.cropBox ?: page.mediaBox
    val rot: Int = ((page.rotation % 360) + 360) % 360
    val w: Float = box.width
    val h: Float = box.height
    val dw: Float = if (rot == 90 || rot == 270) h else w
    val dh: Float = if (rot == 90 || rot == 270) w else h

    fun matrix(): Matrix {
        val ox = box.lowerLeftX
        val oy = box.lowerLeftY
        return when (rot) {
            90 -> Matrix(0f, 1f, -1f, 0f, w + ox, oy)
            180 -> Matrix(-1f, 0f, 0f, -1f, w + ox, h + oy)
            270 -> Matrix(0f, -1f, 1f, 0f, ox, h + oy)
            else -> Matrix(1f, 0f, 0f, 1f, ox, oy)
        }
    }

    fun map(x: Float, y: Float): Pair<Float, Float> {
        val ox = box.lowerLeftX
        val oy = box.lowerLeftY
        return when (rot) {
            90 -> (w - y + ox) to (x + oy)
            180 -> (w - x + ox) to (h - y + oy)
            270 -> (y + ox) to (h - x + oy)
            else -> (x + ox) to (y + oy)
        }
    }
}

object PdfEditEngine {

    // =============================================================================================
    //  Text layout (where each word sits on a page)
    // =============================================================================================

    private class WordCollector : PDFTextStripper() {
        class W(val text: String, val x: Float, val endX: Float, val y: Float, val size: Float, val bold: Boolean)

        val words = ArrayList<W>()

        init { sortByPosition = true }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (text.isBlank() || textPositions.isEmpty()) return
            val first = textPositions.first()
            val last = textPositions.last()
            val fontName = try { first.font?.name.orEmpty() } catch (e: Exception) { "" }
            val bold = fontName.contains("bold", true) || fontName.contains("black", true) || fontName.contains("heavy", true)
            val size = if (first.fontSizeInPt > 0f) first.fontSizeInPt else maxOf(first.heightDir, 8f)
            words += W(text.trim(), first.xDirAdj, last.xDirAdj + last.widthDirAdj, first.yDirAdj, size, bold)
        }

        override fun writeString(text: String) { /* handled with positions */ }
        override fun writeLineSeparator() {}
        override fun writeWordSeparator() {}
    }

    fun computeLayout(doc: PDDocument, index: Int): PageLayout {
        val g = PageGeom(doc.getPage(index))
        val c = WordCollector()
        c.startPage = index + 1
        c.endPage = index + 1
        c.getText(doc)
        return PageLayout(g.dw, g.dh, buildLines(c.words))
    }

    private fun buildLines(words: List<WordCollector.W>): List<TLine> {
        val sorted = words.sortedWith(compareBy({ it.y }, { it.x }))
        val rows = ArrayList<MutableList<WordCollector.W>>()
        for (w in sorted) {
            val row = rows.lastOrNull()
            if (row != null && abs(row[0].y - w.y) <= maxOf(2f, 0.4f * w.size)) row.add(w) else rows.add(mutableListOf(w))
        }
        val segments = ArrayList<List<WordCollector.W>>()
        for (row in rows) {
            var seg = ArrayList<WordCollector.W>()
            for (w in row.sortedBy { it.x }) {
                val prev = seg.lastOrNull()
                // A wide gap is a column or a tab stop: keep the two sides as separate lines of text.
                if (prev != null && w.x - prev.endX > 2.5f * maxOf(prev.size, 6f)) {
                    segments.add(seg)
                    seg = ArrayList()
                }
                seg.add(w)
            }
            if (seg.isNotEmpty()) segments.add(seg)
        }
        return segments.map { seg ->
            val sb = StringBuilder()
            val tw = ArrayList<TWord>()
            for ((i, w) in seg.withIndex()) {
                if (i > 0) sb.append(' ')
                val start = sb.length
                sb.append(w.text)
                tw += TWord(w.text, PBox(w.x, w.y - w.size * 0.85f, w.endX, w.y + w.size * 0.25f), start)
            }
            val size = seg.map { it.size }.average().toFloat()
            TLine(sb.toString(), tw.map { it.box }.reduce { a, b -> a.union(b) }, tw, size, seg.count { it.bold } * 2 > seg.size)
        }
    }

    /** Every place [query] appears, as boxes on screen. Matches are per line of text. */
    fun findAll(session: LayoutSession, query: String, matchCase: Boolean, onPage: (Int) -> Unit = {}): List<Hit> {
        if (query.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        for (p in 0 until session.pageCount) {
            onPage(p)
            val lay = session.page(p)
            for (line in lay.lines) {
                var from = 0
                while (true) {
                    val idx = line.text.indexOf(query, from, ignoreCase = !matchCase)
                    if (idx < 0) break
                    val end = idx + query.length
                    val spanned = line.words.filter { it.start < end && it.start + it.text.length > idx }
                    if (spanned.isNotEmpty()) {
                        val box = spanned.map { it.box }.reduce { a, b -> a.union(b) }
                        val s = spanned.first().start
                        val e = spanned.last().start + spanned.last().text.length
                        hits += Hit(p, box, line.text.substring(s, e), idx - s, query.length, line.size, line.bold)
                    }
                    from = end
                }
            }
        }
        return hits
    }

    // =============================================================================================
    //  Real text editing: change the text inside the page's content stream
    // =============================================================================================

    private fun fontFor(res: PDResources?, nameTok: Any?): PDFont? = try {
        if (res != null && nameTok is COSName) res.getFont(nameTok) else null
    } catch (e: Exception) { null }

    private fun uni(font: PDFont, b: Byte): String = try { font.toUnicode(b.toInt() and 0xFF) ?: "" } catch (e: Exception) { "" }

    private fun isSubset(name: String?) = name != null && Regex("^[A-Z]{6}\\+").containsMatchIn(name)

    private fun collectChars(font: PDFont?, tok: Any?, used: HashMap<String, MutableSet<Char>>) {
        if (font == null) return
        val set = used.getOrPut(font.name ?: "") { HashSet() }
        fun addString(s: COSString) { for (b in s.bytes) uni(font, b).forEach { set.add(it) } }
        if (tok is COSString) addString(tok)
        else if (tok is COSArray) for (i in 0 until tok.size()) (tok.getObject(i) as? COSString)?.let { addString(it) }
    }

    private class ArrRes(val array: COSArray, val done: Int, val blocked: Int, val reason: String?)
    private class PageResult(val done: Int, val blocked: Int, val reason: String?)

    private fun same(a: IntArray, b: IntArray) = a[0] == b[0] && a[1] == b[1]

    /** Rebuilds [arr] with the bytes from element a to element z replaced by [enc]. Kerning is dropped only inside that span. */
    private fun splice(arr: COSArray, a: IntArray, z: IntArray, enc: ByteArray): COSArray {
        val out = COSArray()
        for (i in 0 until a[0]) out.add(arr.get(i))
        val bytes = ByteArrayOutputStream()
        if (a[1] >= 0) { val s = (arr.getObject(a[0]) as COSString).bytes; bytes.write(s, 0, a[1]) }
        bytes.write(enc, 0, enc.size)
        if (z[1] >= 0) { val s = (arr.getObject(z[0]) as COSString).bytes; bytes.write(s, z[1] + 1, s.size - z[1] - 1) }
        out.add(COSString(bytes.toByteArray()))
        for (i in z[0] + 1 until arr.size()) out.add(arr.get(i))
        return out
    }

    private fun editArray(
        font: PDFont, arr: COSArray, find: String, repl: String, matchCase: Boolean,
        used: Map<String, Set<Char>>, limit: Int
    ): ArrRes {
        // Composite (CID) and Type 3 fonts cannot be re-encoded safely, so those stay untouched.
        if (font !is PDSimpleFont || font is PDType3Font) return ArrRes(arr, 0, 0, null)

        val sb = StringBuilder()
        val refs = ArrayList<IntArray>() // for each character: [array element, byte index (-1 = gap that reads as a space)]
        for (e in 0 until arr.size()) {
            val el = arr.getObject(e)
            if (el is COSString) {
                val bytes = el.bytes
                for (b in bytes.indices) for (ch in uni(font, bytes[b])) { sb.append(ch); refs.add(intArrayOf(e, b)) }
            } else if (el is COSNumber && el.floatValue() <= -200f) {
                sb.append(' '); refs.add(intArrayOf(e, -1))
            }
        }
        val text = sb.toString()
        val starts = ArrayList<Int>()
        var from = 0
        while (starts.size < limit) {
            val idx = text.indexOf(find, from, ignoreCase = !matchCase)
            if (idx < 0) break
            starts.add(idx)
            from = idx + find.length
        }
        if (starts.isEmpty()) return ArrRes(arr, 0, 0, null)

        val fontKey = font.name ?: ""
        val enc: ByteArray? = try { font.encode(repl) } catch (e: Exception) { null }
        val allowed = used[fontKey] ?: emptySet()
        val subsetBlocks = isSubset(fontKey) && !repl.all { it in allowed }
        var cur = arr
        var done = 0
        var blocked = 0
        var reason: String? = null
        for (s in starts.asReversed()) {
            if (enc == null || subsetBlocks) {
                blocked++
                reason = reason ?: if (enc == null) "the PDF's font has no letters for the new text" else "the PDF only contains the letters it already uses"
                continue
            }
            val e = s + find.length - 1
            val a = refs[s]
            val z = refs[e]
            val splitsGlyph = (s > 0 && same(refs[s - 1], a)) || (e + 1 < refs.size && same(refs[e + 1], z))
            if (splitsGlyph) { blocked++; reason = reason ?: "the text uses joined letters"; continue }
            cur = splice(cur, a, z, enc)
            done++
        }
        return ArrRes(cur, done, blocked, reason)
    }

    private fun replaceOnPage(doc: PDDocument, page: PDPage, find: String, repl: String, matchCase: Boolean, limit: Int): PageResult {
        val parser = PDFStreamParser(page)
        parser.parse()
        val tokens = ArrayList<Any>(parser.tokens)
        val resources = page.resources

        val used = HashMap<String, MutableSet<Char>>()
        var font: PDFont? = null
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t !is Operator) continue
            when (t.name) {
                "Tf" -> font = fontFor(resources, tokens.getOrNull(i - 2))
                "Tj", "TJ" -> collectChars(font, tokens.getOrNull(i - 1), used)
            }
        }

        var done = 0
        var blocked = 0
        var reason: String? = null
        var changed = false
        font = null
        for (i in tokens.indices) {
            if (done >= limit) break
            val t = tokens[i]
            if (t !is Operator) continue
            when (t.name) {
                "Tf" -> font = fontFor(resources, tokens.getOrNull(i - 2))
                "Tj", "TJ" -> {
                    val f = font ?: continue
                    val operand = tokens.getOrNull(i - 1)
                    val arr: COSArray = when (operand) {
                        is COSString -> COSArray().also { it.add(operand) }
                        is COSArray -> operand
                        else -> continue
                    }
                    val res = editArray(f, arr, find, repl, matchCase, used, limit - done)
                    blocked += res.blocked
                    if (reason == null) reason = res.reason
                    if (res.done > 0) {
                        done += res.done
                        changed = true
                        tokens[i - 1] = if (operand is COSString) res.array.get(0) else res.array
                    }
                }
            }
        }
        if (changed) {
            val stream = PDStream(doc)
            stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
            page.setContents(stream)
        }
        return PageResult(done, blocked, reason)
    }

    /**
     * Replaces text in the PDF's own text layer. Only a copy is written, and only when something changed.
     * [onlyPage] limits the search to one page (0-based); [all] false stops after the first change.
     */
    fun replaceText(src: File, out: File, find: String, repl: String, matchCase: Boolean, all: Boolean, onlyPage: Int?): ReplaceReport {
        if (find.isEmpty()) return ReplaceReport(0, 0, null)
        var done = 0
        var blocked = 0
        var reason: String? = null
        PdfEngine.open(src).use { doc ->
            val range: IntRange = if (onlyPage != null) onlyPage..onlyPage else 0 until doc.numberOfPages
            for (p in range) {
                if (!all && done > 0) break
                val r = replaceOnPage(doc, doc.getPage(p), find, repl, matchCase, if (all) Int.MAX_VALUE else 1)
                done += r.done
                blocked += r.blocked
                if (reason == null) reason = r.reason
            }
            if (done > 0) doc.save(out)
        }
        return ReplaceReport(done, blocked, reason)
    }

    // =============================================================================================
    //  Pages: rebuild a document from a list of pages
    // =============================================================================================

    fun assemble(parts: List<PagePart>, out: File, onPage: (Int) -> Unit = {}) {
        if (parts.isEmpty()) throw PdfProblem("A PDF needs at least one page.")
        val opened = HashMap<File, PDDocument>()
        try {
            PDDocument().use { target ->
                for ((n, p) in parts.withIndex()) {
                    onPage(n)
                    val src = p.src
                    if (p.index < 0 || src == null) { target.addPage(PDPage(PDRectangle(p.blankW, p.blankH))); continue }
                    val doc = opened.getOrPut(src) { PdfEngine.open(src) }
                    val imported = target.importPage(doc.getPage(p.index))
                    if (p.rotateAdd != 0) imported.rotation = ((imported.rotation + p.rotateAdd) % 360 + 360) % 360
                }
                target.save(out)
            }
        } finally {
            opened.values.forEach { try { it.close() } catch (_: Exception) {} }
        }
    }

    fun readSizes(file: File): List<Pair<Float, Float>> {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd)
        try {
            val out = ArrayList<Pair<Float, Float>>()
            for (i in 0 until r.pageCount) {
                val pg = r.openPage(i)
                try { out += pg.width.toFloat() to pg.height.toFloat() } finally { pg.close() }
            }
            return out
        } finally { r.close(); pfd.close() }
    }

    // =============================================================================================
    //  Saving: write the objects into the pages
    // =============================================================================================

    private fun rgb(c: Int) = Triple(Color.red(c), Color.green(c), Color.blue(c))

    private fun gs(alpha: Float): PDExtendedGraphicsState = PDExtendedGraphicsState().also {
        it.setStrokingAlphaConstant(alpha)
        it.setNonStrokingAlphaConstant(alpha)
    }

    private fun width(font: PDFont, s: String, size: Float): Float = try { font.getStringWidth(s) / 1000f * size } catch (e: Exception) { s.length * size * 0.5f }

    private fun canEncode(font: PDFont, s: String): Boolean = try { font.encode(s); true } catch (e: Exception) { false }

    /** Keeps only characters the font can draw, so showing the text can never fail half-way through a page. */
    private fun sanitize(font: PDFont, s: String): String {
        if (canEncode(font, s)) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val piece = String(Character.toChars(cp))
            sb.append(if (canEncode(font, piece)) piece else "?")
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private class Fonts(val doc: PDDocument) {
        private val system = HashMap<String, PDFont?>()

        fun standard(family: TextFamily, bold: Boolean, italic: Boolean): PDFont = when (family) {
            TextFamily.SANS -> if (bold && italic) PDType1Font.HELVETICA_BOLD_OBLIQUE else if (bold) PDType1Font.HELVETICA_BOLD else if (italic) PDType1Font.HELVETICA_OBLIQUE else PDType1Font.HELVETICA
            TextFamily.SERIF -> if (bold && italic) PDType1Font.TIMES_BOLD_ITALIC else if (bold) PDType1Font.TIMES_BOLD else if (italic) PDType1Font.TIMES_ITALIC else PDType1Font.TIMES_ROMAN
            TextFamily.MONO -> if (bold && italic) PDType1Font.COURIER_BOLD_OBLIQUE else if (bold) PDType1Font.COURIER_BOLD else if (italic) PDType1Font.COURIER_OBLIQUE else PDType1Font.COURIER
        }

        /** Standard fonts only cover Western letters; for other scripts use a font that ships with Android. */
        fun pick(o: TextObj): PDFont {
            val std = standard(o.family, o.bold, o.italic)
            if (canEncode(std, o.text.replace("\n", " "))) return std
            val names = when {
                o.text.any { it.code in 0x0900..0x097F } -> listOf("NotoSansDevanagari-Regular.ttf")
                o.text.any { it.code in 0x0A80..0x0AFF } -> listOf("NotoSansGujarati-Regular.ttf")
                else -> listOf("Roboto-Regular.ttf", "NotoSans-Regular.ttf", "DroidSans.ttf")
            }
            for (n in names) {
                val f = system.getOrPut(n) {
                    val file = File("/system/fonts/$n")
                    if (!file.exists()) null else try { PDType0Font.load(doc, file) } catch (e: Exception) { null }
                }
                if (f != null) return f
            }
            return std
        }
    }

    private fun wrap(font: PDFont, size: Float, text: String, maxW: Float): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                val cand = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && width(font, cand, size) > maxW) { out += line; line = word } else line = cand
            }
            out += line
        }
        return out
    }

    private fun decodeImage(file: File): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, b)
        if (b.outWidth <= 0) return null
        var s = 1
        while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= 2000) s *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun drawOne(doc: PDDocument, cs: PDPageContentStream, g: PageGeom, o: EditObj, fonts: Fonts) {
        val dh = g.dh
        when (o) {
            is TextObj -> {
                val invisible = o.ocrText != null && o.ocrText == o.text
                if (!invisible) o.coverBox?.let { c ->
                    val (r, gg, b) = rgb(o.coverColor)
                    cs.setNonStrokingColor(r, gg, b)
                    cs.addRect(c.l, dh - c.b, c.w, c.h)
                    cs.fill()
                }
                if (o.text.isBlank()) return
                val font = fonts.pick(o)
                val lines = if (invisible) listOf(sanitize(font, o.text.replace("\n", " "))) else wrap(font, o.size, o.text, o.box.w).map { sanitize(font, it) }
                val (r, gg, b) = rgb(o.color)
                cs.saveGraphicsState()
                if (o.rotation != 0f) {
                    val cx = (o.box.l + o.box.r) / 2f
                    val cy = dh - (o.box.t + o.box.b) / 2f
                    val th = Math.toRadians(-o.rotation.toDouble())
                    cs.transform(Matrix.getTranslateInstance(cx, cy))
                    cs.transform(Matrix(cos(th).toFloat(), sin(th).toFloat(), -sin(th).toFloat(), cos(th).toFloat(), 0f, 0f))
                    cs.transform(Matrix.getTranslateInstance(-cx, -cy))
                }
                val lh = o.size * 1.2f
                for ((i, line) in lines.withIndex()) {
                    val lw = width(font, line, o.size)
                    val x = when (o.align) { 1 -> o.box.l + (o.box.w - lw) / 2f; 2 -> o.box.r - lw; else -> o.box.l }
                    val y = dh - o.box.t - o.size * 0.95f - i * lh
                    cs.beginText()
                    cs.setFont(font, o.size)
                    if (invisible) cs.setRenderingMode(RenderingMode.NEITHER)
                    cs.setNonStrokingColor(r, gg, b)
                    cs.newLineAtOffset(x, y)
                    cs.showText(line)
                    cs.endText()
                    if (o.underline && !invisible && line.isNotEmpty()) {
                        cs.setStrokingColor(r, gg, b)
                        cs.setLineWidth(maxOf(0.5f, o.size / 14f))
                        cs.moveTo(x, y - o.size * 0.12f)
                        cs.lineTo(x + lw, y - o.size * 0.12f)
                        cs.stroke()
                    }
                }
                cs.restoreGraphicsState()
            }
            is InkObj -> {
                if (o.pts.size < 2) return
                val (r, gg, b) = rgb(o.color)
                cs.saveGraphicsState()
                if (o.alpha < 1f) cs.setGraphicsStateParameters(gs(o.alpha))
                cs.setStrokingColor(r, gg, b)
                cs.setLineWidth(o.width)
                cs.setLineCapStyle(if (o.marker) 0 else 1)
                cs.setLineJoinStyle(1)
                cs.moveTo(o.pts[0], dh - o.pts[1])
                if (o.pts.size < 4) cs.lineTo(o.pts[0] + 0.01f, dh - o.pts[1])
                var i = 2
                while (i + 1 < o.pts.size) { cs.lineTo(o.pts[i], dh - o.pts[i + 1]); i += 2 }
                cs.stroke()
                cs.restoreGraphicsState()
            }
            is ShapeObj -> {
                val (r, gg, b) = rgb(o.color)
                cs.saveGraphicsState()
                if (o.alpha < 1f) cs.setGraphicsStateParameters(gs(o.alpha))
                cs.setStrokingColor(r, gg, b)
                cs.setNonStrokingColor(r, gg, b)
                cs.setLineWidth(o.width)
                cs.setLineJoinStyle(1)
                val bx = PBox.of(o.x1, o.y1, o.x2, o.y2)
                when (o.kind) {
                    ShapeKind.RECT -> { cs.addRect(bx.l, dh - bx.b, bx.w, bx.h); if (o.fill) cs.fill() else cs.stroke() }
                    ShapeKind.OVAL -> {
                        val k = 0.5522848f
                        val rx = bx.w / 2f; val ry = bx.h / 2f
                        val cx = bx.l + rx; val cy = dh - (bx.t + ry)
                        cs.moveTo(cx + rx, cy)
                        cs.curveTo(cx + rx, cy + k * ry, cx + k * rx, cy + ry, cx, cy + ry)
                        cs.curveTo(cx - k * rx, cy + ry, cx - rx, cy + k * ry, cx - rx, cy)
                        cs.curveTo(cx - rx, cy - k * ry, cx - k * rx, cy - ry, cx, cy - ry)
                        cs.curveTo(cx + k * rx, cy - ry, cx + rx, cy - k * ry, cx + rx, cy)
                        cs.closePath()
                        if (o.fill) cs.fill() else cs.stroke()
                    }
                    ShapeKind.LINE, ShapeKind.ARROW -> {
                        cs.moveTo(o.x1, dh - o.y1)
                        cs.lineTo(o.x2, dh - o.y2)
                        cs.stroke()
                        if (o.kind == ShapeKind.ARROW) {
                            val ang = atan2((o.y2 - o.y1).toDouble(), (o.x2 - o.x1).toDouble())
                            val len = maxOf(10f, o.width * 4f)
                            val a1 = ang + Math.PI * 0.85
                            val a2 = ang - Math.PI * 0.85
                            cs.moveTo(o.x2, dh - o.y2)
                            cs.lineTo(o.x2 + (len * cos(a1)).toFloat(), dh - (o.y2 + (len * sin(a1)).toFloat()))
                            cs.lineTo(o.x2 + (len * cos(a2)).toFloat(), dh - (o.y2 + (len * sin(a2)).toFloat()))
                            cs.closePath()
                            cs.fill()
                        }
                    }
                }
                cs.restoreGraphicsState()
            }
            is MarkObj -> {
                val (r, gg, b) = rgb(o.color)
                cs.saveGraphicsState()
                if (o.kind == MarkKind.HIGHLIGHT) {
                    cs.setGraphicsStateParameters(gs(o.alpha))
                    cs.setNonStrokingColor(r, gg, b)
                    for (bx in o.boxes) { cs.addRect(bx.l, dh - bx.b, bx.w, bx.h); cs.fill() }
                } else {
                    cs.setStrokingColor(r, gg, b)
                    for (bx in o.boxes) {
                        val y = if (o.kind == MarkKind.UNDERLINE) dh - bx.b + 1.5f else dh - (bx.t + bx.b) / 2f
                        cs.setLineWidth(maxOf(0.8f, bx.h / 12f))
                        cs.moveTo(bx.l, y)
                        cs.lineTo(bx.r, y)
                        cs.stroke()
                    }
                }
                cs.restoreGraphicsState()
            }
            is ImageObj -> {
                val bmp = decodeImage(o.file) ?: return
                val img = if (o.file.extension.equals("png", true)) LosslessFactory.createFromImage(doc, bmp) else JPEGFactory.createFromImage(doc, bmp, 0.9f)
                bmp.recycle()
                val w = o.box.w; val h = o.box.h
                val cx = (o.box.l + o.box.r) / 2f
                val cy = dh - (o.box.t + o.box.b) / 2f
                val th = Math.toRadians(-o.rotation.toDouble())
                val c = cos(th).toFloat(); val s = sin(th).toFloat()
                // Lower-left corner of the rotated image.
                val ox = cx + (-w / 2f) * c - (-h / 2f) * s
                val oy = cy + (-w / 2f) * s + (-h / 2f) * c
                cs.saveGraphicsState()
                cs.transform(Matrix(w * c, w * s, -h * s, h * c, ox, oy))
                cs.drawImage(img, 0f, 0f, 1f, 1f)
                cs.restoreGraphicsState()
            }
            is NoteObj -> {
                val (r, gg, b) = rgb(o.color)
                val y = dh - o.y - NoteObj.NOTE_SIZE
                cs.saveGraphicsState()
                cs.setNonStrokingColor(r, gg, b)
                cs.setStrokingColor(90, 90, 90)
                cs.setLineWidth(0.8f)
                cs.addRect(o.x, y, NoteObj.NOTE_SIZE, NoteObj.NOTE_SIZE)
                cs.fillAndStroke()
                cs.setStrokingColor(90, 90, 90)
                for (k in 1..3) {
                    cs.moveTo(o.x + 5f, y + 5f + k * 4.5f)
                    cs.lineTo(o.x + NoteObj.NOTE_SIZE - 5f, y + 5f + k * 4.5f)
                    cs.stroke()
                }
                cs.restoreGraphicsState()
            }
            is RedactObj -> { /* handled by flattening the page */ }
        }
    }

    private fun drawOnPage(doc: PDDocument, page: PDPage, objs: List<EditObj>, fonts: Fonts) {
        val g = PageGeom(page)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.transform(g.matrix())
            for (o in objs) {
                // One bad object (say, an unreadable image) must not cost the whole document.
                try { drawOne(doc, cs, g, o, fonts) } catch (e: Exception) { /* skipped */ }
            }
        }
        // Sticky notes also become real annotations so other PDF apps can read their text.
        for (o in objs) {
            if (o !is NoteObj) continue
            try {
                val dh = g.dh
                val (x1, y1) = g.map(o.x, dh - o.y - NoteObj.NOTE_SIZE)
                val (x2, y2) = g.map(o.x + NoteObj.NOTE_SIZE, dh - o.y)
                val ann = PDAnnotationText()
                ann.contents = o.text
                ann.rectangle = PDRectangle(minOf(x1, x2), minOf(y1, y2), abs(x2 - x1), abs(y2 - y1))
                page.annotations.add(ann)
            } catch (e: Exception) { /* the visible square is still there */ }
        }
    }

    /**
     * Writes [objects] into a copy of [src]. Pages with a redaction are turned into pictures with the
     * black boxes burned in, because that is the only way to be sure the covered text and images are gone.
     */
    fun bake(src: File, out: File, objects: List<EditObj>) {
        if (objects.isEmpty()) { src.copyTo(out, overwrite = true); return }
        val redacts = objects.filterIsInstance<RedactObj>().groupBy { it.page }.mapValues { e -> e.value.map { it.box } }
        val draws = objects.filter { it !is RedactObj }.groupBy { it.page }
        val target = if (redacts.isEmpty()) out else File(out.parentFile, out.name + ".tmp")
        PdfEngine.open(src).use { doc ->
            val fonts = Fonts(doc)
            for ((p, list) in draws.toSortedMap()) if (p in 0 until doc.numberOfPages) drawOnPage(doc, doc.getPage(p), list, fonts)
            doc.save(target)
        }
        if (redacts.isNotEmpty()) {
            try { flatten(target, out, redacts) } finally { target.delete() }
        }
    }

    private fun flatten(src: File, out: File, redactions: Map<Int, List<PBox>>) {
        val shots = HashMap<Int, Triple<Bitmap, Float, Float>>()
        val pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            for ((idx, boxes) in redactions) {
                if (idx !in 0 until renderer.pageCount) continue
                val page = renderer.openPage(idx)
                try {
                    val scale = minOf(200f / 72f, 3600f / maxOf(page.width, page.height).toFloat())
                    val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    val canvas = Canvas(bmp)
                    val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
                    for (bx in boxes) canvas.drawRect((bx.l - 1f) * scale, (bx.t - 1f) * scale, (bx.r + 1f) * scale, (bx.b + 1f) * scale, paint)
                    shots[idx] = Triple(bmp, page.width.toFloat(), page.height.toFloat())
                } finally { page.close() }
            }
        } finally { renderer.close(); pfd.close() }

        PdfEngine.open(src).use { doc ->
            for ((idx, shot) in shots) {
                val (bmp, pw, ph) = shot
                val old = doc.getPage(idx)
                val np = PDPage(PDRectangle(pw, ph))
                doc.pages.insertBefore(np, old)
                doc.removePage(old)
                val img = JPEGFactory.createFromImage(doc, bmp, 0.92f)
                PDPageContentStream(doc, np).use { it.drawImage(img, 0f, 0f, pw, ph) }
                bmp.recycle()
            }
            doc.save(out)
        }
    }
}
