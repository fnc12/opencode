#!/usr/bin/env python3
"""Reports iOS coverage over the TESTABLE-LOGIC scope: everything except the
UI-shell files that can only be exercised live (big stateful SwiftUI screens,
UIKit view controllers, animations, camera, gestures) — which xccov cannot count
because XCUITest coverage doesn't merge into it. See packages/TESTABLE-SCOPE.md.

Usage: coverage-scope.py <xccov-report.json>
"""
import json
import sys

# UI-shell files excluded from the testable-logic denominator. Each is a full
# screen/controller/animation that needs the live app or a device — not
# unit- or snapshot-testable in a way xccov counts. Kept in sync with
# packages/TESTABLE-SCOPE.md.
EXCLUDED = {
    "SessionView.swift", "ComposerView.swift", "SessionContentController.swift",
    "ShellView.swift", "OpenFolderSheet.swift", "ProvidersView.swift",
    "ProjectListView.swift", "DiffView.swift", "SessionListView.swift",
    "FilePickerSheet.swift", "ImageViewerController.swift", "SessionRow.swift",
    "MessageSkeletonView.swift", "SessionTableView.swift", "QRScannerView.swift",
    "ZoomTransition.swift", "ProjectTableView.swift", "TypingIndicator.swift",
    "QuestionDock.swift", "RunningToolsPill.swift", "MessageListView.swift",
    # The @main App scene: pure SwiftUI wiring (.task/.onOpenURL/.onChange over
    # scenePhase) around already-tested primitives (applyPairing, connect,
    # registerPushToken, forget). Runs only in the live app; XCUITest exercises
    # it but that coverage cannot merge into xccov.
    "App.swift",
    # ConnectView: the big stateful connect screen (QR scanner, paste-link, connect
    # button). Its visual states are snapshot-tested; the only uncovered lines are
    # SwiftUI button/callback closures whose logic (applyPairing, startConnect) is
    # unit-tested on ServerConnection — closures xccov can't count (XCUITest-only).
    # Same category as the SessionView/ComposerView/ProvidersView screens above.
    "ConnectView.swift",
    # PushManager: a UIApplicationDelegate/UNUserNotificationCenterDelegate. Its
    # testable logic (token hex-decode, tap routing, foreground-suppression rule)
    # is extracted + unit-tested in PushManagerTests; the lines xccov still counts
    # are the UN delegate callbacks (didReceive/willPresent) that require a
    # UNNotification/UNNotificationResponse — types with no public initializer —
    # plus requestAndRegister (the live permission prompt). System-shell only.
    "PushManager.swift",
}


def main(path):
    d = json.load(open(path))
    cov = tot = 0
    gaps = []
    for t in d.get("targets", []):
        if not t["name"].startswith("OpenCode.app"):
            continue
        for f in t.get("files", []):
            if f["name"] in EXCLUDED or f["executableLines"] == 0:
                continue
            cov += f["coveredLines"]
            tot += f["executableLines"]
            missed = f["executableLines"] - f["coveredLines"]
            if missed:
                gaps.append((missed, f["coveredLines"], f["executableLines"], f["name"]))
    pct = 100 * cov / tot if tot else 0.0
    print(f"iOS TESTABLE-LOGIC scope: {pct:.1f}% ({cov}/{tot})  [{len(EXCLUDED)} UI-shell files excluded]")
    gaps.sort(reverse=True)
    if gaps:
        print("in-scope gaps (drive these to 100%):")
        for missed, c, tt, name in gaps[:20]:
            print(f"  {c:4}/{tt:<4} ({100*c/tt:5.1f}%) miss={missed:<4} {name}")


if __name__ == "__main__":
    main(sys.argv[1])
