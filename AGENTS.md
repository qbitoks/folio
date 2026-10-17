# AGENTS.md

Rules for every AI agent in this repo. Deeper `AGENTS.md` files add rules for their area.
Precedence: the user's explicit request > nearest `AGENTS.md` > this file > `.agents/rules/`.

## 1. Orientation (in order; stop as soon as you know enough)

1. Read `.agents/STATE.md` (status, focus, next steps) and `.agents/MAP.md` (areas → their docs).
2. Read the `AGENTS.md` of each area your task touches, unless your tool already loaded it.
   Never read all of them.
3. Before changing architecture, a library, a data format or the layout: grep `.agents/DECISIONS.md`
   for the area or keyword. Don't reverse a recorded decision without telling the user.
4. Search (grep/glob/symbols) for exact names. Read small files (< ~300 lines) whole, large ones by range.
5. Load a playbook (§4) only when the task matches its trigger.

`status: uninitialized` in STATE: if the task is to start or build the project, run
`.agents/playbooks/bootstrap.md` first. Work on the template itself (agent docs, scripts) needs no bootstrap.

## 2. Context hygiene

- Read the minimum. Every tool call re-sends the context: one whole read beats five tiny ones.
- Skip what `.agents/ignore.md` lists (deps, build output, lockfiles, generated code). Open such a file
  only by explicit path when the task is about it. Never read secrets.
- Don't re-read files you just wrote. Pipe noisy output through `tail`/`grep`; run single tests while iterating.
- Broad exploration → a sub-agent or search tool if your harness has one; keep only the conclusions.
- One fact, one place. Link (`see path/file.md`) instead of copying.

## 3. Working rules

- Match surrounding code: style, naming, comment density, idioms. No speculative abstractions.
- Split code by responsibility; aim for files under ~400 lines and composables under ~150, not hard limits.
- Small, verifiable steps. Run the Check command (Project section below) before saying "done".
- Docs disagree with the code → the code wins; fix the doc in the same task.
- Ask the user only for decisions that are theirs; otherwise pick the conventional default and say so.
- Never commit secrets. Destructive commands (force-push, `rm -rf`, DB drops) need explicit approval.
- Commit only when the user asks. Messages: conventional (`feat:`, `fix:`, `docs(agents):`), summary + why.
- Scratch files go to `.agents/tmp/` (gitignored). Don't create report or summary files; report in chat.
- Reply in the user's language. Write agent docs in English.

## 4. Playbooks (load on demand)

| Trigger | File |
|---|---|
| Start/build a project while `status: uninitialized`, or adopt the template in an existing repo | `.agents/playbooks/bootstrap.md` |
| Add a module, package, service, feature area or vault section | `.agents/playbooks/new-module.md` |
| Lint: over a limit (now) or "maintenance due" (offer after the task); docs feel stale | `.agents/playbooks/maintenance.md` |
| Pull agent-infrastructure updates from the upstream template | `.agents/playbooks/upgrade.md` |
| Write or edit any agent doc (AGENTS.md, STATE, MAP, DECISIONS, playbook, rule) | `.agents/rules/writing-agent-docs.md` |

Topic rules live in `.agents/rules/`, indexed in `.agents/MAP.md`. Create new ones there, not here.

## 5. Before saying "done" (every task; most need zero doc edits)

- The Check command passes, or you report the failure.
- Added, moved or removed an area → `.agents/MAP.md` + that area's `AGENTS.md` (new-module playbook).
- Had to explore an undocumented area to understand it → write its `AGENTS.md` (entry points,
  canonical example) so the next agent doesn't pay for it again.
- New or changed command → Project section below.
- Non-obvious decision or rejected alternative → one line in `.agents/DECISIONS.md`.
- Gotcha that cost time and will recur → "Gotchas" in the closest `AGENTS.md`.
- Focus, next steps or blockers changed → rewrite `.agents/STATE.md`.
- Don't write session diaries, changelogs (git has them) or what the code already says.

Put knowledge in the deepest file whose scope covers it. This file holds only repo-wide rules.

<!-- PROJECT-OWNED BELOW: filled by bootstrap; template upgrades never touch it (.agents/playbooks/upgrade.md). -->
## Project

- What: Folio, a native Android e-reader with an OPDS catalog. No app server or Folio accounts; optional
  per-catalog sign-in (HTTP Basic, `core/data/.../catalog/CatalogCredentials.kt`).
- Stack: Kotlin 2.4, Jetpack Compose, Hilt, Room; AGP 9.4, Gradle 9.8; minSdk 34, compileSdk/targetSdk 37.
  Own FB2/EPUB parser and page layout (no Readium), Apple Books–style UI. Areas: `.agents/MAP.md`.
  User-facing product description: `README.md`. Why this stack: `.agents/decisions/0004-prototype-engine.md`.
- Env: JDK 17 (JAVA_HOME → JDK), Android SDK platform 37 (AGP fetches missing SDK parts); versions in
  `gradle/libs.versions.toml`. First build needs Google Maven, Maven Central, Gradle Plugin Portal.

Commands (PowerShell, repo root; Linux/macOS: `bash ./gradlew`, see `scripts/AGENTS.md`):
- After implementation and test writing, build and share a local release APK before running checks;
  follow `scripts/AGENTS.md` → Phone APK first. This is standing authorization for local APK builds.
- Check before "done": `./scripts/build.ps1` (JVM tests, release APK, Android lint) + agent-lint:
  `& "$env:ProgramFiles\Git\bin\sh.exe" scripts/agent-lint.sh`. Docs-only change: agent-lint + link check.
- Setup / env check: `./scripts/doctor.ps1`
- JVM tests: `./gradlew.bat :core:opds:test :core:format:test :core:layout:testDebugUnitTest :core:data:testDebugUnitTest :core:speech:testDebugUnitTest`
  `:core:designsystem:testDebugUnitTest :feature:reader:testDebugUnitTest :feature:catalog:testDebugUnitTest :feature:settings:testDebugUnitTest --console=plain` (one command)
- Test one: `./gradlew.bat :core:opds:test --tests 'app.folio.core.opds.OpdsParserTest' --console=plain`
- Device checks: install the release APK and verify by hand (`scripts/AGENTS.md`); CI checks launch/crashes.
  - Bug in `core`: reproduce with a regression test first.
- A check you can't run: name the reason; never report it as passed.

## Project rules
- `core/model`, `core/format`, `core/opds` stay pure JVM; `core/layout` uses Android text/graphics, no Compose.
- No migrations, no compatibility with earlier versions; saved data may be wiped on update: `.agents/rules/storage.md`.
- HTTPS only, redirects included. Limits stay: book/unpacked FB2 50 MiB, OPDS feed 5 MiB, FB2 depth 128,
  no DTD or external XML entities. Don't relax them to make a sample pass.
- Coroutine cancellation is not a user error. No blocking I/O on the UI thread.
- Publish, push, release and deleting user data only on explicit user request.
- Behaviour change → update the affected `AGENTS.md`/rule and, if it changes the pitch, `README.md` (Russian promo overview, not a spec).
  Long rationale → `.agents/decisions/NNNN-title.md` + one line in `.agents/DECISIONS.md`.
- Don't commit `local.properties`, `.tools/`, `.gradle/`, `**/build/`, `artifacts/`, personal books, keys.
- UI: use the topic linked by the area's `AGENTS.md`; `.agents/design/apple-books-guidelines.md` is the index.
