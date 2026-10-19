---
status: active
---
# STATE

## Current focus
- Work from the user's task; follow-ups below need an explicit task, not autonomous implementation.

## Phone verification before distribution
- 0.12.0: DjVu (import, catalogs) opens only in Folio Документы 1.2.0, untried on a phone. 0.11.0: module renamed.
- 0.10.0: «Настройки → Модули» installs Folio Документы; PDFs open in it (pages, «Как книга», contents, search, themes,
  volume keys) and the page comes back to Folio. Emulator-checked with a generated PDF; not on a phone or a real scan.
- 0.9.0: page keys (volume off while reading aloud), download notifications + «Читать», «Лицензии»,
  «Каталоги» (iKnigi only, import link/file, re-import updates, export; old starred kept).
- «Настройки» (gear on «Главная»): appearance, «Настройки чтения», update check/up-to-date/offline, project link.
- Read aloud (stage 1): «Слушать», mini-player, phrase band, page following, system/headset controls, no-voice hint.
- Voice sheet (0.7.1): samples, rate/pitch, sleep timer; Google «Онлайн-голос» switch + offline fallback unchecked.
- Read aloud via engine modules (0.6.0): install Folio Piper/Silero from «Голос» (unknown-apps permission, system
  installer confirm), voice download in the engine, reading with the chosen voice, Folio self-update card on Home.
- Reader settings: Basic/Advanced tabs, palette cards, Advanced-only reset, default/book reset, preset paragraph
  gaps and seam leading, cyclic interval/margin cards and percentage margins, footer capsules, 36 dp Scroll lines.
- Reader controls: themed scrubber, five-page history arrows, position sheet, bottom search, corner bookmark.
- Import: text files (txt/conf/no extension, windows-1251, open-with, folder scan); real Flibusta MOBI/RTF/HTML/TXT
  downloads and a DOCX (parsers are tested only on synthetic files).
- Catalogs: migration of old sources, switching, Open Library web links, Gutenberg search, the unified «Каталоги»
  list (stars, switcher menu, long-press edit, add by address, preset reset).

## Next steps
- Add secret `ANDROID_DEBUG_KEYSTORE_BASE64` (same preview keystore as Folio) to `qbitoks/folio-modules`, so engine
  tags build in CI; 1.0.0 engines were published by hand from a local build signed with that key.
- Cut Folio v0.6.0: 0.5.0 users get the update card only from 0.6.0 on (0.5.0 has no updater).
- Generate the baseline profile on a disposable emulator; the generator uninstalls the app.
- Replace archived Readium regressions with checks for the current UI; CI uses `scripts/ci-emulator.ps1`.
- Potential reader follow-up: add the roadmap's scroll scrubber with chapter/page bubble.
- folio-modules `catalogs.json` is no longer read by the app; keep it as an importable list or drop it (user's call).

## Known issues / verification gaps
- The emulator's DNS proxy fails behind the host VPN; a fresh AVD with `-dns-server` works.
- Page keys don't work in the built-in PDF viewer (they do in Folio Документы).
- Folio Документы 1.0.0 has no annotations, forms or bookmarks yet (MJ PDF's engine supports them).
