package relayserver

import (
	"errors"
	"fmt"
	"html"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/fnc12/opencode/packages/relay/internal/account"
	"github.com/fnc12/opencode/packages/relay/internal/provision"
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
	now := time.Now().Unix()
	if sub := r.URL.Query().Get("subscription_id"); sub != "" {
		_ = s.accounts.BindSubscription(acc, sub, now)
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, welcomeShell, "Your account", s.accountBody(r, acc, now, r.URL.Query().Get("flash")))
}

// promoRedeem applies a promo code to the signed-in account, then redirects back
// to /account with a flash message.
func (s *Server) promoRedeem(w http.ResponseWriter, r *http.Request) {
	acc, ok := s.accountFromRequest(r)
	if !ok {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	code := r.FormValue("code")
	months, err := s.accounts.RedeemPromo(acc, code, time.Now().Unix())
	flash := fmt.Sprintf("Promo applied — %d free month%s added.", months, plural(months))
	switch {
	case errors.Is(err, account.ErrPromoRedeemed):
		flash = "You've already used that promo code."
	case errors.Is(err, account.ErrPromoUsedUp):
		flash = "That promo code has no uses left."
	case err != nil:
		flash = "That promo code isn't valid."
	}
	http.Redirect(w, r, "/account?flash="+url.QueryEscape(flash), http.StatusSeeOther)
}

// tunnelForAccount returns the account's tunnel — from a bound paid subscription
// or an account-owned free tunnel — if one exists.
func (s *Server) tunnelForAccount(acc string) (provision.Tunnel, bool) {
	if s.provision == nil {
		return provision.Tunnel{}, false
	}
	subs, _ := s.accounts.SubscriptionsForAccount(acc)
	for _, sub := range subs {
		if t, ok := s.provision.GetByCustomer(sub); ok {
			return t, true
		}
	}
	// Free tunnels are minted keyed by the account id.
	if t, ok := s.provision.GetByCustomer(acc); ok {
		return t, true
	}
	return provision.Tunnel{}, false
}

// accountBody renders the account page: plan status, connection (re-pair/install),
// referral link, and promo entry.
func (s *Server) accountBody(r *http.Request, acc string, now int64, flash string) string {
	base := s.baseURL(r)
	var b strings.Builder

	if flash != "" {
		b.WriteString(`<p style="background:#14351c;border:1px solid #22c55e55;color:#86efac;padding:10px 14px;border-radius:10px;margin:0 0 18px;font-size:14px">` + html.EscapeString(flash) + `</p>`)
	}
	b.WriteString(`<h1>Your account</h1>`)

	// Plan status: paid subscription, then free entitlement, else nothing.
	hasPaid := false
	if subs, _ := s.accounts.SubscriptionsForAccount(acc); len(subs) > 0 && s.provision != nil {
		for _, sub := range subs {
			if _, ok := s.provision.GetByCustomer(sub); ok {
				hasPaid = true
				break
			}
		}
	}
	until, _ := s.accounts.EntitledUntil(acc)
	switch {
	case hasPaid:
		b.WriteString(`<p class="sub">✓ Subscription active.</p>`)
	case until > now:
		b.WriteString(`<p class="sub">🎁 Free until <b>` + time.Unix(until, 0).UTC().Format("Jan 2, 2006") + `</b>.</p>`)
	default:
		b.WriteString(`<p class="sub">No active plan. <a href="https://shubat.org#pricing">Subscribe ($5/mo)</a>, or redeem a promo code below.</p>`)
	}

	// Connection state.
	if t, ok := s.tunnelForAccount(acc); ok {
		if t.ClaimCode != "" {
			install := fmt.Sprintf("curl -fsSL %s/i/%s | sh", base, t.ClaimCode)
			b.WriteString(fmt.Sprintf(accountInstallBlock, html.EscapeString(install), html.EscapeString(install)))
		} else {
			link := pairingDeepLink(base, t.ID, t.Token)
			b.WriteString(fmt.Sprintf(accountConnectedBlock, pairingQRDataURI(link), html.EscapeString(link), html.EscapeString(link)))
		}
	}

	// Referral.
	if code, _ := s.accounts.ReferralCode(acc); code != "" {
		refLink := base + "/login?ref=" + code
		n, _ := s.accounts.ReferralCount(acc)
		joined := fmt.Sprintf("%d friend%s joined so far.", n, plural(n))
		b.WriteString(fmt.Sprintf(accountReferralBlock, html.EscapeString(refLink), html.EscapeString(refLink), joined))
	}

	// Promo entry.
	b.WriteString(accountPromoBlock)

	b.WriteString(`<form method="post" action="/logout" style="margin-top:30px"><button class="go" type="submit" style="background:#2a2d37">Sign out</button></form>`)
	return b.String()
}

func plural(n int) string {
	if n == 1 {
		return ""
	}
	return "s"
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

// accountInstallBlock: %[1]s install command (visible), %[2]s (copy payload).
const accountInstallBlock = `<p class="sub">Finish setup on the machine running OpenCode:</p>
<div class="cmd"><button class="copy" data-copy="%[2]s">Copy</button>%[1]s</div>
<p class="note">It installs a small connector and prints a pairing QR to scan in the app.</p>`

// accountConnectedBlock: %[1]s QR data URI, %[2]s pairing link (visible), %[3]s (copy).
const accountConnectedBlock = `<p class="sub">✅ Connected. Reinstalled the app or got a new phone? Scan to reconnect:</p>
<div style="text-align:center;margin:6px 0 18px"><img src="%[1]s" alt="Pairing QR" width="200" height="200" style="border-radius:12px;background:#fff;padding:12px"></div>
<div class="cmd"><button class="copy" data-copy="%[3]s">Copy</button>%[2]s</div>`

// accountReferralBlock: %[1]s referral link (visible), %[2]s (copy), %[3]s joined text.
const accountReferralBlock = `<div style="margin:26px 0 0;padding-top:22px;border-top:1px solid #23272e">
<h3 style="font-size:16px;margin:0 0 6px">Invite friends → a free month each 🎁</h3>
<p class="sub" style="margin:0 0 10px">You and each friend who joins both get a free month.</p>
<div class="cmd"><button class="copy" data-copy="%[2]s">Copy</button>%[1]s</div>
<p class="note">%[3]s</p></div>`

// accountPromoBlock: static promo-entry form.
const accountPromoBlock = `<div style="margin:24px 0 0;padding-top:22px;border-top:1px solid #23272e">
<h3 style="font-size:16px;margin:0 0 10px">Have a promo code?</h3>
<form method="post" action="/account/promo" style="display:flex;gap:10px;flex-wrap:wrap">
  <input type="text" name="code" placeholder="PROMO CODE" autocapitalize="characters" autocomplete="off" spellcheck="false" style="flex:1;min-width:180px">
  <button class="go" type="submit" style="margin-top:0">Apply</button>
</form></div>`
