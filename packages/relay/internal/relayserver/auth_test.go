package relayserver

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fnc12/opencode/packages/relay/internal/account"
	"github.com/fnc12/opencode/packages/relay/internal/provision"
)

// captureSender records the last magic-link email so tests can pull the token.
type captureSender struct {
	to, subject, body string
	sent              int
}

func (c *captureSender) Send(to, subject, body string) error {
	c.to, c.subject, c.body, c.sent = to, subject, body, c.sent+1
	return nil
}

func authServer(t *testing.T) (*httptest.Server, provision.Store, *account.Store, *captureSender) {
	t.Helper()
	pstore, err := provision.NewSQLiteStore(filepath.Join(t.TempDir(), "p.db"))
	if err != nil {
		t.Fatal(err)
	}
	astore, err := account.NewStore(filepath.Join(t.TempDir(), "a.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { astore.Close() })
	sender := &captureSender{}
	srv := New(Config{
		Log:         slog.New(slog.NewTextHandler(io.Discard, nil)),
		Provision:   pstore,
		Accounts:    astore,
		Email:       sender,
		AssetDir:    t.TempDir(),
		PublicURL:   "https://relay.example.org",
		AdminSecret: "admin",
	})
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	return ts, pstore, astore, sender
}

func noRedirect() *http.Client {
	return &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}

func tokenFromEmail(t *testing.T, body string) string {
	t.Helper()
	i := strings.Index(body, "token=")
	if i < 0 {
		t.Fatalf("no token in email body:\n%s", body)
	}
	tok := body[i+len("token="):]
	if j := strings.IndexAny(tok, "\n\r "); j >= 0 {
		tok = tok[:j]
	}
	return tok
}

// signIn runs the full magic-link exchange and returns the session cookie.
func signIn(t *testing.T, ts *httptest.Server, sender *captureSender, email string) *http.Cookie {
	t.Helper()
	c := noRedirect()
	resp, err := c.PostForm(ts.URL+"/login", url.Values{"email": {email}})
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("POST /login = %d, want 200", resp.StatusCode)
	}
	if sender.sent == 0 {
		t.Fatal("no magic-link email was sent")
	}
	tok := tokenFromEmail(t, sender.body)

	vr, err := c.Get(ts.URL + "/login/verify?token=" + tok)
	if err != nil {
		t.Fatal(err)
	}
	vr.Body.Close()
	if vr.StatusCode != http.StatusSeeOther || vr.Header.Get("Location") != "/account" {
		t.Fatalf("verify = %d loc=%q, want 303 -> /account", vr.StatusCode, vr.Header.Get("Location"))
	}
	for _, ck := range vr.Cookies() {
		if ck.Name == sessionCookie && ck.Value != "" {
			return ck
		}
	}
	t.Fatal("verify did not set a session cookie")
	return nil
}

func getWithCookie(t *testing.T, u string, ck *http.Cookie) (int, string) {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, u, nil)
	if ck != nil {
		req.AddCookie(ck)
	}
	resp, err := noRedirect().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b)
}

func TestMagicLinkLoginFlow(t *testing.T) {
	ts, _, _, sender := authServer(t)
	ck := signIn(t, ts, sender, "Me@Example.com")

	status, body := getWithCookie(t, ts.URL+"/account", ck)
	if status != http.StatusOK || !strings.Contains(body, "Your account") {
		t.Fatalf("/account = %d, body missing heading:\n%s", status, body)
	}
	// A fresh account gets a first-100 free month, plus referral + promo sections.
	if !strings.Contains(body, "Free until") {
		t.Errorf("fresh account should show its free-month entitlement:\n%s", body)
	}
	if !strings.Contains(body, "/login?ref=") || !strings.Contains(body, "Invite friends") {
		t.Errorf("account should show a referral link")
	}
	if !strings.Contains(body, "Have a promo code?") {
		t.Errorf("account should show the promo form")
	}

	// No cookie → redirect to /login.
	if status, _ := getWithCookie(t, ts.URL+"/account", nil); status != http.StatusSeeOther {
		t.Errorf("/account without session = %d, want 303", status)
	}
}

func TestLoginRejectsBadEmail(t *testing.T) {
	ts, _, _, sender := authServer(t)
	resp, err := noRedirect().PostForm(ts.URL+"/login", url.Values{"email": {"not-an-email"}})
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK || !strings.Contains(string(b), "email address") {
		t.Errorf("bad email should re-render the form with an error, got %d", resp.StatusCode)
	}
	if sender.sent != 0 {
		t.Errorf("no email should be sent for an invalid address")
	}
}

func TestVerifyRejectsBadToken(t *testing.T) {
	ts, _, _, _ := authServer(t)
	status, body := getWithCookie(t, ts.URL+"/login/verify?token=deadbeef", nil)
	if status != http.StatusBadRequest || !strings.Contains(body, "expired") {
		t.Errorf("bad token = %d, want 400 with expired message", status)
	}
}

func TestAccountConnectedAfterBind(t *testing.T) {
	ts, pstore, _, sender := authServer(t)
	ck := signIn(t, ts, sender, "me@x.com")

	// A paid subscription whose connector already installed (claimed).
	tun, _ := pstore.MintFor("I-SUB-BOUND", "buyer@paypal.com", 0)
	if _, err := pstore.Claim(tun.ClaimCode); err != nil {
		t.Fatal(err)
	}

	// Returning signed-in with the subscription id binds it and shows re-pair.
	status, body := getWithCookie(t, ts.URL+"/account?subscription_id=I-SUB-BOUND", ck)
	if status != http.StatusOK {
		t.Fatalf("/account = %d", status)
	}
	if !strings.Contains(body, "Connected") || !strings.Contains(body, "opencode://pair") {
		t.Errorf("bound+claimed subscription should show the re-pair page:\n%s", body)
	}
	if !strings.Contains(body, "data:image/png;base64,") {
		t.Errorf("re-pair should include a QR")
	}

	// Binding persists: a later plain /account still shows connected.
	if _, body := getWithCookie(t, ts.URL+"/account", ck); !strings.Contains(body, "Connected") {
		t.Errorf("binding should persist across requests")
	}
}

func TestAccountInstallForUnclaimedSubscription(t *testing.T) {
	ts, pstore, _, sender := authServer(t)
	ck := signIn(t, ts, sender, "me@x.com")

	pstore.MintFor("I-SUB-NEW", "buyer@paypal.com", 0) // minted, not claimed

	status, body := getWithCookie(t, ts.URL+"/account?subscription_id=I-SUB-NEW", ck)
	if status != http.StatusOK {
		t.Fatalf("/account = %d", status)
	}
	if !strings.Contains(body, "| sh") || !strings.Contains(body, "Finish setup") {
		t.Errorf("paid-but-unclaimed subscription should show the install command:\n%s", body)
	}
}

func TestLogoutRevokesSession(t *testing.T) {
	ts, _, _, sender := authServer(t)
	ck := signIn(t, ts, sender, "me@x.com")

	// Logout with the session cookie.
	req, _ := http.NewRequest(http.MethodPost, ts.URL+"/logout", nil)
	req.AddCookie(ck)
	resp, err := noRedirect().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusSeeOther {
		t.Fatalf("logout = %d, want 303", resp.StatusCode)
	}
	// The old cookie is now dead → /account redirects to /login.
	if status, _ := getWithCookie(t, ts.URL+"/account", ck); status != http.StatusSeeOther {
		t.Errorf("session should be revoked after logout, /account = %d want 303", status)
	}
}

func postForm(t *testing.T, u string, ck *http.Cookie, form url.Values) *http.Response {
	t.Helper()
	req, _ := http.NewRequest(http.MethodPost, u, strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	if ck != nil {
		req.AddCookie(ck)
	}
	resp, err := noRedirect().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	return resp
}

func TestPromoRedeemFlow(t *testing.T) {
	ts, _, astore, sender := authServer(t)
	ck := signIn(t, ts, sender, "promo@x.com")
	if err := astore.CreatePromo("LAUNCH", 2, 0, 0); err != nil { // 2 months, unlimited uses
		t.Fatal(err)
	}

	resp := postForm(t, ts.URL+"/account/promo", ck, url.Values{"code": {"launch"}}) // case-insensitive
	if resp.StatusCode != http.StatusSeeOther {
		t.Fatalf("promo redeem = %d, want 303", resp.StatusCode)
	}
	if loc := resp.Header.Get("Location"); !strings.Contains(loc, "/account?flash=") || !strings.Contains(loc, "Promo+applied") {
		t.Errorf("success redirect missing flash: %q", loc)
	}
	if _, body := getWithCookie(t, ts.URL+"/account?flash="+url.QueryEscape("Promo applied — 2 free months added."), ck); !strings.Contains(body, "Promo applied") {
		t.Errorf("flash message should render on /account")
	}

	resp2 := postForm(t, ts.URL+"/account/promo", ck, url.Values{"code": {"launch"}})
	if !strings.Contains(resp2.Header.Get("Location"), "already+used") {
		t.Errorf("second redeem should flash already-used: %q", resp2.Header.Get("Location"))
	}
}

func TestAccountConnectFree(t *testing.T) {
	ts, _, _, sender := authServer(t)
	ck := signIn(t, ts, sender, "connect@x.com") // fresh → first-100 free month

	// An entitled account with no tunnel yet sees the Connect button.
	_, body := getWithCookie(t, ts.URL+"/account", ck)
	if !strings.Contains(body, "Connect your OpenCode") {
		t.Fatalf("free account should show the Connect button:\n%s", body)
	}

	// Connecting provisions a free tunnel and returns to /account.
	if resp := postForm(t, ts.URL+"/account/connect", ck, url.Values{}); resp.StatusCode != http.StatusSeeOther {
		t.Fatalf("connect = %d, want 303", resp.StatusCode)
	}
	// Which now shows the install command.
	_, body = getWithCookie(t, ts.URL+"/account", ck)
	if !strings.Contains(body, "| sh") || !strings.Contains(body, "Finish setup") {
		t.Errorf("after connect, /account should show the install command:\n%s", body)
	}
	// Connecting again is idempotent (no second tunnel).
	if resp := postForm(t, ts.URL+"/account/connect", ck, url.Values{}); resp.StatusCode != http.StatusSeeOther {
		t.Errorf("second connect = %d, want 303", resp.StatusCode)
	}
}

func refCodeFromBody(t *testing.T, body string) string {
	t.Helper()
	const marker = "/login?ref="
	i := strings.Index(body, marker)
	if i < 0 {
		t.Fatalf("no referral link in /account body")
	}
	rest := body[i+len(marker):]
	if j := strings.IndexAny(rest, `"< `); j >= 0 {
		rest = rest[:j]
	}
	return rest
}

func TestReferralViaLoginLink(t *testing.T) {
	ts, _, _, sender := authServer(t)

	refCk := signIn(t, ts, sender, "referrer@x.com")
	_, body := getWithCookie(t, ts.URL+"/account", refCk)
	code := refCodeFromBody(t, body)
	if code == "" {
		t.Fatal("empty referral code")
	}
	if !strings.Contains(body, "0 friends joined") {
		t.Errorf("referrer should start at 0 friends")
	}

	// Invitee arrives via the ref link (sets a cookie), then signs in — a jar
	// carries that cookie from /login?ref= through to /login/verify.
	jar, _ := cookiejar.New(nil)
	c := &http.Client{Jar: jar, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	if _, err := c.Get(ts.URL + "/login?ref=" + code); err != nil {
		t.Fatal(err)
	}
	if _, err := c.PostForm(ts.URL+"/login", url.Values{"email": {"invitee@x.com"}}); err != nil {
		t.Fatal(err)
	}
	tok := tokenFromEmail(t, sender.body)
	if _, err := c.Get(ts.URL + "/login/verify?token=" + tok); err != nil {
		t.Fatal(err)
	}

	_, body = getWithCookie(t, ts.URL+"/account", refCk)
	if !strings.Contains(body, "1 friend joined") {
		t.Errorf("referrer should have 1 referral after the invitee signed up:\n%s", body)
	}
}
