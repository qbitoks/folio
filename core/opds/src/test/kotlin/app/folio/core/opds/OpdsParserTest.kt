package app.folio.core.opds

import org.junit.Assert.*
import org.junit.Test

class OpdsParserTest {
    @Test fun resolvesNavigationDownloadsAndPagination() {
        val page = OpdsParser.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><title>Книги</title><link rel="next" href="?page=2"/><entry><id>book:1</id><title>Потоп</title><author><name>Сенкевич</name></author><content>Описание</content><link rel="http://opds-spec.org/acquisition/open-access" type="application/epub+zip" href="/b/1/epub"/><link rel="http://opds-spec.org/acquisition" type="application/fb2+zip" href="/b/1/fb2"/></entry><entry><id>author:2</id><title>Автор</title><link type="application/atom+xml;profile=opds-catalog" href="/opds/author/2"/></entry></feed>""", "https://books.example/opds/search")
        assertEquals("Книги", page.title)
        assertEquals("Сенкевич", page.entries[0].author)
        assertEquals("https://books.example/b/1/epub", page.entries[0].downloads["EPUB"])
        assertEquals("https://books.example/b/1/fb2", page.entries[0].downloads["FB2"])
        assertEquals("https://books.example/opds/author/2", page.entries[1].navigation)
        assertEquals("https://books.example/opds/search?page=2", page.next)
    }
    @Test fun catalogWithoutTextsDoesNotInventDownloads() {
        val page = OpdsParser.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>1</id><title>Книга</title><link type="text/html" href="/b/1"/></entry></feed>""", "https://mirror.example/opds")
        assertTrue(page.entries.single().downloads.isEmpty())
    }
    @Test fun acceptsEntriesWithoutIdAndIgnoresRelatedAuthorLinks() {
        val page = OpdsParser.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Книга</title><author><name>Автор</name></author><link rel="related" type="application/atom+xml" href="/opds/author/1"/><link rel="http://opds-spec.org/acquisition/open-access" type="application/epub+zip" href="/b/1/epub"/></entry></feed>""", "https://books.example/opds")
        assertEquals("https://books.example/b/1/epub", page.entries.single().id)
        assertNull(page.entries.single().navigation)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsHtmlInsteadOfFeed() {
        OpdsParser.parse("<html><body>Ошибка</body></html>", "https://books.example/opds")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsDoctype() {
        OpdsParser.parse("<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///secret'>]><feed>&x;</feed>", "https://books.example")
    }
    @Test fun readsBookDetailsCoverAndRelatedLinks() {
        val page = OpdsParser.parse("""<feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/"><entry><title>Предчувствие</title><author><name>Аршакуни Ольга</name></author><link href="/opds/author/345809" rel="related" type="application/atom+xml" title="Все книги автора Аршакуни Ольга"/><category term="История" label="История"/><dc:language>ru</dc:language><dc:issued>1987</dc:issued><content type="text/html">&lt;p class=&quot;book&quot;&gt;&lt;b&gt;Книга &amp;laquo;о муже&amp;raquo;.&lt;/b&gt;&lt;/p&gt;&lt;br/&gt;Год издания: 1987&lt;br/&gt;Формат: fb2&lt;br/&gt;Язык: ru&lt;br/&gt;Размер: 825 Kb&lt;br/&gt;</content><link href="/i/1/892501/obl.jpg" rel="http://opds-spec.org/image" type="image/jpeg"/><link href="/i/1/892501/thumb.jpg" rel="http://opds-spec.org/thumbnail" type="image/jpeg"/><link href="/b/892501/fb2" rel="http://opds-spec.org/acquisition/open-access" type="application/fb2+zip"/><link href="/b/892501/epub" rel="http://opds-spec.org/acquisition/open-access" type="application/epub+zip"/><link href="/b/892501/mobi" rel="http://opds-spec.org/acquisition/open-access" type="application/x-mobipocket-ebook"/></entry></feed>""", "https://books.example/opds/new/0/new")
        val book = page.entries.single()
        assertTrue(book.isBook)
        assertEquals("Книга «о муже».", book.summary)
        assertEquals("https://books.example/i/1/892501/thumb.jpg", book.cover)
        assertEquals(listOf("История"), book.genres)
        assertEquals("1987", book.year)
        assertEquals("ru", book.language)
        assertEquals(825L * 1024, book.size)
        assertEquals(listOf("EPUB", "FB2", "MOBI"), book.downloads.keys.toList())
        assertTrue(book.otherFormats.isEmpty())
        assertEquals(CatalogLink("Все книги автора Аршакуни Ольга", "https://books.example/opds/author/345809"), book.related.single())
    }
    @Test fun pdfOnlyBookIsDownloadable() {
        val page = OpdsParser.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Артек</title><link href="/opds/author/1" rel="related" type="application/atom+xml" title="Все книги автора"/><link href="/b/1/download" rel="http://opds-spec.org/acquisition/open-access" type="application/pdf"/></entry></feed>""", "https://books.example/opds")
        val book = page.entries.single()
        assertTrue(book.isBook)
        assertEquals(listOf("PDF"), book.downloads.keys.toList())
        assertTrue(book.otherFormats.isEmpty())
    }
    @Test fun convertedFormatsAreDownloadableInPreferenceOrder() {
        val links = listOf("txt+zip", "rtf+zip", "html+zip", "x-mobipocket-ebook", "epub+zip", "fb2+zip", "pdf", "djvu")
            .joinToString("") { """<link href="/b/1/${it.substringBefore('+')}" rel="http://opds-spec.org/acquisition/open-access" type="application/$it"/>""" }
        val book = OpdsParser.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Книга</title>$links</entry></feed>""", "https://books.example/opds").entries.single()
        assertEquals(listOf("EPUB", "FB2", "MOBI", "RTF", "HTML", "TXT", "PDF", "DJVU"), book.downloads.keys.toList())
        assertTrue(book.otherFormats.isEmpty())
    }
    @Test fun authorEntryNavigatesToItsMainLinkNotFacets() {
        val page = OpdsParser.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Лем Станислав</title><content type="text">120 книг</content><link href="/opds/authorsequences/1" rel="http://www.feedbooks.com/opds/facet" type="application/atom+xml;profile=opds-catalog" title="Книги автора по сериям"/><link href="/opds/author/1" type="application/atom+xml;profile=opds-catalog"/><link href="/ia/1/lem.jpg" rel="http://opds-spec.org/image/thumbnail" type="image/jpeg"/></entry></feed>""", "https://books.example/opds/search")
        val author = page.entries.single()
        assertEquals("https://books.example/opds/author/1", author.navigation)
        assertEquals("120 книг", author.summary)
        assertEquals("https://books.example/ia/1/lem.jpg", author.cover)
        assertEquals("Книги автора по сериям", author.related.single().title)
    }
}
