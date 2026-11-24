package com.linkvault.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linkvault.app.pdf.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

enum class Tool(val label: String, val scrolls: Boolean = true) {
    SELECT("Select"), EDIT("Edit"), TEXT("Add text"), IMAGE("Image"),
    DRAW("Draw", false), MARK("Mark", false), SHAPE("Shape", false), NOTE("Note"), REDACT("Redact", false)
}

/** Settings of the drawing tools; they survive switching tools. */
@Stable
class EditorOptions {
    var penColor by mutableIntStateOf(0xFF1E88E5.toInt())
    var penWidth by mutableFloatStateOf(3f)
    var penAlpha by mutableFloatStateOf(1f)
    var eraser by mutableStateOf(false)
    var markKind by mutableIntStateOf(0) // 0 highlight, 1 underline, 2 strike-through, 3 freehand marker
    var markColor by mutableIntStateOf(0xFFFDD835.toInt())
    var shape by mutableStateOf(ShapeKind.RECT)
    var shapeColor by mutableIntStateOf(0xFFE53935.toInt())
    var shapeWidth by mutableFloatStateOf(3f)
    var shapeFill by mutableStateOf(false)
    var textColor by mutableIntStateOf(0xFF111111.toInt())
}

val EditorColors = listOf(0xFF111111, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA, 0xFFFFFFFF).map { it.toInt() }

private val Chrome = Color(0xFF2979FF)

private fun resizeBox(b: PBox, corner: Int, dx: Float, dy: Float): PBox {
    var l = b.l; var t = b.t; var r = b.r; var bt = b.b
    when (corner) {
        0 -> { l += dx; t += dy }
        1 -> { r += dx; t += dy }
        2 -> { l += dx; bt += dy }
        else -> { r += dx; bt += dy }
    }
    if (r - l < 12f) { if (corner == 0 || corner == 2) l = r - 12f else r = l + 12f }
    if (bt - t < 12f) { if (corner == 0 || corner == 1) t = bt - 12f else bt = t + 12f }
    return PBox(l, t, r, bt)
}

private fun cornerAt(b: PBox, x: Float, y: Float, reach: Float): Int {
    val pts = listOf(b.l to b.t, b.r to b.t, b.l to b.b, b.r to b.b)
    for ((i, p) in pts.withIndex()) if (hypot(p.first - x, p.second - y) <= reach) return i
    return -1
}

/** Boxes (one per line) for the words between two touch points, in reading order. */
private fun wordsBetween(layout: PageLayout, a: Offset, b: Offset): List<PBox> {
    val flat = layout.lines.flatMap { it.words }
    if (flat.isEmpty()) return emptyList()
    fun nearest(p: Offset): Int {
        var best = -1
        var bd = Float.MAX_VALUE
        flat.forEachIndexed { i, w ->
            val d = if (w.box.inflate(4f).contains(p.x, p.y)) 0f else hypot((w.box.l + w.box.r) / 2f - p.x, (w.box.t + w.box.b) / 2f - p.y)
            if (d < bd) { bd = d; best = i }
        }
        return if (bd <= 40f) best else -1
    }
    val i = nearest(a)
    val j = nearest(b)
    if (i < 0 || j < 0) return emptyList()
    val out = ArrayList<PBox>()
    for (w in flat.subList(minOf(i, j), maxOf(i, j) + 1)) {
        val last = out.lastOrNull()
        if (last != null && kotlin.math.abs(last.t - w.box.t) < 3f) out[out.lastIndex] = last.union(w.box) else out.add(w.box)
    }
    return out
}

@Composable
fun EditorPage(
    index: Int, st: EditorState, ctl: EditorController, renderer: PageRenderer, tool: Tool, opts: EditorOptions, widthDp: Dp,
    onEditLine: (Int, TLine) -> Unit, onEditText: (TextObj) -> Unit,
    onAskText: (Int, Float, Float) -> Unit, onAskNote: (Int, Float, Float) -> Unit, onAskImage: (Int, Float, Float) -> Unit
) {
    val density = LocalDensity.current
    val (pw, ph) = st.sizes[index]
    val widthPx = with(density) { widthDp.toPx() }
    val k = widthPx / pw
    val bucket = ((widthPx / 256f).roundToInt() * 256).coerceIn(512, 2600)
    val measurer = rememberTextMeasurer()

    val bmp by produceState<Bitmap?>(null, st.file, index, bucket) { value = renderer.render(index, bucket) }
    val needsLayout = tool == Tool.EDIT || tool == Tool.MARK
    val layout by produceState<PageLayout?>(null, st.file, index, needsLayout) { value = if (needsLayout) ctl.layoutOf(index) else null }

    val objs = ctl.objectsOn(index)
    val selected = ctl.selectedId
    val pageHits = ctl.hits.withIndex().filter { it.value.page == index }
    val reachPt = with(density) { 22.dp.toPx() } / k
    val slopPt = with(density) { 8.dp.toPx() } / k

    Box(
        Modifier.width(widthDp).aspectRatio(pw / ph)
            .pointerInput(tool, k, selected, layout) {
                when (tool) {
                    Tool.SELECT -> awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val p0 = down.position / k
                        val list = ctl.objectsOn(index)
                        val sel = list.firstOrNull { it.id == ctl.selectedId }
                        val corner = if (sel != null && sel.resizable) cornerAt(sel.bounds, p0.x, p0.y, reachPt) else -1
                        val target: EditObj? = if (corner >= 0) sel else list.asReversed().firstOrNull { it.hit(p0.x, p0.y, slopPt) }
                        if (target == null) {
                            val up = waitForUpOrCancellation()
                            if (up != null) ctl.selectedId = null
                            return@awaitEachGesture
                        }
                        ctl.selectedId = target.id
                        var moved = false
                        var total = Offset.Zero
                        drag(down.id) { ch ->
                            val delta = ch.positionChange()
                            total += delta
                            if (!moved && total.getDistance() > viewConfiguration.touchSlop) moved = true
                            if (moved) {
                                val d = delta / k
                                val cur = ctl.draft ?: target
                                ctl.draft = if (corner >= 0 && cur.resizable) cur.resized(resizeBox(cur.bounds, corner, d.x, d.y)) else cur.moved(d.x, d.y)
                                ch.consume()
                            }
                        }
                        if (moved) ctl.commitDraft()
                    }

                    Tool.EDIT -> detectTapGestures { off ->
                        val p = off / k
                        val text = ctl.objectsOn(index).asReversed().firstOrNull { it is TextObj && it.hit(p.x, p.y, slopPt) } as? TextObj
                        if (text != null) { ctl.selectedId = text.id; onEditText(text); return@detectTapGestures }
                        val line = layout?.lines?.firstOrNull { it.box.inflate(3f).contains(p.x, p.y) }
                        if (line != null) onEditLine(index, line)
                        else ctl.message = if (layout?.lines?.isEmpty() == true) "No selectable text on this page. Use OCR to make a scan editable." else "Tap on a line of text to edit it."
                    }

                    Tool.TEXT -> detectTapGestures { off -> val p = off / k; onAskText(index, p.x, p.y) }
                    Tool.NOTE -> detectTapGestures { off -> val p = off / k; onAskNote(index, p.x, p.y) }
                    Tool.IMAGE -> detectTapGestures { off -> val p = off / k; onAskImage(index, p.x, p.y) }

                    Tool.DRAW -> {
                        val pts = ArrayList<Float>()
                        var id = 0L
                        detectDragGestures(
                            onDragStart = { off ->
                                val p = off / k
                                pts.clear(); pts.add(p.x); pts.add(p.y)
                                id = ctl.newId()
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val p = change.position / k
                                if (opts.eraser) {
                                    val hitIds = ctl.objectsOn(index).filter { (it is InkObj || it is ShapeObj || it is MarkObj) && it.hit(p.x, p.y, slopPt) }.map { it.id }
                                    if (hitIds.isNotEmpty()) ctl.erasing = ctl.erasing + hitIds
                                } else {
                                    pts.add(p.x); pts.add(p.y)
                                    ctl.draft = InkObj(id, index, pts.toList(), opts.penColor, opts.penWidth, opts.penAlpha)
                                }
                            },
                            onDragEnd = { if (opts.eraser) ctl.commitErase() else ctl.commitDraft() },
                            onDragCancel = { ctl.draft = null; ctl.erasing = emptySet() }
                        )
                    }

                    Tool.MARK -> {
                        var start = Offset.Zero
                        var id = 0L
                        val pts = ArrayList<Float>()
                        detectDragGestures(
                            onDragStart = { off -> start = off / k; id = ctl.newId(); pts.clear(); pts.add(start.x); pts.add(start.y) },
                            onDrag = { change, _ ->
                                change.consume()
                                val p = change.position / k
                                if (opts.markKind == 3) {
                                    pts.add(p.x); pts.add(p.y)
                                    ctl.draft = InkObj(id, index, pts.toList(), opts.markColor, 14f, 0.4f, marker = true)
                                } else {
                                    val lay = layout
                                    val boxes = if (lay != null && lay.lines.isNotEmpty()) wordsBetween(lay, start, p) else listOf(PBox.of(start.x, start.y, p.x, p.y))
                                    val kind = when (opts.markKind) { 1 -> MarkKind.UNDERLINE; 2 -> MarkKind.STRIKE; else -> MarkKind.HIGHLIGHT }
                                    ctl.draft = if (boxes.isEmpty()) null else MarkObj(id, index, kind, boxes, opts.markColor, 0.4f)
                                }
                            },
                            onDragEnd = { ctl.commitDraft() },
                            onDragCancel = { ctl.draft = null }
                        )
                    }

                    Tool.SHAPE -> {
                        var start = Offset.Zero
                        var id = 0L
                        detectDragGestures(
                            onDragStart = { off -> start = off / k; id = ctl.newId() },
                            onDrag = { change, _ ->
                                change.consume()
                                val p = change.position / k
                                ctl.draft = ShapeObj(id, index, opts.shape, start.x, start.y, p.x, p.y, opts.shapeColor, opts.shapeWidth, 1f, opts.shapeFill)
                            },
                            onDragEnd = { ctl.commitDraft() },
                            onDragCancel = { ctl.draft = null }
                        )
                    }

                    Tool.REDACT -> {
                        var start = Offset.Zero
                        var id = 0L
                        detectDragGestures(
                            onDragStart = { off -> start = off / k; id = ctl.newId() },
                            onDrag = { change, _ ->
                                change.consume()
                                val p = change.position / k
                                ctl.draft = RedactObj(id, index, PBox.of(start.x, start.y, p.x, p.y))
                            },
                            onDragEnd = {
                                val d = ctl.draft
                                if (d is RedactObj && (d.box.w < 4f || d.box.h < 4f)) ctl.draft = null else ctl.commitDraft()
                            },
                            onDragCancel = { ctl.draft = null }
                        )
                    }
                }
            }
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        else Box(Modifier.fillMaxSize())

        Canvas(Modifier.fillMaxSize()) {
            for ((i, h) in pageHits) {
                val cur = i == ctl.hitIndex
                drawRect(Color(if (cur) 0x99FF9800 else 0x55FFC107), Offset(h.box.l * k, h.box.t * k), Size(h.box.w * k, h.box.h * k))
            }
            for (o in objs) drawEdit(o, k, measurer, tool == Tool.SELECT || tool == Tool.EDIT)
            val sel = objs.firstOrNull { it.id == selected }
            if (sel != null) drawChrome(sel, k)
        }
    }
}

private fun DrawScope.drawChrome(o: EditObj, k: Float) {
    val b = o.bounds.inflate(2f)
    drawRect(Chrome, Offset(b.l * k, b.t * k), Size(b.w * k, b.h * k), style = Stroke(1.5.dp.toPx()))
    if (o.resizable) for ((x, y) in listOf(b.l to b.t, b.r to b.t, b.l to b.b, b.r to b.b)) {
        drawCircle(Color.White, 8.dp.toPx(), Offset(x * k, y * k))
        drawCircle(Chrome, 6.dp.toPx(), Offset(x * k, y * k))
    }
}

private fun DrawScope.drawEdit(o: EditObj, k: Float, measurer: TextMeasurer, showOutlines: Boolean) {
    when (o) {
        is TextObj -> {
            val unchanged = o.ocrText != null && o.ocrText == o.text
            if (unchanged) {
                if (showOutlines) drawRect(Color(0x552979FF), Offset(o.box.l * k, o.box.t * k), Size(o.box.w * k, o.box.h * k), style = Stroke(1.dp.toPx()))
                return
            }
            o.coverBox?.let { c -> drawRect(Color(o.coverColor), Offset(c.l * k, c.t * k), Size(c.w * k, c.h * k)) }
            val style = TextStyle(
                color = Color(o.color), fontSize = (o.size * k).toSp(), lineHeight = (o.size * 1.2f * k).toSp(),
                fontWeight = if (o.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (o.italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = if (o.underline) TextDecoration.Underline else TextDecoration.None,
                textAlign = when (o.align) { 1 -> TextAlign.Center; 2 -> TextAlign.End; else -> TextAlign.Start },
                fontFamily = when (o.family) { TextFamily.SANS -> FontFamily.SansSerif; TextFamily.SERIF -> FontFamily.Serif; TextFamily.MONO -> FontFamily.Monospace }
            )
            val c = Offset((o.box.l + o.box.r) / 2f * k, (o.box.t + o.box.b) / 2f * k)
            rotate(o.rotation, c) {
                drawText(measurer, o.text, Offset(o.box.l * k, o.box.t * k), style, TextOverflow.Clip, true, Int.MAX_VALUE, Size(o.box.w * k, o.box.h * k))
            }
        }
        is InkObj -> {
            if (o.pts.size < 2) return
            val path = Path()
            path.moveTo(o.pts[0] * k, o.pts[1] * k)
            if (o.pts.size < 4) path.lineTo(o.pts[0] * k + 0.1f, o.pts[1] * k)
            var i = 2
            while (i + 1 < o.pts.size) { path.lineTo(o.pts[i] * k, o.pts[i + 1] * k); i += 2 }
            drawPath(path, Color(o.color), o.alpha, Stroke(o.width * k, cap = if (o.marker) StrokeCap.Butt else StrokeCap.Round, join = StrokeJoin.Round))
        }
        is ShapeObj -> {
            val col = Color(o.color)
            val sw = o.width * k
            val b = PBox.of(o.x1, o.y1, o.x2, o.y2)
            val style = if (o.fill && (o.kind == ShapeKind.RECT || o.kind == ShapeKind.OVAL)) null else Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (o.kind) {
                ShapeKind.RECT ->
                    if (style == null) drawRect(col, Offset(b.l * k, b.t * k), Size(b.w * k, b.h * k), o.alpha)
                    else drawRect(col, Offset(b.l * k, b.t * k), Size(b.w * k, b.h * k), o.alpha, style)
                ShapeKind.OVAL ->
                    if (style == null) drawOval(col, Offset(b.l * k, b.t * k), Size(b.w * k, b.h * k), o.alpha)
                    else drawOval(col, Offset(b.l * k, b.t * k), Size(b.w * k, b.h * k), o.alpha, style)
                ShapeKind.LINE, ShapeKind.ARROW -> {
                    drawLine(col, Offset(o.x1 * k, o.y1 * k), Offset(o.x2 * k, o.y2 * k), sw, StrokeCap.Round, alpha = o.alpha)
                    if (o.kind == ShapeKind.ARROW) {
                        val ang = atan2((o.y2 - o.y1).toDouble(), (o.x2 - o.x1).toDouble())
                        val len = maxOf(10f, o.width * 4f)
                        for (a in listOf(ang + Math.PI * 0.85, ang - Math.PI * 0.85)) {
                            drawLine(col, Offset(o.x2 * k, o.y2 * k), Offset((o.x2 + (len * cos(a)).toFloat()) * k, (o.y2 + (len * sin(a)).toFloat()) * k), sw, StrokeCap.Round, alpha = o.alpha)
                        }
                    }
                }
            }
        }
        is MarkObj -> for (bx in o.boxes) {
            val col = Color(o.color)
            when (o.kind) {
                MarkKind.HIGHLIGHT -> drawRect(col, Offset(bx.l * k, bx.t * k), Size(bx.w * k, bx.h * k), o.alpha)
                MarkKind.UNDERLINE -> drawLine(col, Offset(bx.l * k, (bx.b + 1.5f) * k), Offset(bx.r * k, (bx.b + 1.5f) * k), maxOf(1f, bx.h / 12f * k))
                MarkKind.STRIKE -> drawLine(col, Offset(bx.l * k, (bx.t + bx.b) / 2f * k), Offset(bx.r * k, (bx.t + bx.b) / 2f * k), maxOf(1f, bx.h / 12f * k))
            }
        }
        is ImageObj -> {
            val bmp = ImageCache.get(o.file)
            val c = Offset((o.box.l + o.box.r) / 2f * k, (o.box.t + o.box.b) / 2f * k)
            if (bmp != null) rotate(o.rotation, c) {
                drawImage(bmp.asImageBitmap(), dstOffset = androidx.compose.ui.unit.IntOffset((o.box.l * k).roundToInt(), (o.box.t * k).roundToInt()),
                    dstSize = androidx.compose.ui.unit.IntSize((o.box.w * k).roundToInt().coerceAtLeast(1), (o.box.h * k).roundToInt().coerceAtLeast(1)))
            } else drawRect(Color(0x33888888), Offset(o.box.l * k, o.box.t * k), Size(o.box.w * k, o.box.h * k))
        }
        is NoteObj -> {
            val s = NoteObj.NOTE_SIZE * k
            drawRoundRect(Color(o.color), Offset(o.x * k, o.y * k), Size(s, s), CornerRadius(3.dp.toPx()))
            for (n in 1..3) drawLine(Color(0xFF5A5A5A), Offset((o.x + 5f) * k, (o.y + 5f + n * 4.5f) * k), Offset((o.x + NoteObj.NOTE_SIZE - 5f) * k, (o.y + 5f + n * 4.5f) * k), 1.dp.toPx())
        }
        is RedactObj -> {
            drawRect(Color(0x99000000), Offset(o.box.l * k, o.box.t * k), Size(o.box.w * k, o.box.h * k))
            drawRect(Color(0xFFE53935), Offset(o.box.l * k, o.box.t * k), Size(o.box.w * k, o.box.h * k), style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** Small decoded copies of placed pictures, so the canvas can draw them without re-reading files each frame. */
object ImageCache {
    private val cache = object : android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(file: java.io.File): Bitmap? {
        val key = file.path
        cache.get(key)?.let { return it }
        return try {
            val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, b)
            var s = 1
            while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= 900) s *= 2
            val bmp = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
            if (bmp != null) cache.put(key, bmp)
            bmp
        } catch (e: Throwable) { null }
    }
}
