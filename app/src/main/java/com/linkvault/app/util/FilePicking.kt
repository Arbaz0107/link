package com.linkvault.app.util

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract

/** "Save as" picker where the file type is chosen per call. Input: (mime type, suggested file name). */
class CreateDocWithMime : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.first).putExtra(Intent.EXTRA_TITLE, input.second)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

object FilePicking {
    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "html" -> "text/html"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    /** Copies the content behind [from] into the document the person picked with the Save-as dialog. */
    fun copyUri(context: Context, from: Uri, to: Uri): Boolean = try {
        val input = context.contentResolver.openInputStream(from)
        val output = context.contentResolver.openOutputStream(to, "wt")
        if (input == null || output == null) {
            input?.close(); output?.close(); false
        } else {
            input.use { i -> output.use { o -> i.copyTo(o) } }
            true
        }
    } catch (e: Exception) { false }

    /** Renames a file this app saved (a MediaStore entry). Returns false when Android says no. */
    fun rename(context: Context, uri: Uri, newName: String): Boolean = try {
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }, null, null) > 0
    } catch (e: Exception) { false }
}
