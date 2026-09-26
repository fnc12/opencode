package relayserver

import (
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fnc12/opencode/packages/relay/internal/account"
	"github.com/fnc12/opencode/packages/relay/internal/provision"
)

// ghStub answers the GitHub endpoints the OAuth/manifest flows call, so the
// handlers are testable without the network.
type ghStub struct{ userID int64 }

func (g ghStub) RoundTrip(req *http.Request) (*http.Response, error) {
	body := "{}"
	u := req.URL.String()
	switch {
	case strings.Contains(u, "/app-manifests/"):
		body = `{"client_id":"CID","client_secret":"CSEC"}`
	case strings.Contains(u, "/login/oauth/access_token"):
		body = `{"access_token":"gho_test"}`
	case strings.HasSuffix(req.URL.Path, "/user"):
		body = fmt.Sprintf(`{"id":%d,"login":"octocat"}`, g.userID)
	}
	return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body)), Header: make(http.Header)}, nil
}

func githubServer(t *testing.T) (*httptest.Server, *account.Store) {
	t.Helper()
	astore, err := account.NewStore(filepath.Join(t.TempDir(), "a.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { astore.Close() })
	pstore, _ := provision.NewSQLiteStore(filepath.Join(t.TempDir(), "p.db"))
	srv := New(Config{
		Log:         slog.New(slog.NewTextHandler(io.Discard, nil)),
		Accounts:    astore,
		Provision:   pstore,
		AdminSecret: "adm",
		PublicURL:   "https://relay.example.org",
		HTTPClient:  &http.Client{Transport: ghStub{userID: 4242}},
	})
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	return ts, astore
}

func TestGitHubSetupGate(t *testing.T) {
	ts, _ := githubServer(t)
	if st, _ := getWithCookie(t, ts.URL+"/setup/github", nil); st != http.StatusForbidden {
		t.Errorf("no key → %d, want 403", st)
	}
	st, body := getWithCookie(t, ts.URL+"/setup/github?key=adm", nil)
	if st != http.StatusOK {
		t.Fatalf("with key → %d, want 200", st)
	}
	if !strings.Contains(body, "github.com/settings/apps/new") || !strings.Contains(body, `name="manifest"`) {
		t.Errorf("setup page should carry the manifest form:\n%s", body)
	}
}

func TestGitHubSetupCallbackStoresCreds(t *testing.T) {
	ts, astore := githubServer(t)
	st, _ := getWithCookie(t, ts.URL+"/setup/github/callback?code=abc123&state="+setupState("adm"), nil)
	if st != http.StatusOK {
		t.Fatalf("callback → %d, want 200", st)
	}
	if v, ok := astore.GetConfig(ghClientIDKey); !ok || v != "CID" {
		t.Errorf("client id not stored: %q ok=%v", v, ok)
	}
	if v, ok := astore.GetConfig(ghClientSecretKey); !ok || v != "CSEC" {
		t.Errorf("client secret not stored: %q ok=%v", v, ok)
	}
	// Wrong state is rejected.
	if st, _ := getWithCookie(t, ts.URL+"/setup/github/callback?code=abc&state=wrong", nil); st != http.StatusForbidden {
		t.Errorf("bad state → %d, want 403", st)
	}
}

func TestGitHubLoginFlow(t *testing.T) {
	ts, astore := githubServer(t)
	// GitHub not configured yet → /auth/github is unavailable.
	if st, _ := getWithCookie(t, ts.URL+"/auth/github", nil); st != http.StatusServiceUnavailable {
		t.Errorf("unconfigured /auth/github → %d, want 503", st)
	}
	astore.SetConfig(ghClientIDKey, "CID")
	astore.SetConfig(ghClientSecretKey, "CSEC")

	// Start → redirect to GitHub's authorize with our client id.
	resp, err := noRedirect().Get(ts.URL + "/auth/github")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusSeeOther || !strings.Contains(resp.Header.Get("Location"), "github.com/login/oauth/authorize") || !strings.Contains(resp.Header.Get("Location"), "client_id=CID") {
		t.Fatalf("authorize redirect wrong: %d %q", resp.StatusCode, resp.Header.Get("Location"))
	}

	// Callback → token + user (stub) → account + session → /account.
	cb, err := noRedirect().Get(ts.URL + "/auth/github/callback?code=xyz")
	if err != nil {
		t.Fatal(err)
	}
	cb.Body.Close()
	if cb.StatusCode != http.StatusSeeOther || cb.Header.Get("Location") != "/account" {
		t.Fatalf("callback → %d loc=%q, want 303 /account", cb.StatusCode, cb.Header.Get("Location"))
	}
	var sess *http.Cookie
	for _, ck := range cb.Cookies() {
		if ck.Name == sessionCookie && ck.Value != "" {
			sess = ck
		}
	}
	if sess == nil {
		t.Fatal("github callback did not open a session")
	}
	// The session works and the account was created via the github identity.
	if st, body := getWithCookie(t, ts.URL+"/account", sess); st != http.StatusOK || !strings.Contains(body, "Your account") {
		t.Errorf("github session /account = %d", st)
	}
	if _, _, err := astore.AccountForIdentity("github", "4242", 1); err != nil {
		t.Fatal(err)
	}
}
