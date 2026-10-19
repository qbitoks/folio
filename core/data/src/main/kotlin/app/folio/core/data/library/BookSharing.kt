package app.folio.core.data.library

import android.content.Context
import android.content.Intent
import android.system.Os
import androidx.core.content.FileProvider
import java.io.File

/**
 * «Поделиться» for book originals. Originals are stored as `<id>.<ext>`, so each one is
 * exposed under its title (`cache/share/<title>.<ext>`, a hard link — no copy) with the
 * proper MIME type, and the receiving app sees «Хулиномика.epub» instead of a hash.
 */
object BookSharing {

    /** Shares [books] (original file to title); does nothing if none of them exist. */
    fun share(context: Context, books: List<Pair<File, String>>) {
        val dir = File(context.cacheDir, "share")
        dir.deleteRecursively()
        dir.mkdirs()
        val used = HashSet<String>()
        val files = books.filter { it.first.exists() }.map { (original, title) ->
            val base = safeName(title).ifEmpty { original.nameWithoutExtension }
            var name = base
            var n = 2
            while (!used.add(name)) name = "$base (${n++})"
            exposed(original, File(dir, "$name.${original.extension}"))
        }
        if (files.isEmpty()) return
        val uris = files.map { FileProvider.getUriForFile(context, context.packageName + ".files", it) }
        val types = files.map { mimeType(it) }.distinct()
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.setType(types.singleOrNull() ?: "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null))
    }

    private fun exposed(original: File, target: File): File {
        runCatching { Os.link(original.path, target.path) }.onFailure { original.copyTo(target, overwrite = true) }
        return target
    }

    private fun safeName(title: String) =
        title.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("\\s+"), " ").trim().take(120)

    private fun mimeType(file: File) = when (file.extension.lowercase()) {
        "epub" -> "application/epub+zip"
        "fb2" -> "application/x-fictionbook+xml"
        "zip" -> "application/zip"
        "txt" -> "text/plain"
        "html" -> "text/html"
        "rtf" -> "application/rtf"
        "mobi" -> "application/x-mobipocket-ebook"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pdf" -> "application/pdf"
        "djvu" -> "image/vnd.djvu"
        "txt" -> "text/plain"
        "html" -> "text/html"
        "rtf" -> "application/rtf"
        "mobi" -> "application/x-mobipocket-ebook"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        else -> "application/octet-stream"
    }
}
