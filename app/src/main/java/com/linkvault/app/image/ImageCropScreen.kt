package com.linkvault.app.image

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.linkvault.app.pdf.safeName
import com.linkvault.app.pdf.uniqueFile
import com.linkvault.app.ui.components.ScreenTitle
import com.linkvault.app.ui.theme.Radius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Crop: pick one picture, shape the frame, save as a new picture. */
@Composable
fun ImageCropScreen(sourceUrl: String?, onBack: () -> Unit) {
    val tool = ImageTool.CROP
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outDir = remember { File(context.cacheDir, "imagetools-out/crop-${System.currentTimeMillis()}") }
    DisposableEffect(Unit) { onDispose { outDir.deleteRecursively() } }

    var source by remember { mutableStateOf<ImageSource?>(null) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<ImageSaved?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    val history = remember(source?.uri) { History(CropOp()) }

    fun open(uris: List<Uri>) {
        scope.launch {
            loading = true
            val (found, bad) = loadSources(context, uris, emptySet(), 1)
            toastBad(context, bad)
            found.firstOrNull()?.let { source = it; name = it.baseName + "-cropped"; saved = null; error = null }
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
    val src = source
    val base by produceState<Bitmap?>(null, src?.uri) {
        value = null
        if (src != null) value = withContext(Dispatchers.IO) { try { ImageEngine.decode(context, src.uri, 2048) } catch (_: Throwable) { null } }
    }

    fun save() {
        val s = src ?: return
        saving = true; error = null
        scope.launch {
            try {
                saved = withContext(Dispatchers.Default) {
                    val full = ImageEngine.decode(context, s.uri)
                    val cropped = applyCrop(full, history.current)
                    if (cropped !== full) full.recycle()
                    val fmt = s.format ?: if (s.hasAlpha) ImgFormat.PNG else ImgFormat.JPG
                    outDir.mkdirs()
                    val out = uniqueFile(outDir, "${safeName(name.ifBlank { s.baseName + "-cropped" })}.${fmt.ext}")
                    ImageEngine.encodeToFile(cropped, out, fmt, 95)
                    cropped.recycle()
                    val r = ImageStore.save(context, tool, out, fmt.label)
                    outDir.deleteRecursively()
                    r
                }
            } catch (e: ImageProblem) { error = e.message
            } catch (e: OutOfMemoryError) { error = "Not enough memory for this picture."
            } catch (e: Throwable) { error = "Couldn't save the crop." }
            saving = false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { ScreenTitle(tool.title, subtitle = tool.blurb, onBack = onBack) }
        if (src == null) {
            item {
                PanelCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToolBadge(tool); Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Choose a picture", style = MaterialTheme.typography.titleSmall)
                            Text("Everything stays on your phone. The original is never changed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    PickImages(multi = false, enabled = !loading) { open(it) }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.padding(16.dp).fillMaxWidth()) }
        } else {
            item {
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val b = base
                    if (b == null) Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else CropPanel(b, history)
                    OutlinedTextField(
                        name, { name = it }, label = { Text("Output file name") }, singleLine = true,
                        supportingText = { Text("A new picture is saved; the original stays as it is.") },
                        modifier = Modifier.fillMaxWidth(), shape = Radius.small
                    )
                    error?.let { InfoNote(it, error = true) }
                    val r = saved
                    if (r != null) {
                        SavedRow(r, "${ImageEngine.sizeText(r.size)}  ·  ${r.format}  ·  saved in ${ImageStore.IMAGE_FOLDER}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { saved = null }, shape = Radius.pill) { Text("Process again") }
                            OutlinedButton(onClick = { source = null; saved = null }, shape = Radius.pill) { Text("Another picture") }
                        }
                    } else {
                        Button(onClick = { save() }, enabled = base != null && !saving, shape = Radius.pill, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            if (saving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                            Text(if (saving) "Saving…" else "Save cropped picture", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
    }
}
