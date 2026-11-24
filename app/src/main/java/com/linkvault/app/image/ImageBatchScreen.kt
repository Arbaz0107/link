package com.linkvault.app.image

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linkvault.app.pdf.safeName
import com.linkvault.app.pdf.uniqueFile
import com.linkvault.app.ui.components.ScreenTitle
import com.linkvault.app.ui.components.SectionHeader
import com.linkvault.app.ui.theme.Neon
import com.linkvault.app.ui.theme.Radius
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private sealed interface ItemState {
    data object Pending : ItemState
    data object Running : ItemState
    data class Done(val saved: ImageSaved, val detail: String, val before: Long) : ItemState
    data class Failed(val message: String) : ItemState
    data class Skipped(val message: String) : ItemState
}

private class Processed(val file: File, val format: ImgFormat, val w: Int, val h: Int, val note: String? = null)

private data class RotOp(val turns: Int = 0, val flipH: Boolean = false, val flipV: Boolean = false, val angle: Float = 0f)
private data class EnhOp(val preset: Int = 0, val p: ImageEngine.EnhanceParams = ImageEngine.EnhanceParams(), val up: Int = 1, val auto: Boolean = true)
private class Est(val bytes: Long, val after: Bitmap?)

private val enhancePresets = listOf("Auto Enhance", "Sharpen", "Low Light", "Clear", "Portrait", "High Detail", "Manual")
private val enhanceValues = listOf(
    null,
    ImageEngine.EnhanceParams(sharpen = 0.7f, denoise = 0.1f, contrast = 0.05f, detail = 0.1f),
    ImageEngine.EnhanceParams(sharpen = 0.3f, denoise = 0.4f, brightness = 0.35f, contrast = 0.15f, saturation = 0.1f, detail = 0.2f),
    ImageEngine.EnhanceParams(sharpen = 0.4f, denoise = 0.2f, brightness = 0.05f, contrast = 0.2f, saturation = 0.1f, detail = 0.35f),
    ImageEngine.EnhanceParams(sharpen = 0.25f, denoise = 0.45f, brightness = 0.08f, contrast = 0.05f, saturation = 0.05f),
    ImageEngine.EnhanceParams(sharpen = 0.8f, denoise = 0.15f, contrast = 0.15f, saturation = 0.1f, detail = 0.6f)
)

/**
 * Compress, Resize, Convert, Rotate/Flip and Quality Enhancer share this screen: choose pictures, set options,
 * process them one by one in the background (cancel any time), then Open / Share / Save as / Rename each result.
 */
@Composable
fun ImageBatchScreen(toolId: String, sourceUrl: String?, onBack: () -> Unit) {
    val tool = remember(toolId) { ImageTool.fromId(toolId) ?: ImageTool.COMPRESS }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { System.currentTimeMillis().toString() }
    val outDir = remember { File(context.cacheDir, "imagetools-out/$session") }
    DisposableEffect(Unit) { onDispose { outDir.deleteRecursively() } }

    val sources = remember { mutableStateListOf<ImageSource>() }
    var loading by remember { mutableStateOf(false) }
    val items = remember { mutableStateListOf<ItemState>() }
    val runList = remember { mutableStateListOf<ImageSource>() }
    var running by remember { mutableStateOf(false) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var currentName by remember { mutableStateOf("") }
    var optionsError by remember { mutableStateOf<String?>(null) }

    // ---- options
    var preset by remember { mutableIntStateOf(1) }
    var quality by remember { mutableIntStateOf(75) }
    var maxSide by remember { mutableIntStateOf(0) }
    var outFmt by remember { mutableIntStateOf(0) }                 // compress: 0 same, 1 JPG, 2 WEBP
    var resizeMode by remember { mutableIntStateOf(1) }             // 0 25%, 1 50%, 2 75%, 3 1080, 4 720, 5 custom
    var customPercent by remember { mutableStateOf(false) }
    var customW by remember { mutableStateOf("") }
    var customH by remember { mutableStateOf("") }
    var customPct by remember { mutableStateOf("50") }
    var lockAspect by remember { mutableStateOf(true) }
    var convTarget by remember { mutableIntStateOf(0) }
    var convQuality by remember { mutableIntStateOf(92) }
    var convLossless by remember { mutableStateOf(false) }
    var convBgWhite by remember { mutableStateOf(true) }
    val rot = remember { History(RotOp()) }
    val enh = remember { History(EnhOp()) }
    var nameEdited by remember { mutableStateOf(false) }
    var nameField by remember { mutableStateOf("") }

    val suffix = when (tool) {
        ImageTool.COMPRESS -> "-compressed"; ImageTool.RESIZE -> "-resized"; ImageTool.CONVERT -> "-converted"
        ImageTool.ROTATE -> "-rotated"; else -> "-enhanced"
    }
    val first = sources.firstOrNull()
    val defaultName = if (sources.size == 1) first!!.baseName + suffix else suffix
    LaunchedEffect(sources.size == 1) { nameEdited = false }
    LaunchedEffect(defaultName) { if (!nameEdited) nameField = defaultName }

    // ---- adding pictures
    fun addUris(uris: List<Uri>) {
        scope.launch {
            loading = true
            val room = 100 - sources.size
            val (found, bad) = loadSources(context, uris, sources.map { it.uri.toString() }.toSet(), room)
            sources.addAll(found)
            toastBad(context, bad)
            if (uris.size > room) android.widget.Toast.makeText(context, "Up to 100 pictures at a time.", android.widget.Toast.LENGTH_SHORT).show()
            items.clear(); runList.clear()
            loading = false
        }
    }
    // A picture handed over from a saved link.
    LaunchedEffect(sourceUrl) {
        if (sourceUrl != null) {
            loading = true
            val uri = withContext(Dispatchers.IO) { RemoteImage.fetch(context, sourceUrl) }
            if (uri == null) android.widget.Toast.makeText(context, "Couldn't download that picture.", android.widget.Toast.LENGTH_LONG).show()
            else addUris(listOf(uri))
            loading = false
        }
    }
    LaunchedEffect(tool) { ImageStore.markUsed(context, tool) }

    // ---- preview of the first picture
    val preview by produceState<Bitmap?>(null, first?.uri) {
        value = null
        if (first != null) value = withContext(Dispatchers.IO) { try { ImageEngine.decode(context, first.uri, 1080) } catch (_: Throwable) { null } }
    }

    fun formatFor(src: ImageSource): ImgFormat = src.format ?: if (src.hasAlpha) ImgFormat.PNG else ImgFormat.JPG

    // ---- compress estimate (first picture, real encode)
    var est by remember { mutableStateOf<Est?>(null) }
    if (tool == ImageTool.COMPRESS) {
        LaunchedEffect(first?.uri, quality, maxSide, outFmt, preview) {
            est = null
            val f = first ?: return@LaunchedEffect
            if (preview == null) return@LaunchedEffect
            delay(350)
            est = withContext(Dispatchers.Default) {
                try {
                    val bmp = ImageEngine.decode(context, f.uri, if (maxSide > 0) maxSide else Int.MAX_VALUE)
                    val fmt = when (outFmt) { 1 -> ImgFormat.JPG; 2 -> ImgFormat.WEBP; else -> formatFor(f) }
                    val bytes = ImageEngine.encodeToBytes(bmp, fmt, quality)
                    bmp.recycle()
                    Est(bytes.size.toLong(), ImageEngine.decodeBytes(bytes, 1080))
                } catch (_: Throwable) { null }
            }
        }
    }

    // ---- enhance preview
    val autoParams = remember(preview) { preview?.let { ImageEngine.autoParams(it) } ?: ImageEngine.EnhanceParams() }
    val enhOp = enh.current
    val effective = if (enhOp.auto) autoParams else enhOp.p
    var enhanced by remember { mutableStateOf<Bitmap?>(null) }
    var enhanceError by remember { mutableStateOf<String?>(null) }
    if (tool == ImageTool.ENHANCE) {
        LaunchedEffect(preview, effective, enhOp.up) {
            val p = preview ?: return@LaunchedEffect
            delay(250)
            try {
                enhanced = withContext(Dispatchers.Default) { ImageEngine.enhance(p, effective, enhOp.up) }
                enhanceError = null
            } catch (e: ImageProblem) { enhanceError = e.message } catch (_: Throwable) { enhanceError = "Couldn't preview this picture." }
        }
    }

    // ---- sizes
    fun targetDims(s: ImageSource): Pair<Int, Int> {
        val w = s.width; val h = s.height
        fun scaled(k: Float) = max(1, (w * k).roundToInt()) to max(1, (h * k).roundToInt())
        return when (resizeMode) {
            0 -> scaled(0.25f); 1 -> scaled(0.5f); 2 -> scaled(0.75f)
            3 -> scaled(1080f / max(w, h)); 4 -> scaled(720f / max(w, h))
            else -> if (customPercent) scaled((customPct.toFloatOrNull() ?: 100f).coerceIn(1f, 400f) / 100f) else {
                val cw = customW.toIntOrNull()?.takeIf { it > 0 }; val ch = customH.toIntOrNull()?.takeIf { it > 0 }
                when {
                    cw == null && ch == null -> w to h
                    !lockAspect -> (cw ?: w) to (ch ?: h)
                    cw != null && ch != null -> scaled(min(cw.toFloat() / w, ch.toFloat() / h))
                    cw != null -> scaled(cw.toFloat() / w)
                    else -> scaled(ch!!.toFloat() / h)
                }
            }
        }
    }

    // ---- the work
    fun outFile(src: ImageSource, fmt: ImgFormat): File {
        val base = if (sources.size == 1) safeName(nameField.ifBlank { defaultName }) else safeName(src.baseName + nameField.ifBlank { suffix })
        return uniqueFile(outDir, "${base.removeSuffix("." + fmt.ext)}.${fmt.ext}")
    }

    fun processOne(src: ImageSource, check: () -> Unit): Processed {
        when (tool) {
            ImageTool.COMPRESS -> {
                val fmt = when (outFmt) { 1 -> ImgFormat.JPG; 2 -> ImgFormat.WEBP; else -> formatFor(src) }
                val resized = maxSide > 0 && max(src.width, src.height) > maxSide
                val bmp = ImageEngine.decode(context, src.uri, if (maxSide > 0) maxSide else Int.MAX_VALUE)
                check()
                val out = outFile(src, fmt)
                try { ImageEngine.encodeToFile(bmp, out, fmt, quality, lossless = false) } finally { }
                val w = bmp.width; val h = bmp.height
                bmp.recycle()
                var note: String? = null
                if (fmt == formatFor(src) && !resized && src.size > 0 && out.length() >= src.size) {
                    // Re-encoding would make it bigger: keep the original bytes under the new name.
                    context.contentResolver.openInputStream(src.uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    note = "Already compact, kept as is"
                }
                return Processed(out, fmt, w, h, note)
            }
            ImageTool.RESIZE -> {
                val (tw, th) = targetDims(src)
                if (tw == src.width && th == src.height) throw SkipItem("Already that size")
                val bmp = ImageEngine.decodeTo(context, src.uri, tw, th)
                check()
                val fmt = formatFor(src)
                val out = outFile(src, fmt)
                ImageEngine.encodeToFile(bmp, out, fmt, 95)
                bmp.recycle()
                return Processed(out, fmt, tw, th)
            }
            ImageTool.CONVERT -> {
                val target = ImgFormat.entries[convTarget]
                if (src.format == target) throw SkipItem("Already ${target.label}")
                val bmp = ImageEngine.decode(context, src.uri)
                check()
                val out = outFile(src, target)
                ImageEngine.encodeToFile(bmp, out, target, convQuality, convLossless, if (convBgWhite) Color.WHITE else Color.BLACK)
                val w = bmp.width; val h = bmp.height
                bmp.recycle()
                return Processed(out, target, w, h)
            }
            ImageTool.ROTATE -> {
                val op = rot.current
                if (op == RotOp()) throw SkipItem("No change chosen")
                val fmt = formatFor(src)
                val bmp = ImageEngine.decode(context, src.uri)
                val t = ImageEngine.transform(bmp, op.turns, op.flipH, op.flipV)
                if (t !== bmp) bmp.recycle()
                check()
                val r = ImageEngine.rotateFree(t, op.angle, if (fmt == ImgFormat.JPG) Color.WHITE else Color.TRANSPARENT)
                if (r !== t) t.recycle()
                val out = outFile(src, fmt)
                ImageEngine.encodeToFile(r, out, fmt, 95)
                val w = r.width; val h = r.height
                r.recycle()
                return Processed(out, fmt, w, h)
            }
            else -> {
                val op = enh.current
                val bmp = ImageEngine.decode(context, src.uri, maxPixels = if (op.up == 2) 3_000_000L else 10_000_000L)
                check()
                val params = if (op.auto) ImageEngine.autoParams(bmp) else op.p
                val r = ImageEngine.enhance(bmp, params, op.up, check)
                bmp.recycle()
                val fmt = formatFor(src)
                val out = outFile(src, fmt)
                ImageEngine.encodeToFile(r, out, fmt, 95)
                val w = r.width; val h = r.height
                r.recycle()
                return Processed(out, fmt, w, h)
            }
        }
    }

    fun start(retryOnly: Boolean) {
        if (running || sources.isEmpty()) return
        optionsError = null
        if (tool == ImageTool.RESIZE && resizeMode == 5) {
            val ok = if (customPercent) (customPct.toFloatOrNull() ?: 0f) > 0f else (customW.toIntOrNull() ?: 0) > 0 || (customH.toIntOrNull() ?: 0) > 0
            if (!ok) { optionsError = "Enter a width, a height or a percentage first."; return }
        }
        if (!retryOnly || items.size != runList.size) {
            runList.clear(); runList.addAll(sources); items.clear(); repeat(runList.size) { items.add(ItemState.Pending) }
        }
        val targets = runList.indices.filter { if (retryOnly) items[it] is ItemState.Failed else true }
        targets.forEach { items[it] = ItemState.Pending }
        running = true
        runJob = scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    outDir.mkdirs()
                    for (i in targets) {
                        ensureActive()
                        val src = runList[i]
                        currentName = src.name
                        items[i] = ItemState.Running
                        try {
                            val r = processOne(src) { ensureActive() }
                            val saved = ImageStore.save(context, tool, r.file, r.format.label)
                            r.file.delete()
                            val detail = (if (tool == ImageTool.COMPRESS) sizeChange(src.size, saved.size)
                                else "${src.width}×${src.height} → ${r.w}×${r.h}  ·  ${sizeChange(src.size, saved.size)}") + (r.note?.let { "  ·  $it" } ?: "")
                            items[i] = ItemState.Done(saved, detail, src.size)
                        } catch (e: CancellationException) { items[i] = ItemState.Pending; throw e
                        } catch (e: SkipItem) { items[i] = ItemState.Skipped(e.message ?: "Skipped")
                        } catch (e: ImageProblem) { items[i] = ItemState.Failed(e.message ?: "Couldn't process this picture.")
                        } catch (e: OutOfMemoryError) { items[i] = ItemState.Failed("Not enough memory for this picture.")
                        } catch (e: Throwable) { items[i] = ItemState.Failed("Something went wrong: ${e.message?.take(80) ?: "unknown error"}") }
                    }
                }
            } catch (_: CancellationException) {
            } finally { running = false; currentName = ""; outDir.deleteRecursively() }
        }
    }

    val doneCount = items.count { it is ItemState.Done }
    val failedCount = items.count { it is ItemState.Failed }
    val skippedCount = items.count { it is ItemState.Skipped }
    val finishedCount = items.count { it !is ItemState.Pending && it !is ItemState.Running }
    val hasResults = items.isNotEmpty()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { ScreenTitle(tool.title, subtitle = tool.blurb, onBack = onBack) }

        // ---------- 1. pictures
        item {
            PanelCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (sources.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToolBadge(tool)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Choose pictures", style = MaterialTheme.typography.titleSmall)
                            Text("Everything stays on your phone. Your originals are never changed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    PickImages(enabled = !loading) { addUris(it) }
                } else {
                    Text("${sources.size} ${if (sources.size == 1) "picture" else "pictures"} · ${ImageEngine.sizeText(sources.sumOf { it.size })}", style = MaterialTheme.typography.titleSmall)
                    Text("Up to 100 at a time. Very large photos are processed at up to 16 megapixels.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) }
        itemsIndexed(sources.toList(), key = { _, s -> s.uri.toString() }) { _, s ->
            Surface(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).fillMaxWidth(), shape = Radius.card, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    UriThumb(s.uri, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${s.width}×${s.height}  ·  ${ImageEngine.sizeText(s.size)}  ·  ${s.format?.label ?: "Image"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { if (!running) { sources.remove(s); items.clear(); runList.clear() } }, enabled = !running) {
                        Icon(Icons.Outlined.Close, "Remove ${s.name}", Modifier.size(20.dp))
                    }
                }
            }
        }
        if (sources.isNotEmpty() && sources.size < 100) item {
            PickImages(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp), label = "Add more", enabled = !loading && !running) { addUris(it) }
        }

        if (sources.isNotEmpty()) {
            // ---------- 2. options
            item {
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (tool) {
                        ImageTool.COMPRESS -> {
                            OptSection("Compression") {
                                OptChips(listOf("Low", "Balanced", "High", "Custom"), preset) { i ->
                                    preset = i
                                    when (i) { 0 -> { quality = 90; maxSide = 0 }; 1 -> { quality = 75; maxSide = 0 }; 2 -> { quality = 50; maxSide = 2048 } }
                                }
                                Text(
                                    when (preset) { 0 -> "Light saving, practically no visible change."; 1 -> "Good saving, still looks great."; 2 -> "Smallest files; also limits the longest side to 2048 px."; else -> "You choose the quality and the size limit." },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            LabeledSlider("Quality", "$quality%", quality.toFloat(), 10f..100f, { quality = it.roundToInt(); preset = 3 }, {})
                            OptSection("Longest side") {
                                val values = listOf(0, 4096, 2048, 1080)
                                OptChips(listOf("Original", "4096 px", "2048 px", "1080 px"), values.indexOf(maxSide).coerceAtLeast(0)) { maxSide = values[it]; preset = 3 }
                            }
                            OptSection("Save as") {
                                OptChips(listOf("Same format", "JPG", "WEBP"), outFmt) { outFmt = it }
                                if (outFmt == 0 && sources.any { it.format == ImgFormat.PNG }) Text("PNG is lossless, so quality has no effect on PNG files. Choose JPG or WEBP (or a smaller longest side) to shrink them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (outFmt == 1 && sources.any { it.hasAlpha }) Text("Transparent areas become white in JPG.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            CompressResult(first!!, est, preview, sources)
                        }
                        ImageTool.RESIZE -> {
                            OptSection("Size") {
                                OptChips(listOf("25%", "50%", "75%", "1080 px", "720 px", "Custom"), resizeMode) { resizeMode = it }
                                if (resizeMode == 3 || resizeMode == 4) Text("Sets the longest side; the other side follows.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (resizeMode == 5) {
                                OptChips(listOf("Pixels", "Percent"), if (customPercent) 1 else 0) { customPercent = it == 1 }
                                if (customPercent) {
                                    OutlinedTextField(customPct, { customPct = it.filter(Char::isDigit).take(3) }, label = { Text("Percent") }, singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), shape = Radius.small)
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(customW, { v ->
                                            customW = v.filter(Char::isDigit).take(5)
                                            if (lockAspect && first != null) customH = customW.toIntOrNull()?.let { (it.toFloat() * first.height / first.width).roundToInt().toString() } ?: ""
                                        }, label = { Text("Width") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), shape = Radius.small)
                                        IconButton(onClick = { lockAspect = !lockAspect }) {
                                            Icon(if (lockAspect) Icons.Outlined.Lock else Icons.Outlined.LockOpen, if (lockAspect) "Aspect ratio locked" else "Aspect ratio unlocked")
                                        }
                                        OutlinedTextField(customH, { v ->
                                            customH = v.filter(Char::isDigit).take(5)
                                            if (lockAspect && first != null) customW = customH.toIntOrNull()?.let { (it.toFloat() * first.width / first.height).roundToInt().toString() } ?: ""
                                        }, label = { Text("Height") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), shape = Radius.small)
                                    }
                                    Text(if (lockAspect) "Aspect ratio locked: other pictures fit inside this box." else "Aspect ratio unlocked: every picture is stretched to exactly this size.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            val (tw, th) = targetDims(first!!)
                            Surface(shape = Radius.small, color = MaterialTheme.colorScheme.primaryContainer) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text("${first.width} × ${first.height}  →  $tw × $th", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(if (sources.size > 1) "Shown for the first picture. Each picture gets its own size." else "Resulting size", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            if (tw.toLong() * th > ImageEngine.MAX_OUT_PIXELS) InfoNote("That is more than ${ImageEngine.MAX_OUT_PIXELS / 1_000_000} megapixels; this phone can't make it.", error = true)
                        }
                        ImageTool.CONVERT -> {
                            OptSection("Convert to") { OptChips(ImgFormat.entries.map { it.label }, convTarget) { convTarget = it } }
                            val tgt = ImgFormat.entries[convTarget]
                            val summary = sources.groupBy { it.format?.label ?: "Image" }.entries.joinToString(", ") { "${it.value.size} ${it.key}" }
                            Text("$summary → ${tgt.label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (tgt == ImgFormat.PNG) Text("PNG is lossless: no quality is lost and transparency is kept.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (tgt == ImgFormat.JPG || (tgt == ImgFormat.WEBP && !convLossless)) LabeledSlider("Quality", "$convQuality%", convQuality.toFloat(), 50f..100f, { convQuality = it.roundToInt() }, {})
                            if (tgt == ImgFormat.WEBP && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) OptToggle("Lossless WEBP (larger, exact)", convLossless) { convLossless = it }
                            if (tgt == ImgFormat.JPG) {
                                OptSection("Transparent areas become") { OptChips(listOf("White", "Black"), if (convBgWhite) 0 else 1) { convBgWhite = it == 0 } }
                                if (sources.any { it.hasAlpha }) Text("Some pictures may have transparency; JPG can't keep it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (sources.any { it.format == tgt }) Text("Pictures already in ${tgt.label} will be skipped.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ImageTool.ROTATE -> RotateOptions(rot, preview)
                        else -> EnhanceOptions(enh, effective, preview, enhanced, enhanceError)
                    }

                    OutlinedTextField(
                        nameField, { nameField = it; nameEdited = true },
                        label = { Text(if (sources.size == 1) "Output file name" else "Name added after each file name") }, singleLine = true,
                        supportingText = { Text(if (sources.size == 1) "Your original is never changed. A new file is saved." else "e.g. holiday.jpg becomes holiday$suffix.jpg") },
                        modifier = Modifier.fillMaxWidth(), shape = Radius.small
                    )
                    optionsError?.let { InfoNote(it, error = true) }
                }
            }

            // ---------- 3. action / progress / results
            item {
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
                    if (running) {
                        Surface(Modifier.fillMaxWidth(), shape = Radius.card, color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Processing ${min(finishedCount + 1, items.size)} of ${items.size}", style = MaterialTheme.typography.titleSmall)
                                Text(currentName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                LinearProgressIndicator(progress = { if (items.isEmpty()) 0f else finishedCount / items.size.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                                Text("Completed $doneCount  ·  Failed $failedCount" + if (skippedCount > 0) "  ·  Skipped $skippedCount" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = { runJob?.cancel() }, shape = Radius.pill, modifier = Modifier.padding(top = 12.dp)) { Text("Cancel") }
                            }
                        }
                    } else if (hasResults) {
                        val saved = items.filterIsInstance<ItemState.Done>()
                        val before = saved.sumOf { it.before }; val after = saved.sumOf { it.saved.size }
                        Surface(Modifier.fillMaxWidth(), shape = Radius.card, color = MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "$doneCount done" + (if (failedCount > 0) "  ·  $failedCount failed" else "") + (if (skippedCount > 0) "  ·  $skippedCount skipped" else ""),
                                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                if (tool == ImageTool.COMPRESS && saved.isNotEmpty() && before > 0)
                                    Text("Original: ${ImageEngine.sizeText(before)} → Compressed: ${ImageEngine.sizeText(after)}" + if (before > after) "  ·  saved ${ImageEngine.sizeText(before - after)}" else "",
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                if (tool == ImageTool.CONVERT && saved.isNotEmpty()) Text("${sources.size} → ${ImgFormat.entries[convTarget].label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                if (saved.isNotEmpty()) Text("Saved in ${ImageStore.IMAGE_FOLDER}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (failedCount > 0) Button(onClick = { start(true) }, shape = Radius.pill) { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Retry failed ($failedCount)") }
                            OutlinedButton(onClick = { items.clear(); runList.clear() }, shape = Radius.pill) { Text("Process again") }
                        }
                    } else {
                        Button(
                            onClick = { start(false) }, enabled = !loading, shape = Radius.pill, modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) { Text(if (sources.size > 1) "${tool.title}  ·  ${sources.size} pictures" else tool.title, style = MaterialTheme.typography.titleSmall) }
                    }
                }
            }
            if (hasResults) {
                itemsIndexed(items.toList(), key = { i, _ -> "r$i" }) { i, st ->
                    val src = runList.getOrNull(i) ?: return@itemsIndexed
                    when (st) {
                        is ItemState.Done -> SavedRow(st.saved, st.detail, Modifier.padding(horizontal = 16.dp).padding(top = 8.dp))
                        is ItemState.Failed -> ResultNote(src.name, st.message, error = true)
                        is ItemState.Skipped -> ResultNote(src.name, st.message, error = false)
                        else -> {}
                    }
                }
            }
        }
    }
}

private class SkipItem(message: String) : Exception(message)

@Composable
private fun ResultNote(name: String, message: String, error: Boolean) {
    Surface(
        Modifier.padding(horizontal = 16.dp).padding(top = 8.dp).fillMaxWidth(), shape = Radius.card,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(message, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun LabeledSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(valueText, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value.coerceIn(range.start, range.endInclusive), onChange, valueRange = range, onValueChangeFinished = onFinished)
    }
}

@Composable
private fun CompressResult(first: ImageSource, est: Est?, preview: Bitmap?, all: List<ImageSource>) {
    var full by remember { mutableStateOf(false) }
    Surface(shape = Radius.card, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            if (est == null) {
                Text("Original: ${ImageEngine.sizeText(first.size)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                    Text("Estimating…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            } else {
                Text("Original: ${ImageEngine.sizeText(first.size)} → Compressed: ${ImageEngine.sizeText(est.bytes)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                val pct = if (first.size > 0) (first.size - est.bytes) * 100 / first.size else 0
                Text(
                    (if (pct > 0) "About $pct% smaller" else "No saving for this picture with these settings") + if (all.size > 1) " (first picture; ${all.size} selected)" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
    val after = est?.after
    if (preview != null && after != null) {
        CompareView(preview, after, Modifier.fillMaxWidth().height(300.dp), beforeLabel = "Original", afterLabel = "Compressed")
        TextButton(onClick = { full = true }) { Icon(Icons.Outlined.Fullscreen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Full-screen compare") }
        if (full) FullscreenPreview(after, onClose = { full = false }, compareWith = preview)
    }
}

@Composable
private fun RotateOptions(rot: History<RotOp>, preview: Bitmap?) {
    val op = rot.current
    val shown = remember(preview, op) {
        preview?.let { p ->
            try {
                val t = ImageEngine.transform(p, op.turns, op.flipH, op.flipV)
                ImageEngine.rotateFree(t, op.angle)
            } catch (_: Throwable) { p }
        }
    }
    var dragFrom by remember { mutableStateOf<RotOp?>(null) }
    var full by remember { mutableStateOf(false) }
    val vt = remember { ViewTransform() }
    if (shown != null) ZoomImage(shown, Modifier.fillMaxWidth().height(320.dp), vt, checker = op.angle != 0f)
    EditorBar(
        canUndo = rot.canUndo, canRedo = rot.canRedo, onUndo = { rot.undo() }, onRedo = { rot.redo() }, onReset = { rot.reset() },
        onFit = { vt.reset() }, onFullscreen = if (shown != null) ({ full = true }) else null
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { rot.push(op.copy(turns = (op.turns + 3) % 4)) }, shape = Radius.pill, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(Icons.Outlined.RotateLeft, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("90°")
        }
        FilledTonalButton(onClick = { rot.push(op.copy(turns = (op.turns + 1) % 4)) }, shape = Radius.pill, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(Icons.Outlined.RotateRight, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("90°")
        }
        FilledTonalButton(onClick = { rot.push(op.copy(turns = (op.turns + 2) % 4)) }, shape = Radius.pill, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("180°") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { rot.push(op.copy(flipH = !op.flipH)) }, shape = Radius.pill) { Icon(Icons.Outlined.Flip, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Flip horizontal") }
        FilledTonalButton(onClick = { rot.push(op.copy(flipV = !op.flipV)) }, shape = Radius.pill) {
            Icon(Icons.Outlined.Flip, null, Modifier.size(18.dp).graphicsRotate90()); Spacer(Modifier.width(6.dp)); Text("Flip vertical")
        }
    }
    LabeledSlider(
        "Custom angle", "${op.angle.roundToInt()}°", op.angle, -180f..180f,
        { v -> if (dragFrom == null) dragFrom = op; rot.replace(op.copy(angle = v.roundToInt().toFloat())) },
        { dragFrom?.let { rot.commitFrom(it) }; dragFrom = null }
    )
    if (op.angle != 0f) Text("Corners left empty by a custom angle are transparent (white in JPG).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (full && shown != null) FullscreenPreview(shown, onClose = { full = false }, checker = op.angle != 0f)
}

private fun Modifier.graphicsRotate90(): Modifier = this.then(Modifier.graphicsLayer { rotationZ = 90f })

@Composable
private fun EnhanceOptions(enh: History<EnhOp>, effective: ImageEngine.EnhanceParams, preview: Bitmap?, enhanced: Bitmap?, error: String?) {
    val op = enh.current
    var dragFrom by remember { mutableStateOf<EnhOp?>(null) }
    var full by remember { mutableStateOf(false) }
    InfoNote("Classic image processing (sharpening, noise reduction, tone and 2x smooth upscale). It is not an AI model and can't invent missing detail.")
    if (preview != null && enhanced != null) {
        CompareView(preview, enhanced, Modifier.fillMaxWidth().height(320.dp), "Before", "After")
        EditorBar(
            canUndo = enh.canUndo, canRedo = enh.canRedo, onUndo = { enh.undo() }, onRedo = { enh.redo() }, onReset = { enh.reset() },
            onFullscreen = { full = true }
        )
        if (full) FullscreenPreview(enhanced, onClose = { full = false }, compareWith = preview)
    } else if (preview == null) {
        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    error?.let { InfoNote(it, error = true) }
    OptSection("Preset") {
        OptChips(enhancePresets, op.preset) { i ->
            val p = enhanceValues.getOrNull(i)
            enh.push(if (i == 0) op.copy(preset = 0, auto = true) else if (p != null) op.copy(preset = i, p = p, auto = false) else op.copy(preset = 6, p = effective, auto = false))
        }
        if (op.auto) Text("Auto picks brightness and contrast from this picture's histogram.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    @Composable
    fun slider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, set: (ImageEngine.EnhanceParams, Float) -> ImageEngine.EnhanceParams) {
        val text = "%+.0f".format(value * 100).let { if (range.start >= 0f) it.removePrefix("+") else it }
        LabeledSlider(label, text, value, range, { v ->
            if (dragFrom == null) dragFrom = op
            enh.replace(EnhOp(6, set(effective, v), op.up, false))
        }, { dragFrom?.let { enh.commitFrom(it) }; dragFrom = null })
    }
    slider("Sharpness", effective.sharpen, 0f..1f) { p, v -> p.copy(sharpen = v) }
    slider("Noise reduction", effective.denoise, 0f..1f) { p, v -> p.copy(denoise = v) }
    slider("Brightness", effective.brightness, -0.5f..0.5f) { p, v -> p.copy(brightness = v) }
    slider("Contrast", effective.contrast, -0.5f..0.5f) { p, v -> p.copy(contrast = v) }
    slider("Colour", effective.saturation, -0.5f..0.5f) { p, v -> p.copy(saturation = v) }
    slider("Detail", effective.detail, 0f..1f) { p, v -> p.copy(detail = v) }
    OptSection("Upscale") {
        OptChips(listOf("Original size", "2x (smooth)"), if (op.up == 2) 1 else 0) { enh.push(op.copy(up = if (it == 1) 2 else 1)) }
        Text("2x adds pixels with smooth interpolation; it makes the picture bigger, not more detailed. Works for pictures up to 3 megapixels.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
