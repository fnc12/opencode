package relayserver

import (
	"fmt"
	"html"
	"net/http"
	"time"

	"github.com/fnc12/opencode/packages/relay/internal/account"
)

const (
	sessionCookie  = "shubat_session"
	referralCookie = "shubat_ref"
	sessionTTL     = 30 * 24 * time.Hour
	referralTTL    = 30 * 24 * time.Hour
	loginTokenTTL  = 15 * time.Minute
)

// baseURL is the relay's externally-reachable base (config PublicURL, else the
// request Host). Shared by the auth pages and welcome.
func (s *Server) baseURL(r *http.Request) string {
	if s.publicURL != "" {
		return s.publicURL
	}
	return "https://" + r.Host
}

// login renders the passwordless sign-in form (GET) and issues a magic link
// (POST). It never reveals whether an account exists for the address.
func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	// Capture a referral code from a /login?ref=CODE invite link so it's credited
	// when this visitor signs in for the first time.
	if ref := r.URL.Query().Get("ref"); ref != "" {
		http.SetCookie(w, &http.Cookie{
			Name: referralCookie, Value: ref, Path: "/",
			HttpOnly: true, Secure: true, SameSite: http.SameSiteLaxMode,
			MaxAge: int(referralTTL / time.Second),
		})
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if r.Method == http.MethodPost {
		email := r.FormValue("email")
		norm, err := account.NormalizeEmail(email)
		if err != nil {
			errHTML := `<p style="color:#f87171;margin:0 0 12px">That doesn't look like an email address.</p>`
			fmt.Fprintf(w, welcomeShell, "Sign in", fmt.Sprintf(loginFormBody, errHTML, html.EscapeString(email)))
			return
		}
		exp := time.Now().Add(loginTokenTTL).Unix()
		tok, err := s.accounts.IssueLoginToken(norm, exp)
		if err != nil {
			http.Error(w, "could not start sign-in", http.StatusInternalServerError)
			return
		}
		link := s.baseURL(r) + "/login/verify?token=" + tok
		body := "Sign in to Shubat:\n\n" + link + "\n\nThis link works once and expires in 15 minutes. If you didn't request it, ignore this email."
		if err := s.email.Send(norm, "Your Shubat sign-in link", body); err != nil {
			s.log.Warn("magic link send failed", "err", err)
		}
		fmt.Fprintf(w, welcomeShell, "Check your email", loginSentBody)
		return
	}
	fmt.Fprintf(w, welcomeShell, "Sign in", fmt.Sprintf(loginFormBody, "", ""))
}

// loginVerify consumes a magic-link token, opens a session, and redirects to the
// account page.
func (s *Server) loginVerify(w http.ResponseWriter, r *http.Request) {
	now := time.Now().Unix()
	email, err := s.accounts.RedeemLoginToken(r.URL.Query().Get("token"), now)
	if err != nil {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.WriteHeader(http.StatusBadRequest)
		fmt.Fprintf(w, welcomeShell, "Link expired", loginExpiredBody)
		return
	}
	acc, created, err := s.accounts.AccountForEmail(email, now)
	if err != nil {
		http.Error(w, "sign-in failed", http.StatusInternalServerError)
		return
	}
	// On first sign-in, credit a referrer if the visitor arrived via a ref link
	// (captured into a cookie at /login?ref= or the landing).
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

// account shows the signed-in user's subscription + connection state, and
// opportunistically binds a subscription id from the URL (e.g. the PayPal
// return, when the user is already signed in — this is how a subscription paid
// from a different email gets tied to the account).
func (s *Server) account(w http.ResponseWriter, r *http.Request) {
	acc, ok := s.accountFromRequest(r)
	if !ok {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	if sub := r.URL.Query().Get("subscription_id"); sub != "" {
		_ = s.accounts.BindSubscription(acc, sub, time.Now().Unix())
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, welcomeShell, "Your account", fmt.Sprintf(accountShellBody, s.accountStateBlock(r, acc)))
}

// accountStateBlock renders the per-account subscription/connection state: a
// re-pair QR for a live connection, the install command for a paid-but-unset-up
// subscription, or a subscribe CTA when there's nothing yet.
func (s *Server) accountStateBlock(r *http.Request, acc string) string {
	base := s.baseURL(r)
	subs, _ := s.accounts.SubscriptionsForAccount(acc)
	for _, sub := range subs {
		if s.provision == nil {
			break
		}
		t, ok := s.provision.GetByCustomer(sub)
		if !ok {
			continue
		}
		if t.ClaimCode != "" {
			install := fmt.Sprintf("curl -fsSL %s/i/%s | sh", base, t.ClaimCode)
			return fmt.Sprintf(accountInstallBlock, html.EscapeString(install), html.EscapeString(install))
		}
		link := pairingDeepLink(base, t.ID, t.Token)
		return fmt.Sprintf(accountConnectedBlock, pairingQRDataURI(link), html.EscapeString(link), html.EscapeString(link))
	}
	return accountNoSubBlock
}

// logout revokes the session and clears the cookie.
func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(sessionCookie); err == nil {
		_ = s.accounts.DeleteSession(c.Value)
	}
	s.clearSessionCookie(w)
	http.Redirect(w, r, "/login", http.StatusSeeOther)
}

// accountFromRequest resolves the signed-in account from the session cookie.
func (s *Server) accountFromRequest(r *http.Request) (string, bool) {
	c, err := r.Cookie(sessionCookie)
	if err != nil {
		return "", false
	}
	acc, err := s.accounts.AccountBySession(c.Value, time.Now().Unix())
	if err != nil {
		return "", false
	}
	return acc, true
}

func (s *Server) setSessionCookie(w http.ResponseWriter, sid string) {
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookie,
		Value:    sid,
		Path:     "/",
		HttpOnly: true,
		Secure:   true,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   int(sessionTTL / time.Second),
	})
}

func (s *Server) clearSessionCookie(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookie,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		Secure:   true,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   -1,
	})
}

// loginFormBody: %[1]s optional error HTML, %[2]s prefilled email.
const loginFormBody = `<h1>Sign in</h1>
<p class="sub">Enter your email and we'll send a one-time sign-in link — no password.</p>
%[1]s<form method="post" action="/login">
  <input type="email" name="email" placeholder="you@example.com" value="%[2]s" autocomplete="email" autocapitalize="off" spellcheck="false" required>
  <div><button class="go" type="submit">Send me a link</button></div>
</form>
<p class="note">The link works once and expires in 15 minutes.</p>`

const loginSentBody = `<h1>Check your email 📬</h1>
<p class="sub">If that address can sign in, a one-time link is on its way. It expires in 15 minutes.</p>`

const loginExpiredBody = `<h1>Link expired</h1>
<p class="sub">That sign-in link is invalid or already used. <a href="/login">Get a new one</a>.</p>`

// accountShellBody: %[1]s the state block.
const accountShellBody = `%[1]s
<form method="post" action="/logout" style="margin-top:30px"><button class="go" type="submit" style="background:#2a2d37">Sign out</button></form>`

const accountNoSubBlock = `<h1>Your account</h1>
<p class="sub">No active subscription yet. <a href="https://shubat.org#pricing">Subscribe</a> to reach your OpenCode from anywhere. Paid on another device? Open the link on your receipt while signed in here to attach it.</p>`

// accountInstallBlock: %[1]s install command (visible), %[2]s (copy payload).
const accountInstallBlock = `<h1>You're subscribed 🎉</h1>
<p class="sub">Finish setup on the machine running OpenCode:</p>
<div class="cmd"><button class="copy" data-copy="%[2]s">Copy</button>%[1]s</div>
<p class="note">It installs a small connector and prints a pairing QR to scan in the app.</p>`

// accountConnectedBlock: %[1]s QR data URI, %[2]s pairing link (visible), %[3]s (copy).
const accountConnectedBlock = `<h1>You're connected ✅</h1>
<p class="sub">Reinstalled the app or got a new phone? Scan to reconnect:</p>
<div style="text-align:center;margin:6px 0 18px"><img src="%[1]s" alt="Pairing QR" width="200" height="200" style="border-radius:12px;background:#fff;padding:12px"></div>
<div class="cmd"><button class="copy" data-copy="%[3]s">Copy</button>%[2]s</div>`
