# Rule: book storage and saved data

Trigger: changing import, Room entities/DAOs, `BookFiles`, settings DataStore keys, `Position` encoding,
catalog prefs.
Why: one place for the on-disk layout. No migrations and no compatibility with earlier versions (user decision):
an update may wipe saved data.

## Layout (app-private, see `core/data/.../library/BookFiles.kt`)
- `files/books/<sha1>.<ext>` — original file (PDF: `.pdf`, no `cache/books` entry; position = `Position(page)`); book id = SHA-1 of its bytes. `files/covers/<id>.jpg`.
- `cache/books/<id>/book.bin` + `res/` — parsed model; rebuilt from the original if the cache is cleared.
- Room `folio.db` (`FolioDatabase`, v2): books, collections, bookmarks, highlights, sessions, search
  history, `downloads`. Schemas in `core/data/schemas/`. Bump the version on any schema change and write
  no migration: `fallbackToDestructiveMigration(dropAllTables = true)` wipes the database on upgrade.
- `files/downloads/<sha1 of link>.part` — catalog download in progress; resumed with HTTP Range, deleted
  after import. `cache/catalog-covers/<sha1 of url>` — OPDS cover thumbnails (safe to clear).
- DataStore `settings`: reader/library prefs; `app_appearance` (app-wide), `book_appearance_<book id>`
  (absent = follow the app; removed on delete). Old `appearance` key is no longer read (no migration).
- Shared reader settings: `ReaderPreferences` (existing keys plus `reader_appearance`, `header_layout`,
  `footer_layout`, `guide_window` = `spread;extra;falloff`, junk → single line); visual presets stay in `ChromeStylePreferences` keys;
  `footer_under` and `header_under` (None/Clip/Fade) = what scrolling text does
  under each line independently; resolve None to Fade in Scroll without rewriting saved values.
- Decode only current twelve-field line layouts; obsolete nine-field layouts fall back to factory defaults.
- A book's own settings: `book_reader_<book id>`, `name=value` lines, only fields that differ from shared
  (`BookOverrides`); removed on reset/delete. Ignore obsolete separate book appearance/style keys and unknown fields.
- Store shared typography in the current nine-field `reading_text` codec; reject old text formats to factory defaults.
  Store margins as 0–15 percent of viewport width on each side; do not migrate old dp margins or linked text presets.
- Store book warmth/contrast as separate `warmth`/`contrast` fields and inherit untouched controls.
- Ignore obsolete footer-style, page-turn aliases and book settings-version markers; do not rewrite unopened books on shared edits.
- `shared_prefs/catalog.xml`: `catalogs` — JSON array of `{id,title,address,downloads?,searchBooks?,searchAuthors?,newBooks?,format?,favorite?}`
  (`CatalogCodec`; missing `favorite` = true, so pre-favorites rows stay starred). Holds favorites, own catalogs and
  edited presets only; an unstarred unedited preset is not stored (`CatalogDirectory`). `current` — selected id, a saved
  row or a preset; `recent` — catalog searches, newline-joined;
  `download_issues` — `host<TAB>code` lines of catalogs whose last download was refused. The old `catalog`/`downloads`
  origins migrate once into a Flibusta-style catalog and are removed; with neither key the starter set is seeded.
- `shared_prefs/speech.xml`: `rate`, `pitch` (floats, absent = 1), `engine_package` (TTS engine package; absent = system default),
  `voice_<lang>` (voice name of that engine; the `-local` or `-network` variant itself). `favorites` (0.7.0 only) is
  deleted on start. Keys of 0.5 (`engine`, `piper_*`, `silero_speaker`) and `files/voices/`
  (built-in Piper/Silero voices) are deleted on start (`SpeechVoices`): engines are separate apps now, no migration (user decision).
- `files/catalog-presets.json` and `catalog.xml` `presets_checked` (before 0.9: the daily preset list) are deleted on
  start. Catalog list files for import/export: `CatalogListCodec` (`{"version":1,"catalogs":[…]}`, no sign-ins).
- DataStore `settings` `volume_keys_turn` — volume buttons turn pages (absent = on), shared, never a book's own.
- `shared_prefs/modules.xml`: `index` (last `modules.json`), `checked` (fetch time). `cache/modules/<id>.apk` — APK
  being installed, deleted after the installer answers.
- `shared_prefs/catalog_auth.xml` (`CatalogCredentials`): catalog id → `login\n<base64 IV+AES-GCM ciphertext>`, key
  `catalog_auth` in AndroidKeyStore. Never store the password in plain text; an undecryptable one reads as absent.
  Removing an own catalog removes its row; presets keep it. `allowBackup` stays false (the key doesn't travel).

## Changing any of this
- No migrations, no reading of old formats: change keys, files, positions, IDs and schemas freely; drop old data.
- Keep this file in sync with the layout. Changing Folio's applicationId or APK signature needs a separate decision.
