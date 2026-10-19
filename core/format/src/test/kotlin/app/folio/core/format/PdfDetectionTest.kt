package app.folio.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PdfDetectionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun detect(bytes: ByteArray) = BookParser.detect(tmp.newFile().apply { writeBytes(bytes) })

    @Test
    fun pdfByHeader() {
        val bytes = "%PDF-1.7\n%".toByteArray() + byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0, 1, 2) + "\n1 0 obj".toByteArray()
        assertEquals(BookFormat.Pdf, detect(bytes))
    }

    @Test
    fun pdfAfterLeadingJunk() {
        assertEquals(BookFormat.Pdf, detect(ByteArray(300) { 0 } + "%PDF-1.4\n".toByteArray()))
    }

    @Test
    fun djvuByMagic() {
        val bytes = "AT&TFORM".toByteArray() + byteArrayOf(0, 0, 0x10, 0) + "DJVMDIRM".toByteArray() + ByteArray(64)
        assertEquals(BookFormat.Djvu, detect(bytes))
    }

    @Test
    fun unzipsDjvuFromArchive() {
        val djvu = "AT&TFORM".toByteArray() + ByteArray(1000) { it.toByte() }
        val target = tmp.newFile()
        assertTrue(BookParser.unzipPdf(zip("book.djvu" to djvu), target))
        assertEquals(BookFormat.Djvu, BookParser.detect(target))
    }

    @Test
    fun textMentioningPdfStaysText() {
        assertEquals(BookFormat.Text, detect("Save it as %PDF later.\nSecond line.".toByteArray()))
    }

    @Test(expected = UnsupportedBookException::class)
    fun pdfIsNotReflowed() {
        BookParser.parse(tmp.newFile(), BookFormat.Pdf) { _, _ -> }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): File = tmp.newFile().apply {
        ZipOutputStream(outputStream()).use { out -> entries.forEach { (name, bytes) -> out.putNextEntry(ZipEntry(name)); out.write(bytes) } }
    }

    @Test
    fun zippedPdfIsUnpackedAsLitResServesIt() {
        val pdf = "%PDF-1.5\n1 0 obj\n".toByteArray() + ByteArray(1000) { it.toByte() }
        val archive = zip("141856431.a6.pdf" to pdf)
        assertNull(BookParser.detect(archive))
        val target = tmp.newFile()
        assertTrue(BookParser.unzipPdf(archive, target))
        assertTrue(pdf.contentEquals(target.readBytes()))
        assertEquals(BookFormat.Pdf, BookParser.detect(target))
    }

    @Test
    fun noPdfNoUnpacking() {
        assertFalse(BookParser.unzipPdf(zip("cover.jpg" to byteArrayOf(1, 2, 3)), tmp.newFile()))
        assertFalse(BookParser.unzipPdf(tmp.newFile().apply { writeBytes("plain text".toByteArray()) }, tmp.newFile()))
    }
}
