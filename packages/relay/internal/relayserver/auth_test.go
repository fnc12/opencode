package relayserver

import (
	"io"
	"log/slog"
	"net/http"
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

func authServer(t *testing.T) (*httptest.Server, provision.Store, *captureSender) {
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
	return ts, pstore, sender
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
	ts, _, sender := authServer(t)
	ck := signIn(t, ts, sender, "Me@Example.com")

	status, body := getWithCookie(t, ts.URL+"/account", ck)
	if status != http.StatusOK || !strings.Contains(body, "Your account") {
		t.Fatalf("/account = %d, body missing heading:\n%s", status, body)
	}
	if !strings.Contains(body, "No active subscription") {
		t.Errorf("fresh account should show the no-subscription state")
	}

	// No cookie → redirect to /login.
	if status, _ := getWithCookie(t, ts.URL+"/account", nil); status != http.StatusSeeOther {
		t.Errorf("/account without session = %d, want 303", status)
	}
}

func TestLoginRejectsBadEmail(t *testing.T) {
	ts, _, sender := authServer(t)
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
	ts, _, _ := authServer(t)
	status, body := getWithCookie(t, ts.URL+"/login/verify?token=deadbeef", nil)
	if status != http.StatusBadRequest || !strings.Contains(body, "expired") {
		t.Errorf("bad token = %d, want 400 with expired message", status)
	}
}

func TestAccountConnectedAfterBind(t *testing.T) {
	ts, pstore, sender := authServer(t)
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
	if !strings.Contains(body, "You're connected") || !strings.Contains(body, "opencode://pair") {
		t.Errorf("bound+claimed subscription should show the re-pair page:\n%s", body)
	}
	if !strings.Contains(body, "data:image/png;base64,") {
		t.Errorf("re-pair should include a QR")
	}

	// Binding persists: a later plain /account still shows connected.
	if _, body := getWithCookie(t, ts.URL+"/account", ck); !strings.Contains(body, "You're connected") {
		t.Errorf("binding should persist across requests")
	}
}

func TestAccountInstallForUnclaimedSubscription(t *testing.T) {
	ts, pstore, sender := authServer(t)
	ck := signIn(t, ts, sender, "me@x.com")

	pstore.MintFor("I-SUB-NEW", "buyer@paypal.com", 0) // minted, not claimed

	status, body := getWithCookie(t, ts.URL+"/account?subscription_id=I-SUB-NEW", ck)
	if status != http.StatusOK {
		t.Fatalf("/account = %d", status)
	}
	if !strings.Contains(body, "| sh") || !strings.Contains(body, "subscribed") {
		t.Errorf("paid-but-unclaimed subscription should show the install command:\n%s", body)
	}
}

func TestLogoutRevokesSession(t *testing.T) {
	ts, _, sender := authServer(t)
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
