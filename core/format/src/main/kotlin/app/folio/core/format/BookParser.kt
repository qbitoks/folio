package app.folio.core.format

import app.folio.core.format.epub.EpubParser
import app.folio.core.format.fb2.Fb2Parser
import app.folio.core.format.docx.DocxParser
import app.folio.core.format.html.HtmlBookParser
import app.folio.core.format.mobi.MobiParser
import app.folio.core.format.rtf.RtfParser
import app.folio.core.format.text.TextParser
import app.folio.core.model.Book
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Receives binary resources (images) extracted while parsing. */
fun interface ResourceSink {
    fun put(id: String, bytes: ByteArray)
}

enum class BookFormat(val extension: String) {
    Epub("epub"),
    Fb2("fb2"),
    Fb2Zip("fb2.zip"),
    Text("txt"),
    TextZip("txt.zip"),
    Html("html"),
    HtmlZip("html.zip"),
    Rtf("rtf"),
    RtfZip("rtf.zip"),
    Mobi("mobi"),
    Docx("docx"),
    /** Fixed pages: never parsed into [Book]; the reader shows it with its own page viewer. */
    Pdf("pdf"),
    /** Fixed page images: never parsed; only the Folio Документы module shows it. */
    Djvu("djvu"),
}

class UnsupportedBookException(message: String) : IOException(message)

object BookParser {

    fun parse(file: File, format: BookFormat, resources: ResourceSink): Book = when (format) {
        BookFormat.Epub -> EpubParser(file, resources).parse()
        BookFormat.Fb2 -> file.inputStream().use { Fb2Parser(resources).parse(it) }
        BookFormat.Fb2Zip -> ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { !it.isDirectory && it.name.endsWith(".fb2", ignoreCase = true) }
                ?: throw UnsupportedBookException("No .fb2 inside archive")
            zip.getInputStream(entry).use { Fb2Parser(resources).parse(it) }
        }
        BookFormat.Text -> TextParser.parse(read(file))
        BookFormat.TextZip -> TextParser.parse(zipped(file, ".txt").second)
        BookFormat.Html -> HtmlBookParser.parse(html(read(file)), resources)
        BookFormat.HtmlZip -> ZipFile(file).use { zip ->
            val (name, bytes) = zipped(zip, ".html", ".htm", ".xhtml")
            val dir = name.substringBeforeLast('/', "")
            HtmlBookParser.parse(html(bytes), resources, images = { path ->
                zip.getEntry(path)?.let { e -> zip.getInputStream(e).use(::readBounded) }
            }, dir = dir)
        }
        BookFormat.Rtf -> RtfParser(read(file)).parse()
        BookFormat.RtfZip -> RtfParser(zipped(file, ".rtf").second).parse()
        BookFormat.Mobi -> MobiParser(read(file), resources).parse()
        BookFormat.Docx -> DocxParser(file, resources).parse()
        BookFormat.Pdf -> throw UnsupportedBookException("PDF has fixed pages and is not reflowed")
        BookFormat.Djvu -> throw UnsupportedBookException("DjVu has fixed pages and is not reflowed")
    }

    /**
     * Copies the PDF or DjVu out of a single-book archive (LitRes serves `a6.pdf` zipped, Flibusta `djvu.zip`) into
     * [target], within the 50 MiB limit. False when [file] has neither inside; call it only where [detect] found nothing.
     */
    private val fixedExtensions = listOf(".pdf", ".djvu", ".djv")

    fun unzipPdf(file: File, target: File): Boolean {
        val zip = try { ZipFile(file) } catch (e: IOException) { return false }
        return zip.use {
            val entry = zip.entries().asSequence().firstOrNull { !it.isDirectory && fixedExtensions.any { ext -> it.name.endsWith(ext, ignoreCase = true) } }
                ?: return false
            zip.getInputStream(entry).use { src ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val n = src.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_UNPACKED_BYTES) throw UnsupportedBookException("Book is larger than 50 MiB")
                        out.write(buffer, 0, n)
                    }
                }
            }
            true
        }
    }

    private fun read(file: File): ByteArray = file.inputStream().use(::readBounded)

    private fun zipped(file: File, extension: String): Pair<String, ByteArray> = ZipFile(file).use { zipped(it, extension) }

    /** The first entry with one of [extensions] in a single-book archive, unpacked within the size limit. */
    private fun zipped(zip: ZipFile, vararg extensions: String): Pair<String, ByteArray> {
        val entry = zip.entries().asSequence().firstOrNull { e -> !e.isDirectory && extensions.any { e.name.endsWith(it, ignoreCase = true) } }
            ?: throw UnsupportedBookException("No ${extensions.first()} inside archive")
        return entry.name to zip.getInputStream(entry).use(::readBounded)
    }

    /** Decodes HTML by its declared charset, else like a plain text file. */
    private fun html(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val declared = htmlCharset.find(head)?.groupValues?.get(1)
        val charset = declared?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() }
        return if (charset != null) String(bytes, charset).trimStart(0xFEFF.toChar()) else TextParser.decode(bytes)
    }

    private val pdfHeader = Regex("""%PDF-\d\.\d""")

    private val htmlCharset = Regex("""charset\s*=\s*["']?([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)

    /**
     * Detects the format by content rather than by name: files shared from messengers
     * and browsers often arrive without a meaningful extension.
     */
    fun detect(file: File): BookFormat? {
        val head = ByteArray(64 * 1024)
        val read = file.inputStream().use { it.readNBytes(head, 0, head.size) }
        if (read <= 0) return null
        if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            return try {
                ZipFile(file).use { zip ->
                    when {
                        zip.getEntry("META-INF/container.xml") != null -> BookFormat.Epub
                        zip.getEntry("word/document.xml") != null -> BookFormat.Docx
                        else -> {
                            // Single-book archives, as catalogs serve them: fb2.zip, txt.zip, html.zip, rtf.zip.
                            val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name.lowercase() }.toList()
                            fun has(vararg ext: String) = names.any { n -> ext.any { n.endsWith(it) } }
                            when {
                                has(".fb2") -> BookFormat.Fb2Zip
                                has(".html", ".htm", ".xhtml") -> BookFormat.HtmlZip
                                has(".rtf") -> BookFormat.RtfZip
                                has(".txt") -> BookFormat.TextZip
                                else -> null
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                null
            }
        }
        if (MobiParser.matches(head, read)) return BookFormat.Mobi
        // DjVu: «AT&T» then an IFF «FORM» chunk.
        if (read >= 8 && String(head, 0, 8, Charsets.ISO_8859_1) == "AT&TFORM") return BookFormat.Djvu
        // Readers accept «%PDF-» anywhere in the first KiB (some generators prepend junk).
        if (pdfHeader.containsMatchIn(String(head, 0, minOf(read, 1024), Charsets.ISO_8859_1))) return BookFormat.Pdf
        val text = String(head, 0, minOf(read, 512), Charsets.ISO_8859_1)
        // A UTF-8 BOM reads as «ï»¿» in Latin-1.
        val start = text.trimStart('ï', '»', '¿', ' ', '\t', '\r', '\n').lowercase()
        return when {
            text.contains("<FictionBook") -> BookFormat.Fb2
            start.startsWith("{\\rtf") -> BookFormat.Rtf
            start.startsWith("<!doctype html") || start.startsWith("<html") ||
                (start.startsWith("<?xml") && start.contains("<html")) -> BookFormat.Html
            // Anything else that reads as text opens like a plain-text editor would.
            TextParser.looksLikeText(head, read) -> BookFormat.Text
            else -> null
        }
    }
}
