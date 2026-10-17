package app.folio.core.data.modules

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Folio ↔ Folio Документы (`documents/` in qbitoks/folio-modules, GPL-3.0, a separate app).
 * Folio starts it with [ACTION_OPEN], a read-only `content://` book and the extras below; the module reports
 * the page back through Folio's provider ([AUTHORITY], method [METHOD_POSITION]), which only apps signed
 * with Folio's key may call ([PERMISSION]). Names are stable API: the module has its own copy.
 */
object PdfModule {
    const val PACKAGE = "app.folio.documents"
    const val ACTION_OPEN = "app.folio.action.OPEN_PDF"
    const val EXTRA_BOOK_ID = "app.folio.extra.BOOK_ID"
    /** Zero-based page to open. */
    const val EXTRA_PAGE = "app.folio.extra.PAGE"
    const val EXTRA_TITLE = "app.folio.extra.TITLE"
    /** `auto`, `light` or `dark`: Folio's appearance setting. */
    const val EXTRA_APPEARANCE = "app.folio.extra.APPEARANCE"

    const val AUTHORITY = "app.folio.reading"
    /** `call(METHOD_POSITION, bookId, {page, pages})`: saves the reading position of a PDF. */
    const val METHOD_POSITION = "position"
    const val KEY_PAGE = "page"
    const val KEY_PAGES = "pages"
    const val PERMISSION = "app.folio.permission.READING"
}

/** Opens PDFs in Folio Документы when it is installed and signed with Folio's key. */
@Singleton
class PdfModuleLauncher @Inject constructor(@ApplicationContext private val context: Context) {
    /** Installed and signed like Folio: anything else under that package name never gets a book. */
    fun available(): Boolean = runCatching {
        context.packageManager.getPackageInfo(PdfModule.PACKAGE, 0)
        context.packageManager.checkSignatures(context.packageName, PdfModule.PACKAGE) == PackageManager.SIGNATURE_MATCH
    }.getOrDefault(false)

    fun intent(bookId: String, file: File, page: Int, title: String, appearance: String): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        return Intent(PdfModule.ACTION_OPEN)
            .setPackage(PdfModule.PACKAGE)
            .setDataAndType(uri, "application/pdf")
            .putExtra(PdfModule.EXTRA_BOOK_ID, bookId)
            .putExtra(PdfModule.EXTRA_PAGE, page)
            .putExtra(PdfModule.EXTRA_TITLE, title)
            .putExtra(PdfModule.EXTRA_APPEARANCE, appearance)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .also { it.clipData = ClipData.newRawUri(title, uri) }
    }
}
