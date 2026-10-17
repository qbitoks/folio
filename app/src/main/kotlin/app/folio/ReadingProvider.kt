package app.folio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import app.folio.core.data.library.LibraryRepository
import app.folio.core.data.modules.PdfModule
import app.folio.core.format.BookFormat
import app.folio.core.model.Position
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Takes reading positions from Folio Документы ([PdfModule]). `call` skips the manifest's permission check,
 * so it checks [PdfModule.PERMISSION] (signature level: only apps signed with Folio's key) itself.
 */
class ReadingProvider : ContentProvider() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun library(): LibraryRepository
    }

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        if (context.checkCallingPermission(PdfModule.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Requires ${PdfModule.PERMISSION}")
        }
        if (method != PdfModule.METHOD_POSITION || arg == null || extras == null) return null
        val page = extras.getInt(PdfModule.KEY_PAGE, -1)
        val pages = extras.getInt(PdfModule.KEY_PAGES, 0)
        if (page !in 0 until pages) return null
        val library = EntryPointAccessors.fromApplication(context, Deps::class.java).library()
        // A binder thread, never the main one: blocking here is fine.
        runBlocking {
            val book = library.book(arg).first() ?: return@runBlocking
            if (book.format != BookFormat.Pdf.name) return@runBlocking
            library.savePosition(arg, Position(page), (page + 1f) / pages)
        }
        return Bundle.EMPTY
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
