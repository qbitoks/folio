package app.folio.core.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import app.folio.core.data.db.BookEntity
import app.folio.core.data.db.BookmarkEntity
import app.folio.core.data.db.HighlightEntity
import app.folio.core.data.db.CollectionBookEntity
import app.folio.core.data.db.CollectionEntity
import app.folio.core.data.db.ReadingSessionEntity
import app.folio.core.data.db.FolioDatabase
import app.folio.core.data.db.SearchHistoryEntity
import androidx.room.withTransaction
import app.folio.core.format.BookFormat
import app.folio.core.format.BookParser
import app.folio.core.model.Book
import app.folio.core.model.BookCodec
import app.folio.core.model.Position
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ImportResult {
    val bookId: String

    data class Added(override val bookId: String) : ImportResult
    data class AlreadyInLibrary(override val bookId: String) : ImportResult
}

class ImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

@Singleton
class LibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FolioDatabase,
    private val files: BookFiles,
) {
    private val books = db.books()
    private val importLock = Mutex()

    /** The most recently loaded book stays parsed in memory so closing and reopening is instant. */
    @Volatile private var lastLoaded: Pair<String, Book>? = null

    val allBooks: Flow<List<BookEntity>> = books.observeAll()
    val collections: Flow<List<CollectionEntity>> = db.collections().observeAll()

    fun book(id: String): Flow<BookEntity?> = books.observe(id)
    fun collectionBookIds(id: Long): Flow<List<String>> = db.collections().observeBookIds(id)
    fun collectionsOf(bookId: String): Flow<List<Long>> = db.collections().observeCollectionsOf(bookId)
    fun bookmarks(bookId: String): Flow<List<BookmarkEntity>> = db.bookmarks().observe(bookId)

    /** [title] and [author] (a catalog's) name a book whose file says nothing about itself, such as a PDF. */
    suspend fun import(uri: Uri, sourceUri: String? = null, title: String? = null, author: String? = null): ImportResult =
        withContext(Dispatchers.IO) { importLock.withLock { importLocked(uri, sourceUri, Known(title, author)) } }

    private class Known(val title: String?, val author: String?)

    private suspend fun importLocked(uri: Uri, sourceUri: String?, known: Known): ImportResult {
        val tmp = File.createTempFile("import", ".tmp", files.importDir)
        try {
            val digest = MessageDigest.getInstance("SHA-1")
            val input = context.contentResolver.openInputStream(uri) ?: throw ImportException("Cannot open $uri")
            DigestInputStream(input, digest).use { src ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val n = src.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BOOK_BYTES) throw ImportException("Book is larger than 50 MiB: ${displayName(uri)}")
                        out.write(buffer, 0, n)
                    }
                }
            }
            var id = digest.digest().joinToString("") { "%02x".format(it) }
            var format = BookParser.detect(tmp)
            if (format == null && unzipPdf(tmp, uri)) {
                // The book is the PDF, not its archive: the same PDF imported unzipped must dedupe with it.
                id = sha1(tmp)
                format = BookParser.detect(tmp)
            }

            books.get(id)?.let {
                if (sourceUri != null) books.attachSource(id, sourceUri)
                return ImportResult.AlreadyInLibrary(id)
            }

            if (format == null) throw ImportException("Unsupported file: ${displayName(uri)}")
            val original = files.original(id, format.extension)
            if (!tmp.renameTo(original)) {
                tmp.copyTo(original, overwrite = true)
            }
            if (format == BookFormat.Pdf || format == BookFormat.Djvu) return importFixed(id, format, original, uri, sourceUri, known)
            val book = try {
                parseAndCache(id, original, format)
            } catch (e: Exception) {
                files.delete(id)
                throw ImportException("Cannot read ${displayName(uri)}", e)
            }
            val hasCover = book.metadata.coverResource?.let { writeCover(id, files.resources(id).file(it)) } ?: false
            val colors = if (hasCover) coverColors(files.cover(id)) else null
            books.insert(
                BookEntity(
                    id = id,
                    title = book.metadata.title.ifBlank {
                        // Text files keep their extension: «nginx.conf» says more than «nginx».
                        val name = displayName(uri)
                        (if (format == BookFormat.Text) name else name?.substringBeforeLast('.')) ?: "Book"
                    },
                    authors = book.metadata.authors.joinToString("\n"),
                    series = book.metadata.series,
                    seriesIndex = book.metadata.seriesIndex,
                    language = book.metadata.language,
                    format = format.name,
                    fileSize = original.length(),
                    sourceUri = sourceUri,
                    hasCover = hasCover,
                    addedAt = System.currentTimeMillis(),
                    coverColors = colors,
                    manualOrder = -(System.currentTimeMillis() / 1000).toInt(),
                ),
            )
            lastLoaded = id to book
            return ImportResult.Added(id)
        } finally {
            tmp.delete()
        }
    }

    /**
     * PDF and DjVu keep their fixed pages: no parsed cache. A PDF's first page becomes the cover; Folio can't render
     * DjVu (only the Folio Документы module does), so it gets the generated cover.
     */
    private suspend fun importFixed(id: String, format: BookFormat, original: File, uri: Uri, sourceUri: String?, known: Known): ImportResult {
        val hasCover = if (format != BookFormat.Pdf) false else try {
            PdfCover.render(original, COVER_WIDTH)?.let { bitmap ->
                files.cover(id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                true
            } ?: false
        } catch (e: Exception) {
            // Encrypted or broken: PdfRenderer can't open it, so the viewer couldn't either.
            files.delete(id)
            throw ImportException("Cannot read ${displayName(uri)}", e)
        }
        books.insert(
            BookEntity(
                id = id,
                title = known.title?.takeIf { it.isNotBlank() } ?: displayName(uri)?.substringBeforeLast('.') ?: format.name.uppercase(),
                authors = known.author.orEmpty(),
                series = null,
                seriesIndex = null,
                language = null,
                format = format.name,
                fileSize = original.length(),
                sourceUri = sourceUri,
                hasCover = hasCover,
                addedAt = System.currentTimeMillis(),
                coverColors = if (hasCover) coverColors(files.cover(id)) else null,
                manualOrder = -(System.currentTimeMillis() / 1000).toInt(),
            ),
        )
        return ImportResult.Added(id)
    }

    /** The original file of a book stored as [format]. */
    fun originalFile(id: String, format: BookFormat): File = files.original(id, format.extension)

    /** Loads the parsed book, from memory, the binary cache, or by re-parsing the original. */
    suspend fun load(id: String): Book = withContext(Dispatchers.IO) {
        lastLoaded?.let { (cachedId, book) -> if (cachedId == id) return@withContext book }
        val cache = files.parsedCache(id)
        val book = runCatching { cache.inputStream().use { BookCodec.read(it) } }.getOrNull() ?: run {
            val entity = books.get(id) ?: throw ImportException("Unknown book $id")
            val format = BookFormat.valueOf(entity.format)
            parseAndCache(id, files.original(id, format.extension), format)
        }
        lastLoaded = id to book
        book
    }

    fun resources(id: String): BookResources = files.resources(id)

    fun coverFile(id: String): File = files.cover(id)

    private fun parseAndCache(id: String, original: File, format: BookFormat): Book {
        val resources = files.resources(id)
        val book = BookParser.parse(original, format, resources)
        val cache = files.parsedCache(id)
        cache.parentFile?.mkdirs()
        val tmp = File(cache.parentFile, "book.bin.tmp")
        tmp.outputStream().use { BookCodec.write(book, it) }
        tmp.renameTo(cache)
        return book
    }

    private fun writeCover(id: String, source: File): Boolean {
        if (!source.exists()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0) return false
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= COVER_WIDTH) sample *= 2
        val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val scaled = if (bitmap.width > COVER_WIDTH) {
            Bitmap.createScaledBitmap(bitmap, COVER_WIDTH, bitmap.height * COVER_WIDTH / bitmap.width, true)
        } else {
            bitmap
        }
        files.cover(id).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return true
    }

    /**
     * Two colours for the «Продолжить» card gradient: the average of the top and bottom
     * halves of the cover, darkened so white text stays readable. Stored as "argb,argb".
     */
    private fun coverColors(cover: File): String? {
        val bitmap = BitmapFactory.decodeFile(cover.path, BitmapFactory.Options().apply { inSampleSize = 8 }) ?: return null
        val small = Bitmap.createScaledBitmap(bitmap, 12, 18, true)
        fun average(y0: Int, y1: Int): Int {
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (y in y0 until y1) for (x in 0 until small.width) {
                val p = small.getPixel(x, y)
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
            }
            val k = 0.62 // darken
            return (0xFF shl 24) or ((r / n * k).toInt() shl 16) or ((g / n * k).toInt() shl 8) or (b / n * k).toInt()
        }
        return "${average(0, 9)},${average(9, 18)}"
    }

    /** Fills [BookEntity.coverColors] for books imported before the column existed. */
    suspend fun backfillCoverColors() = withContext(Dispatchers.IO) {
        for (id in books.idsMissingCoverColors()) {
            coverColors(files.cover(id))?.let { books.setCoverColors(id, it) }
        }
    }

    /** Replaces an archive in [file] with the PDF inside it; false when there is none. */
    private fun unzipPdf(file: File, uri: Uri): Boolean {
        val pdf = File.createTempFile("import", ".pdf", files.importDir)
        try {
            if (!BookParser.unzipPdf(file, pdf)) return false
            Files.move(pdf.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return true
        } catch (e: IOException) {
            throw ImportException("Cannot read ${displayName(uri)}", e)
        } finally {
            pdf.delete()
        }
    }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { src ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = src.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    suspend fun knownSourceUris(): Set<String> = books.sourceUris().toHashSet()

    suspend fun contains(id: String): Boolean = books.get(id) != null

    suspend fun savePosition(id: String, position: Position, progress: Float) {
        books.updatePosition(id, position.encode(), progress, System.currentTimeMillis())
    }

    suspend fun markOpened(id: String) = books.touch(id, System.currentTimeMillis())

    suspend fun setFinished(id: String, finished: Boolean) =
        books.setFinished(id, if (finished) System.currentTimeMillis() else null)

    suspend fun setWantToRead(id: String, value: Boolean) = books.setWantToRead(id, value)

    /** Blank title resets to the one from the book file. */
    suspend fun rename(id: String, title: String) = books.rename(id, title.trim().takeIf { it.isNotEmpty() })

    suspend fun setHiddenFromContinue(id: String, hidden: Boolean) = books.setHiddenFromContinue(id, hidden)

    /** Persists a manual library order (ids first to last). */
    suspend fun reorderBooks(ids: List<String>) = db.withTransaction { ids.forEachIndexed { i, id -> books.setManualOrder(id, i) } }

    suspend fun reorderCollections(ids: List<Long>) = db.withTransaction { ids.forEachIndexed { i, id -> db.collections().setSortOrder(id, i) } }

    val collectionCounts: Flow<Map<Long, Int>> = db.collections().observeCounts().map { list -> list.associate { it.id to it.count } }

    val searchHistory: Flow<List<String>> = db.searchHistory().observe().map { list -> list.map { it.query } }

    suspend fun rememberSearch(query: String) {
        if (query.isNotBlank()) db.searchHistory().upsert(SearchHistoryEntity(query.trim(), System.currentTimeMillis()))
    }

    suspend fun clearSearchHistory() = db.searchHistory().clear()

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        books.delete(id)
        files.delete(id)
        if (lastLoaded?.first == id) lastLoaded = null
    }

    suspend fun createCollection(name: String): Long =
        db.collections().insert(CollectionEntity(name = name.trim(), createdAt = System.currentTimeMillis()))

    suspend fun renameCollection(id: Long, name: String) = db.collections().rename(id, name.trim())

    suspend fun deleteCollection(id: Long) = db.collections().delete(id)

    suspend fun addToCollection(collectionId: Long, bookId: String) =
        db.collections().add(CollectionBookEntity(collectionId, bookId, System.currentTimeMillis()))

    suspend fun removeFromCollection(collectionId: Long, bookId: String) = db.collections().remove(collectionId, bookId)

    suspend fun addBookmark(bookmark: BookmarkEntity) = db.bookmarks().insert(bookmark)

    suspend fun deleteBookmark(id: Long) = db.bookmarks().delete(id)

    fun highlights(bookId: String): Flow<List<HighlightEntity>> = db.highlights().observe(bookId)

    suspend fun addHighlight(highlight: HighlightEntity): Long = db.highlights().insert(highlight)

    suspend fun setHighlightNote(id: Long, note: String?) = db.highlights().setNote(id, note)

    suspend fun setHighlightColor(id: Long, color: String) = db.highlights().setColor(id, color)

    suspend fun deleteHighlight(id: Long) = db.highlights().delete(id)

    suspend fun recordSession(bookId: String, startedAt: Long, durationMs: Long) {
        if (durationMs >= 5_000) db.sessions().insert(ReadingSessionEntity(bookId = bookId, startedAt = startedAt, durationMs = durationMs))
    }

    companion object {
        private const val COVER_WIDTH = 600
        /** Project limit for an imported or downloaded book file. */
        const val MAX_BOOK_BYTES = 50L * 1024 * 1024
    }
}
