package app.folio.core.data.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ModuleIndexCodecTest {
    private val sha = "e8a0525246226c72c6fbf659ac09f9f8609c88fe867a814f16ed7f8b01249220"

    @Test fun publishedModulesOnly() {
        val list = ModuleIndexCodec.modules("""{"version":1,"modules":[
            {"id":"piper","package":"app.folio.tts.piper","title":"Folio Piper","description":"d","versionCode":3,"versionName":"1.2.0",
             "url":"https://example.org/p.apk","sha256":"$sha","bytes":10,"new":true},
            {"id":"silero","package":"app.folio.tts.silero","title":"Folio Silero","versionCode":0,"versionName":"","url":"","sha256":"","bytes":0},
            {"id":"http","package":"x","title":"X","versionCode":1,"url":"http://example.org/x.apk","sha256":"$sha"}]}""")!!
        assertEquals(listOf("piper"), list.map { it.id })
        assertEquals(3L, list[0].versionCode)
        assertEquals("app.folio.tts.piper", list[0].packageName)
        assertNull(ModuleIndexCodec.modules("""{"version":2,"modules":[]}"""))
        assertNull(ModuleIndexCodec.modules("<html>"))
    }

    @Test fun viewersComeFromTheirOwnArray() {
        val list = ModuleIndexCodec.modules("""{"version":1,"modules":[],"viewers":[
            {"id":"documents","package":"app.folio.documents","title":"Folio Документы","versionCode":1,"versionName":"1.0.0",
             "url":"https://example.org/m.apk","sha256":"$sha"}]}""")!!
        assertEquals(listOf(ModuleKind.Viewer), list.map { it.kind })
        assertEquals(ModuleKind.Engine, ModuleIndexCodec.modules("""{"version":1,"modules":[
            {"id":"p","package":"p","title":"P","versionCode":1,"url":"https://example.org/p.apk","sha256":"$sha"}]}""")!!.single().kind)
    }

    @Test fun appReleaseTakesTheHashFromTheSums() {
        val metadata = """{"version":"0.6.0","versionName":"0.6.0","versionCode":23,"apkName":"Folio-v0.6.0.apk"}"""
        val sums = "$sha *Folio-v0.6.0.apk\n0000 README-release-ru.md\n"
        val app = ModuleIndexCodec.app(metadata, sums, "app.folio", "https://example.org/")!!
        assertEquals(23L, app.versionCode)
        assertEquals("https://example.org/Folio-v0.6.0.apk", app.url)
        assertEquals(sha, app.sha256)
        assertNull(ModuleIndexCodec.app(metadata, "$sha *Other.apk", "app.folio", "https://example.org/"))
    }

    /** The repository's own `modules.json` (checked out next to Folio) parses. */
    @Test fun repositoryIndexParses() {
        val file = File("../../../folio-modules/modules.json").takeIf { it.isFile } ?: return
        val ids = ModuleIndexCodec.modules(file.readText())!!.map { it.id }.toSet()
        assertEquals(true, ids.containsAll(setOf("piper", "silero")))
    }
}
