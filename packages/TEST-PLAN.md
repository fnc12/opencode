# Mobile test plan — full coverage without a QA team

The goal: **every screen, state, and branch covered by automated tests that run
locally on the Mac** (no CI, no manual testers). This file is the map; the
runner is `packages/mobile-test.sh`.

## Strategy (the testing pyramid, applied)

Four layers, cheapest first. Each layer covers what the layer above can't reach
cheaply — this is how small teams get high coverage without testers.

| Layer | Tool (iOS / Android) | Covers | Speed |
|---|---|---|---|
| **1. Unit** | XCTest / JUnit | pure logic: parsing, rendering-to-model, reducers, every `if/else/loop` in a function | ms |
| **2. Integration (fake server)** | `URLProtocol` stub / `MockWebServer` | networking, `ServerConnection`, `SessionStore`/`ViewModel`, SSE `EventStream` — the connect→load→stream→resolve flow, offline & deterministic | 10s ms |
| **3. Snapshot** | swift-snapshot-testing / Paparazzi | every screen & component in every state (light/dark, dynamic type, RTL, empty/loading/error/long) — visual regressions, layout, the docks | 100s ms |
| **4. E2E UI** | XCUITest / Espresso | a few critical end-to-end journeys against the fake server (connect, send, answer a question, Stop) | seconds |

Coverage is **measured** (Xcode xccov + Kover) and driven to the max: a red
branch in the report = a missing test. Baseline at start of this effort:
**iOS 16.5% lines, Android 15.4% lines / 19.7% branches** — almost all the gap
is UI (0%) that layers 2–3 close.

## Coverage map — every screen/module → owning layer

Legend: ✅ has tests · 🟡 partial · ⬜ none yet. Layer = where the coverage
should come from.

### iOS

| Module | Layer | Status | Key states / branches to cover |
|---|---|---|---|
| `MarkdownRenderer` | 1 unit | ✅ | headings, bold/italic/code, lists, links, tables, empty |
| `DiffRenderer` | 1 unit | ✅ | add/del/context hunks, binary, rename, empty |
| `SyntaxHighlighter` | 1 unit | ✅ | known langs, unknown lang fallback, empty |
| `ToolDisplay`/`PatchDisplay`/`FileRefDisplay` | 1 unit | ✅ | every tool verb, badges, missing metadata |
| `MessageListView` render (blocks) | 1 unit | 🟡 | text/table/code/**image** blocks, tool/patch/file parts, reasoning collapse, synthetic/ignored hidden, compaction, step-start |
| `RunningTools.extract` | 1 unit | ✅ | running vs completed, `question` excluded, finished turn |
| `MessageCache` | 1 unit | ✅ | round-trip, corrupt, miss |
| `PartContent`/`MessagePart` decode | 1 unit | ✅ | every part type, v1/v2, unknown |
| `ServerEvent` decode | 1 unit | ✅ | idle/permission/question v1+v2, part delta, unknown |
| `ServerConnection` | 2 fake | ⬜ | auth header, 401/500, relay token, cursor paging, createSession, abort, reply endpoints |
| `SessionStore` | 2 fake | 🟡 | setInitial, prependOlder dedupe, busy tracking, revert |
| `EventStream` (SSE) | 2 fake | 🟡 | frame split, reconnect/backoff, cancel |
| `ConnectView` | 3 snap + 4 e2e | 🟡 | direct/relay/QR modes, error, connecting |
| `ProjectListView` / `ProjectRow` | 3 snap | ⬜ | empty, list, busy badge |
| `SessionListView` / `SessionRow` | 3 snap | ⬜ | empty, list, working spinner, stale |
| `SessionView` | 3 snap + 4 e2e | ⬜ | loading skeleton, live, streaming, error, docks visible |
| `ComposerView` | 3 snap + 4 e2e | 🟡 | empty/typing, 1-vs-2 line grow, attachments, model picker, Stop-vs-Send |
| `QuestionDock` | 3 snap | 🟡 | single, multi-slide, custom, submit enabled/disabled |
| `PermissionDock` | 3 snap | 🟡 | single, batched |
| `TodoDock` | 3 snap | ⬜ | pill, expanded, statuses |
| `RunningToolsPill` | 3 snap | ⬜ | one/many tools, elapsed |
| `MessageDetailView` | 3 snap | ⬜ | thinking card, tool cards, selectable text |
| `ImageViewerController` / zoom / swipe-dismiss | 3 snap + 4 e2e | ⬜ | fit, zoomed, share, dismiss |
| `DiffView` | 3 snap | ⬜ | changed files list, hunked diff, no-diff |
| `ShellView` | 3 snap + 4 e2e | ⬜ | prompt, running, output, error |
| `OpenFolderSheet` / `FilePickerSheet` | 3 snap | ⬜ | path entry, browse, error |
| `ProvidersView` | 3 snap | ⬜ | list, add key, delete |
| `MessageSkeletonView` / `TypingIndicator` | 3 snap | ⬜ | shimmer, dots |

### Android (mirror of iOS)

| Module | Layer | Status | Notes |
|---|---|---|---|
| `MessageAdapter` render + `MsgBlock` | 1 unit | 🟡 | mirror MessageListView render tests |
| `MarkdownRenderer` | 1 unit | ⬜ | **0% now** — pure logic, easy win |
| `ToolOutput` / `ToolDisplay` / `PatchDisplay` | 1 unit | 🟡 | mirror iOS |
| `RunningTools` | 1 unit | ✅ | done |
| `MessageCache` | 1 unit | ✅ | done |
| `Message`/`PartContent`/`ServerEvent` decode | 1 unit | ✅ | done |
| `ServerConnection` | 2 fake (MockWebServer) | 🟡 | expand: paging, abort, reply, relay token, errors |
| `SessionStore` / `SessionViewModel` | 2 fake | 🟡 | stream loop, prependOlder, busy, refresh |
| `EventStream` | 2 fake | 🟡 | frame split, reconnect, cancel |
| `AppNav` | 1 unit + 4 e2e | 🟡 | rotation saver ✅; back-stack, deep-link, connected/not |
| `ConnectScreen` | 3 snap (Paparazzi) | ⬜ | modes, error |
| `ProjectListScreen`/`Adapter` | 3 snap | ⬜ | empty, list, busy |
| `SessionListScreen`/`Adapter` | 3 snap | ⬜ | empty, list, working |
| `SessionScreen` | 3 snap + 4 e2e | ⬜ | **410 lines 0%** — loading, live, docks |
| Composer (in SessionScreen) | 3 snap + 4 e2e | ⬜ | grow, attachments, picker, Stop/Send |
| `QuestionDock` / slides | 3 snap | 🟡 | mirror iOS |
| `PermissionDock` | 3 snap | 🟡 | mirror iOS |
| `MessageDetailScreen` | 3 snap | ⬜ | cards, selection |
| `ImageViewer` / `ZoomableImageView` | 3 snap + 4 e2e | ⬜ | fit, zoom, swipe-dismiss |
| `ProvidersScreen` | 3 snap | ⬜ | list, add, delete |
| `ShellScreen` | 3 snap + 4 e2e | ⬜ | prompt/run/output |
| `MessageSkeleton` | 3 snap | ⬜ | shimmer |

## Shared state matrix (apply to every screen snapshot)

Each screen is snapshotted across the axes that actually break layout:
`{ light, dark } × { default, XXL dynamic type } × { LTR, RTL } × { empty, populated, long/overflow, error }`.
Not every combination for every screen — the axis is chosen per screen (e.g. the
question dock gets `single/multi/custom`; a list gets `empty/populated/long`).

## Order of work (highest coverage-per-hour first)

1. **Coverage machine** ✅ (Kover + xccov + `mobile-test.sh`).
2. **Pure-logic gaps** — MarkdownRenderer (Android 0%), ToolOutput, MessageList/Adapter render branches. Cheap, fast, no infra.
3. **Fake server** (layer 2) — unlocks ServerConnection + Store/ViewModel + EventStream on both platforms (the ~15% → ~50% jump in testable logic).
4. **Snapshot infra** (layer 3) — Paparazzi + swift-snapshot-testing; then one snapshot test per row of the map above × the state axes. This closes the 0% UI.
5. **A few E2E journeys** (layer 4) against the fake server.
6. Re-run coverage, chase remaining red branches to the wall.
