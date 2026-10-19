package app.folio.core.opds

/** A titled link to another feed: «Все книги автора …», «Книги автора по сериям». */
data class CatalogLink(val title: String, val href: String)

data class CatalogEntry(
    val id: String,
    val title: String,
    val author: String,
    /** Plain text; paragraphs separated by newlines, the «Год издания / Формат / Размер» tail removed. */
    val summary: String,
    /** Readable formats («FB2», «EPUB», «MOBI», «PDF»…) → acquisition link, best format first. */
    val downloads: Map<String, String>,
    /** Feed to open for a folder entry; null for a book. */
    val navigation: String?,
    /** Cover (books) or photo (authors), thumbnail preferred. */
    val cover: String? = null,
    val related: List<CatalogLink> = emptyList(),
    val genres: List<String> = emptyList(),
    val language: String? = null,
    val year: String? = null,
    /** File size the catalog reports, in bytes. */
    val size: Long? = null,
    /** Offered formats this app can't open («DJVU», «DOC»). */
    val otherFormats: List<String> = emptyList(),
    /** The book's web page, for catalogs that only lend or sell (Open Library). */
    val web: String? = null,
) {
    val isBook: Boolean get() = navigation == null
}

/** A feed-level filter, like «Язык: Русский»; [active] marks the one this page shows. */
data class Facet(val title: String, val href: String, val active: Boolean)

data class FacetGroup(val title: String, val facets: List<Facet>)

/** Declared search: a URL template with `{searchTerms}` (or `{?query}`), or an OpenSearch description to fetch first. */
data class SearchLink(val href: String, val isDescription: Boolean)

/** One feed page and what it declares about its catalog; the UI shows only what is there. */
data class CatalogPage(
    val title: String,
    val entries: List<CatalogEntry>,
    val next: String?,
    val search: SearchLink? = null,
    val newBooks: String? = null,
    val popular: String? = null,
    val facets: List<FacetGroup> = emptyList(),
)

object OpdsParser {
    /** MIME fragment → format name. */
    private val readable = listOf(
        "fb2" to "FB2",
        "epub" to "EPUB",
        "mobipocket" to "MOBI",
        "wordprocessingml" to "DOCX",
        "rtf" to "RTF",
        "html" to "HTML",
        "txt" to "TXT",
        "text/plain" to "TXT",
        "pdf" to "PDF",
        // image/vnd.djvu, image/x-djvu, application/djvu: opened by the Folio Документы module.
        "djvu" to "DJVU",
    )
    /** Download order: EPUB and FB2 are real books (the catalog's format goes first in the sheet), then conversions, PDF and DJVU last. */
    private val preference = listOf("EPUB", "FB2", "MOBI", "DOCX", "RTF", "HTML", "TXT", "PDF", "DJVU")
    private val coverRels = listOf(
        "http://opds-spec.org/image/thumbnail",
        "http://opds-spec.org/thumbnail",
        "x-stanza-cover-image-thumbnail",
        "http://opds-spec.org/image",
        "x-stanza-cover-image",
    )

    /** An Atom (OPDS 1) or JSON (OPDS 2) feed. */
    fun parse(text: String, base: String): CatalogPage =
        if (text.trimStart().startsWith("{")) Opds2Parser.parse(text, base) else parseAtom(text, base)

    private fun parseAtom(xml: String, base: String): CatalogPage {
        val root = (runCatching { Xml.parse(xml.toByteArray()) }.getOrNull() ?: Xml.parse(repairQuotes(xml).toByteArray())).documentElement
        require(root.localName == "feed") { "Источник вернул не OPDS каталог" }
        fun resolve(link: org.w3c.dom.Element): String? {
            val href = link.getAttribute("href")
            if (href.isBlank()) return null
            val ancestry = mutableListOf<org.w3c.dom.Element>()
            var node: org.w3c.dom.Node? = link
            while (node is org.w3c.dom.Element) { ancestry += node; node = node.parentNode }
            var effective = base
            ancestry.asReversed().forEach { element ->
                element.getAttributeNS("http://www.w3.org/XML/1998/namespace", "base").takeIf { it.isNotBlank() }?.let { effective = Links.resolve(effective, it) ?: effective }
            }
            return Links.resolve(effective, href)
        }
        val entries = Xml.children(root, "entry").map { entry ->
            val links = Xml.children(entry, "link")
            val downloads = linkedMapOf<String, String>()
            val otherFormats = linkedSetOf<String>()
            links.filter { it.getAttribute("rel").startsWith("http://opds-spec.org/acquisition") }.forEach {
                val type = it.getAttribute("type")
                val format = readable.firstOrNull { (key, _) -> type.contains(key) }?.second
                val address = resolve(it)
                when {
                    format != null && address != null -> downloads.putIfAbsent(format, address)
                    format == null -> formatName(type)?.let(otherFormats::add)
                }
            }
            val navigation = links.firstOrNull { it.getAttribute("type").contains("atom+xml") && it.getAttribute("rel").let { rel -> rel.isEmpty() || rel == "subsection" || rel.startsWith("http://opds-spec.org/sort") } }
                ?.let(::resolve)
                ?: links.firstOrNull { it.getAttribute("type").contains("atom+xml") && it.getAttribute("rel") !in listOf("related", "alternate") && !it.getAttribute("rel").contains("facet") }
                    ?.let(::resolve)
                // OpenEdition links its publishers' feeds only as an Atom `alternate`.
                ?: links.firstOrNull { it.getAttribute("type").contains("atom+xml") && it.getAttribute("rel") == "alternate" }
                    ?.let(::resolve)
            val cover = coverRels.firstNotNullOfOrNull { rel -> links.firstOrNull { it.getAttribute("rel") == rel }?.let(::resolve) }
            val related = links
                .filter { it.getAttribute("type").contains("atom+xml") && (it.getAttribute("rel") == "related" || it.getAttribute("rel").contains("facet")) }
                .mapNotNull { link -> resolve(link)?.let { CatalogLink(link.getAttribute("title").trim(), it) } }
                .filter { it.title.isNotBlank() }
            fun text(name: String) = Xml.children(entry, name).firstOrNull()?.textContent.orEmpty().trim()
            val content = Description.parse(text("content").ifBlank { text("summary") })
            val web = links.firstOrNull { it.getAttribute("rel") == "alternate" && it.getAttribute("type").startsWith("text/html") }?.let(::resolve)
            CatalogEntry(
                id = text("id").ifBlank { downloads.values.firstOrNull() ?: navigation ?: text("title") },
                title = text("title"),
                author = Xml.children(entry, "author").joinToString(", ") { Xml.children(it, "name").firstOrNull()?.textContent.orEmpty().trim() },
                summary = content.text,
                downloads = downloads.entries.sortedBy { preference.indexOf(it.key) }.associate { it.key to it.value },
                navigation = navigation.takeIf { downloads.isEmpty() && otherFormats.isEmpty() },
                cover = cover,
                related = related,
                genres = Xml.children(entry, "category").mapNotNull { c -> c.getAttribute("label").ifBlank { c.getAttribute("term") }.trim().takeIf { it.isNotEmpty() } }.distinct(),
                language = text("language").ifBlank { content.details["Язык"].orEmpty() }.takeIf { it.isNotBlank() },
                year = text("issued").ifBlank { content.details["Год издания"].orEmpty() }.takeIf { it.isNotBlank() },
                size = content.details["Размер"]?.let(::parseSize),
                otherFormats = otherFormats.toList(),
                web = web.takeIf { downloads.isEmpty() && navigation == null },
            )
        }
        val feedLinks = Xml.children(root, "link")
        /** Feed-level link, or a home entry's own link with that rel (iknigi puts «Новинки» there). */
        fun rel(value: String) = (feedLinks + Xml.children(root, "entry").flatMap { Xml.children(it, "link") })
            .firstOrNull { it.getAttribute("rel") == value }?.let(::resolve)
        val searches = feedLinks.filter { it.getAttribute("rel") == "search" }
        val search = searches.firstOrNull { it.getAttribute("href").contains("{searchTerms}") }?.let(::resolve)?.let { SearchLink(it, isDescription = false) }
            ?: searches.firstOrNull { it.getAttribute("type").contains("opensearchdescription") }?.let(::resolve)?.let { SearchLink(it, isDescription = true) }
        val facets = feedLinks.filter { it.getAttribute("rel") == "http://opds-spec.org/facet" }
            .mapNotNull { link ->
                val href = resolve(link) ?: return@mapNotNull null
                val group = link.getAttributeNS(OPDS_NS, "facetGroup").ifBlank { link.getAttribute("opds:facetGroup") }.trim()
                group to Facet(link.getAttribute("title").trim(), href, link.getAttributeNS(OPDS_NS, "activeFacet") == "true")
            }
            .filter { it.second.title.isNotEmpty() }
            .groupBy({ it.first }, { it.second })
            .map { (title, list) -> FacetGroup(title, list) }
        return CatalogPage(
            title = Xml.children(root, "title").firstOrNull()?.textContent.orEmpty().trim(),
            entries = entries,
            next = feedLinks.firstOrNull { it.getAttribute("rel") == "next" }?.let(::resolve),
            search = search,
            newBooks = rel("http://opds-spec.org/sort/new"),
            popular = rel("http://opds-spec.org/sort/popular"),
            facets = facets,
        )
    }

    /**
     * Librusec writes raw quotes inside a link's last attribute (`title="Книги серии "Метро""`);
     * escape quotes between that attribute's opening quote and the tag's end.
     */
    internal fun repairQuotes(xml: String): String =
        Regex("""(<link\b[^<>]*\s[\w:]+=")([^<>]*)("\s*/?>)""").replace(xml) { m ->
            m.groupValues[1] + m.groupValues[2].replace("\"", "&quot;") + m.groupValues[3]
        }

    internal fun formatOf(type: String): String? = readable.firstOrNull { (key, _) -> type.contains(key) }?.second

    internal fun sortFormats(downloads: Map<String, String>): Map<String, String> =
        downloads.entries.sortedBy { preference.indexOf(it.key) }.associate { it.key to it.value }

    private const val OPDS_NS = "http://opds-spec.org/2010/catalog"

    /** «application/x-mobipocket-ebook» → «MOBI», «application/rtf+zip» → «RTF». */
    internal fun formatName(type: String): String? {
        val subtype = type.substringAfter('/', "").substringBefore(';').removeSuffix("+zip").removePrefix("x-").trim()
        if (subtype.isEmpty() || subtype.contains("atom")) return null
        return when {
            subtype.startsWith("mobipocket") -> "MOBI"
            subtype == "octet-stream" -> null
            else -> subtype.uppercase()
        }
    }

    /** «825 Kb», «1.2 Mb» → bytes. */
    private fun parseSize(value: String): Long? {
        val match = Regex("""([\d.,]+)\s*([KkMm])""").find(value) ?: return null
        val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = if (match.groupValues[2].lowercase() == "m") 1024.0 * 1024 else 1024.0
        return (number * unit).toLong()
    }
}

/** Some servers put HTML into `<content>`; this turns it into paragraphs and pulls out the «Ключ: значение» tail. */
internal object Description {
    data class Parsed(val text: String, val details: Map<String, String>)

    private val detailKeys = listOf("Год издания", "Формат", "Язык", "Размер")

    fun parse(html: String): Parsed {
        val plain = decodeEntities(
            html.replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>"), "\n").replace(Regex("<[^>]*>"), ""),
        )
        val details = linkedMapOf<String, String>()
        val lines = plain.lines()
            .map { it.replace(Regex("[\\s ]+"), " ").trim() }
            .filter { line ->
                val key = detailKeys.firstOrNull { line.startsWith("$it:") }
                if (key != null) details[key] = line.substringAfter(':').trim()
                key == null && line.isNotEmpty()
            }
        return Parsed(lines.joinToString("\n"), details)
    }

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "laquo" to "«", "raquo" to "»", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "bdquo" to "„", "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "copy" to "©",
    )

    fun decodeEntities(value: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(value) { m ->
        val code = m.groupValues[1]
        when {
            code.startsWith("#x") || code.startsWith("#X") -> code.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            code.startsWith("#") -> code.drop(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
            else -> named[code]
        } ?: m.value
    }
}
