package relayserver

// Onboarding-from-scratch end-to-end: a brand-new visitor with no account signs
// up (via email, GitHub, or Google), gets their install link, installs the
// connector, and their own OpenCode server becomes reachable through the relay.
// One test, three parallel signup paths — the top-of-funnel journey we care most
// about. See docs/shubat-qa-usecases.md (E2E + AUTH + ONB + ENT).

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/fnc12/opencode/packages/relay/internal/account"
	"github.com/fnc12/opencode/packages/relay/internal/provision"
)

// onboardOAuthStub answers the GitHub + Google token/userinfo endpoints so the
// OAuth callbacks resolve a user without the network.
type onboardOAuthStub struct{}

func (onboardOAuthStub) RoundTrip(req *http.Request) (*http.Response, error) {
	u := req.URL.String()
	body := "{}"
	switch {
	case strings.Contains(u, "github.com/login/oauth/access_token"):
		body = `{"access_token":"gh_tok"}`
	case strings.Contains(u, "api.github.com") && strings.HasSuffix(req.URL.Path, "/user"):
		body = `{"id":50123,"login":"newdev"}`
	case strings.Contains(u, "oauth2.googleapis.com/token"):
		body = `{"access_token":"g_tok"}`
	case strings.Contains(u, "openidconnect.googleapis.com/v1/userinfo"):
		body = `{"sub":"g-sub-777"}`
	}
	return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body)), Header: make(http.Header)}, nil
}

// onboardServer is a fully-wired relay with all three sign-in methods configured.
func onboardServer(t *testing.T) (*httptest.Server, *Server, provision.Store, *account.Store, *captureSender) {
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
	// GitHub + Google credentials, as if setup already ran.
	astore.SetConfig(ghClientIDKey, "CID")
	astore.SetConfig(ghClientSecretKey, "CSEC")
	astore.SetConfig(googleClientIDKey, "GID")
	astore.SetConfig(googleClientSecretKey, "GSEC")

	sender := &captureSender{}
	srv := New(Config{
		Log:          slog.New(slog.NewTextHandler(io.Discard, nil)),
		Provision:    pstore,
		Accounts:     astore,
		Email:        sender,
		AssetDir:     t.TempDir(),
		PublicURL:    "https://relay.example.org",
		AdminSecret:  "adm",
		VerifyPayPal: func(http.Header, []byte) error { return nil },
		HTTPClient:   &http.Client{Transport: onboardOAuthStub{}},
	})
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	return ts, srv, pstore, astore, sender
}

func sessionFrom(t *testing.T, resp *http.Response) *http.Cookie {
	t.Helper()
	if resp.StatusCode != http.StatusSeeOther || resp.Header.Get("Location") != "/account" {
		t.Fatalf("sign-in = %d loc=%q, want 303 -> /account", resp.StatusCode, resp.Header.Get("Location"))
	}
	for _, ck := range resp.Cookies() {
		if ck.Name == sessionCookie && ck.Value != "" {
			return ck
		}
	}
	t.Fatal("sign-in did not open a session")
	return nil
}

func signupViaEmail(t *testing.T, ts *httptest.Server, sender *captureSender) *http.Cookie {
	t.Helper()
	c := noRedirect()
	resp, err := c.PostForm(ts.URL+"/login", url.Values{"email": {"newcomer@user.com"}})
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	tok := tokenFromEmail(t, sender.body)
	vr, err := c.Get(ts.URL + "/login/verify?token=" + tok)
	if err != nil {
		t.Fatal(err)
	}
	vr.Body.Close()
	return sessionFrom(t, vr)
}

func signupViaOAuth(t *testing.T, ts *httptest.Server, callbackPath string) *http.Cookie {
	t.Helper()
	resp, err := noRedirect().Get(ts.URL + callbackPath)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	return sessionFrom(t, resp)
}

func TestQA_Onboarding_FromScratch(t *testing.T) {
	methods := []struct {
		name   string
		signUp func(t *testing.T, ts *httptest.Server, sender *captureSender) *http.Cookie
	}{
		{"email", signupViaEmail},
		{"github", func(t *testing.T, ts *httptest.Server, _ *captureSender) *http.Cookie {
			return signupViaOAuth(t, ts, "/auth/github/callback?code=ghcode")
		}},
		{"google", func(t *testing.T, ts *httptest.Server, _ *captureSender) *http.Cookie {
			return signupViaOAuth(t, ts, "/auth/google/callback?code=ggcode")
		}},
	}

	for _, m := range methods {
		t.Run(m.name, func(t *testing.T) {
			ts, srv, pstore, astore, sender := onboardServer(t)
			now := time.Now().Unix()

			// Start from zero: no accounts exist.
			if n, _ := astore.RegisteredCount(); n != 0 {
				t.Fatalf("precondition: expected 0 accounts, got %d", n)
			}

			// 1) Sign up. A brand-new account is created and, as one of the first
			//    100, is free-active.
			ck := m.signUp(t, ts, sender)
			acc, err := astore.AccountBySession(ck.Value, now)
			if err != nil || acc == "" {
				t.Fatalf("signup did not open a valid session: %v", err)
			}
			if n, _ := astore.RegisteredCount(); n != 1 {
				t.Fatalf("expected exactly 1 account after signup, got %d", n)
			}
			if !astore.FreeActive(acc, now) {
				t.Fatal("a first-100 signup should be free-active")
			}

			// 2) /account shows the free plan + a Connect call-to-action (no tunnel yet).
			if _, body := getWithCookie(t, ts.URL+"/account", ck); !strings.Contains(body, "Free until") || !strings.Contains(body, "Connect your OpenCode") {
				t.Fatalf("account page should show free status + Connect:\n%s", body)
			}

			// 3) Connect → a free tunnel is minted and the install link ("all the
			//    links") is shown.
			if resp := postForm(t, ts.URL+"/account/connect", ck, url.Values{}); resp.StatusCode != http.StatusSeeOther {
				t.Fatalf("connect = %d, want 303", resp.StatusCode)
			}
			if _, body := getWithCookie(t, ts.URL+"/account", ck); !strings.Contains(body, "/i/") || !strings.Contains(body, "| sh") {
				t.Fatalf("after connect, account should show the install command:\n%s", body)
			}
			tun, ok := pstore.GetByCustomer(acc)
			if !ok || tun.ClaimCode == "" {
				t.Fatal("connect did not mint a claimable tunnel")
			}

			// 4) Install the connector + bring up their OpenCode server: claim the
			//    code, register a connector (fronting a fake OpenCode), and confirm
			//    the app can reach that server through the relay.
			id, token := claimCode(t, ts, tun.ClaimCode)
			ws := dialProvisionedConnector(t, ts, id, token)
			defer ws.Close()
			waitTunnel(t, ts)
			okBackend(t, ws)
			resp, err := getTunnel(ts.URL+"/t/"+id+"/global/health", token)
			if err != nil {
				t.Fatal(err)
			}
			b, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			if resp.StatusCode != http.StatusOK || !strings.Contains(string(b), "ok") {
				t.Fatalf("their OpenCode server should be reachable through the relay: %d %s", resp.StatusCode, b)
			}

			_ = srv // reserved for reaper assertions in longer journeys
		})
	}
}
