# feature/reader
- Read `.agents/design/reader.md` for reader UI; use `.agents/design/design-system.md` only for shared components.

## Entry points
- Resolve symbols below in `feature/reader/src/main/kotlin/app/folio/feature/reader/`; search exact names first.
- `ReaderRoute` picks `PdfViewer` (PDF) or `ReaderScreen` — appearance, ink, lifecycle; `ReaderControls` — local UI state.
- PDF/DjVu → Folio Документы: `ReaderKind.External` (`core/data/.../PdfModule.kt`, `app/.../ReadingProvider.kt`); else `ModuleOffer`, DjVu `NeedsModule`.
- `ReaderSurface` — viewport, insets, guide geometry and layer order; `ReaderPages` — paged/scroll rendering.
- `readerInteractions` — tap targets, tap zones, long-press selection; `ReaderSelectionOverlays` — selection, notes, palette.
- `ReaderChrome` — overlays and page labels; `ReaderToolbar` / `ReaderNavigationPanel` — controls; `ReaderSheets` — sheets.
- `WindowEffects`, `ImmersiveMode`, `ReadingSessionEffect` — orientation, brightness, bars, session lifecycle.
- `ReaderKeys` / `ReaderKeysEffect` — hardware page keys from `MainActivity.onKeyDown/onKeyUp` into `controls.keyTurns` (pager and scroll); not in PDF.
- `ReaderViewModel` — snapshot `state`, `PaginationEngine`, navigation/history, eager flows and coroutine scope.
- `ReaderSearch` / `searchBook` — search job/state, normalization and snippets; `ReaderAnnotations` — range/text and persistence.
- `HorizontalPageTurner` — Slide/Curl/Fade; `tapZone` — pure gesture math; `ScrollReader` / `planScrollWindow` — scroll/window.
- `footerCenter`, `headerText`, `rememberClock`, `rememberBattery` — page chrome; `ReaderPageSettings` — inline page settings.
- `ChapterProgress` — shared TOC rows, titles and remaining pages; `GuideNavigation` — line state after successful turns.
- Settings (`core/data/.../settings/SettingsRepository.kt`): `ThemesSheet` → `ReadingSettingsSheet` (also `ReadingDefaultsSheet`) in a full-height `FolioSheet`, no grabber.
  Edit through `SettingsEditor`; `vm.settings` = shared + `ownSettings`.
- Pin `PageSettingsPreview` (a `PreviewSource`) outside per-tab scrolling; use `Paginator`/`PageView` with `samplePreviewSource()`, even in a book (book numbers flicker on re-pagination).
- Preview taps only step the guide (turning at its ends); tabs/manual scroll navigate settings. Keep «Прокрутка» scroll; paged modes swipe via `HorizontalPageTurner`.
- Apply settings immediately; use `SettingsSegments` (labels shrink; `SettingsOptions` only above 130 % text) for short choices, `SettingsChoice` for fonts. See `.agents/design/reader.md`.

## Rules
- Header/footer settings change the text insets, so they re-paginate (new `layoutGeneration`) like a font
  change. Keep the position (`ReaderViewModel.readingPosition`), never the page index.
- Page title, footer and bookmark ribbon (`BookmarkRibbon`) are part of the page in paged mode (they slide with it); in scroll mode they are fixed.
- Fade page labels when controls appear; retain their chosen text format and fixed margins. Keep visibility out of pagination inputs.
- Draw lines through `headerLook`/`footerLook` and `settings.shownFooter`; `textInsets` owns the space they take.
- Count remaining pages from TOC ranges, not parser chapters; leave unknown counts and pre-TOC text unlabeled.
- Count history expiry only on reading landings; keep history in `ReaderPositionSheet`. Guide is drawn per page (`GuidePage`); use actual landings.
- Keep plain visible-control taps inert except dismissal; preserve target/selection priority. See `.agents/design/reader.md`.
- Test pure gestures/progress in `ReaderGesturesTest` / `ReaderProgressTest`; search example: `feature/reader/src/test/kotlin/app/folio/feature/reader/ReaderSearchTest.kt`.
- Keep reader overlays and window brightness in `ReaderControls`; keep settings tab/scroll state in `ThemesSheet`.
- Keep annotation flows and `viewModelScope.launch` in the ViewModel; helpers must preserve persistence order and read timing.

- Auto brightness disables touch/accessibility adjustment; observe the device setting and use it on manual takeover.
- Ignore unchanged settings edits, including repeated slider endpoint events.
- Use `SpacingSettingsCard` for cyclic interval/margin controls and shared-value sliders; follow `.agents/design/reader.md` for custom states.
- Keep palette/size/window/turning on Basic; start Advanced with lines, then font/spacing/colour, guide last. Reset only Advanced. See `.agents/design/reader.md`.
- Disable overscroll stretch in the settings panel; horizontal slider drags at either edge must not deform the panel.
- Use `FooterContentCapsules`; cycle progress on tap. Show only geometry in «Подробнее», or a hidden-line hint when empty; retain saved settings.
- Order line details in `SettingsSubheader` groups: look (size, opacity, capitals), position, separation (rule, gap, boundary), behaviour (chapter start); default Scroll blocks to 36 dp.
- Keep disclosure state separate from persisted line settings; edits activate only the visible legacy values.
- In Scroll, keep each boundary in its line disclosure; use `ReaderLineBlock` geometry in reader and preview.
- Keep the rule on the block edge; absolute gap 0 reaches it. Preserve legacy paged gaps; resolve None to Fade.

## Gotchas
- Scroll list: resolve pages by item key (`Page.key`), not by index. Right after chapters are prepended,
  `firstVisibleItemIndex` and the page list disagree for a frame.
- Scroll reports carry the last shown `ScrollJump.id`; `onScrolledTo` drops older ones, or a report from the
  old list position lands after a jump and undoes it.
- `scrollToItem` right after replacing the list's items clamps to the old item count: wait for
  `layoutInfo` to show the new items first (see `show()` in `ScrollReader`).
- Reading `ScrollLocator.originOf` in composition subscribes to every scroll frame; only do it while a
  selection or highlight palette is open.
- `Page.lead` is the spacing a page break swallowed; paged mode ignores it, scroll mode adds it on top.
- `PageView` draws text in its own layer so title/clock/footer recompositions don't redraw the text.
- On the emulator the cold start takes 4–5 s; `adb` taps sent earlier land on the home screen or trigger
  an input ANR. Open the test book with `am start -W -a android.intent.action.VIEW -d file://...`.
