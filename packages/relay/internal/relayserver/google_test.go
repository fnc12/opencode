package relayserver

import (
	"fmt"
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

// gStub answers the Google OIDC endpoints the login flow calls.
type gStub struct{ sub string }

func (g gStub) RoundTrip(req *http.Request) (*http.Response, error) {
	body := "{}"
	u := req.URL.String()
	switch {
	case strings.Contains(u, "oauth2.googleapis.com/token"):
		body = `{"access_token":"ya29.test"}`
	case strings.Contains(u, "openidconnect.googleapis.com/v1/userinfo"):
		body = fmt.Sprintf(`{"sub":%q,"email":"u@gmail.com"}`, g.sub)
	}
	return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body)), Header: make(http.Header)}, nil
}

func googleServer(t *testing.T) (*httptest.Server, *account.Store) {
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
		HTTPClient:  &http.Client{Transport: gStub{sub: "google-sub-99"}},
	})
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	return ts, astore
}

func TestGoogleSetupStoresCreds(t *testing.T) {
	ts, astore := googleServer(t)

	if st, _ := getWithCookie(t, ts.URL+"/setup/google", nil); st != http.StatusForbidden {
		t.Errorf("no key → %d, want 403", st)
	}
	st, body := getWithCookie(t, ts.URL+"/setup/google?key=adm", nil)
	if st != http.StatusOK || !strings.Contains(body, `name="client_id"`) || !strings.Contains(body, "/auth/google/callback") {
		t.Fatalf("setup form missing fields/redirect (%d):\n%s", st, body)
	}

	resp := postForm(t, ts.URL+"/setup/google", nil, url.Values{"key": {"adm"}, "client_id": {"gid"}, "client_secret": {"gsec"}})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("save creds → %d, want 200", resp.StatusCode)
	}
	if v, ok := astore.GetConfig(googleClientIDKey); !ok || v != "gid" {
		t.Errorf("client id not stored: %q", v)
	}
	// Wrong key on POST is rejected.
	if r := postForm(t, ts.URL+"/setup/google", nil, url.Values{"key": {"nope"}, "client_id": {"x"}, "client_secret": {"y"}}); r.StatusCode != http.StatusForbidden {
		t.Errorf("bad key POST → %d, want 403", r.StatusCode)
	}
}

func TestGoogleLoginFlow(t *testing.T) {
	ts, astore := googleServer(t)
	if st, _ := getWithCookie(t, ts.URL+"/auth/google", nil); st != http.StatusServiceUnavailable {
		t.Errorf("unconfigured /auth/google → %d, want 503", st)
	}
	astore.SetConfig(googleClientIDKey, "gid")
	astore.SetConfig(googleClientSecretKey, "gsec")

	resp, err := noRedirect().Get(ts.URL + "/auth/google")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	loc := resp.Header.Get("Location")
	if resp.StatusCode != http.StatusSeeOther || !strings.Contains(loc, "accounts.google.com/o/oauth2/v2/auth") || !strings.Contains(loc, "client_id=gid") {
		t.Fatalf("authorize redirect wrong: %d %q", resp.StatusCode, loc)
	}

	cb, err := noRedirect().Get(ts.URL + "/auth/google/callback?code=xyz")
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
		t.Fatal("google callback did not open a session")
	}
	if st, body := getWithCookie(t, ts.URL+"/account", sess); st != http.StatusOK || !strings.Contains(body, "Your account") {
		t.Errorf("google session /account = %d", st)
	}
}
