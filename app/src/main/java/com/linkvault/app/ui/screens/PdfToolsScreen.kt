package com.linkvault.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linkvault.app.download.MediaSaver
import com.linkvault.app.pdf.*
import com.linkvault.app.ui.components.*
import com.linkvault.app.ui.theme.Neon
import com.linkvault.app.ui.theme.Radius
import com.linkvault.app.util.Format
import java.io.File

/** The PDF Tools home: search, recent tools, tools by category and recent files. */
@Composable
fun PdfToolsScreen(navigate: (String) -> Unit, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var recentTools by remember { mutableStateOf(PdfStore.recentTools(context)) }
    var history by remember { mutableStateOf(PdfStore.history(context)) }

    // Scratch copies of picked files are only needed while a tool screen is open.
    LaunchedEffect(Unit) {
        File(context.cacheDir, "pdftools").deleteRecursively()
        File(context.cacheDir, "pdftools-out").deleteRecursively()
        recentTools = PdfStore.recentTools(context)
        history = PdfStore.history(context)
    }

    val q = query.trim()
    val matches = remember(q) {
        if (q.isEmpty()) PdfTool.entries.toList()
        else PdfTool.entries.filter { it.title.contains(q, true) || it.blurb.contains(q, true) || it.category.title.contains(q, true) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { ScreenTitle("PDF Tools", subtitle = "Everything runs on your phone. Nothing is uploaded.", onBack = onBack) }
        item {
            SearchField(
                value = query, onValueChange = { query = it }, placeholder = "Search tools…",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        if (q.isEmpty() || "pdf editor edit annotate redact highlight ocr text image sign".contains(q, ignoreCase = true)) {
            item(key = "editor-card") { EditorCard { navigate("pdfeditor") } }
        }

        if (matches.isEmpty()) {
            item { EmptyState("No matching tool", "Try a word like merge, compress, Word or JPG.") }
        } else {
            PdfCategory.entries.forEach { cat ->
                val tools = matches.filter { it.category == cat }
                if (tools.isEmpty()) return@forEach
                item(key = "h-${cat.name}") { SectionHeader(cat.title, Modifier.padding(horizontal = 20.dp)) }
                items(tools.chunked(2), key = { "r-" + it.first().name }) { pair ->
                    Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { t -> ToolTile(t, Modifier.weight(1f)) { navigate("pdftool/${t.name}") } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        if (q.isEmpty()) {
            item { SectionHeader("Recent files", Modifier.padding(horizontal = 20.dp)) }
            if (history.isEmpty()) {
                item {
                    Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = Radius.card, color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.PictureAsPdf, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(8.dp))
                            Text("Nothing here yet", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Files you create with these tools show up here, ready to open or share.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            } else {
                items(history, key = { it.uri }) { e ->
                    HistoryRow(
                        context, e,
                        onEdit = if (e.name.endsWith(".pdf", true)) ({ navigate("pdfeditor?uri=" + android.net.Uri.encode(e.uri)) }) else null
                    ) {
                        MediaSaver.delete(context, e.uri)
                        PdfStore.remove(context, e.uri)
                        history = PdfStore.history(context)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolTile(tool: PdfTool, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.height(132.dp).clip(Radius.card).clickable(onClick = onClick), shape = Radius.card, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(14.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(tool.tint), contentAlignment = Alignment.Center) {
                Icon(tool.icon, null, Modifier.size(22.dp), tint = Neon.ink)
            }
            Spacer(Modifier.height(10.dp))
            Text(tool.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                tool.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HistoryRow(context: Context, e: PdfStore.Entry, onEdit: (() -> Unit)?, onDelete: () -> Unit) {
    val ext = e.name.substringAfterLast('.', "").uppercase()
    var confirm by remember { mutableStateOf(false) }
    Surface(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).fillMaxWidth(), shape = Radius.card, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.clickable { if (!MediaSaver.open(context, e.uri, false)) android.widget.Toast.makeText(context, "No app can open this file, or it was moved.", android.widget.Toast.LENGTH_SHORT).show() }
                .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(ext.take(4), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(ext, e.tool, if (e.size > 0) fmtSize(e.size) else null, Format.relative(e.time)).filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit ${e.name}", Modifier.size(20.dp)) }
            IconButton(onClick = { MediaSaver.share(context, e.uri, false) }) { Icon(Icons.Outlined.Share, "Share ${e.name}", Modifier.size(20.dp)) }
            IconButton(onClick = { confirm = true }) { Icon(Icons.Outlined.DeleteOutline, "Delete ${e.name}", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, shape = Radius.card, title = { Text("Delete this file?") },
        text = { Text("“${e.name}” will be removed from your device.") },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
    )
}

@Composable
private fun EditorCard(onClick: () -> Unit) {
    Surface(
        Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp).fillMaxWidth().clickable(onClick = onClick),
        shape = Radius.card, color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Edit, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("PDF Editor", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "Edit text, add notes and pictures, highlight, redact, OCR and rearrange pages",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
