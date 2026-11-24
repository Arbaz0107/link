package com.linkvault.app.image

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.linkvault.app.download.MediaSaver
import com.linkvault.app.ui.theme.Neon
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class ImageTool(
    val title: String, val blurb: String, val icon: ImageVector, val tint: Color,
    /** True for tools that work on one picture at a time. */
    val single: Boolean = false
) {
    RESIZE("Resize", "By pixels or percent, one or many", Icons.Outlined.PhotoSizeSelectLarge, Neon.lime),
    CROP("Crop", "Free or fixed ratio, zoom and pan", Icons.Outlined.Crop, Neon.green, single = true),
    ROTATE("Rotate / Flip", "90°, 180°, flip or any angle", Icons.Outlined.Rotate90DegreesCw, Neon.teal),
    ENHANCE("Quality Enhancer", "Sharpen, denoise, tone and 2x upscale", Icons.Outlined.AutoFixHigh, Neon.coral),
    COMPRESS("Image Compressor", "Shrink file size, keep the look", Icons.Outlined.Compress, Neon.lime),
    CONVERT("JPG ↔ PNG ↔ WEBP", "Convert between formats", Icons.Outlined.Transform, Neon.green),
    TO_PDF("Image → PDF", "Pictures into a clean PDF", Icons.Outlined.PictureAsPdf, Neon.teal),
    SCREENSHOT_PDF("Screenshot → PDF", "Trim, reorder and combine screenshots", Icons.Outlined.Smartphone, Neon.coral),
    MULTI_PDF("Multiple Images → One PDF", "Drag to order, rotate, remove", Icons.Outlined.Collections, Neon.lime),
    BG_REMOVE("Background Remover", "Cut out the subject, fix with a brush", Icons.Outlined.ContentCut, Neon.green, single = true);

    val isPdf get() = this == TO_PDF || this == SCREENSHOT_PDF || this == MULTI_PDF

    companion object {
        fun fromId(id: String?): ImageTool? = entries.firstOrNull { it.name == id }

        /** Sections as shown on the Image Tools home. The enhancer is listed under both Edit and AI Tools. */
        val sections: List<Pair<String, List<ImageTool>>> = listOf(
            "Edit" to listOf(RESIZE, CROP, ROTATE, ENHANCE),
            "Compress & convert" to listOf(COMPRESS, CONVERT),
            "PDF" to listOf(TO_PDF, SCREENSHOT_PDF, MULTI_PDF),
            "AI tools" to listOf(BG_REMOVE, ENHANCE)
        )
    }
}

/** A file this module saved. */
class ImageSaved(val name: String, val uri: String, val size: Long, val format: String)

/** Small local history: which tools were used lately and which files they produced. Never leaves the phone. */
object ImageStore {
    data class Entry(val name: String, val uri: String, val tool: String, val time: Long, val size: Long, val format: String)

    private fun prefs(c: Context) = c.getSharedPreferences("image_tools", Context.MODE_PRIVATE)

    fun recentTools(c: Context): List<ImageTool> =
        prefs(c).getString("recent_tools", "").orEmpty().split(',').mapNotNull { ImageTool.fromId(it) }

    fun markUsed(c: Context, tool: ImageTool) {
        val list = (listOf(tool.name) + prefs(c).getString("recent_tools", "").orEmpty().split(',').filter { it.isNotBlank() && it != tool.name }).take(6)
        prefs(c).edit().putString("recent_tools", list.joinToString(",")).apply()
    }

    fun history(c: Context): List<Entry> = try {
        val arr = JSONArray(prefs(c).getString("history", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("name"), o.getString("uri"), o.optString("tool"), o.optLong("time"), o.optLong("size"), o.optString("format"))
        }
    } catch (e: Exception) { emptyList() }

    private fun write(c: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().put("name", e.name).put("uri", e.uri).put("tool", e.tool).put("time", e.time).put("size", e.size).put("format", e.format))
        }
        prefs(c).edit().putString("history", arr.toString()).apply()
    }

    fun add(c: Context, entries: List<Entry>) = write(c, (entries + history(c)).take(60))
    fun remove(c: Context, uri: String) = write(c, history(c).filter { it.uri != uri })
    fun rename(c: Context, uri: String, newName: String) =
        write(c, history(c).map { if (it.uri == uri) it.copy(name = newName) else it })

    /** Copies [file] into the shared media library (Pictures or Downloads, in a LinkVault/Image Tools folder) and records it. */
    fun save(context: Context, tool: ImageTool, file: File, format: String): ImageSaved {
        val uri = MediaSaver.save(context, file, false, "Image Tools", asFile = file.extension.equals("pdf", true))
        val saved = ImageSaved(file.name, uri.toString(), file.length(), format)
        add(context, listOf(Entry(saved.name, saved.uri, tool.title, System.currentTimeMillis(), saved.size, format)))
        return saved
    }

    const val IMAGE_FOLDER = "Pictures/LinkVault/Image Tools"
    const val PDF_FOLDER = "Download/LinkVault/Image Tools"
}
