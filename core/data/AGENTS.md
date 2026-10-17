# Data and repositories

## Entry points
- Use `core/data/src/main/kotlin/app/folio/core/data/library/LibraryRepository.kt` as the canonical import/load/mutation example.
- Use `core/data/src/main/kotlin/app/folio/core/data/library/BookFiles.kt` for original, parsed cache and image paths.
- Use `core/data/src/main/kotlin/app/folio/core/data/db/FolioDatabase.kt` for Room schema registration; old migrations are removed.
- Use `core/data/src/main/kotlin/app/folio/core/data/settings/SettingsRepository.kt` for persisted reader/library preferences.
- Use `ReaderPreferences.kt` there for shared reader codecs and `BookOverrides.kt` for a book's own settings.
- Use `core/data/src/main/kotlin/app/folio/core/data/catalog/CatalogRepository.kt` for feeds, covers and catalog sources.
- Use `core/data/src/main/kotlin/app/folio/core/data/catalog/DownloadManager.kt` for download rows, resume and workers.
- Use `core/data/src/main/kotlin/app/folio/core/data/library/FolderScanner.kt` for SAF folder scans and scheduled imports.
- Use `core/data/src/main/kotlin/app/folio/core/data/modules/ModuleInstaller.kt` for engine/app installs from the modules
  repository (`ModulesRepository` URLs, `ModuleIndexCodec`); it verifies SHA-256, package and Folio's certificate before
  `PackageInstaller`. `ModuleKind`: engines (`modules`) vs viewers (`viewers` array); `PdfModule`/`PdfModuleLauncher` — Folio Документы
  protocol (keep in sync with its `FolioLink.kt`). Catalog lists: `CatalogRepository.fetchList`/`import`/`exportList` + `CatalogListCodec`; built-in catalog: `CatalogPresets` (iKnigi only).
- Bind repositories in `core/data/src/main/kotlin/app/folio/core/data/di/DataModule.kt`; keep UI in feature modules.

## Invariants
- Load `.agents/rules/storage.md` before changing files, Room schemas, IDs, position strings or preference keys.
- Import through `LibraryRepository.import`; retain its mutex and byte-based deduplication for concurrent imports.
- Rebuild parsed caches through `LibraryRepository.load` from retained originals; caches are disposable.
- Model new persisted mutations after repository suspend functions and DAO flows, rather than screen-owned files or tables.
- Validate a catalog's feed address and optional download origin before saving; route network requests through `core/opds` HTTPS helpers.
- A download mirror rewrites only links whose host is its own catalog's (`downloadAddress`); other catalogs' links go as is.
- Catalog requests pass `CatalogRepository.auth(link)` to `HttpsRequest`: it scopes a sign-in to the catalog host and mirror.
- Keep acquisition IDs distinct from imported book IDs; download rows use link hashes and retain the returned `bookId`.
- Preserve unique worker scheduling and partial files for pause/resume; removing a download keeps the imported book.
- Keep want-to-read/finished shelves as book flags; user collection membership belongs to Room cross-reference rows.
- Preserve SAF source URI tracking and depth limits when extending folder scans; use the existing scanner worker.
- Edit reader settings through `updateReader`/`updateBookReader` (one DataStore edit); keep only book fields that differ.
- Use `SpacingPresets` for card cycles; persist actual spacing and margin values, without a custom-mode flag or saved preset index.
