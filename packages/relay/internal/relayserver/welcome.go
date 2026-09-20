package relayserver

import (
	"encoding/base64"
	"fmt"
	"html"
	"net/http"
	"net/url"

	"github.com/fnc12/opencode/packages/relay/internal/provision"
	"rsc.io/qr"
)

// welcome serves a human-friendly landing page that turns a claim code into the
// one-line connector install command. This is the "delivery" step the payment
// flow was missing: instead of pasting a raw `curl … | sh` into a chat, the
// owner sends a stranger `<relay>/welcome?code=XXXX-YYYY`.
//
// It is payment-neutral on purpose — a promo code handed to a first user and a
// (future) paid code both land here. Minting still happens elsewhere
// (/admin/tunnels today, a payment webhook later); this only presents a code.
func (s *Server) welcome(w http.ResponseWriter, r *http.Request) {
	base := s.publicURL
	if base == "" {
		base = "https://" + r.Host
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")

	code := r.URL.Query().Get("code")

	// PayPal return: `?sub=<subscription-id>` — after checkout PayPal redirects
	// here; look the minted code up by subscription (the webhook binds it as the
	// customer id). If the webhook hasn't landed yet, show a "activating" page
	// that reloads shortly rather than the form.
	if code == "" {
		// PayPal appends `subscription_id` on its return redirect; `sub` is the
		// short alias for links we build ourselves.
		sub := r.URL.Query().Get("subscription_id")
		if sub == "" {
			sub = r.URL.Query().Get("sub")
		}
		if sub != "" && s.provision != nil {
			if t, ok := s.provision.GetByCustomer(sub); ok {
				if t.ClaimCode != "" {
					// Minted but the connector hasn't claimed it yet → the code is
					// still live, show the install command.
					code = t.ClaimCode
				} else {
					// Already claimed: a returning, active subscriber. Don't loop on
					// the "Activating…" page — show a re-pair QR for the existing
					// tunnel so a reinstalled app or new phone reconnects.
					s.welcomeConnected(w, base, t)
					return
				}
			} else {
				// No tunnel for this subscription yet — the activation webhook is
				// still in flight. Show the auto-refreshing pending page.
				fmt.Fprintf(w, welcomeShell, "Activating…", welcomePendingBody)
				return
			}
		}
	}

	if code == "" || !claimCodeRe.MatchString(code) {
		// No (valid) code yet → a small form to paste one.
		fmt.Fprintf(w, welcomeShell, "Connect your OpenCode", fmt.Sprintf(welcomeFormBody, html.EscapeString(code)))
		return
	}

	install := fmt.Sprintf("curl -fsSL %s/i/%s | sh", base, code)
	body := fmt.Sprintf(welcomeCodeBody,
		html.EscapeString(code),
		html.EscapeString(install), // <code> display
		html.EscapeString(install), // data-copy attribute
	)
	fmt.Fprintf(w, welcomeShell, "Connect your OpenCode", body)
}

// welcomeConnected is shown to a returning subscriber whose connector has already
// claimed its code. Their subscription is active and the connector keeps running,
// so the only thing they might need is to re-pair a device (reinstalled app, new
// phone). We render the same pairing deep link the connector emits, plus a
// scannable QR, so reconnecting is a scan away — no reinstall, no new code.
func (s *Server) welcomeConnected(w http.ResponseWriter, base string, t provision.Tunnel) {
	link := pairingDeepLink(base, t.ID, t.Token)
	body := fmt.Sprintf(welcomeConnectedBody,
		pairingQRDataURI(link),  // <img src>
		html.EscapeString(link), // visible link
		html.EscapeString(link), // data-copy attribute
	)
	fmt.Fprintf(w, welcomeShell, "You're connected", body)
}

// pairingDeepLink builds the opencode://pair link the mobile app consumes. It
// mirrors the connector's own pairingLink exactly (relay/tunnel/token params) so
// a QR from here and a QR from the connector are interchangeable.
func pairingDeepLink(relayBase, tunnelID, token string) string {
	q := url.Values{}
	q.Set("relay", relayBase)
	q.Set("tunnel", tunnelID)
	q.Set("token", token)
	return "opencode://pair?" + q.Encode()
}

// pairingQRDataURI encodes a pairing link as a QR PNG and returns it as a data:
// URI for inline <img> use. Rendered server-side (rsc.io/qr) so the page needs no
// external script or CDN. On the rare encode error it returns "" and the page
// still shows the copyable link.
func pairingQRDataURI(link string) string {
	code, err := qr.Encode(link, qr.M)
	if err != nil {
		return ""
	}
	return "data:image/png;base64," + base64.StdEncoding.EncodeToString(code.PNG())
}

// welcomeShell is the page chrome (title + minimal dark/light styling). Two
// %s: the <title>/heading and the inner body HTML.
const welcomeShell = `<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%[1]s</title>
<style>
  :root { color-scheme: light dark; }
  * { box-sizing: border-box; }
  body { margin: 0; font: 16px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
         background: #0e0f13; color: #e7e9ee; display: flex; justify-content: center; }
  @media (prefers-color-scheme: light) { body { background: #f6f7f9; color: #1a1c20; } }
  main { width: 100%%; max-width: 620px; padding: 40px 22px 64px; }
  h1 { font-size: 24px; margin: 0 0 4px; }
  p.sub { margin: 0 0 28px; opacity: .7; }
  ol { padding-left: 20px; margin: 0 0 28px; }
  li { margin: 0 0 14px; }
  .cmd { position: relative; background: #1b1d24; border: 1px solid #2a2d37; border-radius: 10px;
         padding: 14px 52px 14px 14px; font: 13px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;
         overflow-x: auto; white-space: pre; }
  @media (prefers-color-scheme: light) { .cmd { background: #fff; border-color: #dfe2e8; } }
  button.copy { position: absolute; top: 8px; right: 8px; border: 0; border-radius: 7px; cursor: pointer;
                padding: 6px 10px; font-size: 12px; background: #3b82f6; color: #fff; }
  button.copy:active { transform: translateY(1px); }
  input[type=text] { width: 100%%; padding: 12px 14px; border-radius: 10px; border: 1px solid #2a2d37;
                     background: #1b1d24; color: inherit; font: 14px ui-monospace,monospace; }
  @media (prefers-color-scheme: light) { input[type=text] { background:#fff; border-color:#dfe2e8; } }
  .go { margin-top: 12px; padding: 11px 18px; border: 0; border-radius: 10px; background:#3b82f6;
        color:#fff; font-size:15px; cursor:pointer; }
  .note { font-size: 13px; opacity: .65; margin-top: 22px; }
  a { color: #3b82f6; }
</style></head>
<body><main>%[2]s
<script>
  document.querySelectorAll('button.copy').forEach(function(b){
    b.addEventListener('click', function(){
      navigator.clipboard.writeText(b.dataset.copy).then(function(){
        var t=b.textContent; b.textContent='Copied'; setTimeout(function(){b.textContent=t;},1200);
      });
    });
  });
</script>
</main></body></html>`

// welcomeCodeBody is shown when a valid code is present. One %s for the code,
// two for the install command (visible text + copy payload).
const welcomeCodeBody = `<h1>You're in 🎉</h1>
<p class="sub">Access code <strong>%[1]s</strong>. Two minutes to reach your OpenCode from anywhere.</p>
<ol>
  <li>On the machine where <strong>OpenCode is running</strong>, paste this in a terminal:
    <div class="cmd"><button class="copy" data-copy="%[3]s">Copy</button>%[2]s</div>
    It asks for your OpenCode URL &amp; password, installs a small connector as a background service, and prints a pairing QR.</li>
  <li>Open the app, choose <strong>Relay</strong> → <strong>Scan pairing QR</strong>, and point it at that QR.</li>
  <li>Done — your projects load over the relay, on any network.</li>
</ol>
<p class="note">The connector dials out to the relay, so no ports or firewall changes are needed. Keep OpenCode running for the app to reach it.</p>`

// welcomePendingBody is shown right after a PayPal return, while the activation
// webhook is still in flight. Meta-refresh so the code appears once it lands.
const welcomePendingBody = `<meta http-equiv="refresh" content="4">
<h1>Activating your access…</h1>
<p class="sub">Payment received — setting up your tunnel. This page refreshes automatically; it usually takes a few seconds.</p>`

// welcomeFormBody is shown when no valid code is present. One %s pre-fills any
// (rejected) code the user already typed.
const welcomeFormBody = `<h1>Connect your OpenCode</h1>
<p class="sub">Enter the access code you were given to get your install command.</p>
<form method="get" action="/welcome">
  <input type="text" name="code" placeholder="XXXX-YYYY" value="%[1]s" autocapitalize="characters" autocomplete="off" spellcheck="false">
  <div><button class="go" type="submit">Continue</button></div>
</form>`

// welcomeConnectedBody is shown to a returning, already-connected subscriber.
// Three args: the QR image data URI, and the pairing link twice (visible text +
// copy payload).
const welcomeConnectedBody = `<h1>You're connected ✅</h1>
<p class="sub">Your subscription is active and your connector is set up. Reinstalled the app or got a new phone? Scan to reconnect — no reinstall, no new code.</p>
<div style="text-align:center;margin:6px 0 18px">
  <img src="%[1]s" alt="Pairing QR" width="220" height="220" style="border-radius:12px;background:#fff;padding:12px">
</div>
<div class="cmd"><button class="copy" data-copy="%[3]s">Copy</button>%[2]s</div>
<ol>
  <li>Open the app → <strong>Relay</strong> → <strong>Scan pairing QR</strong>, or paste the link above.</li>
  <li>Your projects load over the relay again — the connector keeps running on your machine.</li>
</ol>
<p class="note">Manage or cancel your subscription anytime in your <a href="https://www.paypal.com/myaccount/autopay/">PayPal account</a>.</p>`
