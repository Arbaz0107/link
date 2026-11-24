package com.linkvault.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix as AMatrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A problem the person can understand and act on (wrong password, bad page range, ...). */
class PdfProblem(message: String) : Exception(message)

/** Everything one tool run needs: a scratch folder for outputs, progress reporting and cancellation. */
class PdfJob(
    val context: Context,
    val outDir: File,
    private val cancelCheck: () -> Unit,
    private val report: (Float, String) -> Unit
) {
    fun check() = cancelCheck()
    fun progress(fraction: Float, text: String) = report(fraction.coerceIn(0f, 1f), text)
    fun out(name: String): File = uniqueFile(outDir, safeName(name))
}

fun safeName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().trim('.').ifEmpty { "file" }.take(120)

fun uniqueFile(dir: File, name: String): File {
    dir.mkdirs()
    var f = File(dir, name)
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "")
    var n = 1
    while (f.exists()) { f = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"); n++ }
    return f
}

enum class CompressLevel(val label: String, val hint: String, val quality: Float, val scale: Float) {
    LOW("Low", "Best quality, smallest saving", 0.85f, 1f),
    MEDIUM("Medium", "Balanced size and quality", 0.6f, 0.75f),
    HIGH("High", "Smallest file, softer pictures", 0.4f, 0.5f)
}

enum class NumberPosition(val label: String) {
    TOP_LEFT("Top left"), TOP_CENTER("Top center"), TOP_RIGHT("Top right"),
    BOTTOM_LEFT("Bottom left"), BOTTOM_CENTER("Bottom center"), BOTTOM_RIGHT("Bottom right")
}

enum class NumberStyle(val label: String) {
    PLAIN("1"), PAGE("Page 1"), OF_TOTAL("1 / 10"), PAGE_OF_TOTAL("Page 1 of 10"), ROMAN("i, ii, iii"), DASHED("- 1 -");

    fun text(n: Int, total: Int): String = when (this) {
        PLAIN -> "$n"
        PAGE -> "Page $n"
        OF_TOTAL -> "$n / $total"
        PAGE_OF_TOTAL -> "Page $n of $total"
        ROMAN -> roman(n)
        DASHED -> "- $n -"
    }

    private fun roman(number: Int): String {
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
        var n = number; val sb = StringBuilder()
        for (i in v.indices) while (n >= v[i]) { sb.append(s[i]); n -= v[i] }
        return sb.toString()
    }
}

enum class ImagePageSize(val label: String) { A4("A4"), LETTER("Letter"), FIT("Fit to image") }
enum class ImageOrientation(val label: String) { AUTO("Auto"), PORTRAIT("Portrait"), LANDSCAPE("Landscape") }
enum class ImageMargin(val label: String, val pt: Float) { NONE("None", 0f), SMALL("Small", 18f), NORMAL("Normal", 36f) }

object PdfEngine {
    private val memory get() = MemoryUsageSetting.setupMixed(32L * 1024 * 1024)

    fun init(context: Context) = PDFBoxResourceLoader.init(context.applicationContext)

    // ---------- helpers ----------

    data class Info(val pages: Int, val encrypted: Boolean)

    /** Quick page count without loading the whole file. encrypted = needs a password to be read. */
    fun info(file: File): Info = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val r = PdfRenderer(pfd)
            val n = r.pageCount
            r.close()
            Info(n, false)
        }
    } catch (e: SecurityException) { Info(0, true) }
    catch (e: Exception) { Info(0, false) }

    fun open(file: File, password: String = ""): PDDocument = try {
        PDDocument.load(file, password, memory)
    } catch (e: InvalidPasswordException) {
        throw PdfProblem(if (password.isBlank()) "This PDF is password protected. Enter its password." else "That password is not correct.")
    } catch (e: PdfProblem) { throw e
    } catch (e: OutOfMemoryError) {
        throw PdfProblem("This PDF is too large for this phone to process.")
    } catch (e: Exception) {
        throw PdfProblem("Couldn't read this PDF. It may be damaged or not a real PDF file.")
    }

    /** "1-3, 5, 8-" -> [[1,2,3],[5],[8..total]]. Page numbers start at 1. */
    fun parseRanges(spec: String, total: Int): List<List<Int>> {
        val parts = spec.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw PdfProblem("Enter the pages you want, for example 1-3, 5, 8-10.")
        return parts.map { part ->
            val m = Regex("""^(\d*)\s*-\s*(\d*)$""").matchEntire(part)
            val (a, b) = if (m != null) {
                val a = m.groupValues[1].ifEmpty { "1" }.toInt()
                val b = m.groupValues[2].ifEmpty { total.toString() }.toInt()
                a to b
            } else {
                val n = part.toIntOrNull() ?: throw PdfProblem("\"$part\" isn't a page number. Use something like 1-3, 5, 8-10.")
                n to n
            }
            if (a < 1 || b < a) throw PdfProblem("\"$part\" isn't a valid page range.")
            if (b > total) throw PdfProblem("Page $b is out of range. This PDF has $total ${if (total == 1) "page" else "pages"}.")
            (a..b).toList()
        }
    }

    fun parsePages(spec: String, total: Int): List<Int> = parseRanges(spec, total).flatten().distinct()

    // ---------- organise ----------

    /** One output PDF per group of pages. */
    fun split(job: PdfJob, src: File, password: String, groups: List<List<Int>>, baseName: String): List<File> {
        val result = ArrayList<File>()
        open(src, password).use { doc ->
            val total = doc.numberOfPages
            groups.forEachIndexed { i, pages ->
                job.check()
                job.progress(i / groups.size.toFloat(), "Creating file ${i + 1} of ${groups.size}")
                val label = if (pages.size == 1) "page ${pages[0]}" else "pages ${pages.first()}-${pages.last()}"
                val file = job.out(if (groups.size == 1) "$baseName.pdf" else "$baseName ($label).pdf")
                PDDocument().use { out ->
                    pages.forEach { p -> if (p in 1..total) out.importPage(doc.getPage(p - 1)) }
                    out.save(file)
                }
                result += file
            }
        }
        return result
    }

    fun merge(job: PdfJob, files: List<File>, baseName: String): File {
        if (files.size < 2) throw PdfProblem("Pick at least two PDFs to merge.")
        val out = job.out("$baseName.pdf")
        val merger = PDFMergerUtility()
        files.forEach { merger.addSource(it) }
        merger.destinationFileName = out.absolutePath
        job.progress(0.1f, "Merging ${files.size} files")
        try {
            merger.mergeDocuments(memory)
        } catch (e: InvalidPasswordException) {
            throw PdfProblem("One of the files is password protected. Unlock it first, then merge.")
        } catch (e: OutOfMemoryError) {
            throw PdfProblem("These files are too large to merge on this phone.")
        } catch (e: Exception) {
            throw PdfProblem("Couldn't merge these files. One of them may be damaged.")
        }
        return out
    }

    fun rotate(job: PdfJob, src: File, password: String, pages: List<Int>?, degrees: Int, baseName: String): File {
        val out = job.out("$baseName.pdf")
        open(src, password).use { doc ->
            val total = doc.numberOfPages
            val targets = pages ?: (1..total).toList()
            targets.forEachIndexed { i, p ->
                job.check()
                job.progress(i / targets.size.toFloat(), "Rotating page $p")
                val page = doc.getPage(p - 1)
                page.rotation = ((page.rotation + degrees) % 360 + 360) % 360
            }
            doc.save(out)
        }
        return out
    }

    /** Page rectangle in the orientation the reader sees (rotation applied). */
    fun addPageNumbers(
        job: PdfJob, src: File, password: String, position: NumberPosition, style: NumberStyle,
        fontSize: Float, startAt: Int, skipFirst: Boolean, baseName: String
    ): File {
        val out = job.out("$baseName.pdf")
        open(src, password).use { doc ->
            val total = doc.numberOfPages
            for (i in 0 until total) {
                job.check()
                job.progress(i / total.toFloat(), "Numbering page ${i + 1} of $total")
                if (skipFirst && i == 0) continue
                val page = doc.getPage(i)
                val label = style.text(startAt + i - if (skipFirst) 1 else 0, startAt + total - 1 - if (skipFirst) 1 else 0)
                stamp(doc, page, label, position, fontSize)
            }
            doc.save(out)
        }
        return out
    }

    private fun stamp(doc: PDDocument, page: PDPage, text: String, position: NumberPosition, size: Float) {
        val box = page.cropBox ?: page.mediaBox
        val rot = ((page.rotation % 360) + 360) % 360
        val w = box.width; val h = box.height            // unrotated page size
        val dw = if (rot == 90 || rot == 270) h else w   // size as displayed
        val dh = if (rot == 90 || rot == 270) w else h
        val font = PDType1Font.HELVETICA
        val textW = font.getStringWidth(text) / 1000f * size
        val margin = 28f
        val dx = when (position) {
            NumberPosition.TOP_LEFT, NumberPosition.BOTTOM_LEFT -> margin
            NumberPosition.TOP_CENTER, NumberPosition.BOTTOM_CENTER -> (dw - textW) / 2f
            else -> dw - margin - textW
        }
        val dy = if (position.name.startsWith("TOP")) dh - margin - size * 0.8f else margin
        // Map the displayed point back to the page's own (unrotated) coordinate space.
        val (ux, uy) = when (rot) {
            90 -> (w - dy) to dx
            180 -> (w - dx) to (h - dy)
            270 -> dy to (h - dx)
            else -> dx to dy
        }
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.beginText()
            cs.setFont(font, size)
            cs.setNonStrokingColor(60, 60, 60)
            cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(rot.toDouble()), ux + box.lowerLeftX, uy + box.lowerLeftY))
            cs.showText(text)
            cs.endText()
        }
    }

    // ---------- optimise & secure ----------

    fun compress(job: PdfJob, src: File, password: String, level: CompressLevel, baseName: String): File {
        val out = job.out("$baseName.pdf")
        open(src, password).use { doc ->
            val total = doc.numberOfPages
            val cache = HashMap<COSStream, PDImageXObject?>()
            for (i in 0 until total) {
                job.check()
                job.progress(i / total.toFloat(), "Compressing page ${i + 1} of $total")
                recompress(doc, doc.getPage(i).resources, level, cache, 0)
            }
            doc.save(out)
        }
        if (out.length() >= src.length()) {
            out.delete()
            throw PdfProblem("This PDF is already well optimised, so no smaller copy could be made. Try a higher level.")
        }
        return out
    }

    private fun recompress(doc: PDDocument, res: PDResources?, level: CompressLevel, cache: HashMap<COSStream, PDImageXObject?>, depth: Int) {
        if (res == null) return
        for (name in res.xObjectNames) {
            val x = try { res.getXObject(name) } catch (e: Exception) { continue }
            when {
                x is PDImageXObject -> {
                    val key = x.cosObject
                    val replacement = if (cache.containsKey(key)) cache[key] else smaller(doc, x, level).also { cache[key] = it }
                    if (replacement != null) res.put(name, replacement)
                }
                x is PDFormXObject && depth < 3 -> recompress(doc, x.resources, level, cache, depth + 1)
            }
        }
    }

    private fun smaller(doc: PDDocument, img: PDImageXObject, level: CompressLevel): PDImageXObject? {
        try {
            if (img.isStencil || img.softMask != null || img.mask != null) return null   // keep transparency intact
            if (img.width.toLong() * img.height < 40_000) return null
            val original = img.image ?: return null
            var bmp = original
            if (level.scale < 1f) {
                bmp = Bitmap.createScaledBitmap(original, maxOf(1, (original.width * level.scale).toInt()), maxOf(1, (original.height * level.scale).toInt()), true)
            }
            val fresh = JPEGFactory.createFromImage(doc, bmp, level.quality)
            val before = img.cosObject.getInt(COSName.LENGTH)
            val after = fresh.cosObject.getInt(COSName.LENGTH)
            return if (after in 1 until before) fresh else null
        } catch (e: Throwable) {
            return null   // an image we can't re-encode simply stays as it was
        }
    }

    fun unlock(job: PdfJob, src: File, password: String, baseName: String): File {
        val out = job.out("$baseName.pdf")
        open(src, password).use { doc ->
            if (!doc.isEncrypted) throw PdfProblem("This PDF isn't password protected.")
            job.progress(0.5f, "Removing protection")
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
        return out
    }

    fun protect(
        job: PdfJob, src: File, openPassword: String, ownerPassword: String,
        allowPrint: Boolean, allowCopy: Boolean, allowEdit: Boolean, baseName: String
    ): File {
        if (openPassword.length < 4) throw PdfProblem("Use a password with at least 4 characters.")
        val out = job.out("$baseName.pdf")
        open(src).use { doc ->
            val perms = AccessPermission().apply {
                setCanPrint(allowPrint); setCanPrintDegraded(allowPrint)
                setCanExtractContent(allowCopy); setCanExtractForAccessibility(true)
                setCanModify(allowEdit); setCanModifyAnnotations(allowEdit); setCanAssembleDocument(allowEdit); setCanFillInForm(allowEdit)
            }
            val owner = ownerPassword.ifBlank { openPassword + "#owner" + System.nanoTime() }
            val policy = StandardProtectionPolicy(owner, openPassword, perms)
            policy.encryptionKeyLength = 128
            policy.isPreferAES = true
            job.progress(0.5f, "Encrypting")
            doc.protect(policy)
            doc.save(out)
        }
        return out
    }

    // ---------- PDF -> images ----------

    fun toImages(
        job: PdfJob, src: File, pages: List<Int>?, dpi: Int, png: Boolean, jpgQuality: Int, asZip: Boolean, baseName: String
    ): List<File> {
        val files = ArrayList<File>()
        val pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { PdfRenderer(pfd) } catch (e: SecurityException) {
            pfd.close(); throw PdfProblem("This PDF is password protected. Unlock it first with Unlock PDF.")
        } catch (e: Exception) { pfd.close(); throw PdfProblem("Couldn't read this PDF.") }
        try {
            val total = renderer.pageCount
            val targets = pages ?: (1..total).toList()
            val ext = if (png) "png" else "jpg"
            val temp = if (asZip) File(job.outDir, "img-tmp").apply { mkdirs() } else job.outDir
            targets.forEachIndexed { i, p ->
                job.check()
                job.progress(i / targets.size.toFloat(), "Converting page $p")
                val page = renderer.openPage(p - 1)
                try {
                    var scale = dpi / 72f
                    val maxPixels = 16_000_000f
                    if (page.width * scale * page.height * scale > maxPixels) scale = Math.sqrt((maxPixels / (page.width * page.height)).toDouble()).toFloat()
                    val bmp = Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val name = if (targets.size == 1) "$baseName.$ext" else "$baseName - page ${p.toString().padStart(3, '0')}.$ext"
                    val f = if (asZip) File(temp, name) else job.out(name)
                    FileOutputStream(f).use { os -> bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else jpgQuality, os) }
                    bmp.recycle()
                    files += f
                } finally { page.close() }
            }
            if (asZip) {
                val zip = job.out("$baseName ($ext images).zip")
                ZipOutputStream(FileOutputStream(zip)).use { z ->
                    files.forEach { f -> z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
                }
                temp.deleteRecursively()
                return listOf(zip)
            }
        } finally { renderer.close(); pfd.close() }
        return files
    }

    // ---------- images -> PDF ----------

    fun imagesToPdf(
        job: PdfJob, images: List<File>, size: ImagePageSize, orientation: ImageOrientation, margin: ImageMargin, baseName: String
    ): File {
        if (images.isEmpty()) throw PdfProblem("Pick at least one image.")
        val out = job.out("$baseName.pdf")
        PDDocument().use { doc ->
            images.forEachIndexed { i, f ->
                job.check()
                job.progress(i / images.size.toFloat(), "Adding image ${i + 1} of ${images.size}")
                val bmp = decodeUpright(f, 2600) ?: throw PdfProblem("Couldn't read \"${f.name}\". Is it a valid image?")
                val m = margin.pt
                val rect: PDRectangle = if (size == ImagePageSize.FIT) {
                    PDRectangle(bmp.width * 0.48f + 2 * m, bmp.height * 0.48f + 2 * m)
                } else {
                    val base = if (size == ImagePageSize.A4) PDRectangle.A4 else PDRectangle.LETTER
                    val landscape = when (orientation) {
                        ImageOrientation.LANDSCAPE -> true
                        ImageOrientation.PORTRAIT -> false
                        ImageOrientation.AUTO -> bmp.width > bmp.height
                    }
                    if (landscape) PDRectangle(base.height, base.width) else PDRectangle(base.width, base.height)
                }
                val page = PDPage(rect)
                doc.addPage(page)
                val availW = rect.width - 2 * m
                val availH = rect.height - 2 * m
                val k = minOf(availW / bmp.width, availH / bmp.height)
                val w = bmp.width * k; val h = bmp.height * k
                val pdImage = if (f.extension.equals("png", true)) LosslessFactory.createFromImage(doc, bmp) else JPEGFactory.createFromImage(doc, bmp, 0.92f)
                PDPageContentStream(doc, page).use { cs -> cs.drawImage(pdImage, (rect.width - w) / 2f, (rect.height - h) / 2f, w, h) }
                bmp.recycle()
            }
            doc.save(out)
        }
        return out
    }

    /** Decodes with sub-sampling so photo-sized images don't exhaust memory, and applies EXIF rotation. */
    private fun decodeUpright(file: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = try {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        if (rotation == 0f) return bmp
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, AMatrix().apply { postRotate(rotation) }, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }
}
