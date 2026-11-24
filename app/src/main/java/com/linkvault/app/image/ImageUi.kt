package com.linkvault.app.image

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.linkvault.app.pdf.safeName
import com.linkvault.app.ui.components.CategoryChip
import com.linkvault.app.ui.theme.Neon
import com.linkvault.app.ui.theme.Radius
import com.linkvault.app.util.CreateDocWithMime
import com.linkvault.app.util.FilePicking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------- small building blocks

@Composable
fun OptSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
fun OptChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label -> CategoryChip(label, i == selected, { onSelect(i) }) }
    }
}

@Composable
fun OptToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange)
    }
}

@Composable
fun InfoNote(text: String, error: Boolean = false, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(), shape = Radius.card,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, null, Modifier.size(20.dp),
                tint = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun PanelCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = Radius.card, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun ToolBadge(tool: ImageTool, size: Int = 44) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.32f).dp)).background(tool.tint), contentAlignment = Alignment.Center) {
        Icon(tool.icon, null, Modifier.size((size * 0.52f).dp), tint = Neon.ink)
    }
}

// ---------------------------------------------------------------------------------------------- picking

/**
 * Two ways to choose pictures, both through Android's own pickers (no storage permission needed):
 * the system Photos picker (has Screenshots and other albums) and the Files browser.
 */
@Composable
fun PickImages(
    modifier: Modifier = Modifier, multi: Boolean = true, enabled: Boolean = true, label: String = if (multi) "Choose pictures" else "Choose a picture",
    onPicked: (List<Uri>) -> Unit
) {
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) onPicked(if (multi) uris else uris.take(1))
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) onPicked(if (multi) uris else uris.take(1))
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            enabled = enabled, shape = Radius.pill, modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(label + " (Photos)") }
        OutlinedButton(
            onClick = { files.launch(arrayOf("image/*")) },
            enabled = enabled, shape = Radius.pill, modifier = Modifier.fillMaxWidth().height(46.dp)
        ) { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Browse files") }
    }
}

/** Reads name, size and dimensions of each pick. Returns the readable ones plus how many were not images. */
suspend fun loadSources(context: android.content.Context, uris: List<Uri>, alreadyHave: Set<String>, room: Int): Pair<List<ImageSource>, Int> =
    withContext(Dispatchers.IO) {
        val out = ArrayList<ImageSource>()
        var bad = 0
        for (u in uris) {
            if (out.size >= room) break
            if (u.toString() in alreadyHave) continue
            val s = ImageEngine.inspect(context, u)
            if (s == null) bad++ else out.add(s)
        }
        out to bad
    }

fun toastBad(context: android.content.Context, bad: Int) {
    if (bad > 0) Toast.makeText(context, if (bad == 1) "1 file couldn't be read as a picture and was skipped." else "$bad files couldn't be read as pictures and were skipped.", Toast.LENGTH_LONG).show()
}

// ---------------------------------------------------------------------------------------------- after-save actions

private fun mimeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    else -> "application/octet-stream"
}

/**
 * One saved result with the file actions every tool offers: Open, Share, Save as, Rename.
 * [detail] is the line under the name, e.g. "4.8 MB → 820 KB  (83% smaller)".
 */
@Composable
fun SavedRow(
    saved: ImageSaved, detail: String, modifier: Modifier = Modifier,
    onRenamed: (ImageSaved) -> Unit = {}
) {
    val context = LocalContext.current
    var shown by remember(saved.uri) { mutableStateOf(saved) }
    var renaming by remember { mutableStateOf(false) }
    val saveAs = rememberLauncherForActivityResult(CreateDocWithMime()) { target ->
        if (target != null) {
            val ok = withNoThrow { FilePicking.copyUri(context, Uri.parse(shown.uri), target) }
            Toast.makeText(context, if (ok) "Saved." else "Couldn't save there.", Toast.LENGTH_SHORT).show()
        }
    }
    val isPdf = shown.name.endsWith(".pdf", true)

    Surface(modifier.fillMaxWidth(), shape = Radius.card, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPdf) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Neon.coral), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PictureAsPdf, null, tint = Neon.ink)
                    }
                } else UriThumb(Uri.parse(shown.uri), Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(shown.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                val pad = PaddingValues(horizontal = 10.dp)
                TextButton(onClick = {
                    if (!MediaSaver.open(context, shown.uri, false)) Toast.makeText(context, "No app can open this file, or it was moved.", Toast.LENGTH_SHORT).show()
                }, contentPadding = pad) { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Open") }
                TextButton(onClick = { MediaSaver.share(context, shown.uri, false) }, contentPadding = pad) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Share")
                }
                TextButton(onClick = { saveAs.launch(mimeOf(shown.name) to shown.name) }, contentPadding = pad) {
                    Icon(Icons.Outlined.SaveAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Save as")
                }
                TextButton(onClick = { renaming = true }, contentPadding = pad) {
                    Icon(Icons.Outlined.DriveFileRenameOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Rename")
                }
            }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(shown.name.substringBeforeLast('.')) }
        val ext = shown.name.substringAfterLast('.', "")
        AlertDialog(
            onDismissRequest = { renaming = false }, shape = Radius.card, title = { Text("Rename file") },
            text = {
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("File name") },
                    suffix = { if (ext.isNotEmpty()) Text(".$ext") }, shape = Radius.small)
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = safeName(text.ifBlank { "image" }) + if (ext.isNotEmpty()) ".$ext" else ""
                    renaming = false
                    if (newName != shown.name) {
                        if (FilePicking.rename(context, Uri.parse(shown.uri), newName)) {
                            ImageStore.rename(context, shown.uri, newName)
                            shown = ImageSaved(newName, shown.uri, shown.size, shown.format)
                            onRenamed(shown)
                        } else Toast.makeText(context, "Android wouldn't rename this file. Use Save as instead.", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } }
        )
    }
}

private inline fun withNoThrow(block: () -> Boolean): Boolean = try { block() } catch (_: Exception) { false }

/** "4.8 MB → 820 KB (83% smaller)". */
fun sizeChange(before: Long, after: Long): String {
    if (before <= 0L) return ImageEngine.sizeText(after)
    val pct = ((before - after) * 100f / before)
    val tail = when {
        pct >= 1f -> "  (${pct.toInt()}% smaller)"
        pct <= -1f -> "  (${(-pct).toInt()}% larger)"
        else -> ""
    }
    return "${ImageEngine.sizeText(before)} → ${ImageEngine.sizeText(after)}$tail"
}
