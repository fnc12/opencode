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
RunningToolsPill.

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

Measure: `./gradlew :app:jacocoMergedReport` on a booted API-35 emulator (only one
device connected), then parse `app/build/reports/jacoco/jacocoMergedReport/…xml`.

## Status (2026-08-20)

- **iOS testable-logic scope: 68.8%** (2827/4107, 20 UI-shell files excluded).
- **Android merged: 60.7%** (still climbing; UI countable, so target is 100%).
