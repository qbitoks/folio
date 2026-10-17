# DECISIONS

Append-only, one line per decision, newest at the bottom:
`YYYY-MM-DD | scope | decision | why (≤ 1 line) | details link (optional)`
Read it (grep by scope or keyword) before changing architecture, a library, a data format or the layout.
Long rationale → `.agents/decisions/NNNN-title.md` (template: `.agents/templates/adr.md`).
Reversed decision → append the new line and delete the old one (git keeps history).
Over the limit → move entries that no longer constrain anything to `.agents/archive/decisions-YYYY.md`;
keep foundational ones (stack, layout) here however old. Parallel appends merge via `merge=union`.

2026-10-12 | repo | AGENTS.md is the single source of truth; tool files (CLAUDE.md, GEMINI.md, …) only point to it | one rule set for every agent, no drift
2026-10-12 | docs | Agent docs in English, chat in the user's language | Cyrillic costs ~1.5–3× tokens (tokenizer-dependent); English is unambiguous
2026-10-12 | docs | Root rules + MAP index + per-area AGENTS.md loaded on demand | always-loaded context stays constant as the project grows
2026-10-12 | docs | Stable facts and commands in root AGENTS.md → Project; STATE holds only volatile status | commands survive STATE rewrites, tools get them free
2026-10-12 | claude | CLAUDE.md stub (@AGENTS.md) next to every AGENTS.md, made by lint --fix | Claude Code skips native AGENTS.md when any CLAUDE.md exists
2026-10-12 | ignore | Tool ignore files generated from .agents/ignore.md; .cursorignore = secrets only | hand-kept mirrors drifted; hard blocks hid lockfiles
2026-10-12 | tooling | agent-lint runs from .githooks/pre-commit and CI | PowerShell has no sh on PATH, but git runs hooks with its own sh
2026-10-12 | process | finish-task inlined into root AGENTS.md §5 | needed on every task: saves a read and removes a duplicate
2026-10-12 | repo | Adopted agent_template; Russian AGENTS.md contract moved into root AGENTS.md (English) + .agents/rules (since archived) | one entry point for all agents
2026-10-12 | docs | docs/ dissolved: module AGENTS.md (core, app, scripts) + .agents/rules (storage, release) + STATE | one doc system, loaded on demand
2026-10-12 | docs | Dated reports, review, plans/specs moved to .agents/archive/ | history, not instructions; kept out of search
2026-10-12 | cursor | Removed .cursor/rules/project.mdc | Cursor reads AGENTS.md natively
2026-10-12 | git | Template .gitattributes (`* text=auto eol=lf`) adopted as is | index was already all-LF; stops CRLF drift on Windows
2026-10-12 | repo | Prototype codebase replaces the Readium app; OPDS kept in core/opds + «Каталог» tab | user chose the prototype as base | .agents/decisions/0004-prototype-engine.md
2026-10-12 | catalog | Downloads: Room row + unique expedited WorkManager work + HTTP Range resume | survives drops and restarts without a foreground service
2026-10-12 | catalog | Download read timeout 150 s, feed 60 s, one quick feed retry | some servers convert EPUB on request (15–90 s silence) and often 502s the first search
2026-10-12 | catalog | Catalog home mirrors «Главная»: bands, «Новинки» cover shelf, sections list | user asked for one design
2026-10-12 | catalog | OPDS covers via own HTTPS loader into BookCover, not Coil network | HTTPS-only redirects kept, no OkHttp
2026-10-12 | ui | App appearance = per-app night mode (UiModeManager); book override by id, absent = follow app | re-themes windows and bars without recreation
2026-10-12 | ui | Old reader-wide `appearance` key dropped without migration | user decision
2026-10-13 | release | Tags publish a regular release automatically: draft during upload, `--draft=false` last | user decision; no half-uploaded public release
2026-10-13 | reader | Tap zones are the outer thirds, not 10 % edges | edges were too narrow: most taps toggled the controls instead of turning
2026-10-13 | reader | Scroll mode: own window of ±2 chapters, positions by item key, jumps via `ScrollJump` id | index-based reads mixed pages during prepends; stale reports undid jumps
2026-10-13 | reader | Scroll seams keep the swallowed spacing as `Page.lead`, not a separate ribbon layout | one pagination for both modes; positions and page numbers stay shared
2026-10-13 | reader | Fade page labels under controls without changing number format | replaces number expansion from earlier design; user-approved plan
2026-10-13 | reader | Use TOC ranges for chapter progress and title; guide direction follows successful landings | user-approved fixes
2026-10-13 | code | Split by responsibility; aim for files <400 lines, composables <150 (soft targets) | targeted reads save context; avoid fragments with no independent purpose
2026-10-13 | reader settings | Fixed dialog, pinned native-text preview, separate choice cards and descriptive styles; preserve settings keys | user-approved redesign
2026-10-13 | reader settings | Compact: segments with shrinking labels; radio list only for >130 % text | user: tiles and lists too tall
2026-10-13 | reader settings | Shared settings + per-book differences for every panel setting; scope menu under title | user decision; replaces shared-only theme/text
2026-10-13 | reader settings | Panel opens on book scope iff the book has own settings; no stored scope | a remembered mode caused accidental edits
2026-10-14 | reader settings | Footer «Нет» drops only the page number; time/battery/left stay in «Свои параметры» | user: wants a clock-only line
2026-10-14 | reader settings | Defaults: header Chapter, footer «42» without clock/battery, Slide; missing keys follow new defaults | user decision
2026-10-14 | reader | Scroll: independent Clip/Fade inside each disclosure; fixed block edges, absolute gap, vertical label alignment | user decision
2026-10-14 | app | Renamed to Folio; package/applicationId `app.folio`, DB `folio.db` | user decision; clean break: old installs do not upgrade, data not migrated
2026-10-14 | reader | Free colour picker rejected; colours come from palettes and tone/contrast presets | too easy to get unreadable text
2026-10-14 | reader settings | «На странице»: omit line preset buttons; clock/battery first, advanced disclosure never disables settings | user-approved simplification
2026-10-14 | reader settings | Footer content: four live capsules (format cycles on tap), chapter/time/battery toggles; width-based wrap | user-approved design, tap cycle per 2026-10-15 correction
2026-10-15 | reader | Replace floating menu with top/bottom overlay panels and release-only scrubber; visible-control taps only dismiss | user-approved design; supersedes edge-tap dismissal+turn
2026-10-15 | reader history | Keep arrows beside tappable percent for five reading pages; remove floating return, retain history in position sheet | user correction
2026-10-15 | reader settings | Supersede linked text/custom themes: five palettes, independent text, details disclosure and scoped factory reset | user-approved design
2026-10-15 | reader settings | Basic: window, palette, size; Advanced: text, colour, page; reset affects Advanced only | user correction; replaces general disclosure/reset
2026-10-15 | reader settings | Move turning to Basic with no group headings; Advanced starts with unchanged lines, then collapsible font/spacing/tone | user-approved layout
2026-10-15 | reader spacing | Use two cyclic cards with custom glyphs; sliders share values; custom taps restart at minimum | user correction
2026-10-15 | storage | Remove old settings and Room v1 migrations; keep current books and fail unsupported schemas without data reset | user request
2026-10-15 | reader theme | Reader UI uses `FolioTheme(palette.uiColors())`: roles mixed from page and text | user request; sepia got white sheets
2026-10-15 | reader | «Быстрое выцветание» replaced by «Без анимации» (listed first); stored value stays `PageTurn.Fade` | user decision; keeps saved settings
2026-10-15 | format | Any non-binary file imports as `BookFormat.Text` (content sniff, not extension); regular font, Markdown as is; folder scan takes common text extensions | user decision
2026-10-16 | format | MOBI, DOCX, RTF, HTML, zipped TXT/HTML/RTF parse into our model, no new deps | user-approved plan
2026-10-16 | format | PDF needs its own page viewer (later); DJVU (GPL native lib) and binary DOC are out | user-approved plan
2026-10-16 | catalog | Order FB2, EPUB, MOBI, DOCX, RTF, HTML, TXT; fold conversions into «Другие форматы» | user-approved plan
2026-10-16 | reader | PDF via platform PdfRenderer in its own pager viewer, not reflowed; no new deps | user-approved plan
2026-10-16 | catalog | Many catalogs as JSON in `catalog` prefs, not Room | user-approved plan
2026-10-16 | catalog | UI shows search/new/popular/facets only when the feed declares them; Flibusta keeps preset templates | user-approved plan
2026-10-16 | opds | OPDS 2 JSON parsed with kotlinx-serialization-json (JsonElement, no plugin); `http://` feed links are upgraded to HTTPS, never fetched as HTTP | user-approved plan
2026-10-16 | catalog | Not presets: Всенаука (expired cert), ФантЛаб (404), FBSearch/Gallica/LITLIB (403) | checked from dev host
2026-10-16 | catalog | Not presets: Maxima (DNS), E-reading (login), Самиздат via OPDS Home (no answer) | checked from dev host
2026-10-16 | catalog | Presets stored only when starred or edited; rows without `favorite` decode as favorites | keeps old lists
2026-10-16 | catalog | Book sheet: one best-format capsule (EPUB first) + ••• menu of all formats | user-approved design
2026-10-16 | catalog | Extra formats stay separate library books | merging would change book ids and storage
2026-10-16 | catalog | Per-catalog download format (EPUB/FB2; Flibusta FB2); old rows decode as EPUB | user decision, no migration
2026-10-16 | catalog | Optional per-catalog sign-in; password AES-GCM with a non-exportable Keystore key in `catalog_auth` prefs | user confirmed relaxing «no accounts» for Librusec
2026-10-16 | opds | Basic auth sent preemptively, only over HTTPS to the catalog host + mirror, checked per redirect hop | no challenge round-trip; other hosts never get it
2026-10-16 | catalog | Capsule = last picked format (download `createdAt`); ••• stays, × gone; «Другие форматы» group | user decision; no new storage
2026-10-16 | designsystem | Own `FolioSheet` instead of `ModalBottomSheet`: close at 35 % pull or fast 80 dp swipe; content scroll never closes | M3 closed sheets on stray flicks
2026-10-16 | reader | Settings sheet drags to close (reverses «never drag»); half detent, grabber and book strip tried and dropped | user decision
2026-10-16 | docs | README.md is a Russian promo-style overview of what Folio does, not a feature spec; behaviour details live in area AGENTS.md | user decision; GitHub visitors want a pitch
2026-10-16 | speech | `core/speech`: `SpeechEngine` interface, system TTS first; platform `MediaSession` + FGS, not Media3 | no new dependency; Piper/Silero become engines
2026-10-16 | speech | Offline voices are separate TTS engine apps in qbitoks/folio-modules | APK 55 → 6 MB; engines update alone; no code loading
2026-10-16 | modules | Folio installs engines and its updates via PackageInstaller after SHA-256 + same-cert checks | no Google Play planned (user)
2026-10-16 | speech | No migration of 0.5 voices/prefs: `files/voices/` and old speech keys are deleted | user decision: voices are downloaded again in the engines
2026-10-17 | speech | Voice sheet: engines by name, uninstalled Folio engines last, no modules section | user chose it; uninstall stays with the system
2026-10-17 | ui | Settings: `feature/settings`, gear on «Главная»; appearance + reader defaults leave library «•••» | user decision; settings will grow
2026-10-17 | catalog | Only iKnigi built in; others by address or list import; no OPDS Home, no daily `catalogs.json` | user: no unlicensed libraries shipped
2026-10-17 | catalog | One-off list import with ticks; re-import updates same id/address; export without sign-ins | user chose it over subscriptions
2026-10-17 | catalog | Download notifications are plain (`DownloadNotifier`), no foreground service | WorkManager + Range resume suffice
2026-10-17 | reader | Page keys via `ReaderKeys` from `MainActivity.onKeyDown/onKeyUp`; volume toggle shared, default on | Compose keys need focus
2026-10-17 | settings | Licenses: hand `OpenSourceList` + assets, checked by a test | no AboutLibraries for ~10 libraries
2026-10-17 | modules | PDF reader = separate GPL-3.0 app «Folio MJ PDF» (`pdf-mj/` in folio-modules, MJ PDF engine + Folio-style Compose UI) | GPL code stays out of Folio; user chose a module
2026-10-17 | modules | Viewers in `modules.json` → own `viewers` array; position back via signature-protected `ReadingProvider.call` | 0.9 reads only `modules` and would list them as engines
2026-10-17 | storage | No migrations or backward compatibility; Room uses destructive fallback (wipes on schema change) | user decision
2026-10-17 | modules | PDF module renamed «Folio Документы», package `app.folio.documents`, folder `documents/`; old app.folio.pdf.mj is not migrated | user decision
