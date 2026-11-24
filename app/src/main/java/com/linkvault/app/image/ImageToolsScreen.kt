package com.linkvault.app.image

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.linkvault.app.ui.components.EmptyState
import com.linkvault.app.ui.components.ScreenTitle
import com.linkvault.app.ui.components.SearchField
import com.linkvault.app.ui.components.SectionHeader
import com.linkvault.app.ui.theme.Neon
import com.linkvault.app.ui.theme.Radius
import com.linkvault.app.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads a picture from a saved link into the app's cache so the tools can work on it. */
object RemoteImage {
    private const val MAX_BYTES = 40L * 1024 * 1024

    private val exts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif")

    fun looksLikeImage(url: String): Boolean {
        val path = try { Uri.parse(url).path.orEmpty() } catch (_: Exception) { return false }
        return path.substringAfterLast('.', "").lowercase() in exts
    }

    /** Blocking. Returns a file:// Uri, or null if the download failed or was too large. */
    fun fetch(context: Context, url: String): Uri? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return try {
            val dir = File(context.cacheDir, "imagetools-dl").apply { mkdirs() }
            val hash = MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val ext = Uri.parse(url).path.orEmpty().substringAfterLast('.', "").lowercase().takeIf { it in exts } ?: "jpg"
            val file = File(dir, "$hash.$ext")
            if (file.exists() && file.length() > 0) return Uri.fromFile(file)
            val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 20_000; instanceFollowRedirects = true }
            try {
                if (conn.responseCode !in 200..299) return null
                if (conn.contentLengthLong > MAX_BYTES) return null
                var total = 0L
                conn.inputStream.use { input -> file.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) { out.close(); file.delete(); return null }
                        out.write(buf, 0, n)
                    }
                } }
            } finally { conn.disconnect() }
            Uri.fromFile(file)
        } catch (_: Exception) { null }
    }
}

/** Chooses the right screen for a tool. */
@Composable
fun ImageToolScreen(toolId: String, sourceUrl: String?, onBack: () -> Unit) {
    when (val tool = ImageTool.fromId(toolId) ?: ImageTool.COMPRESS) {
        ImageTool.CROP -> ImageCropScreen(sourceUrl, onBack)
        ImageTool.BG_REMOVE -> BgRemoveScreen(sourceUrl, onBack)
        ImageTool.TO_PDF, ImageTool.SCREENSHOT_PDF, ImageTool.MULTI_PDF -> ImagePdfScreen(tool.name, sourceUrl, onBack)
        else -> ImageBatchScreen(tool.name, sourceUrl, onBack)
    }
}

/** The Image tab: tools by section, recent tools and recent files. */
@Composable
fun ImageToolsScreen(navigate: (String) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var recentTools by remember { mutableStateOf(ImageStore.recentTools(context)) }
    var history by remember { mutableStateOf(ImageStore.history(context)) }

    // Scratch copies only matter while a tool is open.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            File(context.cacheDir, "imagetools-out").deleteRecursively()
            File(context.cacheDir, "imagetools-dl").deleteRecursively()
        }
        recentTools = ImageStore.recentTools(context)
        history = ImageStore.history(context)
    }

    val q = query.trim()
    fun matches(t: ImageTool) = q.isEmpty() || t.title.contains(q, true) || t.blurb.contains(q, true)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { ScreenTitle("Image Tools", subtitle = "Everything runs on your phone. Nothing is uploaded.") }
        item { SearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), placeholder = "Search tools…") }

        var any = false
        ImageTool.sections.forEach { (header, list) ->
            val tools = list.filter { matches(it) }
            if (tools.isEmpty()) return@forEach
            any = true
            item(key = "h-$header") { SectionHeader(header, Modifier.padding(horizontal = 20.dp)) }
            items(tools.chunked(2), key = { "r-$header-" + it.first().name }) { pair ->
                Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { t -> ToolTile(t, Modifier.weight(1f)) { navigate("imagetool/${t.name}") } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (!any) item { EmptyState("No matching tool", "Try a word like compress, crop, PDF or background.") }

        if (q.isEmpty()) {
            item { SectionHeader("Recent image files", Modifier.padding(horizontal = 20.dp)) }
            if (history.isEmpty()) {
                item {
                    Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = Radius.card, color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Image, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(8.dp))
                            Text("No images yet", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Pictures and PDFs you make with these tools show up here, ready to open or share.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            } else {
                items(history, key = { it.uri }) { e ->
                    HistoryRow(context, e) {
                        MediaSaver.delete(context, e.uri)
                        ImageStore.remove(context, e.uri)
                        history = ImageStore.history(context)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolTile(tool: ImageTool, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.height(132.dp).clip(Radius.card).clickable(onClick = onClick), shape = Radius.card, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(14.dp)) {
            ToolBadge(tool, 38)
            Spacer(Modifier.height(10.dp))
            Text(tool.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tool.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HistoryRow(context: Context, e: ImageStore.Entry, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val isPdf = e.name.endsWith(".pdf", true)
    Surface(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).fillMaxWidth(), shape = Radius.card, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.clickable { if (!MediaSaver.open(context, e.uri, false)) android.widget.Toast.makeText(context, "No app can open this file, or it was moved.", android.widget.Toast.LENGTH_SHORT).show() }
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isPdf) Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Neon.coral), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PictureAsPdf, null, tint = Neon.ink) }
            else UriThumb(Uri.parse(e.uri), Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(e.tool, e.format, if (e.size > 0) ImageEngine.sizeText(e.size) else "", Format.relative(e.time)).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
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

/** Opened from a saved picture link: choose what to do with it. */
@Composable
fun ImageQuickActionScreen(url: String, navigate: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val uri by produceState<Uri?>(null, url) { value = withContext(Dispatchers.IO) { RemoteImage.fetch(context, url) } }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(uri) { if (uri == null) { kotlinx.coroutines.delay(8000); if (uri == null) failed = true } }
    val enc = Uri.encode(url)
    val choices = listOf(ImageTool.COMPRESS, ImageTool.RESIZE, ImageTool.CROP, ImageTool.CONVERT, ImageTool.TO_PDF, ImageTool.ROTATE, ImageTool.ENHANCE, ImageTool.BG_REMOVE)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { ScreenTitle("Image tools", subtitle = "Pick what to do with this picture", onBack = onBack) }
        item {
            Box(Modifier.padding(16.dp).fillMaxWidth().height(200.dp).clip(Radius.card).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                val u = uri
                if (u != null) UriThumb(u, Modifier.fillMaxSize(), side = 800, contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                else if (failed) Text("Couldn't load the picture. Check your connection.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                else CircularProgressIndicator()
            }
        }
        items(choices, key = { it.name }) { t ->
            Surface(
                Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).fillMaxWidth().clip(Radius.card).clickable(enabled = !failed) { navigate("imagetool/${t.name}?url=$enc") },
                shape = Radius.card, color = MaterialTheme.colorScheme.surface
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolBadge(t, 40); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.titleSmall)
                        Text(t.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
