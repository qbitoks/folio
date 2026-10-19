package app.folio.feature.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.folio.core.data.db.BookEntity
import app.folio.core.data.library.LibraryRepository
import app.folio.core.data.modules.InstallState
import app.folio.core.data.modules.ModuleApk
import app.folio.core.data.modules.ModuleInstaller
import app.folio.core.data.modules.PdfModule
import app.folio.core.data.modules.PdfModuleLauncher
import app.folio.core.data.settings.SettingsRepository
import app.folio.core.format.BookFormat
import app.folio.core.model.Position
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the reader route shows: the reflow reader, or the fixed-page PDF viewer. */
sealed interface ReaderKind {
    data object Loading : ReaderKind
    data object Reflow : ReaderKind
    data class Pdf(val title: String, val pageCount: Int, val startPage: Int) : ReaderKind
    /** A PDF or DjVu handed to the Folio Документы module: the route starts [intent] and closes. */
    data class External(val intent: Intent) : ReaderKind
    /** A DjVu without the module: Folio can't show it, the viewer offers the module. */
    data class NeedsModule(val title: String) : ReaderKind
    data object Broken : ReaderKind
}

/**
 * Fixed-page PDF reading through [PdfRenderer]. The position is stored as `Position(page)` in the
 * same column as reflowed books, so library progress and «Продолжить» work unchanged.
 */
@HiltViewModel
class PdfViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val module: PdfModuleLauncher,
    private val installer: ModuleInstaller,
) : ViewModel() {
    private val bookId: String = checkNotNull(savedState["bookId"])

    private val _kind = MutableStateFlow<ReaderKind>(ReaderKind.Loading)
    val kind: StateFlow<ReaderKind> = _kind

    /** [PdfRenderer] allows one open page at a time: every call goes through this one thread. */
    private val pdfThread = Dispatchers.IO.limitedParallelism(1)
    private var renderer: PdfRenderer? = null
    private var fd: ParcelFileDescriptor? = null
    private val bitmaps = LruCache<Pair<Int, Int>, Bitmap>(6)
    private var savedPage = -1

    init {
        viewModelScope.launch {
            val entity = library.book(bookId).first()
            if (entity?.format == BookFormat.Djvu.name) {
                if (module.available()) {
                    _kind.value = ReaderKind.External(moduleIntent(entity, Position.decode(entity.position)?.chapter ?: 0))
                    library.markOpened(bookId)
                } else {
                    _kind.value = ReaderKind.NeedsModule(entity.displayTitle)
                    refreshModules()
                }
                return@launch
            }
            if (entity?.format != BookFormat.Pdf.name) {
                _kind.value = ReaderKind.Reflow
                return@launch
            }
            if (settings.pdfInModule.first() && module.available()) {
                val appearance = settings.appAppearance.first().name.lowercase()
                val page = Position.decode(entity.position)?.chapter ?: 0
                val file = library.originalFile(bookId, BookFormat.Pdf)
                library.markOpened(bookId)
                _kind.value = ReaderKind.External(module.intent(bookId, file, page, entity.displayTitle, appearance))
                return@launch
            }
            _kind.value = runCatching {
                withContext(pdfThread) {
                    val file = library.originalFile(bookId, BookFormat.Pdf)
                    fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val pdf = PdfRenderer(fd!!).also { renderer = it }
                    val count = pdf.pageCount
                    val start = (Position.decode(entity.position)?.chapter ?: 0).coerceIn(0, (count - 1).coerceAtLeast(0))
                    ReaderKind.Pdf(entity.displayTitle, count, start)
                }
            }.getOrElse { ReaderKind.Broken }
            library.markOpened(bookId)
            refreshModules()
        }
    }

    private suspend fun refreshModules() {
        try {
            installer.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PdfViewModel", "Modules index not refreshed", e)
        }
    }

    /** The Folio Документы module, when published and not installed yet: the viewer offers it. */
    val offer: StateFlow<ModuleApk?> = combine(installer.viewers, installer.installed) { viewers, installed ->
        viewers.firstOrNull { it.packageName == PdfModule.PACKAGE && it.packageName !in installed }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val installStates: StateFlow<Map<String, InstallState>> = installer.states

    /** The module got installed while this viewer is open: it offers to reopen the book there. */
    val moduleReady: StateFlow<Boolean> = installer.installed
        .map { PdfModule.PACKAGE in it && module.available() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun installModule(apk: ModuleApk) = installer.install(apk)

    /** Opens the book in the module at [page]. */
    suspend fun moduleIntent(page: Int): Intent? {
        val entity = library.book(bookId).first() ?: return null
        return moduleIntent(entity, page)
    }

    private suspend fun moduleIntent(entity: BookEntity, page: Int): Intent {
        val appearance = settings.appAppearance.first().name.lowercase()
        val djvu = entity.format == BookFormat.Djvu.name
        val file = library.originalFile(bookId, if (djvu) BookFormat.Djvu else BookFormat.Pdf)
        return module.intent(bookId, file, page, entity.displayTitle, appearance, djvu)
    }

    /** Page [index] rendered [width] px wide on white; null if the document is closed. */
    suspend fun render(index: Int, width: Int): Bitmap? = withContext(pdfThread) {
        bitmaps.get(index to width)?.let { return@withContext it }
        val pdf = renderer ?: return@withContext null
        pdf.openPage(index).use { page ->
            val height = (width.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, width * 4)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }.also { bitmaps.put(index to width, it) }
    }

    fun onPage(index: Int, count: Int) {
        if (index == savedPage || count <= 0) return
        savedPage = index
        viewModelScope.launch(NonCancellable) {
            library.savePosition(bookId, Position(index), (index + 1f) / count)
        }
    }

    override fun onCleared() {
        // Close on the renderer's thread so a render in flight finishes first.
        val pdf = renderer
        val file = fd
        renderer = null
        @Suppress("OPT_IN_USAGE")
        kotlinx.coroutines.GlobalScope.launch(pdfThread) {
            runCatching { pdf?.close() }
            runCatching { file?.close() }
        }
    }
}
