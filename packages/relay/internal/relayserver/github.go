package relayserver

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// GitHub sign-in is provisioned via GitHub's App *manifest* flow, so the owner
// creates the app with a single click from any browser they're signed into
// (including mobile Safari — the mobile web has Developer settings, the app
// doesn't). We use a GitHub App (not an OAuth App) because only Apps can be
// created from a manifest; its user-authorization flow still identifies the user
// for login. Captured credentials live in the account store's config table, so
// login works immediately with no redeploy.

const (
	ghClientIDKey     = "github_client_id"
	ghClientSecretKey = "github_client_secret"
)

// setupState derives the manifest/callback CSRF state from the admin secret,
// without putting the raw secret in the URL GitHub echoes back.
func setupState(adminSecret string) string {
	sum := sha256.Sum256([]byte("shubat-github-setup:" + adminSecret))
	return hex.EncodeToString(sum[:])[:24]
}

// setupGitHub renders a one-click page that POSTs an app manifest to GitHub.
// Gated by ?key=<admin secret> so only the owner can provision the app.
func (s *Server) setupGitHub(w http.ResponseWriter, r *http.Request) {
	if s.adminSecret == "" || r.URL.Query().Get("key") != s.adminSecret {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	base := s.baseURL(r)
	manifest := map[string]any{
		"name":          "Shubat Remote",
		"url":           "https://shubat.org",
		"redirect_url":  base + "/setup/github/callback",
		"callback_urls": []string{base + "/auth/github/callback"},
		"public":        false,
	}
	mj, _ := json.Marshal(manifest)
	state := setupState(s.adminSecret)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	body := fmt.Sprintf(githubSetupBody, html.EscapeString(state), html.EscapeString(string(mj)))
	fmt.Fprintf(w, welcomeShell, "Set up GitHub sign-in", body)
}

// setupGitHubCallback receives the temporary code from the manifest flow and
// converts it into the app's credentials, storing them for the login flow.
func (s *Server) setupGitHubCallback(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("state") != setupState(s.adminSecret) {
		http.Error(w, "bad state", http.StatusForbidden)
		return
	}
	code := r.URL.Query().Get("code")
	if code == "" {
		http.Error(w, "missing code", http.StatusBadRequest)
		return
	}
	req, _ := http.NewRequest(http.MethodPost, "https://api.github.com/app-manifests/"+url.PathEscape(code)+"/conversions", nil)
	req.Header.Set("Accept", "application/vnd.github+json")
	resp, err := s.httpClient().Do(req)
	if err != nil {
		http.Error(w, "github exchange failed", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	var out struct {
		ClientID     string `json:"client_id"`
		ClientSecret string `json:"client_secret"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&out); err != nil || out.ClientID == "" {
		http.Error(w, "github did not return app credentials", http.StatusBadGateway)
		return
	}
	_ = s.accounts.SetConfig(ghClientIDKey, out.ClientID)
	_ = s.accounts.SetConfig(ghClientSecretKey, out.ClientSecret)
	s.log.Info("github sign-in configured", "client_id", out.ClientID)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, welcomeShell, "GitHub sign-in ready", githubSetupDoneBody)
}

// authGitHub starts the GitHub user-authorization flow.
func (s *Server) authGitHub(w http.ResponseWriter, r *http.Request) {
	clientID, ok := s.accounts.GetConfig(ghClientIDKey)
	if !ok {
		http.Error(w, "GitHub sign-in is not configured yet", http.StatusServiceUnavailable)
		return
	}
	// Preserve a referral code across the round-trip via the existing cookie.
	q := url.Values{
		"client_id":    {clientID},
		"redirect_uri": {s.baseURL(r) + "/auth/github/callback"},
		"state":        {randState()},
	}
	http.Redirect(w, r, "https://github.com/login/oauth/authorize?"+q.Encode(), http.StatusSeeOther)
}

// authGitHubCallback exchanges the code for a user token, identifies the GitHub
// user, and opens a session — creating/linking an account by the github identity.
func (s *Server) authGitHubCallback(w http.ResponseWriter, r *http.Request) {
	clientID, ok := s.accounts.GetConfig(ghClientIDKey)
	clientSecret, ok2 := s.accounts.GetConfig(ghClientSecretKey)
	if !ok || !ok2 {
		http.Error(w, "GitHub sign-in is not configured", http.StatusServiceUnavailable)
		return
	}
	code := r.URL.Query().Get("code")
	if code == "" {
		http.Error(w, "missing code", http.StatusBadRequest)
		return
	}
	token, err := s.githubAccessToken(clientID, clientSecret, code)
	if err != nil {
		s.log.Warn("github token exchange failed", "err", err)
		http.Error(w, "GitHub sign-in failed", http.StatusBadGateway)
		return
	}
	uid, err := s.githubUserID(token)
	if err != nil || uid == "" {
		http.Error(w, "could not read GitHub user", http.StatusBadGateway)
		return
	}
	now := time.Now().Unix()
	acc, created, err := s.accounts.AccountForIdentity("github", uid, now)
	if err != nil {
		http.Error(w, "sign-in failed", http.StatusInternalServerError)
		return
	}
	if created {
		if rc, err := r.Cookie(referralCookie); err == nil && rc.Value != "" {
			_, _ = s.accounts.ApplyReferral(acc, rc.Value, now)
		}
	}
	sid, err := s.accounts.CreateSession(acc, now, time.Now().Add(sessionTTL).Unix())
	if err != nil {
		http.Error(w, "sign-in failed", http.StatusInternalServerError)
		return
	}
	s.setSessionCookie(w, sid)
	http.Redirect(w, r, "/account", http.StatusSeeOther)
}

func (s *Server) githubAccessToken(clientID, clientSecret, code string) (string, error) {
	form := url.Values{"client_id": {clientID}, "client_secret": {clientSecret}, "code": {code}}
	req, _ := http.NewRequest(http.MethodPost, "https://github.com/login/oauth/access_token", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	resp, err := s.httpClient().Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var out struct {
		AccessToken string `json:"access_token"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&out); err != nil {
		return "", err
	}
	if out.AccessToken == "" {
		return "", fmt.Errorf("no access token")
	}
	return out.AccessToken, nil
}

func (s *Server) githubUserID(token string) (string, error) {
	req, _ := http.NewRequest(http.MethodGet, "https://api.github.com/user", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Accept", "application/vnd.github+json")
	resp, err := s.httpClient().Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var out struct {
		ID int64 `json:"id"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&out); err != nil {
		return "", err
	}
	if out.ID == 0 {
		return "", fmt.Errorf("no user id")
	}
	return strconv.FormatInt(out.ID, 10), nil
}

func (s *Server) httpClient() *http.Client {
	if s.hc != nil {
		return s.hc
	}
	return &http.Client{Timeout: 15 * time.Second}
}

func randState() string { return randHex(12) }

// githubSetupBody: %[1]s state, %[2]s manifest JSON (both escaped).
const githubSetupBody = `<h1>Set up GitHub sign-in</h1>
<p class="sub">One tap creates the Shubat GitHub App under your account and wires up "Sign in with GitHub" automatically — works right here in mobile Safari.</p>
<form action="https://github.com/settings/apps/new?state=%[1]s" method="post">
  <input type="hidden" name="manifest" value="%[2]s">
  <button class="go" type="submit">Create the GitHub App</button>
</form>
<p class="note">You'll see GitHub's confirmation screen; press Create and you'll come back here.</p>`

const githubSetupDoneBody = `<h1>GitHub sign-in is ready ✅</h1>
<p class="sub">The app was created and its credentials are configured. People can now <a href="/login">sign in</a> with GitHub.</p>`
