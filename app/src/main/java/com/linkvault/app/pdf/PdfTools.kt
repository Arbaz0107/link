package com.linkvault.app.pdf

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.linkvault.app.ui.theme.Neon
import org.json.JSONArray
import org.json.JSONObject

enum class PdfCategory(val title: String) {
    ORGANIZE("PDF organization"),
    SECURITY("Optimization & security"),
    CONVERT("PDF converters"),
    CREATE("Create PDF")
}

/** What a tool wants as input, which decides the file picker and the file list. */
enum class PdfInput(val mimes: Array<String>, val many: Boolean, val label: String) {
    PDF(arrayOf("application/pdf"), false, "a PDF"),
    PDF_MANY(arrayOf("application/pdf"), true, "PDFs"),
    JPG(arrayOf("image/jpeg"), true, "JPG images"),
    PNG(arrayOf("image/png"), true, "PNG images"),
    DOCX(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword"), false, "a Word file"),
    XLSX(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel"), false, "an Excel file"),
    PPTX(arrayOf("application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.ms-powerpoint"), false, "a PowerPoint file")
}

enum class PdfTool(
    val title: String, val blurb: String, val category: PdfCategory, val icon: ImageVector, val input: PdfInput, val tint: Color
) {
    SPLIT("Split PDF", "Pull out pages or ranges", PdfCategory.ORGANIZE, Icons.Outlined.CallSplit, PdfInput.PDF, Neon.lime),
    MERGE("Merge PDF", "Combine several PDFs into one", PdfCategory.ORGANIZE, Icons.Outlined.MergeType, PdfInput.PDF_MANY, Neon.green),
    ROTATE("Rotate PDF", "Turn pages 90°, 180° or 270°", PdfCategory.ORGANIZE, Icons.Outlined.Rotate90DegreesCw, PdfInput.PDF, Neon.teal),
    PAGE_NUMBERS("Add page numbers", "Choose position, size and style", PdfCategory.ORGANIZE, Icons.Outlined.FormatListNumbered, PdfInput.PDF, Neon.coral),

    COMPRESS("Compress PDF", "Make the file smaller", PdfCategory.SECURITY, Icons.Outlined.Compress, PdfInput.PDF, Neon.lime),
    UNLOCK("Unlock PDF", "Remove a password you know", PdfCategory.SECURITY, Icons.Outlined.LockOpen, PdfInput.PDF, Neon.green),
    PROTECT("Protect PDF", "Add a password and permissions", PdfCategory.SECURITY, Icons.Outlined.Lock, PdfInput.PDF, Neon.coral),

    TO_WORD("PDF to Word", "Editable .docx from text", PdfCategory.CONVERT, Icons.Outlined.Description, PdfInput.PDF, Neon.teal),
    TO_EXCEL("PDF to Excel", "Tables and rows into .xlsx", PdfCategory.CONVERT, Icons.Outlined.TableChart, PdfInput.PDF, Neon.green),
    TO_JPG("PDF to JPG", "Pages as JPG pictures", PdfCategory.CONVERT, Icons.Outlined.Image, PdfInput.PDF, Neon.lime),
    TO_PNG("PDF to PNG", "Pages as sharp PNG pictures", PdfCategory.CONVERT, Icons.Outlined.PhotoLibrary, PdfInput.PDF, Neon.teal),
    TO_HTML("PDF to HTML", "A simple web page from the text", PdfCategory.CONVERT, Icons.Outlined.Code, PdfInput.PDF, Neon.coral),

    WORD_TO_PDF("Word to PDF", "From a .docx file", PdfCategory.CREATE, Icons.Outlined.Article, PdfInput.DOCX, Neon.teal),
    JPG_TO_PDF("JPG to PDF", "Pictures into one PDF", PdfCategory.CREATE, Icons.Outlined.Image, PdfInput.JPG, Neon.lime),
    EXCEL_TO_PDF("Excel to PDF", "From a .xlsx workbook", PdfCategory.CREATE, Icons.Outlined.TableChart, PdfInput.XLSX, Neon.green),
    PPT_TO_PDF("PPT to PDF", "From a .pptx presentation", PdfCategory.CREATE, Icons.Outlined.Slideshow, PdfInput.PPTX, Neon.coral),
    PNG_TO_PDF("PNG to PDF", "Pictures into one PDF", PdfCategory.CREATE, Icons.Outlined.PhotoLibrary, PdfInput.PNG, Neon.teal);

    companion object {
        fun fromId(id: String?): PdfTool? = entries.firstOrNull { it.name == id }
    }
}

/** Small local history: which tools were used lately and which files they produced. Never leaves the phone. */
object PdfStore {
    data class Entry(val name: String, val uri: String, val tool: String, val time: Long, val size: Long)

    private fun prefs(c: Context) = c.getSharedPreferences("pdf_tools", Context.MODE_PRIVATE)

    fun recentTools(c: Context): List<PdfTool> =
        prefs(c).getString("recent_tools", "").orEmpty().split(',').mapNotNull { PdfTool.fromId(it) }

    fun markUsed(c: Context, tool: PdfTool) {
        val list = (listOf(tool.name) + prefs(c).getString("recent_tools", "").orEmpty().split(',').filter { it.isNotBlank() && it != tool.name }).take(6)
        prefs(c).edit().putString("recent_tools", list.joinToString(",")).apply()
    }

    fun history(c: Context): List<Entry> = try {
        val arr = JSONArray(prefs(c).getString("history", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("name"), o.getString("uri"), o.optString("tool"), o.optLong("time"), o.optLong("size"))
        }
    } catch (e: Exception) { emptyList() }

    private fun write(c: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e -> arr.put(JSONObject().put("name", e.name).put("uri", e.uri).put("tool", e.tool).put("time", e.time).put("size", e.size)) }
        prefs(c).edit().putString("history", arr.toString()).apply()
    }

    fun add(c: Context, entries: List<Entry>) = write(c, (entries + history(c)).take(40))
    fun remove(c: Context, uri: String) = write(c, history(c).filter { it.uri != uri })
}

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / (1L shl 10).toDouble())
    else -> "$b B"
}
