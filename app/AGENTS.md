# app

Android entry: Application (Hilt, WorkManager), `MainActivity` (edge-to-edge,
"Open with"/"Share" import), navigation. Screens live in `feature/*`, data in `core/data`.

## Entry points
- `app/src/main/kotlin/app/folio/FolioApp.kt` — Hilt app; schedules folder scan
- `app/src/main/kotlin/app/folio/FolioNavHost.kt` — tabs Главная/Библиотека/Каталог, settings route, reader route, cover transition
- `app/src/main/kotlin/app/folio/MainActivity.kt` — intents → import → open book; `EXTRA_BOOK_ID` from notifications; onKeyDown/onKeyUp → `ReaderKeys`
- `app/src/main/kotlin/app/folio/ReadingProvider.kt` — Folio Документы reports PDF pages here (`call`, signature permission checked in code)
- `app/src/main/kotlin/app/folio/DownloadNotifier.kt` — download progress/done/failed notifications, started in `FolioApp`

## Conventions
- Tabs stay composed (`TabStack`); add a tab by adding a child there and a `TabSpec`.
- For tab/search layout and shared screen headers, read `.agents/design/design-system.md`.

## Commands
- `./gradlew.bat :app:assembleDebug :app:lintDebug --console=plain`
