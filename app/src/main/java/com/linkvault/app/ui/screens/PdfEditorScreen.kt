package com.linkvault.app.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linkvault.app.download.MediaSaver
import com.linkvault.app.pdf.*
import com.linkvault.app.ui.theme.Neon
import com.linkvault.app.ui.theme.Radius
import com.linkvault.app.util.CreateDocWithMime
import kotlinx.coroutines.launch

private enum class PickFor { OPEN, INSERT, REPLACE }

@Composable
fun PdfEditorScreen(initialUri: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ctl = remember { EditorController(context) }
    DisposableEffect(Unit) { onDispose { ctl.close() } }

    var askPassword by remember { mutableStateOf<Uri?>(null) }
    var wrongPassword by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    fun openUri(uri: Uri, pw: String = "") {
        scope.launch {
            loading = true
            when (val r = ctl.open(uri, pw)) {
                OpenResult.Ok -> { askPassword = null; wrongPassword = false }
                OpenResult.NeedPassword -> askPassword = uri
                is OpenResult.Failed -> {
                    if (pw.isNotEmpty() && r.message.contains("password", true)) { askPassword = uri; wrongPassword = true }
                    else Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                }
            }
            loading = false
        }
    }

    LaunchedEffect(initialUri) { if (initialUri != null && ctl.current == null) openUri(Uri.parse(initialUri)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) openUri(uri) }

    askPassword?.let { target ->
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPassword = null },
            title = { Text("Password needed") },
            text = {
                Column {
                    Text(if (wrongPassword) "That password didn't work. Try again." else "This PDF is protected. Enter its password to edit it.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text("Password") })
                }
            },
            confirmButton = { TextButton(onClick = { openUri(target, pw) }, enabled = pw.isNotEmpty()) { Text("Unlock") } },
            dismissButton = { TextButton(onClick = { askPassword = null }) { Text("Cancel") } }
        )
    }

    if (ctl.current == null) {
        StartView(loading, onBack, onPick = { picker.launch(arrayOf("application/pdf")) }, onRecent = { openUri(Uri.parse(it)) })
    } else {
        Workspace(ctl, onBack, onReopen = { openUri(Uri.parse(it)) })
    }
}

// =================================================================================================
//  Start view
// =================================================================================================

@Composable
private fun StartView(loading: Boolean, onBack: () -> Unit, onPick: () -> Unit, onRecent: (String) -> Unit) {
    val context = LocalContext.current
    val recent = remember { PdfStore.history(context).filter { it.name.endsWith(".pdf", true) } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("PDF Editor", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Edit text, add notes and pictures, highlight, redact and rearrange pages. Everything happens on this phone, and your original file is never changed unless you choose Replace original.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onPick, enabled = !loading, shape = Radius.pill, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else { Icon(Icons.Outlined.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Open a PDF") }
            }
            if (recent.isNotEmpty()) {
                Text("Recent PDFs", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recent.take(8)) { e ->
                        Surface(shape = Radius.small, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable { onRecent(e.uri) }) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.PictureAsPdf, null)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    Text(e.tool, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// =================================================================================================
//  Workspace
// =================================================================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Workspace(ctl: EditorController, onBack: () -> Unit, onReopen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = ctl.current ?: return
    val opts = remember { EditorOptions() }
    var tool by remember { mutableStateOf(Tool.SELECT) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    var sheet by remember { mutableStateOf<String?>(null) } // "find" | "pages" | "ocr" | "save"
    var lineEdit by remember { mutableStateOf<Pair<Int, TLine>?>(null) }
    var textEdit by remember { mutableStateOf<TextObj?>(null) }
    var newText by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }
    var newNote by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }
    var noteEdit by remember { mutableStateOf<NoteObj?>(null) }
    var imageSpot by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }
    var replaceImageId by remember { mutableStateOf<Long?>(null) }
    var pickFor by remember { mutableStateOf(PickFor.INSERT) }
    var pickPage by remember { mutableIntStateOf(0) }
    var pendingReplace by remember { mutableStateOf<Pair<Int, Uri>?>(null) }
    var saved by remember { mutableStateOf<SavedPdf?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var hideScanBanner by remember(st.file) { mutableStateOf(false) }

    val renderer = remember(st.file) { PageRenderer(st.file) }
    DisposableEffect(renderer) { onDispose { renderer.close() } }

    LaunchedEffect(ctl.message) { ctl.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); ctl.message = null } }
    BackHandler { if (ctl.dirty) confirmExit = true else onBack() }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val spot = imageSpot
        val rid = replaceImageId
        if (uri != null) scope.launch {
            if (rid != null) { val o = ctl.current?.objects?.firstOrNull { it.id == rid }; ctl.addImage(uri, o?.page ?: 0, 0f, 0f, rid) }
            else if (spot != null) ctl.addImage(uri, spot.first, spot.second, spot.third)
        }
        replaceImageId = null
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) when (pickFor) {
            PickFor.INSERT -> scope.launch { if (ctl.insertPdf(uri, pickPage)) listState.scrollToItem(pickPage + 1) }
            PickFor.REPLACE -> pendingReplace = pickPage to uri
            PickFor.OPEN -> {}
        }
    }
    val imagesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { if (ctl.insertImages(uris, pickPage)) listState.scrollToItem(pickPage + 1) }
    }
    val saveAs = rememberLauncherForActivityResult(CreateDocWithMime()) { uri ->
        if (uri != null) scope.launch { if (ctl.saveToUri(uri)) Toast.makeText(context, "PDF saved", Toast.LENGTH_SHORT).show() }
    }

    val scanned by produceState(false, st.file, currentPage) {
        val l = ctl.layoutOf(currentPage.coerceIn(0, st.pageCount - 1))
        value = l != null && l.lines.isEmpty()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ---- top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (ctl.dirty) confirmExit = true else onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(ctl.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(if (ctl.dirty) "Unsaved changes" else "No changes", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { ctl.undo() }, enabled = ctl.canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
                IconButton(onClick = { ctl.redo() }, enabled = ctl.canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo") }
                Button(onClick = { sheet = "save" }, shape = Radius.pill, contentPadding = PaddingValues(horizontal = 16.dp)) {
                    Icon(Icons.Outlined.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save")
                }
                Spacer(Modifier.width(6.dp))
            }

            if (scanned && !hideScanBanner) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.DocumentScanner, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("This page has no selectable text. It looks scanned.", Modifier.weight(1f), fontSize = 13.sp)
                        TextButton(onClick = { sheet = "ocr" }) { Text("OCR") }
                        IconButton(onClick = { hideScanBanner = true }, Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, "Dismiss", Modifier.size(18.dp)) }
                    }
                }
            }

            // ---- canvas
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().background(Color(0xFF3A3A3A))
                    .pointerInput(Unit) {
                        // Two fingers always pan and zoom, whatever tool is active; one finger belongs to the tool.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.count { it.pressed } >= 2) {
                                    val z = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    if (z != 1f) zoom = (zoom * z).coerceIn(0.5f, 4f)
                                    scope.launch { listState.scrollBy(-pan.y); hScroll.scrollBy(-pan.x) }
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
            ) {
                val viewportW = maxWidth
                val viewportH = maxHeight
                val pageW = viewportW * zoom
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.horizontalScroll(hScroll)) {
                        LazyColumn(
                            Modifier.width(pageW), state = listState, userScrollEnabled = tool.scrolls,
                            contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            items(st.pageCount, key = { it }) { i ->
                                EditorPage(
                                    index = i, st = st, ctl = ctl, renderer = renderer, tool = tool, opts = opts, widthDp = pageW - 24.dp,
                                    onEditLine = { p, l -> lineEdit = p to l },
                                    onEditText = { textEdit = it },
                                    onAskText = { p, x, y -> newText = Triple(p, x, y) },
                                    onAskNote = { p, x, y -> newNote = Triple(p, x, y) },
                                    onAskImage = { p, x, y -> imageSpot = Triple(p, x, y); replaceImageId = null; imagePicker.launch(arrayOf("image/*")) }
                                )
                            }
                        }
                    }
                }

                // page indicator + zoom
                Surface(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), shape = Radius.pill,
                    color = Color(0xE61A1A1A), contentColor = Color.White
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                        IconButton(onClick = { scope.launch { listState.animateScrollToItem((currentPage - 1).coerceAtLeast(0)) } }, Modifier.size(40.dp)) { Icon(Icons.Outlined.KeyboardArrowUp, "Previous page") }
                        Text("${currentPage + 1} / ${st.pageCount}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        IconButton(onClick = { scope.launch { listState.animateScrollToItem((currentPage + 1).coerceAtMost(st.pageCount - 1)) } }, Modifier.size(40.dp)) { Icon(Icons.Outlined.KeyboardArrowDown, "Next page") }
                        VerticalDivider(Modifier.height(20.dp), color = Color(0x55FFFFFF))
                        IconButton(onClick = { zoom = (zoom * 0.8f).coerceAtLeast(0.5f) }, Modifier.size(40.dp)) { Icon(Icons.Outlined.ZoomOut, "Zoom out") }
                        IconButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(4f) }, Modifier.size(40.dp)) { Icon(Icons.Outlined.ZoomIn, "Zoom in") }
                        TextButton(onClick = { zoom = 1f }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Width", fontSize = 12.sp, color = Color.White) }
                        TextButton(
                            onClick = {
                                val (pw, ph) = st.sizes[currentPage.coerceIn(0, st.pageCount - 1)]
                                val pageHAtFit = (viewportW - 24.dp) * (ph / pw)
                                zoom = ((viewportH - 24.dp) / pageHAtFit).coerceIn(0.5f, 1f)
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) { Text("Page", fontSize = 12.sp, color = Color.White) }
                    }
                }
            }

            // ---- properties / tool options
            val sel = ctl.draft?.takeIf { d -> d.id == ctl.selectedId } ?: st.objects.firstOrNull { it.id == ctl.selectedId }
            if (sel != null) {
                PropertyPanel(
                    sel, ctl,
                    onEditText = { (sel as? TextObj)?.let { textEdit = it }; (sel as? NoteObj)?.let { noteEdit = it } },
                    onReplaceImage = { replaceImageId = sel.id; imagePicker.launch(arrayOf("image/*")) }
                )
            } else ToolOptionsPanel(tool, opts)

            // ---- toolbar
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    ToolButton("Select", Icons.Outlined.NearMe, tool == Tool.SELECT) { tool = Tool.SELECT }
                    ToolButton("Edit", Icons.Outlined.Edit, tool == Tool.EDIT) { tool = Tool.EDIT }
                    ToolButton("Text", Icons.Outlined.TextFields, tool == Tool.TEXT) { tool = Tool.TEXT }
                    ToolButton("Image", Icons.Outlined.Image, tool == Tool.IMAGE) { tool = Tool.IMAGE }
                    ToolButton("Draw", Icons.Outlined.Draw, tool == Tool.DRAW) { tool = Tool.DRAW }
                    ToolButton("Mark", Icons.Outlined.Highlight, tool == Tool.MARK) { tool = Tool.MARK }
                    ToolButton("Shape", Icons.Outlined.Category, tool == Tool.SHAPE) { tool = Tool.SHAPE }
                    ToolButton("Note", Icons.Outlined.StickyNote2, tool == Tool.NOTE) { tool = Tool.NOTE }
                    ToolButton("Redact", Icons.Outlined.VisibilityOff, tool == Tool.REDACT) { tool = Tool.REDACT }
                    VerticalDivider(Modifier.height(36.dp).align(Alignment.CenterVertically).padding(horizontal = 4.dp))
                    ToolButton("Find", Icons.Outlined.Search, false) { sheet = "find" }
                    ToolButton("Pages", Icons.Outlined.GridView, false) { sheet = "pages" }
                    ToolButton("OCR", Icons.Outlined.DocumentScanner, false) { sheet = "ocr" }
                }
            }
        }

        ctl.busy?.let { msg ->
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                Surface(shape = Radius.card, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (ctl.progress in 0f..1f) CircularProgressIndicator(progress = { ctl.progress }) else CircularProgressIndicator()
                        Spacer(Modifier.height(14.dp))
                        Text(msg)
                    }
                }
            }
        }
    }

    // ---- sheets
    if (sheet != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            when (sheet) {
                "find" -> FindSheet(ctl, onGoto = { p -> scope.launch { listState.animateScrollToItem(p) } })
                "pages" -> PagesSheet(
                    ctl, renderer, currentPage,
                    onGoto = { p -> scope.launch { listState.scrollToItem(p) }; sheet = null },
                    onInsertPdf = { p -> pickFor = PickFor.INSERT; pickPage = p; sheet = null; pdfPicker.launch(arrayOf("application/pdf")) },
                    onInsertImages = { p -> pickPage = p; sheet = null; imagesPicker.launch(arrayOf("image/*")) },
                    onReplace = { p -> pickFor = PickFor.REPLACE; pickPage = p; sheet = null; pdfPicker.launch(arrayOf("application/pdf")) }
                )
                "ocr" -> OcrSheet(ctl, currentPage, onDone = { sheet = null })
                "save" -> SaveSheet(
                    ctl,
                    onSaved = { saved = it; sheet = null },
                    onSaveAs = { name -> sheet = null; saveAs.launch("application/pdf" to (name.removeSuffix(".pdf") + ".pdf")) },
                    onReplaceOriginal = { sheet = null; confirmOverwrite = true }
                )
            }
        }
    }

    // ---- dialogs
    lineEdit?.let { (page, line) ->
        LineEditDialog(line, onDismiss = { lineEdit = null }) { text, restyle ->
            lineEdit = null
            scope.launch { Toast.makeText(context, ctl.editLine(page, line, text, restyle), Toast.LENGTH_LONG).show() }
        }
    }
    textEdit?.let { t ->
        TextDialog("Edit text", t.text, onDismiss = { textEdit = null }) { v ->
            textEdit = null
            (ctl.current?.objects?.firstOrNull { it.id == t.id } as? TextObj)?.let { ctl.update(it.copy(text = v)) }
        }
    }
    newText?.let { (p, x, y) ->
        TextDialog("Add text", "", onDismiss = { newText = null }) { v ->
            newText = null
            if (v.isNotBlank()) {
                val ps = st.sizes[p]
                val w = minOf(220f, ps.first - 20f)
                val l = (x - 10f).coerceIn(0f, maxOf(0f, ps.first - w))
                val t = (y - 10f).coerceIn(0f, maxOf(0f, ps.second - 40f))
                ctl.add(TextObj(ctl.newId(), p, PBox(l, t, l + w, t + 40f), v, color = opts.textColor))
            }
        }
    }
    newNote?.let { (p, x, y) ->
        TextDialog("Sticky note", "", onDismiss = { newNote = null }) { v ->
            newNote = null
            if (v.isNotBlank()) ctl.add(NoteObj(ctl.newId(), p, x, y, v, 0xFFFFE082.toInt()))
        }
    }
    noteEdit?.let { n ->
        TextDialog("Sticky note", n.text, onDismiss = { noteEdit = null }) { v ->
            noteEdit = null
            (ctl.current?.objects?.firstOrNull { it.id == n.id } as? NoteObj)?.let { ctl.update(it.copy(text = v)) }
        }
    }
    pendingReplace?.let { (page, uri) ->
        var no by remember { mutableStateOf("1") }
        AlertDialog(
            onDismissRequest = { pendingReplace = null },
            title = { Text("Replace page ${page + 1}") },
            text = {
                Column {
                    Text("Which page of the chosen PDF should take its place?")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(no, { no = it.filter(Char::isDigit) }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("Page number") })
                }
            },
            confirmButton = { TextButton(onClick = { pendingReplace = null; scope.launch { ctl.replacePage(page, uri, no.toIntOrNull() ?: 1) } }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { pendingReplace = null }) { Text("Cancel") } }
        )
    }
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false },
        title = { Text("Leave without saving?") },
        text = { Text("Your changes to this PDF haven't been saved. They will be lost.") },
        confirmButton = { TextButton(onClick = { confirmExit = false; onBack() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmExit = false; sheet = "save" }) { Text("Save…") } }
    )
    if (confirmOverwrite) AlertDialog(
        onDismissRequest = { confirmOverwrite = false },
        title = { Text("Replace the original file?") },
        text = { Text("The file this PDF was opened from will be overwritten with your edited version. This can't be undone. A safer choice is to save a copy.") },
        confirmButton = {
            TextButton(onClick = {
                confirmOverwrite = false
                scope.launch {
                    if (ctl.replaceOriginal()) Toast.makeText(context, "Original replaced", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(context, "Android didn't allow writing to the original here. Save a copy instead.", Toast.LENGTH_LONG).show()
                }
            }) { Text("Replace original", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("Cancel") } }
    )
    saved?.let { s ->
        AlertDialog(
            onDismissRequest = { saved = null },
            icon = { Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("PDF Saved Successfully") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.name, fontWeight = FontWeight.SemiBold)
                    Text("Saved in Downloads / LinkVault / PDF Editor", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { MediaSaver.open(context, s.uri, false) }, label = { Text("Open") }, leadingIcon = { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp)) })
                        AssistChip(onClick = { MediaSaver.share(context, s.uri, false) }, label = { Text("Share") }, leadingIcon = { Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)) })
                        AssistChip(onClick = { renaming = true }, label = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null, Modifier.size(18.dp)) })
                        AssistChip(onClick = { saved = null; onReopen(s.uri) }, label = { Text("Edit again") }, leadingIcon = { Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)) })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { saved = null }) { Text("Done") } }
        )
        if (renaming) TextDialog("Rename file", s.name.removeSuffix(".pdf"), onDismiss = { renaming = false }, singleLine = true) { v ->
            renaming = false
            val r = ctl.renameSaved(s, v)
            if (r != null) saved = r else Toast.makeText(context, "Android didn't allow renaming this file.", Toast.LENGTH_LONG).show()
        }
    }
}

// =================================================================================================
//  Small pieces
// =================================================================================================

@Composable
private fun ToolButton(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Neon.lime else Color.Transparent
    val fg = if (selected) Neon.ink else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier.defaultMinSize(minWidth = 60.dp).clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(24.dp))
        Text(label, fontSize = 11.sp, color = fg, maxLines = 1)
    }
}

@Composable
private fun Swatches(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in EditorColors) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                    .border(if (c == selected) BorderStroke(3.dp, Color(0xFF2979FF)) else BorderStroke(1.dp, Color(0x55888888)), CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onFinish: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(62.dp))
        Slider(value.coerceIn(range.start, range.endInclusive), onChange, Modifier.weight(1f), valueRange = range, onValueChangeFinished = onFinish)
    }
}

@Composable
private fun PanelSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).heightIn(max = 230.dp).verticalScroll(rememberScrollState()), content = content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolOptionsPanel(tool: Tool, o: EditorOptions) {
    PanelSurface {
        when (tool) {
            Tool.SELECT -> Hint("Tap an item to select it. Drag to move; drag a corner to resize. Pinch with two fingers to zoom.")
            Tool.EDIT -> Hint("Tap a line of text to change it. Tap a text box you added to edit it.")
            Tool.TEXT -> { Hint("Tap where the text should go."); Swatches(o.textColor) { o.textColor = it } }
            Tool.IMAGE -> Hint("Tap where the picture should go, then choose it.")
            Tool.NOTE -> Hint("Tap where the sticky note should go.")
            Tool.REDACT -> Hint("Drag over what must disappear for good. When saved, those pages become pictures so the hidden text can't be copied.")
            Tool.DRAW -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!o.eraser, { o.eraser = false }, label = { Text("Pen") })
                    FilterChip(o.eraser, { o.eraser = true }, label = { Text("Eraser") })
                }
                if (!o.eraser) {
                    Swatches(o.penColor) { o.penColor = it }
                    LabeledSlider("Width", o.penWidth, 1f..16f, { o.penWidth = it })
                    LabeledSlider("Opacity", o.penAlpha, 0.1f..1f, { o.penAlpha = it })
                } else Hint("Drag across a drawing, shape or highlight to remove it.")
            }
            Tool.MARK -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Highlight", "Underline", "Strike", "Marker").forEachIndexed { i, l -> FilterChip(o.markKind == i, { o.markKind = i }, label = { Text(l) }) }
                }
                Swatches(o.markColor) { o.markColor = it }
                Hint(if (o.markKind == 3) "Draw freehand over the page." else "Drag across the words to mark them.")
            }
            Tool.SHAPE -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShapeKind.entries.forEach { k -> FilterChip(o.shape == k, { o.shape = k }, label = { Text(k.label) }) }
                }
                Swatches(o.shapeColor) { o.shapeColor = it }
                LabeledSlider("Width", o.shapeWidth, 1f..12f, { o.shapeWidth = it })
                if (o.shape == ShapeKind.RECT || o.shape == ShapeKind.OVAL) Row(verticalAlignment = Alignment.CenterVertically) { Switch(o.shapeFill, { o.shapeFill = it }); Spacer(Modifier.width(8.dp)); Text("Filled", fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun Hint(t: String) { Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PropertyPanel(o: EditObj, ctl: EditorController, onEditText: () -> Unit, onReplaceImage: () -> Unit) {
    fun live(n: EditObj) { ctl.draft = n }
    fun commit() { ctl.commitDraft() }
    PanelSurface {
        when (o) {
            is TextObj -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalIconToggleButton(o.bold, { ctl.update(o.copy(bold = it)) }) { Icon(Icons.Outlined.FormatBold, "Bold") }
                    FilledTonalIconToggleButton(o.italic, { ctl.update(o.copy(italic = it)) }) { Icon(Icons.Outlined.FormatItalic, "Italic") }
                    FilledTonalIconToggleButton(o.underline, { ctl.update(o.copy(underline = it)) }) { Icon(Icons.Outlined.FormatUnderlined, "Underline") }
                    FilledTonalIconToggleButton(o.align == 0, { ctl.update(o.copy(align = 0)) }) { Icon(Icons.Outlined.FormatAlignLeft, "Left") }
                    FilledTonalIconToggleButton(o.align == 1, { ctl.update(o.copy(align = 1)) }) { Icon(Icons.Outlined.FormatAlignCenter, "Centre") }
                    FilledTonalIconToggleButton(o.align == 2, { ctl.update(o.copy(align = 2)) }) { Icon(Icons.Outlined.FormatAlignRight, "Right") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextFamily.entries.forEach { f -> FilterChip(o.family == f, { ctl.update(o.copy(family = f)) }, label = { Text(f.label) }) }
                }
                Swatches(o.color) { ctl.update(o.copy(color = it)) }
                LabeledSlider("Size", o.size, 6f..72f, { live(o.copy(size = it)) }, ::commit)
                LabeledSlider("Rotate", o.rotation, -180f..180f, { live(o.copy(rotation = it)) }, ::commit)
                ActionsRow(ctl, o) { TextButton(onClick = onEditText) { Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Edit text") } }
            }
            is InkObj -> {
                Swatches(o.color) { ctl.update(o.copy(color = it)) }
                LabeledSlider("Width", o.width, 1f..24f, { live(o.copy(width = it)) }, ::commit)
                LabeledSlider("Opacity", o.alpha, 0.1f..1f, { live(o.copy(alpha = it)) }, ::commit)
                ActionsRow(ctl, o)
            }
            is ShapeObj -> {
                Swatches(o.color) { ctl.update(o.copy(color = it)) }
                LabeledSlider("Width", o.width, 1f..24f, { live(o.copy(width = it)) }, ::commit)
                LabeledSlider("Opacity", o.alpha, 0.1f..1f, { live(o.copy(alpha = it)) }, ::commit)
                ActionsRow(ctl, o)
            }
            is MarkObj -> { Swatches(o.color) { ctl.update(o.copy(color = it)) }; ActionsRow(ctl, o) }
            is ImageObj -> {
                LabeledSlider("Rotate", o.rotation, -180f..180f, { live(o.copy(rotation = it)) }, ::commit)
                ActionsRow(ctl, o) { TextButton(onClick = onReplaceImage) { Icon(Icons.Outlined.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Replace") } }
            }
            is NoteObj -> ActionsRow(ctl, o) { TextButton(onClick = onEditText) { Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Edit note") } }
            is RedactObj -> { Hint("Redaction. The area under this box is removed when you save."); ActionsRow(ctl, o) }
        }
    }
}

@Composable
private fun ActionsRow(ctl: EditorController, o: EditObj, extra: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        extra()
        Spacer(Modifier.weight(1f))
        if (o !is RedactObj) IconButton(onClick = { ctl.duplicate(o.id) }) { Icon(Icons.Outlined.ContentCopy, "Duplicate") }
        IconButton(onClick = { ctl.remove(o.id) }) { Icon(Icons.Outlined.DeleteOutline, "Delete", tint = MaterialTheme.colorScheme.error) }
        IconButton(onClick = { ctl.selectedId = null }) { Icon(Icons.Outlined.Check, "Done") }
    }
}

@Composable
private fun TextDialog(title: String, initial: String, singleLine: Boolean = false, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, Modifier.fillMaxWidth(), singleLine = singleLine, minLines = if (singleLine) 1 else 3) },
        confirmButton = { TextButton(onClick = { onConfirm(v) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun LineEditDialog(line: TLine, onDismiss: () -> Unit, onApply: (String, Boolean) -> Unit) {
    var v by remember { mutableStateOf(line.text) }
    var restyle by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Edit text") },
        text = {
            Column {
                OutlinedTextField(v, { v = it }, Modifier.fillMaxWidth(), minLines = 2)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(restyle, { restyle = it })
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Change size, colour or style", fontSize = 14.sp)
                        Text("Turns the line into a text box you can move and restyle.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(v, restyle) }, enabled = v != line.text) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// =================================================================================================
//  Sheets
// =================================================================================================

@Composable
private fun FindSheet(ctl: EditorController, onGoto: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var q by remember { mutableStateOf("") }
    var r by remember { mutableStateOf("") }
    var mc by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var overlayAsk by remember { mutableStateOf<ReplaceOutcome?>(null) }

    fun summarize(o: ReplaceOutcome) {
        status = "Replaced ${o.done}."
        if (o.remaining > 0) overlayAsk = o
        else if (o.done == 0) status = "Nothing was replaced."
    }

    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Find and replace", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Find") })
        OutlinedTextField(r, { r = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Replace with") })
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(mc, { mc = it }); Spacer(Modifier.width(8.dp)); Text("Match case") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { scope.launch { val n = ctl.find(q, mc); status = if (n == 0) "No matches." else "$n match${if (n == 1) "" else "es"}."; ctl.hits.firstOrNull()?.let { onGoto(it.page) } } }, enabled = q.isNotEmpty()) { Text("Find") }
            IconButton(onClick = { if (ctl.hits.isNotEmpty()) { ctl.hitIndex = (ctl.hitIndex - 1 + ctl.hits.size) % ctl.hits.size; onGoto(ctl.hits[ctl.hitIndex].page) } }, enabled = ctl.hits.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowUp, "Previous match") }
            IconButton(onClick = { if (ctl.hits.isNotEmpty()) { ctl.hitIndex = (ctl.hitIndex + 1) % ctl.hits.size; onGoto(ctl.hits[ctl.hitIndex].page) } }, enabled = ctl.hits.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowDown, "Next match") }
            if (ctl.hits.isNotEmpty()) Text("${ctl.hitIndex + 1} of ${ctl.hits.size}", fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { if (ctl.hits.isEmpty()) ctl.find(q, mc); summarize(ctl.replaceOne(q, r, mc)) } }, enabled = q.isNotEmpty()) { Text("Replace") }
            OutlinedButton(onClick = { scope.launch { summarize(ctl.replaceAll(q, r, mc)) } }, enabled = q.isNotEmpty()) { Text("Replace all") }
        }
        if (status.isNotEmpty()) Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Changes are made in the PDF's own text when its fonts allow it.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    overlayAsk?.let { o ->
        AlertDialog(
            onDismissRequest = { overlayAsk = null },
            title = { Text("${o.remaining} couldn't be changed directly") },
            text = { Text("In this PDF, ${o.reason ?: "the text is stored in a way that can't be edited"}. I can place the new text on top of those places instead. The old text would stay hidden underneath rather than being removed.") },
            confirmButton = { TextButton(onClick = { overlayAsk = null; scope.launch { val n = ctl.overlayHits(r); status = "Placed $n as text on top."; Toast.makeText(context, status, Toast.LENGTH_SHORT).show() } }) { Text("Place on top") } },
            dismissButton = { TextButton(onClick = { overlayAsk = null }) { Text("Leave them") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PagesSheet(
    ctl: EditorController, renderer: PageRenderer, currentPage: Int,
    onGoto: (Int) -> Unit, onInsertPdf: (Int) -> Unit, onInsertImages: (Int) -> Unit, onReplace: (Int) -> Unit
) {
    val st = ctl.current ?: return
    val scope = rememberCoroutineScope()
    var sel by remember { mutableIntStateOf(currentPage.coerceIn(0, st.pageCount - 1)) }
    var extract by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<SavedPdf?>(null) }
    val context = LocalContext.current
    if (sel !in 0 until st.pageCount) sel = sel.coerceIn(0, st.pageCount - 1)

    Column(Modifier.padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pages", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(st.pageCount, key = { it }) { i ->
                val thumb by produceState<ImageBitmap?>(null, st.file, i) { value = renderer.render(i, 256)?.asImageBitmap() }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { sel = i }) {
                    Box(
                        Modifier.width(78.dp).height(104.dp).clip(RoundedCornerShape(8.dp)).background(Color.White)
                            .border(BorderStroke(if (i == sel) 3.dp else 1.dp, if (i == sel) Color(0xFF2979FF) else Color(0x55888888)), RoundedCornerShape(8.dp))
                    ) { thumb?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
                    Text("${i + 1}", fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { onGoto(sel) }, label = { Text("Go to page") })
            AssistChip(onClick = { scope.launch { ctl.addBlankAfter(sel) } }, label = { Text("Add blank after") })
            AssistChip(onClick = { scope.launch { ctl.duplicatePage(sel) } }, label = { Text("Duplicate") })
            AssistChip(onClick = { scope.launch { ctl.movePage(sel, -1); sel = (sel - 1).coerceAtLeast(0) } }, label = { Text("Move earlier") }, enabled = sel > 0)
            AssistChip(onClick = { scope.launch { ctl.movePage(sel, 1); sel = (sel + 1).coerceAtMost(st.pageCount - 1) } }, label = { Text("Move later") }, enabled = sel < st.pageCount - 1)
            AssistChip(onClick = { scope.launch { ctl.rotatePage(sel, 90) } }, label = { Text("Rotate 90°") })
            AssistChip(onClick = { confirmDelete = true }, label = { Text("Delete") }, enabled = st.pageCount > 1)
            AssistChip(onClick = { extract = true }, label = { Text("Extract pages") })
            AssistChip(onClick = { onInsertPdf(sel) }, label = { Text("Insert PDF after") })
            AssistChip(onClick = { onInsertImages(sel) }, label = { Text("Insert pictures after") })
            AssistChip(onClick = { onReplace(sel) }, label = { Text("Replace page") })
        }
        Text("Rotating writes your current marks into the page first.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete page ${sel + 1}?") },
        text = { Text("You can undo this until you leave the editor.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { ctl.deletePages(setOf(sel)) } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
    if (extract) TextDialog("Extract pages (e.g. 1-3, 5)", "${sel + 1}", singleLine = true, onDismiss = { extract = false }) { spec ->
        extract = false
        val pages = try { PdfEngine.parsePages(spec, st.pageCount).map { it - 1 } } catch (e: PdfProblem) {
            Toast.makeText(context, e.message ?: "Enter pages like 1-3, 5", Toast.LENGTH_SHORT).show(); emptyList()
        }
        if (pages.isNotEmpty()) scope.launch { done = ctl.extractPages(pages) }
    }
    done?.let { d ->
        AlertDialog(
            onDismissRequest = { done = null }, title = { Text("Pages extracted") },
            text = { Text("${d.name} was saved in Downloads / LinkVault / PDF Editor. This document is unchanged.") },
            confirmButton = { TextButton(onClick = { MediaSaver.open(context, d.uri, false); done = null }) { Text("Open") } },
            dismissButton = { TextButton(onClick = { MediaSaver.share(context, d.uri, false); done = null }) { Text("Share") } }
        )
    }
}

@Composable
private fun OcrSheet(ctl: EditorController, currentPage: Int, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val st = ctl.current ?: return
    var hindi by remember { mutableStateOf(false) }
    fun run(pages: List<Int>) {
        scope.launch {
            val n = ctl.runOcr(pages, hindi)
            Toast.makeText(context, if (n == 0) "No text was found." else "Found $n lines. Switch to Edit and tap a line to change it.", Toast.LENGTH_LONG).show()
            onDone()
        }
    }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("OCR: make text editable", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Reads the words on scanned pages, on this phone. Recognised lines become text you can edit, and untouched lines are saved as hidden text so the PDF becomes searchable.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!hindi, { hindi = false }, label = { Text("English and European") })
            FilterChip(hindi, { hindi = true }, label = { Text("Hindi / Devanagari") })
        }
        Text("Other scripts, such as Gujarati or Arabic, aren't supported by the on-device reader.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { run(listOf(currentPage.coerceIn(0, st.pageCount - 1))) }, modifier = Modifier.fillMaxWidth()) { Text("Read page ${currentPage + 1}") }
        OutlinedButton(onClick = { run((0 until st.pageCount).toList()) }, modifier = Modifier.fillMaxWidth()) { Text("Read all ${st.pageCount} pages") }
    }
}

@Composable
private fun SaveSheet(ctl: EditorController, onSaved: (SavedPdf) -> Unit, onSaveAs: (String) -> Unit, onReplaceOriginal: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(ctl.title.removeSuffix(".pdf").removeSuffix(".PDF") + " (edited)") }
    val redactions = ctl.current?.objects?.count { it is RedactObj } ?: 0
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Save PDF", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("File name") }, suffix = { Text(".pdf") })
        if (redactions > 0) Text("$redactions redaction${if (redactions == 1) "" else "s"}: pages with a redaction are saved as pictures so the covered content can't be extracted.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { scope.launch { ctl.saveCopy(name)?.let(onSaved) } }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = Radius.pill) { Text("Save as a new file") }
        OutlinedButton(onClick = { onSaveAs(name) }, modifier = Modifier.fillMaxWidth(), shape = Radius.pill) { Text("Save as… (choose location)") }
        if (ctl.sourceUri != null) TextButton(onClick = onReplaceOriginal, modifier = Modifier.fillMaxWidth()) { Text("Replace original…", color = MaterialTheme.colorScheme.error) }
    }
}
