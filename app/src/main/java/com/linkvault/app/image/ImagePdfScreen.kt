package com.linkvault.app.image

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.linkvault.app.pdf.PdfEngine
import com.linkvault.app.pdf.safeName
import com.linkvault.app.pdf.uniqueFile
import com.linkvault.app.ui.components.ScreenTitle
import com.linkvault.app.ui.theme.Neon
import com.linkvault.app.ui.theme.Radius
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private data class PdfPageItem(val id: Long, val src: ImageSource, val op: CropOp = CropOp(), val auto: Boolean = false)

/** Where the picture sits on the page. All values in PDF points, y measured from the top. */
private class PageLayout(val pageW: Float, val pageH: Float, val x: Float, val y: Float, val w: Float, val h: Float, val fill: Boolean)

private const val MM = 72f / 25.4f
private val sizeLabels = listOf("A4", "A5", "Letter", "Original size", "Custom")
private val marginLabels = listOf("None", "Small", "Normal", "Large")
private val marginMm = listOf(0f, 6f, 12f, 20f)
private val qualityLabels = listOf("Low", "Medium", "High", "Maximum")
private val qualityJpeg = listOf(0.55f, 0.75f, 0.88f, 0.96f)
private val qualitySide = listOf(1280, 1800, 2480, Int.MAX_VALUE)

private class PdfOptions(
    val sizeIdx: Int, val customWmm: Float?, val customHmm: Float?, val orient: Int, val fill: Boolean, val margin: Float, val quality: Int
)

private fun pageDims(p: PdfPageItem): Pair<Int, Int> {
    val (w, h) = if (p.op.turns % 2 == 0) p.src.width to p.src.height else p.src.height to p.src.width
    return max(1, (w * p.op.rect.w).roundToInt()) to max(1, (h * p.op.rect.h).roundToInt())
}

private fun computeLayout(imgW: Int, imgH: Int, o: PdfOptions): PageLayout {
    val m = o.margin * MM
    if (o.sizeIdx == 3) {
        // Original size: the page is the picture (at 96 dpi) plus the margin.
        val w = imgW * 0.75f; val h = imgH * 0.75f
        val k = min(1f, 14000f / max(w + 2 * m, h + 2 * m))
        return PageLayout((w + 2 * m) * k, (h + 2 * m) * k, m * k, m * k, w * k, h * k, false)
    }
    var (bw, bh) = when (o.sizeIdx) {
        0 -> 595.28f to 841.89f
        1 -> 419.53f to 595.28f
        2 -> 612f to 792f
        else -> {
            val cw = o.customWmm; val ch = o.customHmm
            if (cw != null && ch != null && cw >= 20f && ch >= 20f) min(cw, 4000f) * MM to min(ch, 4000f) * MM else 595.28f to 841.89f
        }
    }
    val landscape = when (o.orient) { 1 -> false; 2 -> true; else -> imgW > imgH }
    if (landscape != (bw > bh)) { val t = bw; bw = bh; bh = t }
    val cw = max(10f, bw - 2 * m); val ch = max(10f, bh - 2 * m)
    return if (o.fill) PageLayout(bw, bh, (bw - cw) / 2f, (bh - ch) / 2f, cw, ch, true) else {
        val k = min(cw / imgW, ch / imgH)
        val w = imgW * k; val h = imgH * k
        PageLayout(bw, bh, (bw - w) / 2f, (bh - h) / 2f, w, h, false)
    }
}

/** Image → PDF, Screenshot → PDF and Multiple Images → One PDF. */
@Composable
fun ImagePdfScreen(toolId: String, sourceUrl: String?, onBack: () -> Unit) {
    val tool = remember(toolId) { ImageTool.fromId(toolId)?.takeIf { it.isPdf } ?: ImageTool.TO_PDF }
    val isShot = tool == ImageTool.SCREENSHOT_PDF
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { System.currentTimeMillis().toString() }
    val outDir = remember { File(context.cacheDir, "imagetools-out/$session") }
    DisposableEffect(Unit) { onDispose { outDir.deleteRecursively() } }

    val pages = remember { mutableStateListOf<PdfPageItem>() }
    var nextId by remember { mutableLongStateOf(1L) }
    var loading by remember { mutableStateOf(false) }
    var sizeIdx by remember { mutableIntStateOf(if (isShot) 3 else 0) }
    var customW by remember { mutableStateOf("210") }
    var customH by remember { mutableStateOf("297") }
    var orient by remember { mutableIntStateOf(0) }
    var fill by remember { mutableStateOf(false) }
    var marginIdx by remember { mutableIntStateOf(if (isShot) 0 else 1) }
    var quality by remember { mutableIntStateOf(2) }
    var autoTrim by remember { mutableStateOf(false) }
    var nameEdited by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(if (isShot) "Screenshots" else "Images") }
    var progress by remember { mutableFloatStateOf(0f) }
    var progressText by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<ImageSaved?>(null) }
    var resultPages by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    var previewId by remember { mutableStateOf<Long?>(null) }
    var cropId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(tool) { ImageStore.markUsed(context, tool) }

    fun add(uris: List<Uri>) {
        scope.launch {
            loading = true
            val room = 100 - pages.size
            val (found, bad) = loadSources(context, uris, emptySet(), room)   // the same picture may appear on several pages
            found.forEach { pages.add(PdfPageItem(nextId++, it)) }
            toastBad(context, bad)
            if (uris.size > room) android.widget.Toast.makeText(context, "Up to 100 pages at a time.", android.widget.Toast.LENGTH_SHORT).show()
            result = null; failure = null
            loading = false
            if (isShot && autoTrim) {
                // New pages also get the automatic border trim.
                withContext(Dispatchers.Default) {
                    for (i in pages.indices) {
                        val p = pages[i]
                        if (p.op.rect == NRect.FULL && !p.auto) {
                            val r = try { ImageEngine.detectBorders(ImageEngine.decode(context, p.src.uri, 400)) } catch (_: Throwable) { null }
                            if (r != null) pages[i] = p.copy(op = p.op.copy(rect = NRect(r.left, r.top, r.right, r.bottom)), auto = true)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(sourceUrl) {
        if (sourceUrl != null) {
            loading = true
            val uri = withContext(Dispatchers.IO) { RemoteImage.fetch(context, sourceUrl) }
            if (uri == null) android.widget.Toast.makeText(context, "Couldn't download that picture.", android.widget.Toast.LENGTH_LONG).show() else add(listOf(uri))
            loading = false
        }
    }

    fun options() = PdfOptions(sizeIdx, customW.toFloatOrNull(), customH.toFloatOrNull(), orient, fill, marginMm[marginIdx], quality)

    fun create() {
        if (pages.isEmpty() || running) return
        val snapshot = pages.toList()
        val o = options()
        val base = safeName(name.ifBlank { "Images" }).removeSuffix(".pdf")
        running = true; failure = null; result = null; progress = 0f; progressText = "Starting"
        job = scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    PdfEngine.init(context)
                    outDir.deleteRecursively(); outDir.mkdirs()
                    val out = uniqueFile(outDir, "$base.pdf")
                    PDDocument(MemoryUsageSetting.setupMixed(32L * 1024 * 1024)).use { doc ->
                        snapshot.forEachIndexed { i, pg ->
                            ensureActive()
                            progress = i / snapshot.size.toFloat(); progressText = "Adding page ${i + 1} of ${snapshot.size}"
                            val full = ImageEngine.decode(context, pg.src.uri, qualitySide[o.quality])
                            var bmp = applyCrop(full, pg.op)
                            if (bmp !== full) full.recycle()
                            val layout = computeLayout(pageDims(pg).first, pageDims(pg).second, o)
                            if (layout.fill) {
                                val target = layout.w / layout.h
                                val cur = bmp.width.toFloat() / bmp.height
                                val cropped = if (cur > target) {
                                    val nw = (bmp.height * target).roundToInt().coerceIn(1, bmp.width)
                                    Bitmap.createBitmap(bmp, (bmp.width - nw) / 2, 0, nw, bmp.height)
                                } else {
                                    val nh = (bmp.width / target).roundToInt().coerceIn(1, bmp.height)
                                    Bitmap.createBitmap(bmp, 0, (bmp.height - nh) / 2, bmp.width, nh)
                                }
                                if (cropped !== bmp) bmp.recycle()
                                bmp = cropped
                            }
                            val flat = ImageEngine.flatten(bmp)
                            val img = JPEGFactory.createFromImage(doc, flat, qualityJpeg[o.quality])
                            val page = PDPage(PDRectangle(layout.pageW, layout.pageH))
                            doc.addPage(page)
                            PDPageContentStream(doc, page).use { cs -> cs.drawImage(img, layout.x, layout.pageH - layout.y - layout.h, layout.w, layout.h) }
                            if (flat !== bmp) flat.recycle()
                            bmp.recycle()
                        }
                        ensureActive()
                        progress = 1f; progressText = "Saving"
                        doc.save(out)
                    }
                    val s = ImageStore.save(context, tool, out, "PDF")
                    outDir.deleteRecursively()
                    s
                }
                result = saved; resultPages = snapshot.size
            } catch (e: CancellationException) { throw e
            } catch (e: ImageProblem) { failure = e.message
            } catch (e: OutOfMemoryError) { failure = "Not enough memory. Try fewer pages or a lower quality."
            } catch (e: Throwable) { failure = "Couldn't create the PDF${e.message?.let { ": " + it.take(100) } ?: "."}" }
            finally { running = false; outDir.deleteRecursively() }
        }
    }

    val opts = options()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { ScreenTitle(tool.title, subtitle = tool.blurb, onBack = onBack) }

        item {
            PanelCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (pages.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToolBadge(tool)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(if (isShot) "Choose screenshots" else "Choose pictures", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (isShot) "In Photos, open the Screenshots album and select several. Nothing is uploaded." else "Pick one or many. Nothing is uploaded; originals stay untouched.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    PickImages(enabled = !loading) { add(it) }
                } else {
                    Text("${pages.size} ${if (pages.size == 1) "image" else "images"} → 1 PDF", style = MaterialTheme.typography.titleMedium)
                    Text("${pages.size} ${if (pages.size == 1) "page" else "pages"}. Hold the handle and drag to reorder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) }

        if (pages.isNotEmpty()) {
            item {
                PageList(
                    pages, opts, isShot, enabled = !running,
                    onPreview = { previewId = it }, onCrop = { cropId = it },
                    onRotate = { id ->
                        val i = pages.indexOfFirst { it.id == id }
                        if (i >= 0) {
                            val p = pages[i]; val r = p.op.rect
                            // A quarter turn clockwise moves (x, y) to (1 - y, x).
                            pages[i] = p.copy(op = p.op.copy(turns = (p.op.turns + 1) % 4, rect = NRect(1f - r.b, r.l, 1f - r.t, r.r)))
                        }
                    },
                    onRemove = { id -> pages.removeAll { it.id == id }; result = null }
                )
            }
            if (pages.size < 100) item { PickImages(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp), label = "Add more", enabled = !loading && !running) { add(it) } }

            item {
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OptSection("Page size") {
                        OptChips(sizeLabels, sizeIdx) { sizeIdx = it }
                        if (sizeIdx == 4) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(customW, { customW = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("Width (mm)") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f), shape = Radius.small)
                            Text("×")
                            OutlinedTextField(customH, { customH = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("Height (mm)") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f), shape = Radius.small)
                        }
                        if (sizeIdx == 3) Text("Each page takes the size of its picture.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (sizeIdx != 3) OptSection("Orientation") { OptChips(listOf("Auto", "Portrait", "Landscape"), orient) { orient = it } }
                    if (sizeIdx != 3) OptSection("Picture on page") {
                        OptChips(listOf("Fit whole picture", "Fill page (trims edges)"), if (fill) 1 else 0) { fill = it == 1 }
                    }
                    OptSection("Margin") { OptChips(marginLabels, marginIdx) { marginIdx = it } }
                    OptSection("Image quality") {
                        OptChips(qualityLabels, quality) { quality = it }
                        Text(
                            when (quality) { 0 -> "Smallest file. Good for sharing."; 1 -> "Balanced."; 2 -> "Sharp on screen and in print."; else -> "Largest file, best detail." },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isShot) {
                        OptToggle("Auto-trim plain borders", autoTrim) { on ->
                            autoTrim = on
                            scope.launch {
                                withContext(Dispatchers.Default) {
                                    for (i in pages.indices) {
                                        val p = pages[i]
                                        if (on && p.op.rect == NRect.FULL) {
                                            val r = try { ImageEngine.detectBorders(ImageEngine.decode(context, p.src.uri, 400)) } catch (_: Throwable) { null }
                                            if (r != null) pages[i] = p.copy(op = p.op.copy(rect = NRect(r.left, r.top, r.right, r.bottom)), auto = true)
                                        } else if (!on && p.auto) pages[i] = p.copy(op = p.op.copy(rect = NRect.FULL), auto = false)
                                    }
                                }
                            }
                        }
                        Text("Removes plain bars along the edges (black bars, solid margins). It can't see status-bar icons or app toolbars: use the crop button on a page for those.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        name, { name = it; nameEdited = true }, label = { Text("PDF file name") }, singleLine = true,
                        suffix = { Text(".pdf") }, modifier = Modifier.fillMaxWidth(), shape = Radius.small
                    )
                }
            }

            item {
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
                    val r = result
                    when {
                        running -> Surface(Modifier.fillMaxWidth(), shape = Radius.card, color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.padding(16.dp)) {
                                Text(progressText, style = MaterialTheme.typography.titleSmall)
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                                OutlinedButton(onClick = { job?.cancel() }, shape = Radius.pill) { Text("Cancel") }
                            }
                        }
                        r != null -> {
                            Surface(Modifier.fillMaxWidth(), shape = Radius.card, color = MaterialTheme.colorScheme.primaryContainer) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("$resultPages ${if (resultPages == 1) "image" else "images"} → 1 PDF", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text("Saved in ${ImageStore.PDF_FOLDER}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            SavedRow(r, "${ImageEngine.sizeText(r.size)}  ·  $resultPages ${if (resultPages == 1) "page" else "pages"}", Modifier.padding(top = 8.dp))
                            OutlinedButton(onClick = { result = null }, shape = Radius.pill, modifier = Modifier.padding(top = 10.dp)) { Text("Process again") }
                        }
                        else -> {
                            failure?.let { InfoNote(it, error = true, modifier = Modifier.padding(bottom = 8.dp)) }
                            Button(onClick = { create() }, enabled = !loading, shape = Radius.pill, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                Text("Create PDF  ·  ${pages.size} ${if (pages.size == 1) "page" else "pages"}", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- full page preview
    val pid = previewId
    if (pid != null) {
        val idx = pages.indexOfFirst { it.id == pid }
        if (idx < 0) previewId = null else PagePreviewDialog(
            pages[idx], idx, pages.size, options(),
            onPrev = { if (idx > 0) previewId = pages[idx - 1].id }, onNext = { if (idx < pages.lastIndex) previewId = pages[idx + 1].id },
            onClose = { previewId = null }
        )
    }
    // ---- per-page crop
    val cid = cropId
    if (cid != null) {
        val idx = pages.indexOfFirst { it.id == cid }
        if (idx < 0) cropId = null else PageCropDialog(
            pages[idx], idx + 1,
            onDone = { op -> val i = pages.indexOfFirst { it.id == cid }; if (i >= 0) pages[i] = pages[i].copy(op = op, auto = false); cropId = null },
            onCancel = { cropId = null }
        )
    }
}

// =================================================================================================

@Composable
private fun PageList(
    pages: SnapshotStateList<PdfPageItem>, opts: PdfOptions, showCrop: Boolean, enabled: Boolean,
    onPreview: (Long) -> Unit, onCrop: (Long) -> Unit, onRotate: (Long) -> Unit, onRemove: (Long) -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rowPx = with(density) { 84.dp.toPx() }
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        pages.forEach { pg ->
            key(pg.id) {
                val isDragging = dragId == pg.id
                Surface(
                    Modifier.padding(bottom = 8.dp).fillMaxWidth().height(76.dp)
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsOffset(if (isDragging) dragOffset else 0f),
                    shape = Radius.card, color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface,
                    shadowElevation = if (isDragging) 8.dp else 0.dp
                ) {
                    Row(Modifier.padding(start = 2.dp, end = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(width = 36.dp, height = 76.dp).pointerInput(pg.id, enabled) {
                                if (!enabled) return@pointerInput
                                detectDragGestures(
                                    onDragStart = { dragId = pg.id; dragOffset = 0f },
                                    onDragEnd = { dragId = null; dragOffset = 0f },
                                    onDragCancel = { dragId = null; dragOffset = 0f }
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    var idx = pages.indexOfFirst { it.id == pg.id }
                                    while (dragOffset > rowPx / 2f && idx < pages.lastIndex) { pages.add(idx + 1, pages.removeAt(idx)); idx++; dragOffset -= rowPx }
                                    while (dragOffset < -rowPx / 2f && idx > 0) { pages.add(idx - 1, pages.removeAt(idx)); idx--; dragOffset += rowPx }
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Outlined.DragHandle, "Drag to reorder page ${pages.indexOf(pg) + 1}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }

                        val (dw, dh) = pageDims(pg)
                        val layout = remember(pg, opts.sizeIdx, opts.orient, opts.fill, opts.margin, opts.customWmm, opts.customHmm) { computeLayout(dw, dh, opts) }
                        val bmp by rememberPageBitmap(pg, 220)
                        Box(Modifier.width(64.dp).height(64.dp).clickable { onPreview(pg.id) }, contentAlignment = Alignment.Center) {
                            val ar = (layout.pageW / layout.pageH).coerceIn(0.3f, 3f)
                            PagePreview(layout, bmp, Modifier.then(if (ar >= 1f) Modifier.width(60.dp) else Modifier.height(60.dp)).aspectRatio(ar))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).clickable { onPreview(pg.id) }) {
                            Text("Page ${pages.indexOf(pg) + 1}", style = MaterialTheme.typography.labelLarge)
                            Text(pg.src.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (pg.op.rect != NRect.FULL) Text(if (pg.auto) "Auto-trimmed" else "Cropped", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { onRotate(pg.id) }, Modifier.size(38.dp), enabled = enabled) { Icon(Icons.Outlined.RotateRight, "Rotate page", Modifier.size(20.dp)) }
                        if (showCrop) IconButton(onClick = { onCrop(pg.id) }, Modifier.size(38.dp), enabled = enabled) { Icon(Icons.Outlined.Crop, "Crop page", Modifier.size(20.dp)) }
                        IconButton(onClick = { onRemove(pg.id) }, Modifier.size(38.dp), enabled = enabled) { Icon(Icons.Outlined.Close, "Remove page", Modifier.size(20.dp)) }
                    }
                }
            }
        }
    }
}

private fun Modifier.graphicsOffset(y: Float): Modifier = this.then(Modifier.offset { IntOffset(0, y.roundToInt()) })

@Composable
private fun rememberPageBitmap(pg: PdfPageItem, side: Int): State<Bitmap?> {
    val context = LocalContext.current
    return produceState<Bitmap?>(null, pg.src.uri, pg.op, side) {
        value = withContext(Dispatchers.IO) {
            try {
                val base = if (side <= 256) ThumbCache.get(context, pg.src.uri, side) else ImageEngine.decode(context, pg.src.uri, side)
                base?.let { applyCrop(it, pg.op) }
            } catch (_: Throwable) { null }
        }
    }
}

/** A white page with the picture placed where it will be in the PDF. */
@Composable
private fun PagePreview(layout: PageLayout, bmp: Bitmap?, modifier: Modifier) {
    val img = remember(bmp) { bmp?.asImageBitmap() }
    Canvas(modifier.background(Color.White).clip(RoundedCornerShape(2.dp))) {
        val s = size.width / layout.pageW
        if (img != null) {
            var sx = 0; var sy = 0; var sw = img.width; var sh = img.height
            if (layout.fill) {
                val target = layout.w / layout.h; val cur = img.width.toFloat() / img.height
                if (cur > target) { sw = (img.height * target).roundToInt().coerceIn(1, img.width); sx = (img.width - sw) / 2 }
                else { sh = (img.width / target).roundToInt().coerceIn(1, img.height); sy = (img.height - sh) / 2 }
            }
            drawImage(
                img, srcOffset = IntOffset(sx, sy), srcSize = IntSize(sw, sh),
                dstOffset = IntOffset((layout.x * s).roundToInt(), (layout.y * s).roundToInt()),
                dstSize = IntSize((layout.w * s).roundToInt().coerceAtLeast(1), (layout.h * s).roundToInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.Low
            )
        }
        drawRect(Color(0x33000000), Offset.Zero, Size(size.width, size.height), style = Stroke(2f))
    }
}

@Composable
private fun PagePreviewDialog(pg: PdfPageItem, index: Int, total: Int, opts: PdfOptions, onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    val bmp by rememberPageBitmap(pg, 1400)
    val (dw, dh) = pageDims(pg)
    val layout = remember(pg, opts.sizeIdx, opts.orient, opts.fill, opts.margin) { computeLayout(dw, dh, opts) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF101010)) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Page ${index + 1} of $total", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close preview", tint = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val ar = layout.pageW / layout.pageH
                        val w = if (maxWidth / maxHeight > ar) maxHeight * ar else maxWidth
                        PagePreview(layout, bmp, Modifier.width(w).aspectRatio(ar))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilledTonalIconButton(onClick = onPrev, enabled = index > 0) { Icon(Icons.Outlined.ChevronLeft, "Previous page") }
                    FilledTonalIconButton(onClick = onNext, enabled = index < total - 1) { Icon(Icons.Outlined.ChevronRight, "Next page") }
                }
            }
        }
    }
}

@Composable
private fun PageCropDialog(pg: PdfPageItem, number: Int, onDone: (CropOp) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val base by produceState<Bitmap?>(null, pg.src.uri) { value = withContext(Dispatchers.IO) { try { ImageEngine.decode(context, pg.src.uri, 1600) } catch (_: Throwable) { null } } }
    val history = remember { History(pg.op) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Text("Crop page $number", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Button(onClick = { onDone(history.current) }, shape = Radius.pill) { Text("Done") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val b = base
                    if (b == null) Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else CropPanel(b, history, editorHeight = 360)
                }
            }
        }
    }
}
