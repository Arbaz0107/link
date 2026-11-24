package com.linkvault.app.pdf

import java.io.File
import kotlin.math.hypot

/** A rectangle in PDF points, measured from the top-left corner of the page as it is displayed. */
data class PBox(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w: Float get() = r - l
    val h: Float get() = b - t
    fun contains(x: Float, y: Float) = x >= l && x <= r && y >= t && y <= b
    fun intersects(o: PBox) = l < o.r && o.l < r && t < o.b && o.t < b
    fun union(o: PBox) = PBox(minOf(l, o.l), minOf(t, o.t), maxOf(r, o.r), maxOf(b, o.b))
    fun moved(dx: Float, dy: Float) = PBox(l + dx, t + dy, r + dx, b + dy)
    fun inflate(d: Float) = PBox(l - d, t - d, r + d, b + d)

    companion object {
        fun of(x1: Float, y1: Float, x2: Float, y2: Float) = PBox(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }
}

enum class TextFamily(val label: String) { SANS("Sans"), SERIF("Serif"), MONO("Mono") }
enum class ShapeKind(val label: String) { RECT("Rectangle"), OVAL("Circle"), LINE("Line"), ARROW("Arrow") }
enum class MarkKind(val label: String) { HIGHLIGHT("Highlight"), UNDERLINE("Underline"), STRIKE("Strike") }

/**
 * Everything the person adds on top of a page. Objects stay editable (move, resize, restyle) until the
 * PDF is saved, when they are written into the page. All coordinates are page points, top-left origin.
 */
sealed class EditObj {
    abstract val id: Long
    abstract val page: Int
    abstract val bounds: PBox
    abstract fun copyTo(page: Int, id: Long): EditObj
    abstract fun moved(dx: Float, dy: Float): EditObj
    open fun resized(to: PBox): EditObj = this
    open val resizable: Boolean get() = false
    open fun hit(x: Float, y: Float, slop: Float): Boolean = bounds.inflate(slop).contains(x, y)
}

data class TextObj(
    override val id: Long, override val page: Int, val box: PBox, val text: String,
    val size: Float = 14f, val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false,
    val align: Int = 0, // 0 left, 1 centre, 2 right
    val color: Int = 0xFF111111.toInt(), val family: TextFamily = TextFamily.SANS, val rotation: Float = 0f,
    /** Set when this text replaces text that is already on the page: that area is painted over first. */
    val coverBox: PBox? = null, val coverColor: Int = 0xFFFFFFFF.toInt(),
    /** Set for text found by OCR. While unchanged it is saved as an invisible text layer (searchable scan). */
    val ocrText: String? = null
) : EditObj() {
    override val bounds: PBox get() = box
    override val resizable: Boolean get() = true
    override fun copyTo(page: Int, id: Long) = copy(page = page, id = id)
    override fun moved(dx: Float, dy: Float) = copy(box = box.moved(dx, dy))
    override fun resized(to: PBox) = copy(box = to)
}

data class InkObj(
    override val id: Long, override val page: Int, val pts: List<Float>, // x0, y0, x1, y1, ...
    val color: Int, val width: Float, val alpha: Float, val marker: Boolean = false
) : EditObj() {
    override val bounds: PBox
        get() {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            var i = 0
            while (i + 1 < pts.size) {
                l = minOf(l, pts[i]); r = maxOf(r, pts[i]); t = minOf(t, pts[i + 1]); b = maxOf(b, pts[i + 1])
                i += 2
            }
            if (l > r) return PBox(0f, 0f, 0f, 0f)
            return PBox(l, t, r, b).inflate(width / 2f)
        }

    override fun copyTo(page: Int, id: Long) = copy(page = page, id = id)
    override fun moved(dx: Float, dy: Float) = copy(pts = pts.mapIndexed { i, v -> if (i % 2 == 0) v + dx else v + dy })
    override fun hit(x: Float, y: Float, slop: Float): Boolean {
        val reach = slop + width / 2f + 2f
        var i = 0
        while (i + 1 < pts.size) {
            if (hypot(pts[i] - x, pts[i + 1] - y) <= reach) return true
            i += 2
        }
        return false
    }
}

data class ShapeObj(
    override val id: Long, override val page: Int, val kind: ShapeKind,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val color: Int, val width: Float, val alpha: Float, val fill: Boolean = false
) : EditObj() {
    override val bounds: PBox get() = PBox.of(x1, y1, x2, y2).inflate(width / 2f)
    override val resizable: Boolean get() = true
    override fun copyTo(page: Int, id: Long) = copy(page = page, id = id)
    override fun moved(dx: Float, dy: Float) = copy(x1 = x1 + dx, y1 = y1 + dy, x2 = x2 + dx, y2 = y2 + dy)
    override fun resized(to: PBox): EditObj =
        if (kind == ShapeKind.RECT || kind == ShapeKind.OVAL) copy(x1 = to.l, y1 = to.t, x2 = to.r, y2 = to.b)
        else copy(
            x1 = if (x1 <= x2) to.l else to.r, x2 = if (x1 <= x2) to.r else to.l,
            y1 = if (y1 <= y2) to.t else to.b, y2 = if (y1 <= y2) to.b else to.t
        )

    override fun hit(x: Float, y: Float, slop: Float): Boolean {
        if (kind == ShapeKind.LINE || kind == ShapeKind.ARROW) {
            val dx = x2 - x1; val dy = y2 - y1
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0f) 0f else (((x - x1) * dx + (y - y1) * dy) / len2).coerceIn(0f, 1f)
            return hypot(x - (x1 + t * dx), y - (y1 + t * dy)) <= slop + width / 2f + 2f
        }
        return bounds.inflate(slop).contains(x, y)
    }
}

/** Highlight, underline or strike-through snapped to words. */
data class MarkObj(
    override val id: Long, override val page: Int, val kind: MarkKind, val boxes: List<PBox>, val color: Int, val alpha: Float
) : EditObj() {
    override val bounds: PBox get() = if (boxes.isEmpty()) PBox(0f, 0f, 0f, 0f) else boxes.reduce { a, b -> a.union(b) }
    override fun copyTo(page: Int, id: Long) = copy(page = page, id = id)
    override fun moved(dx: Float, dy: Float) = copy(boxes = boxes.map { it.moved(dx, dy) })
    override fun hit(x: Float, y: Float, slop: Float): Boolean = boxes.any { it.inflate(slop).contains(x, y) }
}

data class ImageObj(
    override val id: Long, override val page: Int, val file: File, val box: PBox, val rotation: Float = 0f
) : EditObj() {
    override val bounds: PBox get() = box
    override val resizable: Boolean get() = true
    override fun copyTo(page: Int, id: Long) = copy(page = page, id = id)
    override fun moved(dx: Float, dy: Float) = copy(box = box.moved(dx, dy))
    override fun resized(to: PBox) = copy(box = to)
}

data class NoteObj(
    override val id: Long, override val page: Int, val x: Float, val y: Float, val text: String, val color: Int
) : EditObj() {
    override val bounds: PBox get() = PBox(x, y, x + NOTE_SIZE, y + NOTE_SIZE)
    override fun copyTo(page: Int, id: Long) = copy(page = page, id = id)
    override fun moved(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)

    companion object { const val NOTE_SIZE = 24f }
}

/** An area to remove for good. At save time the whole page is flattened so nothing underneath can be extracted. */
data class RedactObj(override val id: Long, override val page: Int, val box: PBox) : EditObj() {
    override val bounds: PBox get() = box
    override val resizable: Boolean get() = true
    override fun copyTo(page: Int, id: Long) = copy(page = page, id = id)
    override fun moved(dx: Float, dy: Float) = copy(box = box.moved(dx, dy))
    override fun resized(to: PBox) = copy(box = to)
}

/** One undo step: a working PDF file plus the objects laid over it. Files are never edited in place. */
data class EditorState(
    val file: File,
    val sizes: List<Pair<Float, Float>>, // page size in points, as displayed
    val objects: List<EditObj>,
    val label: String
) {
    val pageCount: Int get() = sizes.size
}

// ---------- text layout of an existing page ----------

class TWord(val text: String, val box: PBox, val start: Int)
class TLine(val text: String, val box: PBox, val words: List<TWord>, val size: Float, val bold: Boolean)
class PageLayout(val width: Float, val height: Float, val lines: List<TLine>)

/** A search match on screen. [spanText] is the whole words the match touches, so it can be re-typed as an overlay. */
class Hit(val page: Int, val box: PBox, val spanText: String, val matchStart: Int, val matchLen: Int, val size: Float, val bold: Boolean)

/** One page of a rebuilt document: a page of [src], or a blank page when [index] is negative. */
class PagePart(val src: File?, val index: Int, val rotateAdd: Int = 0, val blankW: Float = 595f, val blankH: Float = 842f)
