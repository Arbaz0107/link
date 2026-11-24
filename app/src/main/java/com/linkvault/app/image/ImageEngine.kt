package com.linkvault.app.image

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Raised for problems that have a message worth showing to the person (corrupt file, too large, ...). */
class ImageProblem(message: String) : Exception(message)

enum class ImgFormat(val ext: String, val mime: String, val label: String) {
    JPG("jpg", "image/jpeg", "JPG"),
    PNG("png", "image/png", "PNG"),
    WEBP("webp", "image/webp", "WEBP");

    val supportsAlpha get() = this != JPG

    companion object {
        fun fromMime(mime: String?, name: String = ""): ImgFormat? = when {
            mime == "image/jpeg" || mime == "image/jpg" -> JPG
            mime == "image/png" -> PNG
            mime == "image/webp" -> WEBP
            else -> when (name.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> JPG
                "png" -> PNG
                "webp" -> WEBP
                else -> null
            }
        }
    }
}

/** One picked image: nothing is copied, the content Uri is read on demand. */
class ImageSource(
    val uri: Uri, val name: String, val size: Long, val width: Int, val height: Int, val format: ImgFormat?, val hasAlpha: Boolean
) {
    val baseName: String get() = name.substringBeforeLast('.', name)
    val megapixels: Float get() = width * height / 1_000_000f
}

object ImageEngine {
    /** Largest picture we keep in memory at once (16 megapixels is about 64 MB as a bitmap). */
    const val MAX_PIXELS = 16_000_000L
    /** Largest picture we will produce when the person asks for a bigger size. */
    const val MAX_OUT_PIXELS = 25_000_000L

    // ------------------------------------------------------------------ reading

    /** Reads name, size and dimensions without decoding the picture. Returns null for files that aren't readable images. */
    fun inspect(context: Context, uri: Uri): ImageSource? {
        val cr = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "image"
        var size = 0L
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (_: Exception) {}
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try { cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } } catch (_: Exception) { return null }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        if (size <= 0L) size = try { cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L } catch (_: Exception) { 0L }
        val fmt = ImgFormat.fromMime(opts.outMimeType ?: cr.getType(uri), name)
        val orient = exifOrientation(cr, uri)
        val swap = orient == 90 || orient == 270
        val alpha = opts.outMimeType == "image/png" || opts.outMimeType == "image/webp" || opts.outMimeType == "image/gif"
        return ImageSource(uri, name, size, if (swap) opts.outHeight else opts.outWidth, if (swap) opts.outWidth else opts.outHeight, fmt, alpha)
    }

    private fun exifOrientation(cr: ContentResolver, uri: Uri): Int = try {
        cr.openInputStream(uri)?.use { s ->
            when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
                ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
                ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
                else -> 0
            }
        } ?: 0
    } catch (_: Exception) { 0 }

    private fun exifFlip(cr: ContentResolver, uri: Uri): Boolean = try {
        cr.openInputStream(uri)?.use { s ->
            when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL, ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> true
                else -> false
            }
        } ?: false
    } catch (_: Exception) { false }

    /**
     * Decodes [uri] upright (EXIF applied) so that its longest side is at most [maxSide] and it has at most
     * [maxPixels] pixels. Tries smaller sizes if the phone runs out of memory.
     */
    fun decode(context: Context, uri: Uri, maxSide: Int = Int.MAX_VALUE, maxPixels: Long = MAX_PIXELS): Bitmap {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try { cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } } catch (_: Exception) { throw ImageProblem("Couldn't open this file.") }
        if (bounds.outWidth <= 0) throw ImageProblem("This isn't a readable image (it may be corrupt or an unsupported format).")
        var w = bounds.outWidth; var h = bounds.outHeight
        var limitSide = maxSide
        var attempt = 0
        while (true) {
            // Largest power-of-two sample size that keeps us at or above the requested size.
            var sample = 1
            while (true) {
                val nw = w / (sample * 2); val nh = h / (sample * 2)
                if (nw < 1 || nh < 1) break
                val tooBigSide = max(nw, nh) >= limitSide
                val tooBigPixels = nw.toLong() * nh > maxPixels
                if (tooBigSide || tooBigPixels) sample *= 2 else break
            }
            try {
                val bmp = cr.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                } ?: throw ImageProblem("Couldn't read this image.")
                var out = orient(context, uri, bmp)
                // Exact limit (sample sizes are only powers of two).
                val scale = minOf(1f, limitSide.toFloat() / max(out.width, out.height), Math.sqrt(maxPixels.toDouble() / (out.width.toLong() * out.height)).toFloat())
                if (scale < 0.999f) out = scaleTo(out, max(1, (out.width * scale).roundToInt()), max(1, (out.height * scale).roundToInt()))
                return out
            } catch (e: OutOfMemoryError) {
                attempt++
                if (attempt > 3) throw ImageProblem("Not enough memory to open this image.")
                limitSide = min(limitSide, max(w, h) / (attempt + 1))
            }
        }
    }

    /** Decodes so the result is exactly [tw] x [th] (used by Resize, which may also enlarge). */
    fun decodeTo(context: Context, uri: Uri, tw: Int, th: Int): Bitmap {
        if (tw.toLong() * th > MAX_OUT_PIXELS) throw ImageProblem("That size is too large for this phone (over ${MAX_OUT_PIXELS / 1_000_000} megapixels).")
        val src = decode(context, uri, maxSide = max(tw, th), maxPixels = MAX_OUT_PIXELS)
        return if (src.width == tw && src.height == th) src else scaleTo(src, tw, th)
    }

    private fun orient(context: Context, uri: Uri, bmp: Bitmap): Bitmap {
        val cr = context.contentResolver
        val rot = exifOrientation(cr, uri)
        val flip = exifFlip(cr, uri)
        if (rot == 0 && !flip) return bmp
        val m = Matrix()
        if (flip) m.postScale(-1f, 1f)
        if (rot != 0) m.postRotate(rot.toFloat())
        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (r !== bmp) bmp.recycle()
        return r
    }

    // ------------------------------------------------------------------ geometry

    /** Smooth resize. Large reductions go in halving steps so the result isn't aliased. */
    fun scaleTo(src: Bitmap, tw: Int, th: Int): Bitmap {
        if (src.width == tw && src.height == th) return src
        var cur = src
        while (cur.width / 2 >= tw && cur.height / 2 >= th) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        val out = Bitmap.createScaledBitmap(cur, tw, th, true)
        if (cur !== src && cur !== out) cur.recycle()
        return out
    }

    fun fitWithin(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        val longest = max(w, h)
        if (longest <= maxSide) return w to h
        val k = maxSide.toFloat() / longest
        return max(1, (w * k).roundToInt()) to max(1, (h * k).roundToInt())
    }

    /** Quarter turns clockwise, then optional flips. */
    fun transform(src: Bitmap, turns: Int, flipH: Boolean, flipV: Boolean): Bitmap {
        val t = ((turns % 4) + 4) % 4
        if (t == 0 && !flipH && !flipV) return src
        val m = Matrix()
        if (flipH || flipV) m.postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
        if (t != 0) m.postRotate(90f * t)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Free-angle rotation. The canvas grows to hold the whole picture; corners are [fill] (transparent by default). */
    fun rotateFree(src: Bitmap, degrees: Float, fill: Int = Color.TRANSPARENT): Bitmap {
        if (abs(degrees) < 0.01f) return src
        val m = Matrix().apply { postRotate(degrees, src.width / 2f, src.height / 2f) }
        val r = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        m.mapRect(r)
        val w = r.width().roundToInt().coerceAtLeast(1); val h = r.height().roundToInt().coerceAtLeast(1)
        if (w.toLong() * h > MAX_OUT_PIXELS) throw ImageProblem("The rotated picture would be too large for this phone.")
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (fill != Color.TRANSPARENT) c.drawColor(fill)
        m.postTranslate(-r.left, -r.top)
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** [r] is a rectangle in 0..1 of the bitmap. */
    fun cropNormalized(src: Bitmap, r: RectF): Bitmap {
        val l = (r.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val t = (r.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val rr = (r.right * src.width).roundToInt().coerceIn(l + 1, src.width)
        val bb = (r.bottom * src.height).roundToInt().coerceIn(t + 1, src.height)
        return Bitmap.createBitmap(src, l, t, rr - l, bb - t)
    }

    // ------------------------------------------------------------------ writing

    fun compressFormat(format: ImgFormat, lossless: Boolean): Bitmap.CompressFormat = when (format) {
        ImgFormat.JPG -> Bitmap.CompressFormat.JPEG
        ImgFormat.PNG -> Bitmap.CompressFormat.PNG
        ImgFormat.WEBP ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) (if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY)
            else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
    }

    /** JPG has no transparency: transparent areas are painted with [background] first. */
    fun flatten(bmp: Bitmap, background: Int = Color.WHITE): Bitmap {
        if (!bmp.hasAlpha()) return bmp
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(background)
        c.drawBitmap(bmp, 0f, 0f, null)
        return out
    }

    fun encodeToFile(bmp: Bitmap, file: File, format: ImgFormat, quality: Int, lossless: Boolean = false, background: Int = Color.WHITE) {
        val src = if (format == ImgFormat.JPG) flatten(bmp, background) else bmp
        try {
            FileOutputStream(file).use { out ->
                if (!src.compress(compressFormat(format, lossless), quality.coerceIn(1, 100), out)) throw ImageProblem("Couldn't encode the picture as ${format.label}.")
            }
        } finally { if (src !== bmp) src.recycle() }
    }

    fun encodeToBytes(bmp: Bitmap, format: ImgFormat, quality: Int, lossless: Boolean = false, background: Int = Color.WHITE): ByteArray {
        val src = if (format == ImgFormat.JPG) flatten(bmp, background) else bmp
        try {
            val out = ByteArrayOutputStream()
            src.compress(compressFormat(format, lossless), quality.coerceIn(1, 100), out)
            return out.toByteArray()
        } finally { if (src !== bmp) src.recycle() }
    }

    fun decodeBytes(bytes: ByteArray, maxSide: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        if (b.outWidth <= 0) return null
        var s = 1
        while (max(b.outWidth, b.outHeight) / (s * 2) >= maxSide) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    // ------------------------------------------------------------------ enhancement (classic filters, no AI model)

    /** All values are -1..1 or 0..1; see [Enhance.presets]. */
    data class EnhanceParams(
        val sharpen: Float = 0f, val denoise: Float = 0f, val brightness: Float = 0f, val contrast: Float = 0f,
        val saturation: Float = 0f, val detail: Float = 0f
    ) {
        val isNeutral get() = sharpen == 0f && denoise == 0f && brightness == 0f && contrast == 0f && saturation == 0f && detail == 0f
    }

    /** Looks at the brightness histogram to pick brightness/contrast, then adds a little sharpening and noise reduction. */
    fun autoParams(bmp: Bitmap): EnhanceParams {
        val small = if (max(bmp.width, bmp.height) > 256) {
            val (w, h) = fitWithin(bmp.width, bmp.height, 256); Bitmap.createScaledBitmap(bmp, w, h, true)
        } else bmp
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        if (small !== bmp) small.recycle()
        val hist = IntArray(256)
        var n = 0
        for (p in px) {
            if (Color.alpha(p) < 16) continue
            hist[((Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000)]++; n++
        }
        if (n == 0) return EnhanceParams(sharpen = 0.35f)
        fun pct(f: Float): Int { var acc = 0; val target = (n * f).toInt(); for (i in 0..255) { acc += hist[i]; if (acc >= target) return i }; return 255 }
        val lo = pct(0.01f); val hi = pct(0.99f); val mean = pct(0.5f)
        val spread = (hi - lo).coerceAtLeast(1)
        val contrast = ((200f - spread) / 200f).coerceIn(0f, 0.45f)
        val brightness = ((118f - mean) / 255f * 1.2f).coerceIn(-0.25f, 0.3f)
        return EnhanceParams(sharpen = 0.4f, denoise = 0.15f, brightness = brightness, contrast = contrast, saturation = 0.08f, detail = 0.15f)
    }

    /**
     * Applies the adjustments. Order: noise reduction, tone/colour, sharpen, detail (local contrast).
     * [upscale] 2 enlarges first with smooth interpolation (it adds pixels, not new detail).
     */
    fun enhance(src: Bitmap, p: EnhanceParams, upscale: Int = 1, check: () -> Unit = {}): Bitmap {
        var base = src
        if (upscale == 2) {
            val w = src.width * 2; val h = src.height * 2
            if (w.toLong() * h > 12_000_000L) throw ImageProblem("Too large to enlarge 2x on this phone. Use a smaller picture or 1x.")
            base = Bitmap.createScaledBitmap(src, w, h, true)
        }
        if (p.isNeutral) return if (base === src) src.copy(Bitmap.Config.ARGB_8888, true) else base
        val w = base.width; val h = base.height
        if (w.toLong() * h > 12_000_000L) throw ImageProblem("This picture is too large to enhance on this phone (limit 12 megapixels).")
        var px = IntArray(w * h)
        base.getPixels(px, 0, w, 0, 0, w, h)
        if (base !== src) base.recycle()
        check()

        if (p.denoise > 0f) {
            val blur = boxBlur(px, w, h, if (max(w, h) > 2500) 2 else 1)
            val k = p.denoise
            for (i in px.indices) {
                val a = px[i]; val b = blur[i]
                val diff = (abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))) / 3f
                // Flat areas (small difference) are smoothed; real edges are kept.
                val mix = k * (1f - min(1f, diff / 40f))
                px[i] = lerpRgb(a, b, mix)
            }
            check()
        }

        if (p.brightness != 0f || p.contrast != 0f || p.saturation != 0f) {
            val off = p.brightness * 90f
            val cf = 1f + p.contrast
            val sat = 1f + p.saturation
            val lut = IntArray(256) { v -> (((v - 128f) * cf + 128f) + off).roundToInt().coerceIn(0, 255) }
            for (i in px.indices) {
                val c = px[i]
                var r = lut[Color.red(c)]; var g = lut[Color.green(c)]; var b = lut[Color.blue(c)]
                if (p.saturation != 0f) {
                    val gray = 0.299f * r + 0.587f * g + 0.114f * b
                    r = (gray + (r - gray) * sat).roundToInt().coerceIn(0, 255)
                    g = (gray + (g - gray) * sat).roundToInt().coerceIn(0, 255)
                    b = (gray + (b - gray) * sat).roundToInt().coerceIn(0, 255)
                }
                px[i] = Color.argb(Color.alpha(c), r, g, b)
            }
            check()
        }

        if (p.sharpen > 0f) {
            px = unsharp(px, w, h, radius = if (max(w, h) > 2500) 2 else 1, amount = p.sharpen * 1.6f)
            check()
        }
        if (p.detail > 0f) {
            val r = (max(w, h) / 120).coerceIn(4, 24)
            px = unsharp(px, w, h, radius = r, amount = p.detail * 0.8f)
            check()
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun lerpRgb(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt()
        return Color.argb(Color.alpha(a), r, g, bl)
    }

    private fun unsharp(px: IntArray, w: Int, h: Int, radius: Int, amount: Float): IntArray {
        val blur = boxBlur(px, w, h, radius)
        val out = IntArray(px.size)
        for (i in px.indices) {
            val a = px[i]; val b = blur[i]
            val r = (Color.red(a) + amount * (Color.red(a) - Color.red(b))).roundToInt().coerceIn(0, 255)
            val g = (Color.green(a) + amount * (Color.green(a) - Color.green(b))).roundToInt().coerceIn(0, 255)
            val bl = (Color.blue(a) + amount * (Color.blue(a) - Color.blue(b))).roundToInt().coerceIn(0, 255)
            out[i] = Color.argb(Color.alpha(a), r, g, bl)
        }
        return out
    }

    /** Separable box blur with running sums (cost does not depend on the radius). Alpha is kept as is. */
    fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)
        val d = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) { val c = src[row + k.coerceIn(0, w - 1)]; sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c) }
            for (x in 0 until w) {
                tmp[row + x] = Color.argb(Color.alpha(src[row + x]), sr / d, sg / d, sb / d)
                val add = src[row + (x + r + 1).coerceAtMost(w - 1)]; val sub = src[row + (x - r).coerceAtLeast(0)]
                sr += Color.red(add) - Color.red(sub); sg += Color.green(add) - Color.green(sub); sb += Color.blue(add) - Color.blue(sub)
            }
        }
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) { val c = tmp[k.coerceIn(0, h - 1) * w + x]; sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c) }
            for (y in 0 until h) {
                out[y * w + x] = Color.argb(Color.alpha(src[y * w + x]), sr / d, sg / d, sb / d)
                val add = tmp[(y + r + 1).coerceAtMost(h - 1) * w + x]; val sub = tmp[(y - r).coerceAtLeast(0) * w + x]
                sr += Color.red(add) - Color.red(sub); sg += Color.green(add) - Color.green(sub); sb += Color.blue(add) - Color.blue(sub)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ background removal masks

    /**
     * Colour-based mask for plain backgrounds: grows from the picture's edges across pixels that look like
     * the background colour. Works well on solid or softly shaded backgrounds, not on busy scenes.
     * Returns one alpha byte per pixel (255 = keep).
     */
    fun maskFromBackgroundColor(bmp: Bitmap, tolerance: Int, check: () -> Unit = {}): ByteArray {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        // Reference colours: the average of each corner patch.
        val pw = max(2, w / 40); val ph = max(2, h / 40)
        fun patch(x0: Int, y0: Int): Int {
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (y in y0 until min(h, y0 + ph)) for (x in x0 until min(w, x0 + pw)) { val c = px[y * w + x]; r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++ }
            return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }
        val refs = intArrayOf(patch(0, 0), patch(w - pw, 0), patch(0, h - ph), patch(w - pw, h - ph))
        fun dist(c: Int, ref: Int): Int = abs(Color.red(c) - Color.red(ref)) + abs(Color.green(c) - Color.green(ref)) + abs(Color.blue(c) - Color.blue(ref))
        fun isBg(c: Int): Boolean { if (Color.alpha(c) < 16) return true; for (r in refs) if (dist(c, r) <= tolerance * 3) return true; return false }

        val visited = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        fun push(i: Int) { if (!visited[i] && isBg(px[i])) { visited[i] = true; stack[sp++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        var steps = 0
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w; val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
            steps++
            if ((steps and 0x3FFFF) == 0) check()
        }
        val mask = ByteArray(w * h) { if (visited[it]) 0 else 255.toByte() }
        return softenMask(mask, w, h)
    }

    /** Turns a float confidence map (0..1) into soft alpha bytes. */
    fun maskFromConfidence(conf: FloatArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (i in out.indices) {
            val c = conf[i]
            val a = when { c <= 0.25f -> 0f; c >= 0.75f -> 1f; else -> (c - 0.25f) / 0.5f }
            out[i] = (a * a * (3f - 2f * a) * 255f).roundToInt().toByte()
        }
        return out
    }

    /** One pass of 3x3 smoothing so cut-out edges aren't jagged. */
    fun softenMask(mask: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(mask.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) { s += mask[yy * w + xx].toInt() and 0xFF; n++ }
            }
            out[y * w + x] = (s / n).toByte()
        }
        return out
    }

    /** Applies a (possibly lower-resolution) mask to [bmp], sampling the mask bilinearly. */
    fun applyMask(bmp: Bitmap, mask: ByteArray, mw: Int, mh: Int): Bitmap {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val sx = if (w > 1) (mw - 1).toFloat() / (w - 1) else 0f
        val sy = if (h > 1) (mh - 1).toFloat() / (h - 1) else 0f
        for (y in 0 until h) {
            val fy = y * sy; val y0 = fy.toInt().coerceIn(0, mh - 1); val y1 = min(mh - 1, y0 + 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = x * sx; val x0 = fx.toInt().coerceIn(0, mw - 1); val x1 = min(mw - 1, x0 + 1); val tx = fx - x0
                val a00 = mask[y0 * mw + x0].toInt() and 0xFF; val a10 = mask[y0 * mw + x1].toInt() and 0xFF
                val a01 = mask[y1 * mw + x0].toInt() and 0xFF; val a11 = mask[y1 * mw + x1].toInt() and 0xFF
                val a = (a00 * (1 - tx) + a10 * tx) * (1 - ty) + (a01 * (1 - tx) + a11 * tx) * ty
                val c = px[y * w + x]
                px[y * w + x] = Color.argb(((Color.alpha(c) * a) / 255f).roundToInt(), Color.red(c), Color.green(c), Color.blue(c))
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    // ------------------------------------------------------------------ screenshots

    /**
     * Finds plain bars along the edges (black letterbox bars, solid margins) and returns the content area as
     * fractions of the picture, or null when nothing worth trimming was found. It cannot see status-bar icons
     * or in-app chrome: for those, crop the page by hand.
     */
    fun detectBorders(bmp: Bitmap): RectF? {
        val (sw, sh) = fitWithin(bmp.width, bmp.height, 400)
        val s = Bitmap.createScaledBitmap(bmp, sw, sh, false)
        val px = IntArray(sw * sh); s.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (s !== bmp) s.recycle()
        fun lum(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        fun rowUniform(y: Int, ref: Int): Boolean { for (x in 0 until sw step 2) if (abs(lum(px[y * sw + x]) - ref) > 10) return false; return true }
        fun colUniform(x: Int, ref: Int): Boolean { for (y in 0 until sh step 2) if (abs(lum(px[y * sw + x]) - ref) > 10) return false; return true }
        var top = 0; val tRef = lum(px[0])
        while (top < sh * 0.2 && rowUniform(top, tRef)) top++
        var bottom = sh; val bRef = lum(px[(sh - 1) * sw])
        while (bottom > sh * 0.8 && rowUniform(bottom - 1, bRef)) bottom--
        var left = 0; val lRef = lum(px[0])
        while (left < sw * 0.2 && colUniform(left, lRef)) left++
        var right = sw; val rRef = lum(px[sw - 1])
        while (right > sw * 0.8 && colUniform(right - 1, rRef)) right--
        val r = RectF(left.toFloat() / sw, top.toFloat() / sh, right.toFloat() / sw, bottom.toFloat() / sh)
        val trimmed = r.left + r.top + (1f - r.right) + (1f - r.bottom)
        return if (trimmed > 0.015f && r.width() > 0.3f && r.height() > 0.3f) r else null
    }

    fun sizeText(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}
