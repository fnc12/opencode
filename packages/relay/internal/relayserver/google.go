package relayserver

import (
	"encoding/json"
	"fmt"
	"html"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Google sign-in. Unlike GitHub there's no manifest flow, so the owner creates
// an OAuth client in Google Cloud Console and pastes its id/secret into the
// gated /setup/google form; they're stored in the account config table and take
// effect immediately. Login uses the standard OpenID Connect code flow, keying
// accounts by the stable "sub" claim.

const (
	googleClientIDKey     = "google_client_id"
	googleClientSecretKey = "google_client_secret"
)

// setupGoogle renders the paste-credentials form (GET) and stores them (POST),
// gated by the admin key (query or form field).
func (s *Server) setupGoogle(w http.ResponseWriter, r *http.Request) {
	if s.adminSecret == "" || (r.URL.Query().Get("key") != s.adminSecret && r.FormValue("key") != s.adminSecret) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	if r.Method == http.MethodPost {
		cid := strings.TrimSpace(r.FormValue("client_id"))
		csec := strings.TrimSpace(r.FormValue("client_secret"))
		if cid == "" || csec == "" {
			http.Error(w, "both client_id and client_secret are required", http.StatusBadRequest)
			return
		}
		_ = s.accounts.SetConfig(googleClientIDKey, cid)
		_ = s.accounts.SetConfig(googleClientSecretKey, csec)
		s.log.Info("google sign-in configured")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprintf(w, welcomeShell, "Google sign-in ready", googleSetupDoneBody)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	body := fmt.Sprintf(googleSetupBody, html.EscapeString(s.adminSecret), html.EscapeString(s.baseURL(r)+"/auth/google/callback"))
	fmt.Fprintf(w, welcomeShell, "Set up Google sign-in", body)
}

// authGoogle starts the Google OpenID Connect flow.
func (s *Server) authGoogle(w http.ResponseWriter, r *http.Request) {
	cid, ok := s.accounts.GetConfig(googleClientIDKey)
	if !ok {
		http.Error(w, "Google sign-in is not configured yet", http.StatusServiceUnavailable)
		return
	}
	q := url.Values{
		"client_id":     {cid},
		"redirect_uri":  {s.baseURL(r) + "/auth/google/callback"},
		"response_type": {"code"},
		"scope":         {"openid email profile"},
		"state":         {randState()},
	}
	http.Redirect(w, r, "https://accounts.google.com/o/oauth2/v2/auth?"+q.Encode(), http.StatusSeeOther)
}

// authGoogleCallback exchanges the code, reads the user's sub claim, and opens a
// session — creating/linking an account by the google identity.
func (s *Server) authGoogleCallback(w http.ResponseWriter, r *http.Request) {
	cid, ok := s.accounts.GetConfig(googleClientIDKey)
	csec, ok2 := s.accounts.GetConfig(googleClientSecretKey)
	if !ok || !ok2 {
		http.Error(w, "Google sign-in is not configured", http.StatusServiceUnavailable)
		return
	}
	code := r.URL.Query().Get("code")
	if code == "" {
		http.Error(w, "missing code", http.StatusBadRequest)
		return
	}
	redirect := s.baseURL(r) + "/auth/google/callback"
	token, err := s.googleAccessToken(cid, csec, code, redirect)
	if err != nil {
		s.log.Warn("google token exchange failed", "err", err)
		http.Error(w, "Google sign-in failed", http.StatusBadGateway)
		return
	}
	uid, err := s.googleUserID(token)
	if err != nil || uid == "" {
		http.Error(w, "could not read Google user", http.StatusBadGateway)
		return
	}
	now := time.Now().Unix()
	acc, created, err := s.accounts.AccountForIdentity("google", uid, now)
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

func (s *Server) googleAccessToken(cid, csec, code, redirect string) (string, error) {
	form := url.Values{
		"client_id":     {cid},
		"client_secret": {csec},
		"code":          {code},
		"grant_type":    {"authorization_code"},
		"redirect_uri":  {redirect},
	}
	req, _ := http.NewRequest(http.MethodPost, "https://oauth2.googleapis.com/token", strings.NewReader(form.Encode()))
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

func (s *Server) googleUserID(token string) (string, error) {
	req, _ := http.NewRequest(http.MethodGet, "https://openidconnect.googleapis.com/v1/userinfo", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := s.httpClient().Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var out struct {
		Sub string `json:"sub"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&out); err != nil {
		return "", err
	}
	return out.Sub, nil
}

// googleLoginButton returns a "Sign in with Google" section, or "" when Google
// sign-in isn't configured yet.
func (s *Server) googleLoginButton() string {
	if _, ok := s.accounts.GetConfig(googleClientIDKey); !ok {
		return ""
	}
	return `<div style="text-align:center;margin:12px 0 0">
<a href="/auth/google" style="display:inline-block;padding:11px 18px;border-radius:10px;background:#fff;color:#1a1c20;text-decoration:none;font-size:15px;border:1px solid #dadce0">Sign in with Google</a></div>`
}

// socialButtons is the combined third-party sign-in section shown on /login.
func (s *Server) socialButtons() string {
	return s.githubLoginButton() + s.googleLoginButton()
}

// googleSetupBody: %[1]s admin key (hidden), %[2]s the redirect URI to register.
const googleSetupBody = `<h1>Set up Google sign-in</h1>
<p class="sub">In Google Cloud Console → APIs &amp; Services → Credentials, create an <b>OAuth client ID</b> (type: Web application). Add this redirect URI, then paste the client id &amp; secret below.</p>
<div class="cmd"><button class="copy" data-copy="%[2]s">Copy</button>%[2]s</div>
<form method="post" action="/setup/google" style="margin-top:16px">
  <input type="hidden" name="key" value="%[1]s">
  <input type="text" name="client_id" placeholder="Client ID" autocomplete="off" spellcheck="false" required style="margin-bottom:10px">
  <input type="text" name="client_secret" placeholder="Client secret" autocomplete="off" spellcheck="false" required>
  <div><button class="go" type="submit">Save</button></div>
</form>`

const googleSetupDoneBody = `<h1>Google sign-in is ready ✅</h1>
<p class="sub">Credentials saved. People can now <a href="/login">sign in</a> with Google.</p>`
