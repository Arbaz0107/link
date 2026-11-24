package com.linkvault.app.image

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.linkvault.app.ui.theme.Neon
import com.linkvault.app.ui.theme.Radius
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A rectangle in 0..1 of the (rotated) picture. */
data class NRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
    fun toRectF() = RectF(l, t, r, b)

    companion object { val FULL = NRect(0f, 0f, 1f, 1f) }
}

/** Everything a crop is made of; small and immutable so undo/redo is just swapping values. */
data class CropOp(
    val turns: Int = 0, val flipH: Boolean = false, val rect: NRect = NRect.FULL,
    /** Locked width/height ratio, or null for free crop. */
    val ratio: Float? = null, val ratioIdx: Int = 0
)

/** Result of the crop on a picture decoded the same way as the one the person saw. */
fun applyCrop(full: Bitmap, op: CropOp): Bitmap {
    val turned = ImageEngine.transform(full, op.turns, op.flipH, false)
    return if (op.rect == NRect.FULL) turned else ImageEngine.cropNormalized(turned, op.rect.toRectF())
}

private enum class CropMode { NONE, MOVE, TL, TR, BL, BR, L, R, T, B }

private fun ratioRect(cw: Float, ch: Float, ratio: Float): NRect {
    val w: Float; val h: Float
    if (cw / ch > ratio) { h = ch; w = ch * ratio } else { w = cw; h = cw / ratio }
    val l = (cw - w) / 2f / cw; val t = (ch - h) / 2f / ch
    return NRect(l, t, l + w / cw, t + h / ch)
}

private fun dimsAfter(base: Bitmap, turns: Int): Pair<Float, Float> =
    if (turns % 2 == 0) base.width.toFloat() to base.height.toFloat() else base.height.toFloat() to base.width.toFloat()

private val ratioLabels = listOf("Free", "1:1", "4:3", "16:9", "9:16", "Custom")
private val ratioValues = listOf<Float?>(null, 1f, 4f / 3f, 16f / 9f, 9f / 16f, null)

/**
 * The reusable crop editor: picture with crop frame (drag corners, edges or the inside), pinch zoom and pan,
 * rotate/flip, aspect ratios, undo/redo/reset and a full-screen preview of the result.
 * [base] is an upright preview of the picture; the crop is stored as fractions so it can be re-applied to the full picture.
 */
@Composable
fun CropPanel(base: Bitmap, history: History<CropOp>, modifier: Modifier = Modifier, editorHeight: Int = 380) {
    val op = history.current
    val shown = remember(base, op.turns, op.flipH) { ImageEngine.transform(base, op.turns, op.flipH, false) }
    val vt = remember(base) { ViewTransform() }
    var previewing by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CropCanvas(shown, history, vt, Modifier.fillMaxWidth().height(editorHeight.dp))
        EditorBar(
            canUndo = history.canUndo, canRedo = history.canRedo, onUndo = { history.undo() }, onRedo = { history.redo() },
            onReset = { history.reset(); vt.reset() }, onFit = { vt.reset() }, onFullscreen = { previewing = true }
        )
        // Rotate / flip while cropping.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            fun turn(by: Int) {
                val t = ((op.turns + by) % 4 + 4) % 4
                val (w, h) = dimsAfter(base, t)
                history.push(op.copy(turns = t, rect = if (op.ratio != null) ratioRect(w, h, op.ratio) else NRect.FULL))
                vt.reset()
            }
            FilledTonalIconButton(onClick = { turn(-1) }) { Icon(Icons.Outlined.RotateLeft, "Rotate left") }
            FilledTonalIconButton(onClick = { turn(1) }) { Icon(Icons.Outlined.RotateRight, "Rotate right") }
            FilledTonalIconButton(onClick = {
                val (w, h) = dimsAfter(base, op.turns)
                history.push(op.copy(flipH = !op.flipH, rect = if (op.ratio != null) ratioRect(w, h, op.ratio) else NRect.FULL))
            }) { Icon(Icons.Outlined.Flip, "Flip horizontally") }
            Spacer(Modifier.weight(1f))
            val r = op.rect
            Text("${(r.w * shown.width).toInt()} × ${(r.h * shown.height).toInt()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OptSection("Shape") {
            OptChips(ratioLabels, op.ratioIdx) { i ->
                val (w, h) = dimsAfter(base, op.turns)
                val ratio = ratioValues[i]
                history.push(op.copy(ratioIdx = i, ratio = if (i == 5) op.ratio else ratio, rect = if (ratio != null) ratioRect(w, h, ratio) else if (i == 0) op.rect else op.rect))
            }
            if (op.ratio != null && op.ratioIdx != 5) {
                TextButton(onClick = {
                    val (w, h) = dimsAfter(base, op.turns)
                    val inv = 1f / op.ratio
                    history.push(op.copy(ratio = inv, rect = ratioRect(w, h, inv)))
                }) { Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Swap portrait / landscape") }
            }
            if (op.ratioIdx == 5) {
                var cw by remember { mutableStateOf("") }
                var ch by remember { mutableStateOf("") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(cw, { cw = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("Width") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f), shape = Radius.small)
                    Text(":")
                    OutlinedTextField(ch, { ch = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("Height") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f), shape = Radius.small)
                    FilledTonalButton(onClick = {
                        val a = cw.toFloatOrNull(); val b = ch.toFloatOrNull()
                        if (a != null && b != null && a > 0f && b > 0f) {
                            val (w, h) = dimsAfter(base, op.turns)
                            val ratio = (a / b).coerceIn(0.05f, 20f)
                            history.push(op.copy(ratio = ratio, rect = ratioRect(w, h, ratio)))
                        }
                    }, shape = Radius.pill) { Text("Apply") }
                }
            }
        }
        Text("Drag the corners or edges. Pinch to zoom, drag outside the frame to move the picture.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (previewing) {
        val result = remember(op, base) { applyCrop(base, op) }
        FullscreenPreview(result, onClose = { previewing = false })
    }
}

@Composable
private fun CropCanvas(shown: Bitmap, history: History<CropOp>, vt: ViewTransform, modifier: Modifier) {
    val img = remember(shown) { shown.asImageBitmap() }
    val shownNow by rememberUpdatedState(shown)
    val density = LocalDensity.current
    val slop = with(density) { 30.dp.toPx() }
    val handle = Neon.lime
    // Per-gesture memory.
    val drag = remember { object { var mode = CropMode.NONE; var from: CropOp? = null } }

    Canvas(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF101010)).viewGestures(
            vt,
            onStart = { p ->
                val op = history.current
                val cw = shownNow.width.toFloat(); val ch = shownNow.height.toFloat()
                val m = hitTest(vt, op.rect, cw, ch, p, slop, free = op.ratio == null)
                drag.mode = m; drag.from = op
                m != CropMode.NONE
            },
            onDrag = { pos, delta ->
                val op = history.current
                val cw = shownNow.width.toFloat(); val ch = shownNow.height.toFloat()
                val rect = dragRect(drag.mode, op.rect, vt, pos, delta, cw, ch, op.ratio)
                history.replace(op.copy(rect = rect))
            },
            onEnd = { drag.from?.let { history.commitFrom(it) }; drag.from = null; drag.mode = CropMode.NONE }
        ).semantics { contentDescription = "Crop area. Drag the corners to resize." }
    ) {
        vt.layout(size.width, size.height, shown.width.toFloat(), shown.height.toFloat())
        drawBitmapAt(img, vt)
        val cw = shown.width.toFloat(); val ch = shown.height.toFloat()
        val r = history.current.rect
        val a = vt.toView(Offset(r.l * cw, r.t * ch)); val b = vt.toView(Offset(r.r * cw, r.b * ch))
        val dim = Color(0x99000000)
        // Dim everything outside the frame.
        drawRect(dim, Offset.Zero, Size(size.width, a.y.coerceAtLeast(0f)))
        drawRect(dim, Offset(0f, b.y), Size(size.width, (size.height - b.y).coerceAtLeast(0f)))
        drawRect(dim, Offset(0f, a.y), Size(a.x.coerceAtLeast(0f), b.y - a.y))
        drawRect(dim, Offset(b.x, a.y), Size((size.width - b.x).coerceAtLeast(0f), b.y - a.y))
        drawRect(Color.White, a, Size(b.x - a.x, b.y - a.y), style = Stroke(2f))
        // Thirds grid.
        val gridColor = Color(0x66FFFFFF)
        for (i in 1..2) {
            val x = a.x + (b.x - a.x) * i / 3f; val y = a.y + (b.y - a.y) * i / 3f
            drawLine(gridColor, Offset(x, a.y), Offset(x, b.y), 1f)
            drawLine(gridColor, Offset(a.x, y), Offset(b.x, y), 1f)
        }
        // Corner brackets.
        val arm = 22.dp.toPx(); val sw = 5.dp.toPx()
        fun bracket(c: Offset, dx: Float, dy: Float) {
            drawLine(handle, c, Offset(c.x + dx * arm, c.y), sw, StrokeCap.Round)
            drawLine(handle, c, Offset(c.x, c.y + dy * arm), sw, StrokeCap.Round)
        }
        bracket(a, 1f, 1f); bracket(Offset(b.x, a.y), -1f, 1f); bracket(Offset(a.x, b.y), 1f, -1f); bracket(b, -1f, -1f)
        if (history.current.ratio == null) {
            val mx = (a.x + b.x) / 2f; val my = (a.y + b.y) / 2f; val half = 14.dp.toPx()
            drawLine(handle, Offset(mx - half, a.y), Offset(mx + half, a.y), sw, StrokeCap.Round)
            drawLine(handle, Offset(mx - half, b.y), Offset(mx + half, b.y), sw, StrokeCap.Round)
            drawLine(handle, Offset(a.x, my - half), Offset(a.x, my + half), sw, StrokeCap.Round)
            drawLine(handle, Offset(b.x, my - half), Offset(b.x, my + half), sw, StrokeCap.Round)
        }
    }
}

private fun hitTest(vt: ViewTransform, rect: NRect, cw: Float, ch: Float, p: Offset, slop: Float, free: Boolean): CropMode {
    val a = vt.toView(Offset(rect.l * cw, rect.t * ch)); val b = vt.toView(Offset(rect.r * cw, rect.b * ch))
    fun near(x: Float, y: Float) = abs(p.x - x) < slop && abs(p.y - y) < slop
    if (near(a.x, a.y)) return CropMode.TL
    if (near(b.x, a.y)) return CropMode.TR
    if (near(a.x, b.y)) return CropMode.BL
    if (near(b.x, b.y)) return CropMode.BR
    if (free) {
        val inX = p.x in a.x..b.x; val inY = p.y in a.y..b.y
        if (inX && abs(p.y - a.y) < slop) return CropMode.T
        if (inX && abs(p.y - b.y) < slop) return CropMode.B
        if (inY && abs(p.x - a.x) < slop) return CropMode.L
        if (inY && abs(p.x - b.x) < slop) return CropMode.R
    }
    return if (p.x in a.x..b.x && p.y in a.y..b.y) CropMode.MOVE else CropMode.NONE
}

private fun dragRect(mode: CropMode, rect: NRect, vt: ViewTransform, pos: Offset, delta: Offset, cw: Float, ch: Float, ratio: Float?): NRect {
    val dx = delta.x / vt.scale / cw; val dy = delta.y / vt.scale / ch
    val minS = 0.05f
    if (mode == CropMode.MOVE) {
        val w = rect.w; val h = rect.h
        val l = (rect.l + dx).coerceIn(0f, 1f - w); val t = (rect.t + dy).coerceIn(0f, 1f - h)
        return NRect(l, t, l + w, t + h)
    }
    if (ratio != null) {
        // Fixed ratio: the opposite corner stays put, the dragged corner follows the finger.
        val p = vt.toContent(pos)
        val left = mode == CropMode.TL || mode == CropMode.BL
        val top = mode == CropMode.TL || mode == CropMode.TR
        val ax = (if (left) rect.r else rect.l) * cw; val ay = (if (top) rect.b else rect.t) * ch
        val roomX = if (left) ax else cw - ax
        val roomY = if (top) ay else ch - ay
        val wMax = min(roomX, roomY * ratio)
        val wMin = max(cw * minS, 16f)
        var w = max(abs(p.x - ax), abs(p.y - ay) * ratio)
        w = w.coerceIn(min(wMin, wMax), wMax)
        val h = w / ratio
        val l = if (left) ax - w else ax; val t = if (top) ay - h else ay
        return NRect(l / cw, t / ch, (l + w) / cw, (t + h) / ch)
    }
    var l = rect.l; var t = rect.t; var r = rect.r; var b = rect.b
    when (mode) {
        CropMode.TL -> { l += dx; t += dy }
        CropMode.TR -> { r += dx; t += dy }
        CropMode.BL -> { l += dx; b += dy }
        CropMode.BR -> { r += dx; b += dy }
        CropMode.L -> l += dx
        CropMode.R -> r += dx
        CropMode.T -> t += dy
        CropMode.B -> b += dy
        else -> {}
    }
    if (mode == CropMode.TL || mode == CropMode.BL || mode == CropMode.L) l = l.coerceIn(0f, r - minS)
    if (mode == CropMode.TR || mode == CropMode.BR || mode == CropMode.R) r = r.coerceIn(l + minS, 1f)
    if (mode == CropMode.TL || mode == CropMode.TR || mode == CropMode.T) t = t.coerceIn(0f, b - minS)
    if (mode == CropMode.BL || mode == CropMode.BR || mode == CropMode.B) b = b.coerceIn(t + minS, 1f)
    return NRect(l, t, r, b)
}
