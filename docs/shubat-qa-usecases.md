# Shubat — QA Use Cases

The manual acceptance checklist for Shubat (OpenCode Remote): the native iOS/Android
apps + the relay backend (accounts, billing, onboarding). A tester walks these to
verify product quality before a release. Each case is **Given / When / Then**.

Legend: ✅ pass · ❌ fail · ⚠️ blocked. Prod relay: `https://relay.shubat.org`,
landing `https://shubat.org`.

---

## 1. Landing site (shubat.org)

| ID | Given / When / Then |
|----|---------------------|
| LAND-1 | **Given** a visitor opens shubat.org **When** the page loads **Then** hero, "$5/month" pricing, the 3 feature cards (push / native render / your keys), and the "Private by design" strip render correctly in both light and dark. |
| LAND-2 | **Given** PayPal is configured **When** the pricing section loads **Then** the PayPal **Subscribe** + **Debit/Credit Card** buttons render (not the "Join the beta" fallback). |
| LAND-3 | **Given** at least one push has been delivered **When** viewing the hero **Then** the live "N pushes delivered" counter shows and ticks up; **and** it stays hidden while the count is 0. |
| LAND-4 | **Given** fewer than 100 accounts exist **When** viewing pricing **Then** "N of 100 launch spots left" shows the correct remaining number, polled live from `/stats`. |
| LAND-5 | **Given** the relay is unreachable **When** the counters poll **Then** the page degrades silently (no errors, last value kept). |
| LAND-6 | **Given** a click on "Get started" **Then** the page scrolls to the pricing section. |

## 2. Onboarding & connector install

| ID | Given / When / Then |
|----|---------------------|
| ONB-1 | **Given** a valid access code **When** opening `/welcome?code=XXXX-YYYY` **Then** the page shows the one-line `curl … /i/<code> \| sh` install command with a working Copy button + 3 steps. |
| ONB-2 | **Given** an unknown/blank code **When** opening `/welcome` **Then** the code-entry form shows (no install command). |
| ONB-3 | **Given** a malformed code (e.g. `../etc/passwd`) **When** opening `/welcome?code=…` **Then** it falls back to the form, never echoing an install command. |
| ONB-4 | **Given** a fresh machine with OpenCode running **When** running the install command **Then** it prompts for OpenCode URL (default 127.0.0.1:4096) + password, installs the connector as a background service (launchd `org.shubat.connector` / systemd `shubat-connector.service`), and prints the pairing link (+ a scannable QR if `qrencode` is present). |
| ONB-5 | **Given** the connector installs **When** it claims the code via `POST /connector/claim` **Then** it persists identity to `~/.config/shubat/connector.json` and dials out to the relay (`GET /connector`, WebSocket) — no inbound ports opened. |
| ONB-6 | **Given** a claim code already redeemed **When** running the installer again **Then** the claim is refused ("invalid or already-used code"), not silently duplicated. |
| ONB-7 | **Given** the connector is running **When** `GET /healthz` on the relay **Then** the live tunnel count includes it. |

## 3. Pairing (app ↔ relay)

| ID | Given / When / Then |
|----|---------------------|
| PAIR-1 | **Given** a printed pairing QR (`opencode://pair?relay=…&tunnel=…&token=…`) **When** scanned in the app (Relay → Scan pairing QR) **Then** the app stores relay/tunnel/token and connects; projects load over the relay. |
| PAIR-2 | **Given** the pairing deep link **When** opened on the phone (paste-link path) **Then** the app parses relay/tunnel/token identically to the QR. |
| PAIR-3 | **Given** a paired app **When** it calls `/t/{id}/…` **Then** the relay proxies to the connector only when `X-Tunnel-Token` matches the tunnel's stored token; a wrong/missing token is rejected. |
| PAIR-4 | **Given** the connector's QR only reaches the service log **When** the installer runs **Then** the pairing link is still surfaced in the install terminal (paste-link always; QR when qrencode present). |

## 4. Accounts & authentication

### 4a. Email magic-link
| ID | Given / When / Then |
|----|---------------------|
| AUTH-1 | **Given** `/login` **When** a valid email is submitted **Then** "Check your email" shows and a one-time link email is sent (never revealing whether the account exists). |
| AUTH-2 | **Given** the magic link **When** opened within 15 min **Then** `/login/verify` redirects (303) to `/account` and sets a `shubat_session` cookie (HttpOnly, Secure, SameSite=Lax, 30d). |
| AUTH-3 | **Given** a used or expired token **When** opening the verify link **Then** a 400 "Link expired" page shows (link is single-use). |
| AUTH-4 | **Given** an invalid email format **When** submitted to `/login` **Then** the form re-renders with an error and no email is sent. |
| AUTH-5 | **Given** a signed-in session **When** `POST /logout` **Then** the session is revoked and `/account` redirects to `/login`. |

### 4b. GitHub sign-in
| ID | Given / When / Then |
|----|---------------------|
| AUTH-6 | **Given** GitHub is configured **When** `/login` renders **Then** a "Sign in with GitHub" button shows; **and** it is absent when unconfigured. |
| AUTH-7 | **Given** the GitHub button **When** clicked and authorized on GitHub **Then** `/auth/github/callback` creates/links an account by GitHub user id, opens a session, and lands on `/account`. |
| AUTH-8 | **Given** GitHub not configured **When** hitting `/auth/github` **Then** 503 "not configured"; `/setup/github` without the admin key → 403. |

### 4c. Google sign-in
| ID | Given / When / Then |
|----|---------------------|
| AUTH-9 | **Given** Google is configured **When** `/login` renders **Then** a "Sign in with Google" button shows. |
| AUTH-10 | **Given** the Google button **When** clicked and authorized (test user while in Testing mode) **Then** `/auth/google/callback` creates/links an account by the OIDC `sub`, opens a session, lands on `/account`. |
| AUTH-11 | **Given** Google not configured **When** hitting `/auth/google` **Then** 503; `/setup/google` without the admin key (query or form) → 403. |

### 4d. Identity linking
| ID | Given / When / Then |
|----|---------------------|
| AUTH-12 | **Given** the same email/provider identity **When** signing in twice **Then** the same account resolves (no duplicate account). |
| AUTH-13 | **Given** a subscription paid from a different email than the login **When** the signed-in user returns via `?subscription_id=` **Then** it binds to their account (login email ≠ PayPal email is supported). |

## 5. Billing & subscription (PayPal)

| ID | Given / When / Then |
|----|---------------------|
| BILL-1 | **Given** the Subscribe button **When** a buyer approves a $5/mo subscription **Then** `onApprove` redirects to `/welcome?subscription_id=…`. |
| BILL-2 | **Given** `BILLING.SUBSCRIPTION.ACTIVATED` (verified webhook) **When** received at `/paypal/webhook` **Then** a tunnel is minted for that subscription and `/welcome?subscription_id=` shows the install command. |
| BILL-3 | **Given** the webhook hasn't landed yet **When** `/welcome?subscription_id=` is opened **Then** an auto-refreshing "Activating…" page shows (not the form). |
| BILL-4 | **Given** a webhook with a bad/failed signature **When** posted to `/paypal/webhook` **Then** 400 and no tunnel is minted. |
| BILL-5 | **Given** a webhook retry for the same subscription **When** re-delivered **Then** it's idempotent (no second tunnel). |
| BILL-6 | **Given** `BILLING.SUBSCRIPTION.CANCELLED/EXPIRED/SUSPENDED` **When** received **Then** the subscription's tunnel is revoked and any live connection is closed. |
| BILL-7 | **Given** a returning subscriber whose connector already installed **When** re-opening `/welcome?subscription_id=` **Then** a "You're connected" re-pair page shows (QR + link), **not** the infinite "Activating…" spinner. |

## 6. Entitlements (first-100 / referral / promo / free access)

| ID | Given / When / Then |
|----|---------------------|
| ENT-1 | **Given** fewer than 100 accounts **When** a new account is created **Then** it gets one free month (`free_until`); `/account` shows "Free until DATE". |
| ENT-2 | **Given** the 100-account cap reached **When** a new account is created **Then** it gets no free month; `/stats` shows 0 free spots left. |
| ENT-3 | **Given** an account's referral link `/login?ref=CODE` **When** a new user signs up through it **Then** the referrer gets +1 month and their `/account` "friends joined" count increments. |
| ENT-4 | **Given** the referral cap (12 months) **When** more than 12 referrals sign up **Then** only 12 months are credited; all referrals are still recorded. |
| ENT-5 | **Given** self-referral or an unknown code or an already-referred account **When** applied **Then** no credit is given. |
| ENT-6 | **Given** a valid promo code **When** submitted at `/account/promo` **Then** its months are granted once per account, with a success flash; a second attempt flashes "already used". |
| ENT-7 | **Given** a promo at its max-uses or past expiry **When** redeemed **Then** it's refused ("no uses left" / "not valid"). |
| ENT-8 | **Given** an entitled account with no tunnel **When** clicking "Connect your OpenCode" on `/account` (`POST /account/connect`) **Then** a free account-owned tunnel is minted and the install command shows; connecting again is idempotent. |
| ENT-9 | **Given** a non-entitled account (no free time, no paid sub) **When** hitting `/account/connect` **Then** it's refused with a "subscribe or add a promo" flash. |
| ENT-10 | **Given** a free tunnel whose account's free period lapsed and has no paid sub **When** the hourly reaper runs **Then** the free tunnel is revoked and the connection closed; paid tunnels and free tunnels of paying accounts are untouched. |

## 7. Account page & management

| ID | Given / When / Then |
|----|---------------------|
| ACC-1 | **Given** a signed-in account **When** opening `/account` **Then** it shows plan status (Subscription active / Free until DATE / no plan), the connection state (re-pair QR or install command or Connect button), a referral link + count, and a promo form. |
| ACC-2 | **Given** a connected (claimed) account **When** viewing `/account` **Then** a scannable re-pair QR + pairing link are shown so a reinstalled app/new phone reconnects without a new code. |
| ACC-3 | **Given** no session **When** hitting `/account` or `/account/promo` or `/account/connect` **Then** it redirects to `/login`. |

## 8. Relay proxy, admin & infra

| ID | Given / When / Then |
|----|---------------------|
| INF-1 | **Given** the admin secret **When** `POST /admin/tunnels` **Then** a tunnel is minted; without `X-Admin-Secret` it's rejected. `GET/DELETE /admin/tunnels` behave accordingly. |
| INF-2 | **Given** device registration **When** `POST/DELETE /api/devices` **Then** APNs/FCM tokens are stored/removed for push. |
| INF-3 | **Given** a push is delivered **When** it succeeds **Then** the durable counter increments (survives a relay restart) and `/stats` reflects it. |
| INF-4 | **Given** a dead push token **When** a push fails with token-gone **Then** the device is pruned. |
| INF-5 | **Given** the relay restarts **When** it boots **Then** it loads `provision.db` (SQLite), enables PayPal (live), accounts (SMTP), and push; the legacy `tunnels.json` was imported once. |
| INF-6 | **Given** `GET /healthz` **Then** it returns `{status:ok, tunnels:N}`. |

## 9. End-to-end scenarios

| ID | Given / When / Then |
|----|---------------------|
| E2E-1 | **Paid path:** subscribe ($5) → `/welcome` install command → run installer on the OpenCode machine → scan pairing QR in the app → a session runs and streams; cancel the subscription → access is revoked. |
| E2E-2 | **Free path:** sign up (one of the first 100) → `/account` shows Free-until → Connect → install → pair → use; after the free month lapses (no paid sub) → the reaper revokes access. |
| E2E-3 | **Referral path:** user A shares `/login?ref=CODE` → user B signs up → A gains +1 month (visible on A's `/account`). |
| E2E-4 | **Re-pair path:** a connected user reinstalls the app → signs in / opens `/account` → scans the re-pair QR → reconnects without a new code. |
| E2E-5 | **Multi-provider identity:** sign in with email, then GitHub, then Google using the same verified email → consistent account resolution and access.

---

## 10. Mobile app (iOS & Android)

Apps are feature-mirrored (iOS SwiftUI + UIKit message list; Android Compose +
RecyclerView islands). Cases are **[both]** unless a platform is noted. Most
features have automated tests (`OpenCodeTests`/`OpenCodeUITests`; `src/test` +
`src/androidTest`) — cross-reference when a case regresses.

### 10a. Connect & pairing
| ID | Given / When / Then |
|----|---------------------|
| MOB-CONN-1 | **Given** a disconnected app **When** launched **Then** the Connect screen shows with a **Relay / Direct** segmented control. |
| MOB-CONN-2 | **Given** Relay mode **When** Relay URL + Tunnel ID + Token are entered and Connect tapped **Then** the app connects (base `relayURL/t/{tunnelID}`) and the project list loads. |
| MOB-CONN-3 | **Given** Direct mode **When** Server URL (+ optional password) entered and Connect tapped **Then** the app connects directly. |
| MOB-CONN-4 | **Given** Relay mode **When** "Scan pairing QR" is tapped and an `opencode://pair?…` QR is scanned **Then** relay/tunnel/token fill in and it connects. |
| MOB-CONN-5 | **Given** a pairing link **When** pasted into the paste field and applied **Then** the fields fill identically to the QR. **[iOS]** (Android: via QR/deep link). |
| MOB-CONN-6 | **Given** an `opencode://pair?relay=…&tunnel=…&token=…` deep link **When** opened on the phone **Then** the app auto-fills and connects. |
| MOB-CONN-7 | **Given** the Connect form **When** the config is incomplete **Then** Connect is disabled; while connecting, inputs are locked with a spinner; a failure shows inline error text. |
| MOB-CONN-8 | **Given** a previously-saved config (Keychain / EncryptedSharedPreferences) **When** the app launches **Then** it auto-connects. |
| MOB-CONN-9 | **Given** a device whose camera scanner is unavailable **When** opening the QR scanner **Then** a graceful "scanning unavailable" fallback shows. **[iOS VisionKit]** |
| MOB-CONN-10 | **Given** a connected app **When** Disconnect is tapped (Projects top bar) **Then** it returns to Connect; the saved config persists. |

### 10b. Navigation & projects
| ID | Given / When / Then |
|----|---------------------|
| MOB-NAV-1 | **Given** a connection **When** navigating **Then** Projects → Sessions → Session works, and the back gesture walks the same path. |
| MOB-NAV-2 | **Given** the project list **When** "Open Folder" is used **Then** the server filesystem browser opens and picking a folder starts a new session. |

### 10c. Session management
| ID | Given / When / Then |
|----|---------------------|
| MOB-SESS-1 | **Given** a project **When** the session list loads **Then** sessions sort by last-updated, and a "Working…" indicator shows on actively-generating sessions (with a ~3-min staleness cutoff). |
| MOB-SESS-2 | **Given** the session list **When** "New session" is tapped (toolbar or empty state) **Then** a new session opens. |
| MOB-SESS-3 | **Given** a session row **When** tapped **Then** the session opens/resumes with its transcript. |
| MOB-SESS-4 | **Given** a session **When** renamed (iOS swipe/context menu · Android long-press) **Then** the new title persists. |
| MOB-SESS-5 | **Given** a session **When** deleted **Then** it's removed from the list. |
| MOB-SESS-6 | **Given** a session **When** Share / Stop sharing is used **Then** a public share link is created/removed and the system share sheet opens. |
| MOB-SESS-7 | **Given** a generating session **When** Stop/Abort is tapped **Then** generation halts (send↔stop toggle). |
| MOB-SESS-8 | **Given** a message **When** Revert/Restore is confirmed **Then** that message and everything after (incl. file changes) is undone/redone, with a revert banner. |
| MOB-SESS-9 | **Given** no data **Then** "No projects yet" / "No sessions yet" empty states show. |

### 10d. Messaging & rendering
| ID | Given / When / Then |
|----|---------------------|
| MOB-MSG-1 | **Given** a session **When** a prompt is sent **Then** the reply streams in over SSE, with connecting/live/reconnecting status and automatic reconnect/backoff. |
| MOB-MSG-2 | **Given** streamed content **Then** markdown (bold/italic/inline code/links/headers), syntax-highlighted code blocks, colored diffs (add/delete), and GFM tables render correctly. |
| MOB-MSG-3 | **Given** reasoning content **Then** it collapses to a one-line "💭 Thinking" marker, expandable in message detail. |
| MOB-MSG-4 | **Given** tool calls **Then** each renders as verb + target + status glyph (✓/…/✕/◷) with the right extras (diff `+N −M`, grep match count, todo ratio, error snippet); detail shows per-tool output (edit→diff, read→highlighted file, todowrite→checklist, else clean monospace). |
| MOB-MSG-5 | **Given** an IDE file reference or a patch part **Then** a "📎 file:line" chip / "⌥ Patch — N files" renders. |
| MOB-MSG-6 | **Given** an inline image (pasted/attached) **When** tapped **Then** it opens the full-screen viewer; a non-image falls back to a filename chip. |
| MOB-MSG-7 | **Given** unrenderable or empty content **Then** a plain-text fallback shows and truly empty messages are filtered out. |
| MOB-MSG-8 | **Given** a message **When** long-pressed / context-menu → Copy (or Copy all in detail) **Then** its text lands on the clipboard. |
| MOB-MSG-9 | **Given** a message row **When** tapped **Then** it zooms to a full message-detail view. |
| MOB-MSG-10 | **Given** a long session **When** scrolling up **Then** older pages load (newest first); reopening the session shows cached messages instantly. |
| MOB-MSG-11 | **Given** a cold load / active generation **Then** a skeleton shimmer / typing dots show; each assistant message shows a token summary. |

### 10e. Permissions & questions
| ID | Given / When / Then |
|----|---------------------|
| MOB-PERM-1 | **Given** the agent requests a permission **Then** a dock shows "Permission needed" + summary with **Reject / Always / Allow (once)**; the agent stays blocked until answered; multiple requests stack. |
| MOB-PERM-2 | **Given** the agent asks a question **Then** a question dock rides the transcript: single-select (radio) or multi-select; multi-question shows swipeable slides with progress dots + auto-advance; **Skip / Submit** work. |

### 10f. Composer & attachments
| ID | Given / When / Then |
|----|---------------------|
| MOB-COMP-1 | **Given** the composer **Then** it grows multi-line, supports keyboard dictation, and shows a "Message" placeholder. |
| MOB-COMP-2 | **Given** the "+" menu **Then** Photo / File / Command options show (Command only when commands exist). |
| MOB-COMP-3 | **Given** the photo picker **When** up to 4 images are chosen **Then** they attach as thumbnails (removable) and send as image parts. |
| MOB-COMP-4 | **Given** the file picker **When** a server file is chosen **Then** it attaches as a context chip (removable). |
| MOB-COMP-5 | **Given** slash commands exist **When** one is picked **Then** it runs and its expansion streams back. |
| MOB-COMP-6 | **Given** the agent + model selectors **Then** selecting build/plan/custom and a provider→model works, and the last-used choices are remembered. |
| MOB-COMP-7 | **Given** the shell runner **When** a command is run **Then** output logs, persists per session, and Clear resets it. |
| MOB-COMP-8 | **Given** a todo list **When** the "Tasks n/m" pill is tapped **Then** the full checklist shows. |

### 10g. Push notifications
| ID | Given / When / Then |
|----|---------------------|
| MOB-PUSH-1 | **Given** a connected app **Then** its APNs (iOS) / FCM (Android) token registers with the relay (`/api/devices`). |
| MOB-PUSH-2 | **Given** a session goes idle or needs a permission **Then** a push is delivered. |
| MOB-PUSH-3 | **Given** a push **When** tapped **Then** the app deep-links to that session with the nav path rebuilt (Back works). |
| MOB-PUSH-4 | **Given** the session is already on screen **Then** its push is suppressed; a push for another session shows a foreground banner. |
| MOB-PUSH-5 | **Given** Android 13+ **When** first launched **Then** the POST_NOTIFICATIONS runtime permission is requested. **[Android]** |
| MOB-PUSH-6 | **Given** returning to foreground **Then** stale SSE reconnects and data re-fetches (live re-sync). |

### 10h. Providers, viewers & resilience
| ID | Given / When / Then |
|----|---------------------|
| MOB-PROV-1 | **Given** the Providers screen **Then** API-key providers list with a green ✓ when configured; a key can be pasted and removed; a note says OAuth (ChatGPT/Copilot) needs desktop. |
| MOB-VIEW-1 | **Given** the diff viewer **Then** files list with ±counts and status icons; each opens a selectable colored diff with a Copy button; "No changes" empty state shows. |
| MOB-VIEW-2 | **Given** the image viewer **Then** pinch/double-tap zoom, drag pan, swipe-to-dismiss, and Save-to-gallery + share work. |
| MOB-RES-1 | **Given** a running tool **Then** a one-line "background processes" strip shows spinner + tool name + elapsed (count badge if >1), only while a tool actually runs. |
| MOB-RES-2 | **Given** ~3 min with no output **Then** a "This turn looks stuck — tap ■ to cancel" hint replaces the typing dots. |
| MOB-RES-3 | **Given** the session top bar **Then** a stream status badge shows green (Live) / spinner (connecting/reconnecting). |
| MOB-RES-4 | **Given** a load/connect error **Then** an in-place Retry (or error text) shows; retrying recovers. |

---

## 11. Automated coverage

The **backend** cases (§1–9) run as Go tests on every commit —
`go test ./packages/relay/...`. This is the automated QA harness that walks the
checklist; a regression fails CI. Mapping (QA area → test):

| QA area | Automated by (`packages/relay/internal/relayserver/…` unless noted) |
|---------|--------------------------------------------------------------------|
| **E2E-1 paid journey** (mint → welcome → claim → connector register → proxy → cancel → revoke) | `qa_e2e_test.go` · `TestQA_E2E_PaidPath` |
| **E2E-2 free journey** (first-100 → connect → claim → register → proxy → reaper) | `qa_e2e_test.go` · `TestQA_E2E_FreePath` |
| Proxy round-trip & token gating (PAIR-3) | `server_test.go` · `TestProxyRoundTrip`, `TestProxyRejectsWrongToken` |
| Welcome / re-pair (ONB-1..3, BILL-3/7) | `welcome_test.go` |
| Email magic-link (AUTH-1..5) | `auth_test.go` |
| GitHub sign-in (AUTH-6..8) | `github_test.go` |
| Google sign-in (AUTH-9..11) | `google_test.go` |
| PayPal billing (BILL-2/4/5/6) | `paypal_test.go`, `qa_e2e_test.go` |
| Entitlement: first-100/promo/referral/connect (ENT-1..9, E2E-3) | `entitlement_test.go`, `auth_test.go` (`TestReferralViaLoginLink`, `TestPromoRedeemFlow`, `TestAccountConnectFree`) |
| Reaper (ENT-10) | `reaper_test.go`, `qa_e2e_test.go` |
| Stats / live counters (LAND-3/4, INF-3/4) | `stats_test.go`, `internal/push/counter_test.go` |
| Store durability + JSON→SQLite migration (INF-5) | `internal/provision/store_contract_test.go` |
| Admin mint / devices (INF-1/2) | `server_test.go`, `internal/push/*_test.go` |

### Mobile (§10)

Most mobile cases are **already automated** by the apps' existing suites (the
work behind ~97.7% iOS / ~94.9% Android coverage): iOS `OpenCodeTests` (unit +
snapshot) + `OpenCodeUITests` (XCUITest, driven by `UITEST_*` / `PAIR_LINK`
launch hooks + `URLProtocol` fake server); Android `src/test` (unit) +
`src/androidTest` (instrumented, `MockWebServer` + `UiTestFlags`). They run on
the self-hosted Apple / Android runners (heavier than the Go suite).

| Mobile QA | iOS test(s) | Android test(s) |
|-----------|-------------|-----------------|
| Connect & pairing (MOB-CONN) | `ConnectionConfigTests`, `ConnectViewSnapshotTests`, `ServerConnectionFakeTests` | `AppViewModelInstrumentedTest` (applyPairing/config/disconnect/cancel), `QrScanInstrumentedTest` |
| Navigation & projects (MOB-NAV) | `OpenFolderUITests` | `ListScreensInstrumentedTest`, `AppViewModelInstrumentedTest` |
| Sessions: busy/rename/delete/share (MOB-SESS) | `SessionBusyTests`, `SessionStoreTests` | `SessionListAdapterInstrumentedTest`, `SessionListScreenInteractionsInstrumentedTest`, `ShareChooserInstrumentedTest` |
| Messaging & rendering (MOB-MSG) | `MarkdownRendererTests`, `DiffRendererTests`, `SyntaxHighlighterTests`, `CodeBlockViewTests`, `MessageCell/DetailSnapshotTests`, `MessageRenderPipeline/RenderingTests`, `MessageCacheTests`, `EventStreamTests`, `LiveSessionIntegrationTests`, `StreamingTests` | `MessageRenderBranchesInstrumentedTest`, `MessageAdapterImageInstrumentedTest`, `SessionStreamInstrumentedTest` |
| Permissions & questions (MOB-PERM) | `PermissionTests`, `QuestionTests`, `PermissionDockUITests`, `QuestionDockUITests`, `QuestionDockLayoutUITests` | `PermissionDockUITest` |
| Composer & attachments (MOB-COMP) | `ComposerTests`, `ComposerSendUITests`, `GrowingTextViewTests`, `ShellStoreTests`, `ShellTests` | `ShellScreenInstrumentedTest`, `DialogScreensInstrumentedTest` (file picker), `ActivitySoftInputModeTest` |
| Push (MOB-PUSH) | `PushManagerTests` | `AppViewModelInstrumentedTest` (pending open session) |
| Providers / viewers / resilience (MOB-PROV/VIEW/RES) | `DiffRendererTests`, `RunningToolsTests` | `DialogScreensInstrumentedTest` (providers), `ImageViewerInstrumentedTest`, `ListScreensInstrumentedTest` (empty/error) |

**Gaps to add (mobile phase-2 backlog)** — cases without a clear dedicated test:
MOB-CONN-3 (Direct-mode connect UI), MOB-CONN-9 (scanner-unavailable fallback),
MOB-SESS-8 (revert/restore UI flow), MOB-MSG-8 (copy-message UI), MOB-VIEW-1
(diff-viewer file list + Copy), MOB-RES-2 (stuck-turn hint), MOB-PUSH tap→deep-link
on Android. These are the next mobile tests to write.

**Live onboarding smoke (real relay + real connector + real OpenCode, no mocks):**
`scripts/qa-onboarding-live.sh <CLAIM_CODE>` stands up an isolated connector on a
host running OpenCode, has it claim a real code and register with the relay, and
verifies an app-facing request reaches OpenCode through the relay (wrong token →
401). The account/email half (POST `/login` → open the magic link → `/account` →
Connect → get the code) is real too; automating its inbox read needs a Gmail
token. This whole chain was verified live end-to-end (real email delivered & read,
real relay, real connector, real OpenCode reachable).

**How to run the automated QA suite:**
```sh
# backend unit + E2E journeys (fast, every commit)
cd packages/relay && go test ./...
# live onboarding smoke (real components)
scripts/qa-onboarding-live.sh <CLAIM_CODE>
# mobile (on the runners)
# iOS:     xcodebuild test -scheme OpenCode -destination 'platform=iOS Simulator,name=iPhone 15'
# Android: ./gradlew :app:connectedAndroidTest
```
