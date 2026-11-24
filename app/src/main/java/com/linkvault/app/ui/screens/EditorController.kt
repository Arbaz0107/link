package com.linkvault.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linkvault.app.download.MediaSaver
import com.linkvault.app.pdf.*
import com.linkvault.app.util.FilePicking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as lockWith

sealed interface OpenResult {
    data object Ok : OpenResult
    data object NeedPassword : OpenResult
    data class Failed(val message: String) : OpenResult
}

class SavedPdf(val name: String, val uri: String, val size: Long)
class ReplaceOutcome(val done: Int, val remaining: Int, val reason: String?)

/** Renders pages of one PDF file. One page is open at a time, so calls are serialised. */
class PageRenderer(file: File) : AutoCloseable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val lock = ReentrantLock()
    private var closed = false
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? {
        val key = "$index@$widthPx"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            lock.lockWith {
                if (closed) null
                else try {
                    val page = renderer.openPage(index)
                    try {
                        val h = (widthPx * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        cache.put(key, bmp)
                        bmp
                    } finally { page.close() }
                } catch (e: Throwable) { null }
            }
        }
    }

    override fun close() {
        // Closing waits for a render in progress, so do it off the main thread.
        Thread {
            lock.lockWith {
                if (!closed) { closed = true; try { renderer.close(); pfd.close() } catch (_: Exception) {} }
            }
        }.start()
    }
}

/**
 * All state and logic of the PDF editor. The document is a list of undo steps; each step is a working
 * file (never the person's original) plus the objects drawn over it.
 */
@Stable
class EditorController(private val context: Context) {
    val workDir = File(context.cacheDir, "pdfeditor/${System.currentTimeMillis()}").apply { mkdirs() }
    private var fileCounter = 0
    private var idCounter = 1L

    var history by mutableStateOf<List<EditorState>>(emptyList())
        private set
    var cursor by mutableIntStateOf(-1)
        private set
    private var savedMark by mutableIntStateOf(-1)

    var title by mutableStateOf("Untitled.pdf")
    var sourceUri: Uri? = null
    var busy by mutableStateOf<String?>(null)
    var progress by mutableFloatStateOf(-1f)
    var message by mutableStateOf<String?>(null)

    var selectedId by mutableStateOf<Long?>(null)
    var draft by mutableStateOf<EditObj?>(null)
    var erasing by mutableStateOf<Set<Long>>(emptySet())

    var hits by mutableStateOf<List<Hit>>(emptyList())
        private set
    var hitIndex by mutableIntStateOf(0)

    val current: EditorState? get() = history.getOrNull(cursor)
    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor in 0 until history.lastIndex
    val dirty: Boolean get() = cursor >= 0 && cursor != savedMark

    private var layout: LayoutSession? = null
    private var layoutFile: File? = null
    private val layoutMutex = Mutex()

    fun newId(): Long = idCounter++
    private fun newFile(prefix: String, ext: String = "pdf") = File(workDir, "$prefix-${fileCounter++}.$ext")

    // ---------- history ----------

    private fun push(s: EditorState) {
        val keep = history.take(cursor + 1)
        val dropped = history.drop(cursor + 1)
        if (keep.lastOrNull()?.file != s.file) { layout?.close(); layout = null; layoutFile = null }
        history = keep + s
        cursor = history.lastIndex
        if (savedMark >= keep.size) savedMark = -1
        val live = history.map { it.file }.toSet()
        dropped.forEach { if (it.file !in live) it.file.delete() }
        selectedId = null
        draft = null
    }

    fun mutate(label: String, f: (List<EditObj>) -> List<EditObj>) {
        val st = current ?: return
        push(st.copy(objects = f(st.objects), label = label))
    }

    fun undo() {
        if (!canUndo) return
        cursor -= 1
        selectedId = null; draft = null; hits = emptyList()
        if (current?.file != layoutFile) { layout?.close(); layout = null; layoutFile = null }
    }

    fun redo() {
        if (!canRedo) return
        cursor += 1
        selectedId = null; draft = null; hits = emptyList()
        if (current?.file != layoutFile) { layout?.close(); layout = null; layoutFile = null }
    }

    private suspend fun <T> working(msg: String, block: suspend () -> T): T {
        busy = msg
        progress = -1f
        try { return block() } finally { busy = null; progress = -1f }
    }

    // ---------- opening ----------

    private fun copyIn(uri: Uri, prefix: String, defaultExt: String = "pdf"): Pair<File, String> {
        var name = "document.$defaultExt"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        val ext = name.substringAfterLast('.', defaultExt).lowercase().let { if (it in setOf("pdf", "png", "jpg", "jpeg")) it else defaultExt }
        val f = newFile(prefix, ext)
        val input = context.contentResolver.openInputStream(uri) ?: throw PdfProblem("Couldn't read that file.")
        input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f to name
    }

    suspend fun open(uri: Uri, password: String = ""): OpenResult {
        return try {
            val loaded = withContext(Dispatchers.IO) {
                PdfEngine.init(context)
                val (f, name) = copyIn(uri, "src")
                val info = PdfEngine.info(f)
                var file = f
                if (info.encrypted) {
                    if (password.isBlank()) return@withContext null
                    file = PdfEngine.unlock(PdfJob(context, workDir, {}, { _, _ -> }), f, password, "unlocked")
                } else if (info.pages <= 0) throw PdfProblem("Couldn't read this PDF. It may be damaged or not a real PDF file.")
                Triple(file, name, PdfEditEngine.readSizes(file))
            } ?: return OpenResult.NeedPassword
            history = emptyList(); cursor = -1; savedMark = -1
            title = loaded.second
            sourceUri = uri
            push(EditorState(loaded.first, loaded.third, emptyList(), "Opened"))
            savedMark = 0
            OpenResult.Ok
        } catch (e: PdfProblem) {
            OpenResult.Failed(e.message ?: "Couldn't open this PDF.")
        } catch (e: Throwable) {
            OpenResult.Failed("Couldn't open this PDF.")
        }
    }

    // ---------- objects ----------

    fun add(o: EditObj, select: Boolean = true) {
        mutate("Add") { it + o }
        if (select) selectedId = o.id
    }

    fun update(o: EditObj) { mutate("Edit") { list -> list.map { if (it.id == o.id) o else it } } }

    fun remove(id: Long) {
        mutate("Delete") { list -> list.filter { it.id != id } }
        selectedId = null
    }

    fun duplicate(id: Long) {
        val o = current?.objects?.firstOrNull { it.id == id } ?: return
        add(o.copyTo(o.page, newId()).moved(14f, 14f))
    }

    /** Ends a drag or a drawn shape: the draft becomes a real undo step. */
    fun commitDraft() {
        val d = draft ?: return
        draft = null
        val exists = current?.objects?.any { it.id == d.id } == true
        if (exists) update(d) else add(d)
    }

    fun commitErase() {
        val ids = erasing
        erasing = emptySet()
        if (ids.isNotEmpty()) mutate("Erase") { list -> list.filter { it.id !in ids } }
    }

    fun objectsOn(page: Int): List<EditObj> {
        val st = current ?: return emptyList()
        val d = draft
        val base = st.objects.filter { it.page == page && it.id !in erasing }.map { if (d != null && d.id == it.id) d else it }
        return if (d != null && d.page == page && base.none { it.id == d.id }) base + d else base
    }

    // ---------- text layout & search ----------

    private suspend fun session(): LayoutSession? = layoutMutex.withLock {
        val st = current ?: return@withLock null
        val existing = layout
        if (existing != null && layoutFile == st.file) return@withLock existing
        existing?.close()
        val created = try { withContext(Dispatchers.IO) { LayoutSession(st.file) } } catch (e: Throwable) { null }
        layout = created
        layoutFile = if (created != null) st.file else null
        created
    }

    suspend fun layoutOf(page: Int): PageLayout? {
        val s = session() ?: return null
        return withContext(Dispatchers.IO) { try { s.page(page) } catch (e: Throwable) { null } }
    }

    suspend fun find(query: String, matchCase: Boolean): Int {
        hitIndex = 0
        if (query.isEmpty()) { hits = emptyList(); return 0 }
        val s = session() ?: run { hits = emptyList(); return 0 }
        hits = withContext(Dispatchers.IO) { try { PdfEditEngine.findAll(s, query, matchCase) } catch (e: Throwable) { emptyList() } }
        return hits.size
    }

    // ---------- editing existing text ----------

    suspend fun replaceReal(find: String, repl: String, matchCase: Boolean, all: Boolean, onlyPage: Int?): ReplaceReport {
        val st = current ?: return ReplaceReport(0, 0, null)
        return working("Changing text…") {
            val out = newFile("v")
            val rep = withContext(Dispatchers.IO) {
                try { PdfEditEngine.replaceText(st.file, out, find, repl, matchCase, all, onlyPage) }
                catch (e: PdfProblem) { ReplaceReport(0, 0, e.message) }
                catch (e: Throwable) { ReplaceReport(0, 0, "the PDF could not be edited") }
            }
            if (rep.done > 0) {
                val sizes = withContext(Dispatchers.IO) { PdfEditEngine.readSizes(out) }
                push(EditorState(out, sizes, st.objects, "Edit text"))
            } else out.delete()
            rep
        }
    }

    suspend fun replaceAll(query: String, repl: String, matchCase: Boolean): ReplaceOutcome {
        val before = find(query, matchCase)
        if (before == 0) return ReplaceOutcome(0, 0, null)
        val rep = replaceReal(query, repl, matchCase, true, null)
        find(query, matchCase)
        return ReplaceOutcome(rep.done, (before - rep.done).coerceAtLeast(0), rep.reason)
    }

    suspend fun replaceOne(query: String, repl: String, matchCase: Boolean): ReplaceOutcome {
        val h = hits.getOrNull(hitIndex) ?: return ReplaceOutcome(0, 0, null)
        val rep = replaceReal(query, repl, matchCase, false, h.page)
        find(query, matchCase)
        return ReplaceOutcome(rep.done, if (rep.done == 0) 1 else 0, rep.reason)
    }

    private fun overlayBox(src: PBox, text: String, size: Float): PBox =
        PBox(src.l, src.t - 1f, maxOf(src.r, src.l + text.length * size * 0.52f) + 4f, src.b + 1f)

    /** Colours next to each requested spot, so replacement text can be placed on a matching background. */
    private suspend fun backgrounds(spots: List<Triple<Int, Float, Float>>): List<Int> = withContext(Dispatchers.IO) {
        val st = current ?: return@withContext spots.map { Color.WHITE }
        val out = ArrayList<Int>()
        val pfd = ParcelFileDescriptor.open(st.file, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd)
        try {
            val shots = HashMap<Int, Bitmap?>()
            for ((p, x, y) in spots) {
                val bmp = shots.getOrPut(p) {
                    try {
                        val page = r.openPage(p)
                        try {
                            val b = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                            b.eraseColor(Color.WHITE)
                            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            b
                        } finally { page.close() }
                    } catch (e: Throwable) { null }
                }
                out += if (bmp == null) Color.WHITE else (bmp.getPixel(x.toInt().coerceIn(0, bmp.width - 1), y.toInt().coerceIn(0, bmp.height - 1)) or 0xFF000000.toInt())
            }
            shots.values.forEach { it?.recycle() }
        } finally { r.close(); pfd.close() }
        out
    }

    /**
     * Applies an edit of one line of text. First tries to change the PDF's own text; when the PDF's font
     * cannot do it (or the person restyled the text) the line is covered and re-typed as a movable text box.
     * Returns a sentence describing what happened.
     */
    suspend fun editLine(page: Int, line: TLine, newText: String, restyle: Boolean): String {
        val old = line.text
        if (newText == old) return "Nothing changed."
        if (!restyle) {
            var p = 0
            while (p < old.length && p < newText.length && old[p] == newText[p]) p++
            var s = 0
            while (s < old.length - p && s < newText.length - p && old[old.length - 1 - s] == newText[newText.length - 1 - s]) s++
            val oldMid = old.substring(p, old.length - s)
            val newMid = newText.substring(p, newText.length - s)
            for ((k1, k2) in listOf(12 to 12, 6 to 6, 3 to 3, 0 to 0)) {
                val before = old.substring(maxOf(0, p - k1), p)
                val after = old.substring(old.length - s, minOf(old.length, old.length - s + k2))
                val find = before + oldMid + after
                if (find.isEmpty()) continue
                val rep = replaceReal(find, before + newMid + after, true, false, page)
                if (rep.done > 0) return "Changed in the PDF's own text."
            }
        }
        val bg = backgrounds(listOf(Triple(page, line.box.l - 3f, (line.box.t + line.box.b) / 2f))).first()
        val o = TextObj(
            id = newId(), page = page, box = overlayBox(line.box, newText, line.size), text = newText,
            size = line.size, bold = line.bold, coverBox = line.box.inflate(1.5f), coverColor = bg
        )
        add(o)
        return if (restyle) "Placed as a text box you can move, resize and restyle."
        else "This PDF's font can't be changed directly, so the line was re-typed as a text box on top of the page."
    }

    /** Re-types every current search match as an overlay (for matches the PDF's font could not take). */
    suspend fun overlayHits(repl: String): Int {
        val list = hits.toList()
        if (list.isEmpty()) return 0
        val bgs = backgrounds(list.map { Triple(it.page, it.box.l - 3f, (it.box.t + it.box.b) / 2f) })
        val created = list.mapIndexed { i, h ->
            val end = (h.matchStart + h.matchLen).coerceAtMost(h.spanText.length)
            val txt = h.spanText.replaceRange(h.matchStart.coerceAtMost(end), end, repl)
            TextObj(newId(), h.page, overlayBox(h.box, txt, h.size), txt, size = h.size, bold = h.bold, coverBox = h.box.inflate(1.5f), coverColor = bgs[i])
        }
        mutate("Replace text") { it + created }
        hits = emptyList()
        return created.size
    }

    /** Places a picture on [page] centred at (cx, cy), or swaps the picture of [replaceId] keeping its position and width. */
    suspend fun addImage(uri: Uri, page: Int, cx: Float, cy: Float, replaceId: Long? = null) {
        val st = current ?: return
        try {
            val made = withContext(Dispatchers.IO) {
                val f = copyIn(uri, "pic", "jpg").first
                val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.path, b)
                if (b.outWidth <= 0) null else Triple(f, b.outWidth, b.outHeight)
            }
            if (made == null) { message = "Couldn't read that picture."; return }
            val (f, iw, ih) = made
            val old = if (replaceId != null) st.objects.firstOrNull { it.id == replaceId } as? ImageObj else null
            if (old != null) {
                val w = old.box.w
                val h = w * ih / iw.toFloat()
                update(old.copy(file = f, box = PBox(old.box.l, old.box.t, old.box.r, old.box.t + h)))
            } else {
                val ps = st.sizes[page.coerceIn(0, st.sizes.lastIndex)]
                val w = minOf(180f, ps.first * 0.5f)
                val h = w * ih / iw.toFloat()
                val l = (cx - w / 2f).coerceIn(0f, maxOf(0f, ps.first - w))
                val t = (cy - h / 2f).coerceIn(0f, maxOf(0f, ps.second - h))
                add(ImageObj(newId(), page, f, PBox(l, t, l + w, t + h)))
            }
        } catch (e: Throwable) { message = "Couldn't add that picture." }
    }

    // ---------- OCR ----------

    suspend fun runOcr(pages: List<Int>, devanagari: Boolean): Int {
        val st = current ?: return 0
        return working("Reading text on the page…") {
            val created = withContext(Dispatchers.IO) {
                val list = ArrayList<EditObj>()
                val pfd = ParcelFileDescriptor.open(st.file, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(pfd)
                try {
                    for ((n, p) in pages.withIndex()) {
                        progress = n / pages.size.toFloat()
                        val page = r.openPage(p)
                        try {
                            val scale = minOf(200f / 72f, 2600f / maxOf(page.width, page.height).toFloat())
                            val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            for (ln in PdfOcr.recognize(bmp, devanagari)) {
                                val rc = ln.rect
                                val box = PBox(rc.left / scale, rc.top / scale, rc.right / scale, rc.bottom / scale)
                                val bg = bmp.getPixel((rc.left + 2).coerceIn(0, bmp.width - 1), (rc.top + 2).coerceIn(0, bmp.height - 1)) or 0xFF000000.toInt()
                                list += TextObj(
                                    id = newId(), page = p, box = PBox(box.l, box.t, box.r + box.w * 0.1f, box.b), text = ln.text,
                                    size = (box.h * 0.8f).coerceIn(6f, 48f), coverBox = box.inflate(1f), coverColor = bg, ocrText = ln.text
                                )
                            }
                            bmp.recycle()
                        } finally { page.close() }
                    }
                } finally { r.close(); pfd.close() }
                list
            }
            if (created.isNotEmpty()) mutate("Text recognition") { it + created }
            created.size
        }
    }

    // ---------- pages ----------

    private fun remap(objs: List<EditObj>, mapping: List<Int>): List<EditObj> {
        val out = ArrayList<EditObj>()
        val seen = HashSet<Int>()
        mapping.forEachIndexed { newIdx, oldIdx ->
            if (oldIdx < 0) return@forEachIndexed
            val first = seen.add(oldIdx)
            for (o in objs) if (o.page == oldIdx) out += o.copyTo(newIdx, if (first) o.id else newId())
        }
        return out
    }

    private suspend fun restructure(label: String, parts: List<PagePart>, mapping: List<Int>, keepObjects: Boolean = true) {
        val st = current ?: return
        try {
            working(label) {
                val out = newFile("v")
                val sizes = withContext(Dispatchers.IO) {
                    PdfEditEngine.assemble(parts, out) { n -> progress = n / parts.size.toFloat() }
                    PdfEditEngine.readSizes(out)
                }
                push(EditorState(out, sizes, if (keepObjects) remap(st.objects, mapping) else emptyList(), label))
            }
        } catch (e: PdfProblem) {
            message = e.message
        } catch (e: Throwable) {
            message = "That change couldn't be made to this PDF."
        }
    }

    private fun identity(st: EditorState) = (0 until st.pageCount).toList()

    suspend fun addBlankAfter(index: Int) {
        val st = current ?: return
        val ref = st.sizes[index.coerceIn(0, st.sizes.lastIndex)]
        val parts = ArrayList<PagePart>()
        val map = ArrayList<Int>()
        for (i in 0 until st.pageCount) {
            parts += PagePart(st.file, i); map += i
            if (i == index) { parts += PagePart(null, -1, 0, ref.first, ref.second); map += -1 }
        }
        restructure("Add blank page", parts, map)
    }

    suspend fun deletePages(indices: Set<Int>) {
        val st = current ?: return
        if (indices.size >= st.pageCount) { message = "A PDF needs at least one page."; return }
        val keep = identity(st).filter { it !in indices }
        restructure("Delete page", keep.map { PagePart(st.file, it) }, keep)
    }

    suspend fun duplicatePage(index: Int) {
        val st = current ?: return
        val order = ArrayList<Int>()
        for (i in 0 until st.pageCount) { order += i; if (i == index) order += i }
        restructure("Duplicate page", order.map { PagePart(st.file, it) }, order)
    }

    suspend fun movePage(index: Int, delta: Int) {
        val st = current ?: return
        val to = index + delta
        if (to !in 0 until st.pageCount) return
        val order = identity(st).toMutableList()
        order.add(to, order.removeAt(index))
        restructure("Move page", order.map { PagePart(st.file, it) }, order)
    }

    private suspend fun bakedFile(st: EditorState): File = withContext(Dispatchers.IO) {
        if (st.objects.isEmpty()) st.file else newFile("baked").also { PdfEditEngine.bake(st.file, it, st.objects) }
    }

    /** Rotating writes the current marks into the page first, because their positions would no longer fit. */
    suspend fun rotatePage(index: Int, degrees: Int) {
        val st = current ?: return
        val base = try { working("Rotating…") { bakedFile(st) } } catch (e: Throwable) { message = "Couldn't rotate this page."; return }
        restructure("Rotate page", identity(st).map { PagePart(base, it, if (it == index) degrees else 0) }, identity(st), keepObjects = false)
    }

    private suspend fun insertFile(f: File, afterIndex: Int, label: String): Boolean {
        val st = current ?: return false
        val info = withContext(Dispatchers.IO) { PdfEngine.info(f) }
        if (info.encrypted) { message = "That PDF is password protected. Unlock it in PDF Tools first."; return false }
        if (info.pages <= 0) { message = "Couldn't read that PDF."; return false }
        val parts = ArrayList<PagePart>()
        val map = ArrayList<Int>()
        for (i in 0 until st.pageCount) {
            parts += PagePart(st.file, i); map += i
            if (i == afterIndex) for (j in 0 until info.pages) { parts += PagePart(f, j); map += -1 }
        }
        restructure(label, parts, map)
        return true
    }

    suspend fun insertPdf(uri: Uri, afterIndex: Int): Boolean {
        val f = try { withContext(Dispatchers.IO) { copyIn(uri, "ins").first } } catch (e: Throwable) { message = "Couldn't read that PDF."; return false }
        return insertFile(f, afterIndex, "Insert PDF")
    }

    suspend fun insertImages(uris: List<Uri>, afterIndex: Int): Boolean {
        return try {
            val pdf = working("Making pages from pictures…") {
                withContext(Dispatchers.IO) {
                    PdfEngine.init(context)
                    val files = uris.map { copyIn(it, "img", "jpg").first }
                    PdfEngine.imagesToPdf(PdfJob(context, workDir, {}, { _, _ -> }), files, ImagePageSize.A4, ImageOrientation.AUTO, ImageMargin.SMALL, "images-${fileCounter++}")
                }
            }
            insertFile(pdf, afterIndex, "Insert pictures")
        } catch (e: PdfProblem) { message = e.message; false }
        catch (e: Throwable) { message = "Couldn't add those pictures."; false }
    }

    suspend fun replacePage(index: Int, uri: Uri, sourcePage: Int): Boolean {
        val st = current ?: return false
        val f = try { withContext(Dispatchers.IO) { copyIn(uri, "rep").first } } catch (e: Throwable) { message = "Couldn't read that PDF."; return false }
        val info = withContext(Dispatchers.IO) { PdfEngine.info(f) }
        if (info.encrypted) { message = "That PDF is password protected. Unlock it in PDF Tools first."; return false }
        if (sourcePage !in 1..info.pages) { message = "That PDF has ${info.pages} pages."; return false }
        val parts = identity(st).map { if (it == index) PagePart(f, sourcePage - 1) else PagePart(st.file, it) }
        val map = identity(st).map { if (it == index) -1 else it }
        restructure("Replace page", parts, map)
        return true
    }

    /** Saves the chosen pages as a separate PDF (with the marks on them) and leaves this document as it is. */
    suspend fun extractPages(indices: List<Int>): SavedPdf? {
        val st = current ?: return null
        if (indices.isEmpty()) return null
        return try {
            working("Extracting pages…") {
                withContext(Dispatchers.IO) {
                    val base = bakedFile(st)
                    val out = uniqueFile(workDir, safeName(title.removeSuffix(".pdf") + " (extract).pdf"))
                    PdfEditEngine.assemble(indices.map { PagePart(base, it) }, out)
                    val uri = MediaSaver.save(context, out, false, "PDF Editor", true)
                    PdfStore.add(context, listOf(PdfStore.Entry(out.name, uri.toString(), "PDF Editor", System.currentTimeMillis(), out.length())))
                    SavedPdf(out.name, uri.toString(), out.length())
                }
            }
        } catch (e: Throwable) { message = "Couldn't extract those pages."; null }
    }

    // ---------- saving ----------

    private fun cleanName(name: String): String =
        safeName(name.trim().ifBlank { title }).removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "Document" } + ".pdf"

    /** Writes the document with all marks into the LinkVault folder as a NEW file. The original is untouched. */
    suspend fun saveCopy(name: String): SavedPdf? {
        val st = current ?: return null
        return try {
            val saved = working("Saving…") {
                withContext(Dispatchers.IO) {
                    PdfEngine.init(context)
                    val out = uniqueFile(workDir, cleanName(name))
                    PdfEditEngine.bake(st.file, out, st.objects)
                    val uri = MediaSaver.save(context, out, false, "PDF Editor", true)
                    PdfStore.add(context, listOf(PdfStore.Entry(out.name, uri.toString(), "PDF Editor", System.currentTimeMillis(), out.length())))
                    SavedPdf(out.name, uri.toString(), out.length())
                }
            }
            savedMark = cursor
            saved
        } catch (e: Throwable) { message = "Couldn't save the PDF. Check that there is free space on the phone."; null }
    }

    /** Save as: writes to the place the person picked with Android's file dialog. */
    suspend fun saveToUri(target: Uri): Boolean {
        val st = current ?: return false
        return try {
            val ok = working("Saving…") {
                withContext(Dispatchers.IO) {
                    PdfEngine.init(context)
                    val tmp = newFile("export")
                    PdfEditEngine.bake(st.file, tmp, st.objects)
                    FilePicking.copyUri(context, Uri.fromFile(tmp), target)
                }
            }
            if (ok) savedMark = cursor else message = "Couldn't write to that location."
            ok
        } catch (e: Throwable) { message = "Couldn't write to that location."; false }
    }

    /** Only when the person explicitly asks: overwrites the file this PDF was opened from, if Android allows it. */
    suspend fun replaceOriginal(): Boolean {
        val st = current ?: return false
        val src = sourceUri ?: return false
        return try {
            val ok = working("Replacing the original…") {
                withContext(Dispatchers.IO) {
                    PdfEngine.init(context)
                    val tmp = newFile("export")
                    PdfEditEngine.bake(st.file, tmp, st.objects)
                    val out = context.contentResolver.openOutputStream(src, "wt")
                    if (out == null) false else { tmp.inputStream().use { i -> out.use { o -> i.copyTo(o) } }; true }
                }
            }
            if (ok) savedMark = cursor
            ok
        } catch (e: Throwable) { false }
    }

    fun renameSaved(saved: SavedPdf, newName: String): SavedPdf? {
        val clean = cleanName(newName)
        if (!FilePicking.rename(context, Uri.parse(saved.uri), clean)) return null
        PdfStore.remove(context, saved.uri)
        PdfStore.add(context, listOf(PdfStore.Entry(clean, saved.uri, "PDF Editor", System.currentTimeMillis(), saved.size)))
        return SavedPdf(clean, saved.uri, saved.size)
    }

    fun close() {
        layout?.close()
        layout = null
        Thread { workDir.deleteRecursively() }.start()
    }
}
