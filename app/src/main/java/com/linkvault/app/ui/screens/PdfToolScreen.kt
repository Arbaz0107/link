package com.linkvault.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linkvault.app.download.MediaSaver
import com.linkvault.app.pdf.*
import com.linkvault.app.ui.components.*
import com.linkvault.app.ui.theme.Neon
import com.linkvault.app.ui.theme.Radius
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

private class Picked(val name: String, val file: File, val size: Long, val pages: Int, val encrypted: Boolean)
private class Saved(val name: String, val uri: String, val size: Long)

private sealed interface Phase {
    data object Idle : Phase
    data class Running(val progress: Float, val label: String) : Phase
    data class Done(val files: List<Saved>, val note: String?, val summary: String?) : Phase
    data class Failed(val message: String) : Phase
}

private data class Options(
    val password: String, val splitMode: Int, val ranges: String, val allPages: Boolean, val pageSpec: String, val degrees: Int,
    val numPos: NumberPosition, val numStyle: NumberStyle, val numSize: Float, val numStart: Int, val numSkipFirst: Boolean,
    val level: CompressLevel, val openPw: String, val ownerPw: String, val allowPrint: Boolean, val allowCopy: Boolean, val allowEdit: Boolean,
    val dpi: Int, val jpgQuality: Int, val zip: Boolean,
    val imgSize: ImagePageSize, val imgOrient: ImageOrientation, val imgMargin: ImageMargin
)

private val passwordTools = setOf(PdfTool.SPLIT, PdfTool.ROTATE, PdfTool.PAGE_NUMBERS, PdfTool.COMPRESS, PdfTool.UNLOCK, PdfTool.TO_WORD, PdfTool.TO_EXCEL, PdfTool.TO_HTML)

@Composable
fun PdfToolScreen(toolId: String, onBack: () -> Unit, onEdit: (String) -> Unit = {}) {
    val tool = remember(toolId) { PdfTool.fromId(toolId) ?: PdfTool.SPLIT }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { System.currentTimeMillis().toString() }
    val inDir = remember { File(context.cacheDir, "pdftools/$session") }
    val outDir = remember { File(context.cacheDir, "pdftools-out/$session") }
    DisposableEffect(Unit) { onDispose { inDir.deleteRecursively(); outDir.deleteRecursively() } }

    val files = remember { mutableStateListOf<Picked>() }
    var loading by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var outName by remember { mutableStateOf("") }
    var nameEdited by remember { mutableStateOf(false) }

    // options
    var password by remember { mutableStateOf("") }
    var splitMode by remember { mutableIntStateOf(0) }
    var ranges by remember { mutableStateOf("") }
    var allPages by remember { mutableStateOf(true) }
    var pageSpec by remember { mutableStateOf("") }
    var degrees by remember { mutableIntStateOf(90) }
    var numPos by remember { mutableStateOf(NumberPosition.BOTTOM_CENTER) }
    var numStyle by remember { mutableStateOf(NumberStyle.PLAIN) }
    var numSize by remember { mutableFloatStateOf(12f) }
    var numStart by remember { mutableStateOf("1") }
    var numSkipFirst by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(CompressLevel.MEDIUM) }
    var openPw by remember { mutableStateOf("") }
    var confirmPw by remember { mutableStateOf("") }
    var ownerPw by remember { mutableStateOf("") }
    var allowPrint by remember { mutableStateOf(true) }
    var allowCopy by remember { mutableStateOf(true) }
    var allowEdit by remember { mutableStateOf(false) }
    var dpi by remember { mutableIntStateOf(150) }
    var jpgQuality by remember { mutableIntStateOf(85) }
    var zip by remember { mutableStateOf(false) }
    var imgSize by remember { mutableStateOf(ImagePageSize.A4) }
    var imgOrient by remember { mutableStateOf(ImageOrientation.AUTO) }
    var imgMargin by remember { mutableStateOf(ImageMargin.SMALL) }

    val defaultName = remember(tool, files.firstOrNull()?.name, files.size) {
        val base = files.firstOrNull()?.name?.substringBeforeLast('.') ?: "Document"
        when (tool) {
            PdfTool.MERGE -> "Merged"
            PdfTool.ROTATE -> "$base (rotated)"
            PdfTool.PAGE_NUMBERS -> "$base (numbered)"
            PdfTool.COMPRESS -> "$base (compressed)"
            PdfTool.UNLOCK -> "$base (unlocked)"
            PdfTool.PROTECT -> "$base (protected)"
            PdfTool.JPG_TO_PDF, PdfTool.PNG_TO_PDF -> if (files.size == 1) base else "Images"
            else -> base
        }
    }
    LaunchedEffect(defaultName) { if (!nameEdited) outName = defaultName }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            val added: List<Picked> = withContext(Dispatchers.IO) {
                inDir.mkdirs()
                uris.take(if (tool.input.many) 60 else 1).mapNotNull { uri: Uri ->
                    try {
                        var name = "file"
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) name = c.getString(0) ?: name
                        }
                        val f = File(inDir, "${System.nanoTime()}-${safeName(name)}")
                        context.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } } ?: return@mapNotNull null
                        val isPdf = tool.input == PdfInput.PDF || tool.input == PdfInput.PDF_MANY
                        val info = if (isPdf) PdfEngine.info(f) else PdfEngine.Info(0, false)
                        Picked(name, f, f.length(), info.pages, info.encrypted)
                    } catch (e: Exception) { null }
                }
            }
            if (added.isEmpty()) android.widget.Toast.makeText(context, "Couldn't read that file.", android.widget.Toast.LENGTH_SHORT).show()
            if (!tool.input.many) files.clear()
            files.addAll(added)
            loading = false
            phase = Phase.Idle
        }
    }

    val needsPassword = tool in passwordTools && (files.any { it.encrypted } || tool == PdfTool.UNLOCK)
    val encryptedBlocked = files.any { it.encrypted } && tool in setOf(PdfTool.MERGE, PdfTool.PROTECT, PdfTool.TO_JPG, PdfTool.TO_PNG)

    fun run() {
        if (files.isEmpty() || phase is Phase.Running) return
        val o = Options(password, splitMode, ranges, allPages, pageSpec, degrees, numPos, numStyle, numSize, numStart.toIntOrNull() ?: 1, numSkipFirst,
            level, openPw, ownerPw, allowPrint, allowCopy, allowEdit, dpi, jpgQuality, zip, imgSize, imgOrient, imgMargin)
        if (tool == PdfTool.PROTECT) {
            if (openPw != confirmPw) { phase = Phase.Failed("The two passwords don't match."); return }
        }
        val picked = files.toList()
        val name = safeName(outName.ifBlank { defaultName }).removeSuffix(".pdf").removeSuffix(".docx").removeSuffix(".xlsx").removeSuffix(".html")
        phase = Phase.Running(0f, "Starting")
        runJob = scope.launch {
            try {
                var summary: String? = null
                val result = withContext(Dispatchers.IO) {
                    val job = coroutineContext[Job]
                    PdfEngine.init(context)
                    outDir.deleteRecursively(); outDir.mkdirs()
                    val pj = PdfJob(context, outDir, { job?.ensureActive() }, { f, t -> phase = Phase.Running(f, t) })
                    val r = execute(tool, pj, picked, name, o)
                    if (tool == PdfTool.COMPRESS) {
                        val before = picked.first().size; val after = r.files.first().length()
                        summary = "${fmtSize(before)} → ${fmtSize(after)}  (${((before - after) * 100 / before.coerceAtLeast(1))}% smaller)"
                    }
                    r
                }
                phase = Phase.Running(1f, "Saving")
                val saved = withContext(Dispatchers.IO) {
                    result.files.map { f ->
                        val uri = MediaSaver.save(context, f, false, "PDF Tools", true)
                        Saved(f.name, uri.toString(), f.length())
                    }
                }
                val now = System.currentTimeMillis()
                PdfStore.markUsed(context, tool)
                PdfStore.add(context, saved.map { PdfStore.Entry(it.name, it.uri, tool.title, now, it.size) })
                outDir.deleteRecursively()
                phase = Phase.Done(saved, result.note, summary)
            } catch (e: CancellationException) {
                phase = Phase.Idle
                throw e
            } catch (e: PdfProblem) {
                phase = Phase.Failed(e.message ?: "Something went wrong.")
            } catch (e: OutOfMemoryError) {
                phase = Phase.Failed("This file is too large for this phone to process.")
            } catch (e: Throwable) {
                phase = Phase.Failed("Something went wrong${e.message?.let { ": " + it.take(120) } ?: "."}")
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { ScreenTitle(tool.title, subtitle = tool.blurb, onBack = onBack) }

        // ---- 1. files ----
        item {
            Surface(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), shape = Radius.card, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp)) {
                    if (files.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tool.tint), contentAlignment = Alignment.Center) {
                                Icon(tool.icon, null, tint = Neon.ink)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("Choose ${tool.input.label}", style = MaterialTheme.typography.titleSmall)
                                Text("Files are copied only into this app's temporary space and never uploaded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = { picker.launch(tool.input.mimes) }, enabled = !loading, shape = Radius.pill, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                            Text(if (loading) "Preparing…" else "Browse files")
                        }
                    } else {
                        Text(if (tool.input.many) "${files.size} ${if (files.size == 1) "file" else "files"} selected" else "Selected file", style = MaterialTheme.typography.titleSmall)
                        if (tool.input.many && files.size > 1) {
                            Text("Use the arrows to set the order.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (files.isNotEmpty()) {
            items(files.toList(), key = { it.file.path }) { p ->
                val idx = files.indexOf(p)
                PickedCard(
                    tool, p, canMove = tool.input.many && files.size > 1,
                    onUp = { if (idx > 0) { files.removeAt(idx); files.add(idx - 1, p) } },
                    onDown = { if (idx in 0 until files.lastIndex) { files.removeAt(idx); files.add(idx + 1, p) } },
                    onRemove = { files.remove(p); phase = Phase.Idle }
                )
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picker.launch(tool.input.mimes) }, enabled = !loading, shape = Radius.pill) {
                        Icon(if (tool.input.many) Icons.Outlined.Add else Icons.Outlined.SwapHoriz, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (tool.input.many) "Add more" else "Change file")
                    }
                }
            }
            if (encryptedBlocked) item { Note("A selected PDF is password protected. Open Unlock PDF first, then come back.", error = true) }
            if (loading) item { LinearProgressIndicator(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()) }

            // ---- 2. options ----
            item {
                val first = files.first()
                val total = first.pages
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (needsPassword) PasswordField(if (tool == PdfTool.UNLOCK) "PDF password" else "PDF password (this file is protected)", password) { password = it }

                    when (tool) {
                        PdfTool.SPLIT -> Section("Split mode") {
                            Chips(listOf("Separate files", "One file", "Every page"), splitMode) { splitMode = it }
                            if (splitMode != 2) {
                                OutlinedTextField(
                                    ranges, { ranges = it }, label = { Text("Pages") }, placeholder = { Text("1-3, 5, 8-10") }, singleLine = true,
                                    supportingText = { Text(if (total > 0) "This PDF has $total pages. " + (if (splitMode == 0) "Each range becomes its own PDF." else "All of them go into one new PDF.") else "Each range becomes its own PDF.") },
                                    modifier = Modifier.fillMaxWidth(), shape = Radius.small
                                )
                            } else Text(if (total > 0) "Creates $total PDFs, one per page." else "Creates one PDF per page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PdfTool.ROTATE -> {
                            Section("Rotate by") { Chips(listOf("90° right", "180°", "90° left"), listOf(90, 180, 270).indexOf(degrees)) { degrees = listOf(90, 180, 270)[it] } }
                            Section("Pages") {
                                Chips(listOf("Whole PDF", "Selected pages"), if (allPages) 0 else 1) { allPages = it == 0 }
                                if (!allPages) OutlinedTextField(pageSpec, { pageSpec = it }, label = { Text("Pages") }, placeholder = { Text("1-3, 5") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = Radius.small)
                            }
                        }
                        PdfTool.PAGE_NUMBERS -> {
                            Section("Position") {
                                Chips(NumberPosition.entries.take(3).map { it.label }, NumberPosition.entries.take(3).indexOf(numPos)) { numPos = NumberPosition.entries[it] }
                                Chips(NumberPosition.entries.drop(3).map { it.label }, NumberPosition.entries.drop(3).indexOf(numPos)) { numPos = NumberPosition.entries[3 + it] }
                            }
                            Section("Numbering style") { Chips(NumberStyle.entries.map { it.label }, NumberStyle.entries.indexOf(numStyle)) { numStyle = NumberStyle.entries[it] } }
                            Section("Font size: ${numSize.toInt()} pt") { Slider(numSize, { numSize = it }, valueRange = 8f..24f, steps = 15) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(numStart, { numStart = it.filter(Char::isDigit).take(4) }, label = { Text("Start at") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(120.dp), shape = Radius.small)
                                Spacer(Modifier.width(16.dp))
                                Checkbox(numSkipFirst, { numSkipFirst = it })
                                Text("Skip first page", style = MaterialTheme.typography.bodyMedium)
                            }
                            NumberPreview(first.file, numPos, numStyle, numSize, numStart.toIntOrNull() ?: 1, numSkipFirst, total)
                        }
                        PdfTool.COMPRESS -> Section("Compression") {
                            Chips(CompressLevel.entries.map { it.label }, CompressLevel.entries.indexOf(level)) { level = CompressLevel.entries[it] }
                            Text(level.hint + ". Pictures are re-encoded; text stays sharp.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PdfTool.UNLOCK -> Text(
                            "Works only when you know the password or the file just has editing/printing limits. It makes an unlocked copy and never breaks real encryption.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        PdfTool.PROTECT -> Section("Protection") {
                            PasswordField("Open password", openPw) { openPw = it }
                            PasswordField("Confirm password", confirmPw) { confirmPw = it }
                            PasswordField("Owner password (optional)", ownerPw) { ownerPw = it }
                            Toggle("Allow printing", allowPrint) { allowPrint = it }
                            Toggle("Allow copying text", allowCopy) { allowCopy = it }
                            Toggle("Allow editing", allowEdit) { allowEdit = it }
                            Text("Permissions are honoured by most PDF apps but are a request, not a lock. The open password is the real protection. Forgotten passwords can't be recovered.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PdfTool.TO_JPG, PdfTool.TO_PNG -> {
                            Section("Pages") {
                                Chips(listOf("All pages", "Selected pages"), if (allPages) 0 else 1) { allPages = it == 0 }
                                if (!allPages) OutlinedTextField(pageSpec, { pageSpec = it }, label = { Text("Pages") }, placeholder = { Text("1-3, 5") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = Radius.small)
                            }
                            Section("Resolution") {
                                val values = listOf(100, 150, 220, 300)
                                Chips(listOf("Small", "Standard", "High", "Max"), values.indexOf(dpi)) { dpi = values[it] }
                                Text("${dpi} DPI", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (tool == PdfTool.TO_JPG) Section("JPG quality") {
                                val values = listOf(60, 85, 95)
                                Chips(listOf("Low", "Good", "Best"), values.indexOf(jpgQuality)) { jpgQuality = values[it] }
                            }
                            Section("Save as") { Chips(listOf("Separate images", "One ZIP"), if (zip) 1 else 0) { zip = it == 1 } }
                        }
                        PdfTool.JPG_TO_PDF, PdfTool.PNG_TO_PDF -> {
                            Section("Page size") { Chips(ImagePageSize.entries.map { it.label }, ImagePageSize.entries.indexOf(imgSize)) { imgSize = ImagePageSize.entries[it] } }
                            if (imgSize != ImagePageSize.FIT) Section("Orientation") { Chips(ImageOrientation.entries.map { it.label }, ImageOrientation.entries.indexOf(imgOrient)) { imgOrient = ImageOrientation.entries[it] } }
                            Section("Margin") { Chips(ImageMargin.entries.map { it.label }, ImageMargin.entries.indexOf(imgMargin)) { imgMargin = ImageMargin.entries[it] } }
                        }
                        PdfTool.TO_WORD, PdfTool.TO_EXCEL, PdfTool.TO_HTML -> Text(
                            "Converts the text and its basic structure (headings, bold, paragraphs${if (tool == PdfTool.TO_EXCEL) ", table columns" else ""}). Pictures and exact layout aren't carried over. Scanned PDFs have no text to convert.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        PdfTool.WORD_TO_PDF, PdfTool.EXCEL_TO_PDF, PdfTool.PPT_TO_PDF -> Text(
                            when (tool) {
                                PdfTool.WORD_TO_PDF -> "Text, headings, bold/italic and tables are kept. Pictures and exact fonts are not."
                                PdfTool.EXCEL_TO_PDF -> "Every sheet is printed with its cell values. Wide sheets continue on extra pages. Charts and cell styling are not drawn."
                                else -> "Slide order, text and pictures are kept. Backgrounds, shapes and animations are not."
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> {}
                    }

                    // ---- 3. output name ----
                    OutlinedTextField(
                        outName, { outName = it; nameEdited = true }, label = { Text("Output file name") }, singleLine = true,
                        supportingText = { Text("Your original file is never changed. A new file is saved.") },
                        modifier = Modifier.fillMaxWidth(), shape = Radius.small
                    )
                }
            }

            // ---- 4. action ----
            item {
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
                    when (val ph = phase) {
                        is Phase.Running -> Surface(Modifier.fillMaxWidth(), shape = Radius.card, color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.padding(16.dp)) {
                                Text(ph.label, style = MaterialTheme.typography.titleSmall)
                                LinearProgressIndicator(progress = { ph.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                                Text("${(ph.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = { runJob?.cancel(); phase = Phase.Idle }, shape = Radius.pill, modifier = Modifier.padding(top = 12.dp)) { Text("Cancel") }
                            }
                        }
                        is Phase.Failed -> {
                            Note(ph.message, error = true)
                            Button(onClick = { run() }, shape = Radius.pill, modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 8.dp)) { Text("Try again") }
                        }
                        is Phase.Done -> ResultCard(
                            context, ph, tool, onEdit,
                            onDelete = { s -> MediaSaver.delete(context, s.uri); PdfStore.remove(context, s.uri); phase = Phase.Done(ph.files.filter { it.uri != s.uri }, ph.note, ph.summary) },
                            onAnother = { phase = Phase.Idle }
                        )
                        else -> Button(
                            onClick = { run() }, enabled = !loading && !encryptedBlocked, shape = Radius.pill,
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) { Text(tool.title, style = MaterialTheme.typography.titleSmall) }
                    }
                }
            }
        }
    }
}

// =================================================================================================

private fun pageTotal(p: Picked, password: String): Int =
    if (p.pages > 0) p.pages else PdfEngine.open(p.file, password).use { it.numberOfPages }

private fun execute(tool: PdfTool, pj: PdfJob, files: List<Picked>, name: String, o: Options): PdfResult {
    val first = files.first()
    fun one(f: File) = PdfResult(listOf(f))
    return when (tool) {
        PdfTool.SPLIT -> {
            val total = pageTotal(first, o.password)
            val groups = when (o.splitMode) {
                0 -> PdfEngine.parseRanges(o.ranges, total)
                1 -> listOf(PdfEngine.parsePages(o.ranges, total))
                else -> (1..total).map { listOf(it) }
            }
            PdfResult(PdfEngine.split(pj, first.file, o.password, groups, name))
        }
        PdfTool.MERGE -> one(PdfEngine.merge(pj, files.map { it.file }, name))
        PdfTool.ROTATE -> {
            val pages = if (o.allPages) null else PdfEngine.parsePages(o.pageSpec, pageTotal(first, o.password))
            one(PdfEngine.rotate(pj, first.file, o.password, pages, o.degrees, name))
        }
        PdfTool.PAGE_NUMBERS -> one(PdfEngine.addPageNumbers(pj, first.file, o.password, o.numPos, o.numStyle, o.numSize, o.numStart, o.numSkipFirst, name))
        PdfTool.COMPRESS -> one(PdfEngine.compress(pj, first.file, o.password, o.level, name))
        PdfTool.UNLOCK -> one(PdfEngine.unlock(pj, first.file, o.password, name))
        PdfTool.PROTECT -> one(PdfEngine.protect(pj, first.file, o.openPw, o.ownerPw, o.allowPrint, o.allowCopy, o.allowEdit, name))
        PdfTool.TO_WORD -> one(PdfText.toDocx(pj, first.file, o.password, name))
        PdfTool.TO_EXCEL -> one(PdfText.toXlsx(pj, first.file, o.password, name))
        PdfTool.TO_HTML -> one(PdfText.toHtml(pj, first.file, o.password, name))
        PdfTool.TO_JPG, PdfTool.TO_PNG -> {
            val pages = if (o.allPages) null else PdfEngine.parsePages(o.pageSpec, pageTotal(first, o.password))
            PdfResult(PdfEngine.toImages(pj, first.file, pages, o.dpi, tool == PdfTool.TO_PNG, o.jpgQuality, o.zip, name))
        }
        PdfTool.WORD_TO_PDF -> OfficeToPdf.docxToPdf(pj, first.file, name)
        PdfTool.EXCEL_TO_PDF -> OfficeToPdf.xlsxToPdf(pj, first.file, name)
        PdfTool.PPT_TO_PDF -> OfficeToPdf.pptxToPdf(pj, first.file, name)
        PdfTool.JPG_TO_PDF, PdfTool.PNG_TO_PDF -> one(PdfEngine.imagesToPdf(pj, files.map { it.file }, o.imgSize, o.imgOrient, o.imgMargin, name))
    }
}

// =================================================================================================
//  Small building blocks
// =================================================================================================

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun Chips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label -> CategoryChip(label, i == selected, { onSelect(i) }) }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange)
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Surface(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), shape = Radius.card,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, null, Modifier.size(20.dp),
                tint = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(), shape = Radius.small
    )
}

@Composable
private fun PickedCard(tool: PdfTool, p: Picked, canMove: Boolean, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit) {
    Surface(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).fillMaxWidth(), shape = Radius.card, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val thumbMod = Modifier.size(width = 46.dp, height = 58.dp).clip(RoundedCornerShape(8.dp))
            when (tool.input) {
                PdfInput.PDF, PdfInput.PDF_MANY -> PdfThumb(p.file, thumbMod)
                PdfInput.JPG, PdfInput.PNG -> ImageThumb(p.file, thumbMod)
                else -> Box(thumbMod.background(tool.tint), contentAlignment = Alignment.Center) { Icon(tool.icon, null, tint = Neon.ink) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(fmtSize(p.size), if (p.pages > 0) "${p.pages} ${if (p.pages == 1) "page" else "pages"}" else null, if (p.encrypted) "Protected" else null).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (canMove) {
                Column {
                    IconButton(onClick = onUp, Modifier.size(32.dp)) { Icon(Icons.Outlined.KeyboardArrowUp, "Move up", Modifier.size(22.dp)) }
                    IconButton(onClick = onDown, Modifier.size(32.dp)) { Icon(Icons.Outlined.KeyboardArrowDown, "Move down", Modifier.size(22.dp)) }
                }
            }
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, "Remove ${p.name}", Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun ResultCard(context: android.content.Context, done: Phase.Done, tool: PdfTool, onEdit: (String) -> Unit, onDelete: (Saved) -> Unit, onAnother: () -> Unit) {
    var copyOf by remember { mutableStateOf<Saved?>(null) }
    val saveAs = rememberLauncherForActivityResult(com.linkvault.app.util.CreateDocWithMime()) { target ->
        val src = copyOf
        copyOf = null
        if (target != null && src != null) {
            val ok = com.linkvault.app.util.FilePicking.copyUri(context, android.net.Uri.parse(src.uri), target)
            android.widget.Toast.makeText(context, if (ok) "Copy saved" else "Couldn't save the copy", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    Surface(Modifier.fillMaxWidth(), shape = Radius.card, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(if (done.files.isEmpty()) "All files removed" else "Done", style = MaterialTheme.typography.titleMedium)
            }
            done.summary?.let { Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
            if (done.files.isNotEmpty()) Text(
                "Saved in Downloads / LinkVault / PDF Tools",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            done.files.forEach { s ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(fmtSize(s.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            if (!MediaSaver.open(context, s.uri, false)) android.widget.Toast.makeText(context, "No app on this phone can open this file type.", android.widget.Toast.LENGTH_SHORT).show()
                        }, shape = Radius.pill, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Open") }
                        FilledTonalButton(onClick = { MediaSaver.share(context, s.uri, false) }, shape = Radius.pill, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Share") }
                        TextButton(onClick = { onDelete(s) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { copyOf = s; saveAs.launch(com.linkvault.app.util.FilePicking.mimeFor(s.name) to s.name) }) { Text("Save a copy to…") }
                        if (s.name.endsWith(".pdf", true)) TextButton(onClick = { onEdit(s.uri) }) { Text("Edit PDF") }
                    }
                }
            }
            done.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) }
            OutlinedButton(onClick = onAnother, shape = Radius.pill, modifier = Modifier.fillMaxWidth()) { Text("Run ${tool.title} again") }
        }
    }
}

// ---------- thumbnails & preview ----------

private class PagePreview(val bitmap: Bitmap, val widthPt: Int, val heightPt: Int)

private fun renderFirstPage(file: File, width: Int): PagePreview? = try {
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val r = PdfRenderer(pfd)
    try {
        val page = r.openPage(0)
        try {
            val h = (width * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(AColor.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            PagePreview(bmp, page.width, page.height)
        } finally { page.close() }
    } finally { r.close(); pfd.close() }
} catch (e: Throwable) { null }

@Composable
private fun PdfThumb(file: File, modifier: Modifier) {
    val preview by produceState<PagePreview?>(null, file) { value = withContext(Dispatchers.IO) { renderFirstPage(file, 180) } }
    val p = preview
    if (p != null) Image(p.bitmap.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.PictureAsPdf, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ImageThumb(file: File, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            try {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, b)
                var s = 1
                while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= 200) s *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
            } catch (e: Throwable) { null }
        }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

/** Shows where the number will land on the first page before anything is saved. */
@Composable
private fun NumberPreview(file: File, pos: NumberPosition, style: NumberStyle, size: Float, start: Int, skipFirst: Boolean, total: Int) {
    val preview by produceState<PagePreview?>(null, file) { value = withContext(Dispatchers.IO) { renderFirstPage(file, 520) } }
    val p = preview ?: return
    val widthDp = 220f
    val k = widthDp / p.widthPt
    val label = style.text(if (skipFirst) start else start, (if (total > 0) total else 1) + start - 1)
    Section("Preview (page 1)") {
        Box(Modifier.width(widthDp.dp).aspectRatio(p.widthPt / p.heightPt.toFloat()).clip(RoundedCornerShape(6.dp))) {
            Image(p.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            if (skipFirst) {
                Text("First page is skipped", Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            } else {
                val align = when (pos) {
                    NumberPosition.TOP_LEFT -> Alignment.TopStart
                    NumberPosition.TOP_CENTER -> Alignment.TopCenter
                    NumberPosition.TOP_RIGHT -> Alignment.TopEnd
                    NumberPosition.BOTTOM_LEFT -> Alignment.BottomStart
                    NumberPosition.BOTTOM_CENTER -> Alignment.BottomCenter
                    NumberPosition.BOTTOM_RIGHT -> Alignment.BottomEnd
                }
                Text(
                    label, Modifier.align(align).padding((28f * k).dp),
                    fontSize = (size * k).sp, color = androidx.compose.ui.graphics.Color(0xFF3C3C3C)
                )
            }
        }
    }
}
