package app.folio.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.folio.core.layout.ReaderStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Stable saved identifiers; Bold is a legacy alias of Original. */
enum class ReaderTheme { Original, Quiet, Paper, Bold, Calm, Focus }

/** «Как на устройстве / Светлое / Тёмное». */
enum class Appearance { Auto, Light, Dark }

/** «Скольжение / Загиб / Быстрое выцветание / Прокрутка». */
enum class PageTurn { Slide, Curl, Fade, Scroll }

/** «Затемнение фона» of the guide line: how far the text off the line fades into the page. */
enum class GuideDim(val fade: Float) { High(0.85f), Medium(0.75f), Low(0.65f), None(0f) }

/** «Сверху страницы»: what the line above the text shows; [None] gives the text the whole height. */
enum class PageHeader { None, Book, Chapter }

/** Page part of the bottom line: «42», «42 из 586» or the book's percent. */
enum class FooterPage { None, Number, NumberOfTotal, Percent }

/**
 * Persisted scrolling boundary. None is retained for compatibility and resolves to a clean clip.
 */
enum class FooterStyle { None, Clip, Fade }

/** The line under the text: time on the left, the page in the middle, battery on the right. */
data class PageFooter(
    val page: FooterPage = FooterPage.Number,
    /** «ещё 3 стр.» after the page. */
    val pagesLeft: Boolean = false,
    val clock: Boolean = false,
    val battery: Boolean = false,
    val style: FooterStyle = FooterStyle.Clip,
) {
    /** Nothing to show: the text runs to the bottom of the screen. */
    val isEmpty: Boolean get() = page == FooterPage.None && !pagesLeft && !clock && !battery

    /** Time and battery without «ещё N стр.», nothing under scrolling text: what the line shows without «Свои параметры». */
    val hasDefaultContent: Boolean get() = PageFooter(page = page) == this
}

enum class LibraryLayout { Grid, List }

enum class LibrarySort { Recent, Title, Author, Manual }

/** Built-in collections of the «Коллекции» sheet; the user can hide them in edit mode. */
enum class SystemCollection { WantToRead, Finished, Books }

data class ReaderSettings(
    /** Shared by all themes, changed with the A │ A buttons. */
    val fontSize: Float = ReaderStyle().fontSize,
    val theme: ReaderTheme = ReaderTheme.Original,
    /** Optional tone/contrast adjustments to [theme], independent of typography. */
    val customColors: CustomColors? = null,
    /** Current typography, independent of the palette. */
    val readingText: TextSet = TextSet.DEFAULT,
    val pageTurn: PageTurn = PageTurn.Slide,
    /** «Направляющая линия» (guide line) is on. */
    val guideLine: Boolean = false,
    val guideDim: GuideDim = GuideDim.Low,
    /** Lines lit around the guided one and how they fade. */
    val guideWindow: GuideWindow = GuideWindow(),
    /** Colour of the last highlight; new highlights use it, as in Apple Books. */
    val highlightColor: String = "yellow",
    val header: PageHeader = PageHeader.Chapter,
    val footer: PageFooter = PageFooter(),
    /** Visual presets of the lines. */
    val chromeStyles: ChromeStyles = ChromeStyles(),
    /** Light/Dark of the reading page; Auto follows Android. */
    val appearance: Appearance = Appearance.Auto,
    val headerLayout: LineLayout = LineLayout.HEADER,
    val footerLayout: LineLayout = LineLayout.FOOTER,
    /** Persisted top scrolling boundary; the bottom line has its own choice. */
    val headerUnder: FooterStyle = FooterStyle.Clip,
) {
    val textSet: TextSet get() = readingText
    val style: ReaderStyle get() = textSet.toReaderStyle(fontSize)

    fun withText(value: TextSet): ReaderSettings = if (value == textSet) this else copy(readingText = value)

    fun withColors(value: CustomColors): ReaderSettings = copy(theme = CustomColors.of(value.base).base,
        customColors = value.takeUnless { it.warmth == 0 && it.contrast == 0 })

    /** A palette changes ink only; text and tone/contrast remain unchanged. */
    fun withTheme(value: ReaderTheme): ReaderSettings {
        val base = CustomColors.of(value).base
        return copy(theme = base, readingText = textSet, customColors = customColors?.copy(base = base))
    }

    /** Reset only the Advanced tab: typography, tone/contrast, page chrome and the guide line. */
    fun withAdvancedDefaults(): ReaderSettings = withLinesOf(ReaderSettings()).copy(
        readingText = TextSet.DEFAULT, customColors = null,
        guideLine = false, guideDim = GuideDim.Low, guideWindow = GuideWindow(),
    )

    /** Both lines as in [source], «Свои параметры» included: what «Сбросить настройки страницы» restores. */
    fun withLinesOf(source: ReaderSettings): ReaderSettings = copy(
        header = source.header, footer = source.footer, chromeStyles = source.chromeStyles,
        headerLayout = source.headerLayout, footerLayout = source.footerLayout, headerUnder = source.headerUnder,
    )

    /** Line parameters as drawn: the stored ones only while «Свои параметры» is on. */
    val shownHeaderLayout: LineLayout get() = if (headerLayout.customize) headerLayout else LineLayout.HEADER
    val shownFooterLayout: LineLayout get() = if (footerLayout.customize) footerLayout else LineLayout.FOOTER
    val shownHeaderUnder: FooterStyle get() = if (headerLayout.customize) headerUnder else FooterStyle.Clip

    val scrollHeaderBoundary: FooterStyle get() = if (shownHeaderUnder == FooterStyle.Fade) FooterStyle.Fade else FooterStyle.Clip
    val scrollFooterBoundary: FooterStyle get() = if (shownFooter.style == FooterStyle.Fade) FooterStyle.Fade else FooterStyle.Clip

    /**
     * «Нет» drops only the page number. Time, battery and pages left are «Свои параметры»; with it off
     * the standard parts stay, so the line hides only when everything in it is switched off.
     */
    val shownFooter: PageFooter get() = if (footerLayout.customize) footer else PageFooter(page = footer.page)
}

data class LibrarySettings(
    val layout: LibraryLayout = LibraryLayout.Grid,
    val sort: LibrarySort = LibrarySort.Recent,
    val watchedFolder: String? = null,
    val hiddenSystemCollections: Set<SystemCollection> = emptySet(),
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.dataStore

    private object Keys {
        // "appearance" held the reader-wide appearance before the app-wide one; left unread (no migration, user decision).
        val appAppearance = stringPreferencesKey("app_appearance")
        val libraryLayout = stringPreferencesKey("library_layout")
        val librarySort = stringPreferencesKey("library_sort")
        val watchedFolder = stringPreferencesKey("watched_folder")
        val hiddenSystem = stringSetPreferencesKey("hidden_system_collections")
        val volumeKeys = booleanPreferencesKey("volume_keys_turn")
        val pdfInModule = booleanPreferencesKey("pdf_in_module")
    }

    /** Settings shared by all books. */
    val reader: Flow<ReaderSettings> = store.data.map(ReaderPreferences::read).distinctUntilChanged()

    /** What a book changes over [reader]. */
    fun bookOverrides(bookId: String): Flow<BookOverrides> = store.data
        .map { ReaderPreferences.readBook(it, bookId) }
        .distinctUntilChanged()

    val library: Flow<LibrarySettings> = store.data.map { p ->
        LibrarySettings(
            layout = enumOr(p[Keys.libraryLayout], LibraryLayout.Grid),
            sort = enumOr(p[Keys.librarySort], LibrarySort.Recent),
            watchedFolder = p[Keys.watchedFolder],
            hiddenSystemCollections = p[Keys.hiddenSystem].orEmpty()
                .mapNotNull { runCatching { SystemCollection.valueOf(it) }.getOrNull() }.toSet(),
        )
    }.distinctUntilChanged()

    /** Appearance of the whole app; Auto follows the system. */
    val appAppearance: Flow<Appearance> = store.data.map { enumOr(it[Keys.appAppearance], Appearance.Auto) }.distinctUntilChanged()

    /** Volume buttons turn pages in the reader (shared by all books, not a book's own setting). */
    val volumeKeys: Flow<Boolean> = store.data.map { it[Keys.volumeKeys] ?: true }.distinctUntilChanged()

    suspend fun setVolumeKeys(value: Boolean) {
        store.edit { it[Keys.volumeKeys] = value }
    }

    /** PDFs open in the Folio Документы module when it is installed; off keeps the built-in viewer. */
    val pdfInModule: Flow<Boolean> = store.data.map { it[Keys.pdfInModule] ?: true }.distinctUntilChanged()

    suspend fun setPdfInModule(value: Boolean) {
        store.edit { it[Keys.pdfInModule] = value }
    }

    suspend fun currentReader(): ReaderSettings = reader.first()

    suspend fun updateReader(change: (ReaderSettings) -> ReaderSettings) {
        store.edit { ReaderPreferences.write(it, change(ReaderPreferences.read(it))) }
    }

    /** Edits the book as it looks now; whatever ends up equal to the shared value stops being its own. */
    suspend fun updateBookReader(bookId: String, change: (ReaderSettings) -> ReaderSettings) {
        store.edit {
            val shared = ReaderPreferences.read(it)
            val edited = change(ReaderPreferences.readBook(it, bookId).applyTo(shared))
            ReaderPreferences.writeBook(it, bookId, BookOverrides.between(shared, edited))
        }
    }

    /** «Сделать общими для всех книг»: the book's look becomes the shared one. */
    suspend fun shareBookReader(bookId: String) {
        store.edit {
            ReaderPreferences.write(it, ReaderPreferences.readBook(it, bookId).applyTo(ReaderPreferences.read(it)))
            ReaderPreferences.writeBook(it, bookId, BookOverrides())
        }
    }

    /** «Вернуть общие настройки», and cleanup when the book is deleted. */
    suspend fun resetBookReader(bookId: String) {
        store.edit { ReaderPreferences.writeBook(it, bookId, BookOverrides()) }
    }

    suspend fun setAppAppearance(value: Appearance) {
        store.edit { it[Keys.appAppearance] = value.name }
    }

    suspend fun setHighlightColor(value: String) = updateReader { it.copy(highlightColor = value) }

    suspend fun setLibraryLayout(value: LibraryLayout) {
        store.edit { it[Keys.libraryLayout] = value.name }
    }

    suspend fun setLibrarySort(value: LibrarySort) {
        store.edit { it[Keys.librarySort] = value.name }
    }

    suspend fun setWatchedFolder(uri: String?) {
        store.edit { if (uri == null) it.remove(Keys.watchedFolder) else it[Keys.watchedFolder] = uri }
    }

    suspend fun setSystemCollectionHidden(collection: SystemCollection, hidden: Boolean) {
        store.edit {
            val current = it[Keys.hiddenSystem].orEmpty()
            it[Keys.hiddenSystem] = if (hidden) current + collection.name else current - collection.name
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
}
