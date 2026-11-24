package com.linkvault.app.pdf

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OcrLine(val text: String, val rect: Rect)

/**
 * On-device text recognition (ML Kit, models bundled in the app, so it works offline and nothing is uploaded).
 * Latin covers English and most European languages; Devanagari covers Hindi, Marathi and Nepali.
 * Call from a background thread.
 */
object PdfOcr {
    fun recognize(bmp: Bitmap, devanagari: Boolean): List<OcrLine> {
        val recognizer = if (devanagari) TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        else TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
            val out = ArrayList<OcrLine>()
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val r = line.boundingBox ?: continue
                    if (line.text.isNotBlank()) out += OcrLine(line.text, r)
                }
            }
            return out
        } finally {
            recognizer.close()
        }
    }
}
