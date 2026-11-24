package com.linkvault.app.image

import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Color as AColor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.linkvault.app.pdf.safeName
import com.linkvault.app.pdf.uniqueFile
import com.linkvault.app.ui.components.ScreenTitle
import com.linkvault.app.ui.theme.Neon
import com.linkvault.app.ui.theme.Radius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private sealed interface BgSpec {
    data object Transparent : BgSpec
    data class Solid(val argb: Int) : BgSpec
    class Img(val bmp: Bitmap) : BgSpec
}

/** Picture + editable cut-out mask. The mask has one alpha byte per pixel of the working picture. */
private class CutState(val w: Int, val h: Int, val orig: IntArray, mask: ByteArray) {
    var mask: ByteArray = mask
    val initial: ByteArray = mask.copyOf()
    val preview: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    var version by mutableIntStateOf(0)
    private val undoStack = ArrayList<ByteArray>()
    private val redoStack = ArrayList<ByteArray>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var last: Offset? = null

    init { renderAll() }

    private fun px(i: Int, m: Int): Int {
        val c = orig[i]
        return AColor.argb(AColor.alpha(c) * m / 255, AColor.red(c), AColor.green(c), AColor.blue(c))
    }

    fun renderAll() {
        val out = IntArray(w * h)
        for (i in out.indices) out[i] = px(i, mask[i].toInt() and 0xFF)
        preview.setPixels(out, 0, w, 0, 0, w, h)
        version++
    }

    private fun sync() { canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty() }
    fun beginStroke() { undoStack.add(mask.copyOf()); if (undoStack.size > 12) undoStack.removeAt(0); redoStack.clear(); sync() }
    fun undo() { if (undoStack.isEmpty()) return; redoStack.add(mask); mask = undoStack.removeAt(undoStack.lastIndex); sync(); renderAll() }
    fun redo() { if (redoStack.isEmpty()) return; undoStack.add(mask); mask = redoStack.removeAt(redoStack.lastIndex); sync(); renderAll() }
    fun reset() { beginStroke(); mask = initial.copyOf(); renderAll() }

    /** Paints a soft round dab; erase makes pixels transparent, restore brings them back. */
    fun stamp(cx: Float, cy: Float, r: Float, erase: Boolean) {
        val x0 = max(0, (cx - r).toInt()); val x1 = min(w - 1, (cx + r).toInt() + 1)
        val y0 = max(0, (cy - r).toInt()); val y1 = min(h - 1, (cy + r).toInt() + 1)
        if (x1 < x0 || y1 < y0) return
        val rw = x1 - x0 + 1; val rh = y1 - y0 + 1
        val buf = IntArray(rw * rh)
        val soft = max(1f, r * 0.25f)
        for (y in y0..y1) for (x in x0..x1) {
            val i = y * w + x
            val d = hypot(x - cx, y - cy)
            val a = ((r - d) / soft).coerceIn(0f, 1f)
            if (a > 0f) {
                val cur = mask[i].toInt() and 0xFF
                mask[i] = (if (erase) cur * (1f - a) else max(cur.toFloat(), 255f * a)).roundToInt().coerceIn(0, 255).toByte()
            }
            buf[(y - y0) * rw + (x - x0)] = px(i, mask[i].toInt() and 0xFF)
        }
        preview.setPixels(buf, 0, rw, x0, y0, rw, rh)
        version++
    }

    fun stampLine(from: Offset, to: Offset, r: Float, erase: Boolean) {
        val dist = hypot(to.x - from.x, to.y - from.y)
        val step = max(1f, r / 3f)
        val n = max(1, (dist / step).toInt())
        for (k in 1..n) stamp(from.x + (to.x - from.x) * k / n, from.y + (to.y - from.y) * k / n, r, erase)
    }
}

private fun composeBg(cut: Bitmap, bg: BgSpec): Bitmap {
    val out = Bitmap.createBitmap(cut.width, cut.height, Bitmap.Config.ARGB_8888)
    val c = ACanvas(out)
    when (bg) {
        BgSpec.Transparent -> {}
        is BgSpec.Solid -> c.drawColor(bg.argb)
        is BgSpec.Img -> {
            val b = bg.bmp
            val target = cut.width.toFloat() / cut.height; val cur = b.width.toFloat() / b.height
            val src = if (cur > target) { val nw = (b.height * target).roundToInt().coerceIn(1, b.width); android.graphics.Rect((b.width - nw) / 2, 0, (b.width - nw) / 2 + nw, b.height) }
            else { val nh = (b.width / target).roundToInt().coerceIn(1, b.height); android.graphics.Rect(0, (b.height - nh) / 2, b.width, (b.height - nh) / 2 + nh) }
            c.drawBitmap(b, src, android.graphics.Rect(0, 0, cut.width, cut.height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
    }
    c.drawBitmap(cut, 0f, 0f, null)
    return out
}

/** Float confidence map from ML Kit's selfie segmenter, resampled to [w] x [h] if needed. */
private fun segmentPerson(bmp: Bitmap): ByteArray {
    val options = SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
    val seg = Segmentation.getClient(options)
    try {
        val m = Tasks.await(seg.process(InputImage.fromBitmap(bmp, 0)))
        val buf = m.buffer; buf.rewind()
        val mw = m.width; val mh = m.height
        val conf = FloatArray(mw * mh)
        for (i in conf.indices) conf[i] = buf.getFloat()
        val w = bmp.width; val h = bmp.height
        val full = if (mw == w && mh == h) conf else FloatArray(w * h) { i -> conf[min(mh - 1, (i / w) * mh / h) * mw + min(mw - 1, (i % w) * mw / w)] }
        return ImageEngine.maskFromConfidence(full, w, h)
    } finally { seg.close() }
}

private val bgSwatches = listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFD9F55A.toInt(), 0xFF3FD0C6.toInt(), 0xFFFF7F62.toInt(), 0xFF3B5BA5.toInt())

@Composable
fun BgRemoveScreen(sourceUrl: String?, onBack: () -> Unit) {
    val tool = ImageTool.BG_REMOVE
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outDir = remember { File(context.cacheDir, "imagetools-out/bg-${System.currentTimeMillis()}") }
    DisposableEffect(Unit) { onDispose { outDir.deleteRecursively() } }

    var source by remember { mutableStateOf<ImageSource?>(null) }
    var loading by remember { mutableStateOf(false) }
    var work by remember { mutableStateOf<Bitmap?>(null) }
    var cut by remember { mutableStateOf<CutState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var method by remember { mutableIntStateOf(0) }          // 0 person (AI), 1 plain colour
    var tolerance by remember { mutableIntStateOf(30) }
    var viewMode by remember { mutableIntStateOf(0) }        // 0 result, 1 compare
    var brush by remember { mutableIntStateOf(0) }           // 0 move, 1 erase, 2 restore
    var brushSize by remember { mutableFloatStateOf(28f) }
    var bg by remember { mutableStateOf<BgSpec>(BgSpec.Transparent) }
    var bgIdx by remember { mutableIntStateOf(-1) }
    var name by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<ImageSaved?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val vt = remember(work) { ViewTransform() }
    val density = androidx.compose.ui.platform.LocalDensity.current

    fun open(uris: List<Uri>) {
        scope.launch {
            loading = true
            val (found, bad) = loadSources(context, uris, emptySet(), 1)
            toastBad(context, bad)
            val s = found.firstOrNull()
            if (s != null) {
                val bmp = withContext(Dispatchers.IO) { try { ImageEngine.decode(context, s.uri, 2048) } catch (_: Throwable) { null } }
                if (bmp == null) error = "Couldn't open this picture." else {
                    source = s; work = bmp; cut = null; saved = null; error = null; name = s.baseName + "-nobg"
                }
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { ImageStore.markUsed(context, tool) }
    LaunchedEffect(sourceUrl) {
        if (sourceUrl != null) {
            loading = true
            val u = withContext(Dispatchers.IO) { RemoteImage.fetch(context, sourceUrl) }
            if (u == null) android.widget.Toast.makeText(context, "Couldn't download that picture.", android.widget.Toast.LENGTH_LONG).show() else open(listOf(u))
            loading = false
        }
    }
    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val b = withContext(Dispatchers.IO) { try { ImageEngine.decode(context, uri, 2048) } catch (_: Throwable) { null } }
            if (b != null) { bg = BgSpec.Img(b); bgIdx = 100 } else android.widget.Toast.makeText(context, "Couldn't read that picture.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun removeBackground() {
        val w = work ?: return
        busy = true; error = null; saved = null
        scope.launch {
            try {
                val c = withContext(Dispatchers.Default) {
                    val mask = if (method == 0) segmentPerson(w) else ImageEngine.maskFromBackgroundColor(w, tolerance)
                    val px = IntArray(w.width * w.height); w.getPixels(px, 0, w.width, 0, 0, w.width, w.height)
                    CutState(w.width, w.height, px, mask)
                }
                cut = c; viewMode = 0
            } catch (e: OutOfMemoryError) { error = "Not enough memory for this picture."
            } catch (e: Throwable) { error = "Couldn't detect the subject${e.message?.let { ": " + it.take(80) } ?: "."} Try the plain-background method." }
            busy = false
        }
    }

    fun export() {
        val s = source ?: return; val c = cut ?: return
        exporting = true; error = null
        scope.launch {
            try {
                saved = withContext(Dispatchers.Default) {
                    val full = ImageEngine.decode(context, s.uri, 4096, maxPixels = 12_000_000L)
                    val cutFull = ImageEngine.applyMask(full, c.mask, c.w, c.h)
                    full.recycle()
                    val result = if (bg == BgSpec.Transparent) cutFull else composeBg(cutFull, bg).also { cutFull.recycle() }
                    outDir.mkdirs()
                    val out = uniqueFile(outDir, "${safeName(name.ifBlank { s.baseName + "-nobg" })}.png")
                    ImageEngine.encodeToFile(result, out, ImgFormat.PNG, 100)
                    result.recycle()
                    val r = ImageStore.save(context, tool, out, "PNG")
                    outDir.deleteRecursively()
                    r
                }
            } catch (e: OutOfMemoryError) { error = "Not enough memory to export. Try a smaller picture."
            } catch (e: Throwable) { error = "Couldn't export the picture." }
            exporting = false
        }
    }

    val c = cut
    val w = work
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { ScreenTitle(tool.title, subtitle = tool.blurb, onBack = onBack) }
        if (source == null || w == null) {
            item {
                PanelCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToolBadge(tool); Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Choose a picture", style = MaterialTheme.typography.titleSmall)
                            Text("Runs on your phone. No picture is uploaded and no account or key is needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    PickImages(multi = false, enabled = !loading) { open(it) }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.padding(16.dp).fillMaxWidth()) }
            item { Box(Modifier.padding(horizontal = 16.dp)) { InfoNote("Two on-device methods: an AI model for people, and a colour-based method for pictures with a plain background. Pictures of objects on busy backgrounds may need the brush to fix.") } }
        } else {
            item {
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Original -> Background removed -> Export PNG
                    val stage = if (saved != null) 2 else if (c != null) 1 else 0
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("Original", "Background removed", "Export PNG").forEachIndexed { i, t ->
                            if (i > 0) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                            Text(
                                t, Modifier.clip(Radius.pill).background(if (i <= stage) Neon.lime else MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium, color = if (i <= stage) Neon.ink else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // ---- picture
                    val previewMod = Modifier.fillMaxWidth().height(360.dp)
                    if (c == null) ZoomImage(w, previewMod, vt)
                    else if (viewMode == 1) {
                        val composed = remember(c.version, bg) { composeBg(c.preview, bg) }
                        CompareView(w, composed, previewMod, "Original", "Result")
                    } else {
                        val img = remember(c.preview) { c.preview.asImageBitmap() }
                        val bgImg = (bg as? BgSpec.Img)?.bmp?.let { b -> remember(b) { b.asImageBitmap() } }
                        Canvas(
                            previewMod.clip(RoundedCornerShape(16.dp)).background(Color(0xFF101010)).viewGestures(
                                vt,
                                onStart = { p ->
                                    if (brush == 0) false else {
                                        c.beginStroke()
                                        val q = vt.toContent(p)
                                        val r = with(density) { brushSize.dp.toPx() } / 2f / vt.scale
                                        c.stamp(q.x, q.y, r, brush == 1); c.last = q
                                        true
                                    }
                                },
                                onDrag = { pos, _ ->
                                    val q = vt.toContent(pos)
                                    val r = with(density) { brushSize.dp.toPx() } / 2f / vt.scale
                                    c.last?.let { c.stampLine(it, q, r, brush == 1) }
                                    c.last = q
                                },
                                onEnd = { c.last = null }
                            )
                        ) {
                            vt.layout(size.width, size.height, c.w.toFloat(), c.h.toFloat())
                            val v = c.version   // redraw after every brush dab
                            val x = vt.ox; val y = vt.oy; val dw = c.w * vt.scale; val dh = c.h * vt.scale
                            when (val b = bg) {
                                BgSpec.Transparent -> drawChecker(x, y, dw, dh)
                                is BgSpec.Solid -> drawRect(Color(b.argb), Offset(x, y), androidx.compose.ui.geometry.Size(dw, dh))
                                is BgSpec.Img -> if (bgImg != null) {
                                    val target = c.w.toFloat() / c.h; val cur = bgImg.width.toFloat() / bgImg.height
                                    var sx = 0; var sy = 0; var sw = bgImg.width; var sh = bgImg.height
                                    if (cur > target) { sw = (bgImg.height * target).roundToInt().coerceIn(1, bgImg.width); sx = (bgImg.width - sw) / 2 }
                                    else { sh = (bgImg.width / target).roundToInt().coerceIn(1, bgImg.height); sy = (bgImg.height - sh) / 2 }
                                    drawImage(bgImg, IntOffset(sx, sy), IntSize(sw, sh), IntOffset(x.roundToInt(), y.roundToInt()), IntSize(dw.roundToInt(), dh.roundToInt()))
                                }
                            }
                            if (v >= 0) drawBitmapAt(img, vt)
                            drawRect(Color(0x33FFFFFF), Offset(x, y), androidx.compose.ui.geometry.Size(dw, dh), style = Stroke(1f))
                        }
                    }

                    if (c != null) {
                        EditorBar(
                            canUndo = c.canUndo, canRedo = c.canRedo, onUndo = { c.undo() }, onRedo = { c.redo() }, onReset = { c.reset() },
                            onFit = { vt.reset() }
                        )
                        OptChips(listOf("Result", "Compare with original"), viewMode) { viewMode = it }
                    }
                    if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())

                    // ---- detection
                    OptSection("Detect subject") {
                        OptChips(listOf("Person (on-device AI)", "Plain background (colour)"), method) { method = it }
                        Text(
                            if (method == 0) "An on-device AI model finds people. It does not recognise objects, animals or products."
                            else "Removes everything that looks like the picture's edge colour. Best on plain or softly shaded backgrounds.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (method == 1) LabeledSlider("Colour tolerance", "$tolerance", tolerance.toFloat(), 5f..90f, { tolerance = it.roundToInt() }, {})
                    }
                    Button(onClick = { removeBackground() }, enabled = !busy, shape = Radius.pill, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Icon(Icons.Outlined.ContentCut, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (c == null) "Remove background" else "Detect again")
                    }

                    if (c != null) {
                        // ---- brush
                        OptSection("Fix by hand") {
                            OptChips(listOf("Move / zoom", "Erase", "Restore"), brush) { brush = it; viewMode = 0 }
                            if (brush != 0) {
                                LabeledSlider("Brush size", "${brushSize.roundToInt()}", brushSize, 8f..96f, { brushSize = it }, {})
                                Text("Paint over leftovers with Erase, or bring back missing parts with Restore. Pinch with two fingers to zoom.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        // ---- background
                        OptSection("Background") {
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Swatch(selected = bgIdx == -1, label = "Transparent", color = null) { bg = BgSpec.Transparent; bgIdx = -1 }
                                bgSwatches.forEachIndexed { i, col -> Swatch(selected = bgIdx == i, label = "Colour ${i + 1}", color = Color(col)) { bg = BgSpec.Solid(col); bgIdx = i } }
                                FilledTonalButton(onClick = { bgPicker.launch(arrayOf("image/*")) }, shape = Radius.pill) {
                                    Icon(Icons.Outlined.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (bgIdx == 100) "Change image" else "Image…")
                                }
                            }
                        }
                        // ---- export
                        OutlinedTextField(
                            name, { name = it }, label = { Text("Output file name") }, singleLine = true, suffix = { Text(".png") },
                            supportingText = { Text("Saved as PNG" + if (bg == BgSpec.Transparent) " with a transparent background." else ".") },
                            modifier = Modifier.fillMaxWidth(), shape = Radius.small
                        )
                        error?.let { InfoNote(it, error = true) }
                        val r = saved
                        if (r != null) {
                            SavedRow(r, "${ImageEngine.sizeText(r.size)}  ·  PNG  ·  saved in ${ImageStore.IMAGE_FOLDER}")
                            OutlinedButton(onClick = { saved = null }, shape = Radius.pill) { Text("Process again") }
                        } else {
                            Button(onClick = { export() }, enabled = !exporting, shape = Radius.pill, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                if (exporting) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                                Text(if (exporting) "Exporting…" else "Export PNG", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    } else error?.let { InfoNote(it, error = true) }
                }
            }
        }
    }
}

@Composable
private fun Swatch(selected: Boolean, label: String, color: Color?, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick)
            .background(color ?: Color(0xFFE0E0E0))
            .then(if (selected) Modifier.padding(0.dp) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (color == null) Icon(Icons.Outlined.GridOn, label, Modifier.size(18.dp), tint = Color(0xFF555555))
        if (selected) Icon(Icons.Outlined.Check, "$label selected", Modifier.size(22.dp), tint = if (color == null || color.luminanceHigh()) Neon.ink else Color.White)
    }
}

private fun Color.luminanceHigh() = (red * 0.299f + green * 0.587f + blue * 0.114f) > 0.55f
