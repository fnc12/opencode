# Testable-coverage scope (the "100%" target)

The goal is **100% coverage of the testable logic**, plus snapshot/instrumented
coverage of the screens — not a literal 100% of every executable line. This file
defines exactly what's in and out, and why, so "done" is checkable.

## Why not literal 100%

- **iOS has a hard ceiling:** XCUITest coverage **cannot be merged into `xccov`**.
  So the UI shell of the big stateful SwiftUI screens (SessionView, ComposerView,
  …) — layout, keyboard handling, bindings, gestures, entrance animations — is
  not countable by the coverage tool at all. It's exercised live by XCUITest, but
  those lines never show as covered. Driving them to 100% via xccov is impossible
  in principle, not just slow.
- **Android does not have this ceiling:** the `jacocoMergedReport` merges unit +
  instrumented (emulator) coverage, so its UI *is* countable. Android's target is
  therefore 100% of the merged report minus only generated code and truly
  device-only shells.

## iOS — excluded from the denominator (UI shell, uncountable via xccov)

Full screens / controllers / animations that need the live app or a device and
are not unit- or snapshot-testable in a way xccov counts. Kept in sync with
`packages/ios/scripts/coverage-scope.py`:

SessionView, ComposerView, SessionContentController, ShellView, OpenFolderSheet,
ProvidersView, ProjectListView, DiffView, SessionListView, FilePickerSheet,
ImageViewerController, SessionRow, MessageSkeletonView, SessionTableView,
QRScannerView, ZoomTransition, ProjectTableView, TypingIndicator, QuestionDock,
RunningToolsPill, App (the @main scene — pure `.task`/`.onOpenURL`/`.onChange`
wiring over already-tested primitives; runs only in the live app), ConnectView
(big stateful connect screen — snapshot-tested visuals, interaction closures are
XCUITest-only and their logic is unit-tested on ServerConnection), PushManager
(UIApplicationDelegate/UN delegate — its logic is extracted + unit-tested; the
remaining lines are UN callbacks needing an uninitializable `UNNotification`).

Reasons by class: **big stateful screens** (SessionView/ComposerView/…) need the
live app; **UIKit controllers/animators** (SessionContentController, ZoomTransition,
*TableView) need a running view hierarchy; **animations** (TypingIndicator,
MessageSkeletonView, RunningToolsPill's TimelineView, QuestionDock's entrance
`@State appeared`) render blank/non-deterministically in a static snapshot;
**camera** (QRScannerView); **relative-time rows** (SessionRow) are
non-deterministic.

Everything else is **in scope** and must reach 100% — models, API, `ServerConnection`,
`ServerEvent`, `EventStream`, stores, `MarkdownRenderer`/`SyntaxHighlighter`/
`DiffRenderer`/`ToolDisplay`, the message **render pipeline**, and the
snapshot-testable views (MessageDetailView, MessageCell, TableBlockView,
CodeBlockView, PermissionDock, TodoDock static parts).

Measure: `xcrun xccov view --report --json X.xcresult | python3 packages/ios/scripts/coverage-scope.py -`.

**Mixed files** (a view file that also holds testable logic — e.g. `MessageListView`
mixes the render pipeline with the UITableView coordinator): the plan is to
**extract the logic into its own file** so it counts and the UI shell is excluded,
rather than leave the file capped below 100%.

## Android — excluded

Generated code + device-only shells only: `*.databinding.*`, `BuildConfig`, `R`,
`MainActivity*`, `push/ShubatMessagingService*`, `ComposableSingletons*`.
Everything else (incl. Compose UI, adapters, view models) is countable via the
merged report and must reach 100%.

**Practical streaming/window ceiling** (countable, but needs harnesses beyond
unit + single-screen instrumented tests). NOTE: `ImageViewer.kt`'s
`ZoomableImageView` gestures + `saveToGallery` are now COVERED via a real-Activity
multi-touch harness (`createAndroidComposeRule<ComponentActivity>` +
`dispatchTouchEvent`). Still uncovered: `ShellScreen`'s terminal needs a live PTY
stream; `SessionViewModel.start`'s SSE reconnect loop
is infinite by design and flakes any unit test that drives it against real
dispatchers (a flaky test is worse than an uncovered branch); and the deepest
`SessionScreen` composer flows
(send-with-attachments through the picker, `readFile` on file-attach) need
multi-step live flows. These stay uncovered pending a dedicated Activity/gesture
harness — they are the reason Android's merged number plateaus below 100%.

Measure: `./gradlew :app:jacocoMergedReport` on a booted API-35 emulator (only one
device connected), then parse `app/build/reports/jacoco/jacocoMergedReport/…xml`.

## Status (2026-08-20)

_Latest: iOS 97.6% of testable-logic scope, Android 94.4% merged. Both suites green; remaining gaps are the gesture/streaming/nav ceiling documented above._

- **iOS testable-logic scope: 97.6%** (was 68.8%; 24 UI-shell files excluded —
  App, ConnectView, PushManager added). Render pipeline extracted to
  `MessageRenderer`; ServerConnection (incl. trackSessionActivity via an
  injectable EventStream session), SessionStore, GrowingTextView, ToolOutputView,
  the whole model layer, EventStream, and MessageDetailView driven up with
  fake-server/unit/snapshot tests. The ~3% remainder is an xccov floor:
  defensive `invalidURL` guards (unreachable with a valid config), the
  `cancelConnect` body (needs a mid-flight hang), and MessageDetailView's
  `toggleThinking`/`CodeBlockRepresentable` (SwiftUI @State/Context — XCUITest
  territory that can't merge into xccov).
- **Android merged: 94.4%** (was 60.7%; UI countable via JaCoCo merge). Cracked
  two long-standing blockers: (1) the NetworkOnMainThread on instrumented screen
  tests was building `ServerConnection` inside `setContent` (server.url() does
  reverse-DNS on the Compose main thread) — fixed by hoisting it to the test
  thread; (2) Paparazzi record is broken on this machine's JDK 22 (ByteBuddy
  can't retransform), so View-render branches are covered by instrumented tests
  instead. Now instrumented: self-fetch screens, dialog screens, SessionScreen
  composer/dock interactions (+ menus/dialogs via clock advance), OpenFolderBrowser,
  ConnectScreen, MessageViewHolder + SessionListAdapter listeners, and the AppNav
  host, the ImageViewer/ZoomableImageView gestures (pinch/pan/double-tap/
  dismiss) via a real-Activity multi-touch harness, ShellScreen (request/response),
  and every wire-model accessor + ServerConnection endpoint. Remaining ~6%: the
  live SSE reconnect loop and the deepest SessionScreen composer flows
  (send-with-attachments through the picker, readFile) — multi-step live streaming
  flows — plus JaCoCo's `withContext(Dispatchers.IO)` suspend-boundary artifacts
  (the enclosing methods are tested) and two @Ignore'd RecyclerView-in-Compose
  rename/delete tests that flake under full-suite CPU load (covered indirectly by
  `SessionListAdapterInstrumentedTest`).
