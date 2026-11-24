package com.linkvault.app.image

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.linkvault.app.ui.theme.Neon
import com.linkvault.app.ui.theme.Radius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Maps picture pixels to screen pixels: scale plus offset. Shared by every editor in this module so pinch-zoom,
 * pan and fit-to-screen behave the same everywhere.
 */
class ViewTransform {
    var scale by mutableFloatStateOf(1f)
    var ox by mutableFloatStateOf(0f)
    var oy by mutableFloatStateOf(0f)
    var viewW = 0f; private set
    var viewH = 0f; private set
    var contentW = 1f; private set
    var contentH = 1f; private set
    private var fitScale = 1f
    private var fitted = false
    private var lastKey = 0L

    /** Call whenever the view size or the picture size changes. Keeps the person's zoom unless the picture changed. */
    fun layout(vw: Float, vh: Float, cw: Float, ch: Float) {
        if (vw <= 0f || vh <= 0f || cw <= 0f || ch <= 0f) return
        val key = (cw.toLong() shl 40) xor (ch.toLong() shl 20) xor vw.toLong() xor (vh.toLong() shl 10)
        if (fitted && key == lastKey) return
        val sameContent = fitted && cw == contentW && ch == contentH
        viewW = vw; viewH = vh; contentW = cw; contentH = ch
        lastKey = key
        val oldFit = fitScale
        fitScale = min(vw / cw, vh / ch)
        if (!sameContent || !fitted) reset() else {
            // Only the view size changed: keep the same relative zoom.
            val rel = scale / oldFit
            scale = fitScale * rel
            clamp()
        }
        fitted = true
    }

    fun reset() {
        scale = fitScale
        ox = (viewW - contentW * scale) / 2f
        oy = (viewH - contentH * scale) / 2f
    }

    val isZoomed get() = scale > fitScale * 1.02f

    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val ns = (scale * zoom).coerceIn(fitScale * 0.9f, fitScale * 12f)
        val f = ns / scale
        ox = centroid.x - (centroid.x - ox) * f + pan.x
        oy = centroid.y - (centroid.y - oy) * f + pan.y
        scale = ns
        clamp()
    }

    fun panBy(d: Offset) { ox += d.x; oy += d.y; clamp() }

    /** Keeps at least a quarter of the picture on screen so it can't be lost off the edge. */
    private fun clamp() {
        val w = contentW * scale; val h = contentH * scale
        val minX = min(viewW - w, 0f) - w * 0.25f; val maxX = max(viewW - w, 0f) + w * 0.25f
        val minY = min(viewH - h, 0f) - h * 0.25f; val maxY = max(viewH - h, 0f) + h * 0.25f
        ox = ox.coerceIn(minX, maxX); oy = oy.coerceIn(minY, maxY)
    }

    fun toContent(p: Offset) = Offset((p.x - ox) / scale, (p.y - oy) / scale)
    fun toView(p: Offset) = Offset(p.x * scale + ox, p.y * scale + oy)
}

/**
 * One finger: [onStart] decides whether it belongs to the tool (return true) or pans the picture.
 * Two fingers always pinch-zoom and pan. [onDrag] gets the finger position and its movement in screen pixels.
 */
fun Modifier.viewGestures(
    vt: ViewTransform,
    onStart: (Offset) -> Boolean = { false },
    onDrag: (Offset, Offset) -> Unit = { _, _ -> },
    onEnd: () -> Unit = {}
): Modifier = pointerInput(vt) { detectViewGestures(vt, onStart, onDrag, onEnd) }

private suspend fun PointerInputScope.detectViewGestures(
    vt: ViewTransform, onStart: (Offset) -> Boolean, onDrag: (Offset, Offset) -> Unit, onEnd: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var handled = onStart(down.position)
        var multi = false
        do {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.count { it.pressed }
            if (pressed >= 2) {
                if (!multi) { multi = true; if (handled) { onEnd(); handled = false } }
                val zoom = ev.calculateZoom(); val pan = ev.calculatePan(); val c = ev.calculateCentroid()
                if (c != Offset.Unspecified) vt.transform(c, pan, zoom)
                ev.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (!multi) {
                val ch = ev.changes.firstOrNull()
                if (ch != null && ch.pressed) {
                    if (handled) onDrag(ch.position, ch.positionChange()) else vt.panBy(ch.positionChange())
                    if (ch.positionChanged()) ch.consume()
                }
            }
        } while (ev.changes.any { it.pressed })
        if (handled) onEnd()
    }
}

/** Draws [bmp] through [vt]. */
fun DrawScope.drawBitmapAt(img: ImageBitmap, vt: ViewTransform, quality: FilterQuality = FilterQuality.Medium) {
    drawImage(
        img, dstOffset = IntOffset(vt.ox.toInt(), vt.oy.toInt()),
        dstSize = IntSize((img.width * vt.scale).toInt().coerceAtLeast(1), (img.height * vt.scale).toInt().coerceAtLeast(1)),
        filterQuality = quality
    )
}

/** Chequerboard shown behind transparent pictures. */
fun DrawScope.drawChecker(x: Float, y: Float, w: Float, h: Float, cell: Float = 14f) {
    clipRect(x, y, x + w, y + h) {
        drawRect(Color(0xFFE6E6E6), Offset(x, y), Size(w, h))
        var r = 0
        var yy = y
        while (yy < y + h) {
            var xx = x + if (r % 2 == 0) 0f else cell
            while (xx < x + w) { drawRect(Color(0xFFC8C8C8), Offset(xx, yy), Size(cell, cell)); xx += cell * 2 }
            yy += cell; r++
        }
    }
}

/** Pinch-to-zoom, pan and double-tap-free fit button: the plain viewer used for previews. */
@Composable
fun ZoomImage(bitmap: Bitmap, modifier: Modifier = Modifier, vt: ViewTransform = remember { ViewTransform() }, checker: Boolean = false) {
    val img = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF101010))
            .viewGestures(vt)
            .semantics { contentDescription = "Picture preview. Pinch to zoom, drag to move." }
    ) {
        vt.layout(size.width, size.height, bitmap.width.toFloat(), bitmap.height.toFloat())
        if (checker) drawChecker(vt.ox, vt.oy, bitmap.width * vt.scale, bitmap.height * vt.scale)
        drawBitmapAt(img, vt)
    }
}

/**
 * Before/after with a draggable divider. Both pictures must have the same aspect ratio; pinch and pan zoom them
 * together. Grab the divider handle to compare, anywhere else to move the picture.
 */
@Composable
fun CompareView(
    before: Bitmap, after: Bitmap, modifier: Modifier = Modifier, beforeLabel: String = "Before", afterLabel: String = "After",
    vt: ViewTransform = remember { ViewTransform() }
) {
    val b = remember(before) { before.asImageBitmap() }
    val a = remember(after) { after.asImageBitmap() }
    var split by remember { mutableFloatStateOf(0.5f) }
    var viewWidth by remember { mutableFloatStateOf(1f) }
    val handle = Neon.lime
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF101010))) {
        Canvas(
            Modifier.fillMaxSize().viewGestures(
                vt,
                onStart = { p -> kotlin.math.abs(p.x - split * viewWidth) < with(density) { 36.dp.toPx() } },
                onDrag = { p, _ -> split = (p.x / viewWidth).coerceIn(0.02f, 0.98f) }
            ).semantics { contentDescription = "Before and after comparison. Drag the handle sideways." }
        ) {
            viewWidth = size.width
            // The compared bitmaps can differ in pixel size (e.g. after 2x upscale): draw both into the "before" frame.
            vt.layout(size.width, size.height, before.width.toFloat(), before.height.toFloat())
            val dw = (before.width * vt.scale); val dh = (before.height * vt.scale)
            drawImage(b, dstOffset = IntOffset(vt.ox.toInt(), vt.oy.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()), filterQuality = FilterQuality.Medium)
            val x = split * size.width
            clipRect(left = x, top = 0f, right = size.width, bottom = size.height) {
                drawImage(a, dstOffset = IntOffset(vt.ox.toInt(), vt.oy.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()), filterQuality = FilterQuality.Medium)
            }
            drawLine(handle, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
            drawCircle(handle, 18.dp.toPx(), Offset(x, size.height / 2f))
            drawCircle(Neon.ink, 18.dp.toPx(), Offset(x, size.height / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        }
        Text(beforeLabel, Modifier.align(Alignment.TopStart).padding(10.dp).clip(Radius.pill).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = Color.White)
        Text(afterLabel, Modifier.align(Alignment.TopEnd).padding(10.dp).clip(Radius.pill).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

/** Full-screen, pinch-to-zoom preview of one or two pictures. */
@Composable
fun FullscreenPreview(bitmap: Bitmap, onClose: () -> Unit, compareWith: Bitmap? = null, checker: Boolean = false) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (compareWith != null) CompareView(compareWith, bitmap, Modifier.fillMaxSize().padding(top = 56.dp))
            else ZoomImage(bitmap, Modifier.fillMaxSize().padding(top = 56.dp), checker = checker)
            IconButton(onClick = onClose, Modifier.align(Alignment.TopEnd).padding(8.dp).statusBarsPadding()) {
                Icon(Icons.Outlined.Close, "Close preview", tint = Color.White)
            }
        }
    }
}

/** Undo / redo for any small immutable state. */
class History<T>(initial: T) {
    private val undoStack = ArrayList<T>()
    private val redoStack = ArrayList<T>()
    var current by mutableStateOf(initial); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    private val first = initial

    fun push(value: T) {
        if (value == current) return
        undoStack.add(current); redoStack.clear(); current = value; sync()
    }
    fun undo() { if (undoStack.isEmpty()) return; redoStack.add(current); current = undoStack.removeAt(undoStack.lastIndex); sync() }
    fun redo() { if (redoStack.isEmpty()) return; undoStack.add(current); current = redoStack.removeAt(redoStack.lastIndex); sync() }
    fun reset() { if (current == first && undoStack.isEmpty()) return; undoStack.add(current); redoStack.clear(); current = first; sync() }
    /** After a live drag with [replace]: records [previous] as the undo step. */
    fun commitFrom(previous: T) { if (previous != current) { undoStack.add(previous); redoStack.clear(); sync() } }
    /** Replaces the present value without adding an undo step (live dragging). */
    fun replace(value: T) { current = value }
    private fun sync() { canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty() }
}

/** Undo, redo, reset, fit and full-screen buttons shared by the editors. */
@Composable
fun EditorBar(
    modifier: Modifier = Modifier,
    canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit, onReset: () -> Unit,
    onFit: (() -> Unit)? = null, onFullscreen: (() -> Unit)? = null
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.Outlined.Undo, "Undo") }
        IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.Outlined.Redo, "Redo") }
        TextButton(onClick = onReset) { Text("Reset") }
        Spacer(Modifier.weight(1f))
        if (onFit != null) IconButton(onClick = onFit) { Icon(Icons.Outlined.FitScreen, "Fit to screen") }
        if (onFullscreen != null) IconButton(onClick = onFullscreen) { Icon(Icons.Outlined.Fullscreen, "Full-screen preview") }
    }
}

// ---------------------------------------------------------------------------------------------- thumbnails

object ThumbCache {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(context: android.content.Context, uri: Uri, side: Int): Bitmap? {
        val key = "$uri@$side"
        cache.get(key)?.let { return it }
        val b = try { ImageEngine.decode(context, uri, maxSide = side, maxPixels = 4_000_000L) } catch (_: Throwable) { null }
        if (b != null) cache.put(key, b)
        return b
    }

    fun drop(uri: Uri) { cache.snapshot().keys.filter { it.startsWith("$uri@") }.forEach { cache.remove(it) } }
}

/** A small picture loaded off the main thread. Shows a quiet placeholder while loading or when the file is gone. */
@Composable
fun UriThumb(uri: Uri, modifier: Modifier = Modifier, side: Int = 200, contentScale: ContentScale = ContentScale.Crop, rotation: Float = 0f) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { ThumbCache.get(context, uri, side) } }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize().then(if (rotation != 0f) Modifier.graphicsRotate(rotation) else Modifier), contentScale = contentScale)
        else Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.outline)
    }
}

private fun Modifier.graphicsRotate(deg: Float): Modifier = this.then(Modifier.graphicsLayer { rotationZ = deg })
