package relayserver

// End-to-end journey tests that walk the Shubat QA use cases (docs/
// shubat-qa-usecases.md) against a real in-process relay + a real WebSocket
// connector + a fake OpenCode backend. These tie the pieces together —
// mint -> welcome -> claim -> register -> proxy -> revoke — so a regression in
// any link fails here. Run as part of `go test ./...` on every commit.

import (
	"encoding/json"
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
	"github.com/fnc12/opencode/packages/relay/internal/tunnel"
	"github.com/gorilla/websocket"
)

// qaServer builds a fully-wired relay (provision + accounts + PayPal + installer
// routes) for end-to-end journeys, returning the *Server too (for the reaper).
func qaServer(t *testing.T) (*httptest.Server, *Server, provision.Store, *account.Store) {
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
	srv := New(Config{
		Log:          slog.New(slog.NewTextHandler(io.Discard, nil)),
		Provision:    pstore,
		Accounts:     astore,
		Email:        &captureSender{},
		AssetDir:     t.TempDir(),
		PublicURL:    "https://relay.example.org",
		AdminSecret:  "adm",
		VerifyPayPal: func(http.Header, []byte) error { return nil },
	})
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	return ts, srv, pstore, astore
}

// dialProvisionedConnector registers a connector for a provisioned tunnel via
// its per-tunnel token — the real self-auth path (no shared secret).
func dialProvisionedConnector(t *testing.T, ts *httptest.Server, tunnelID, tunnelToken string) *websocket.Conn {
	t.Helper()
	u := "ws" + strings.TrimPrefix(ts.URL, "http") + "/connector"
	ws, _, err := websocket.DefaultDialer.Dial(u, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	reg, _ := json.Marshal(tunnel.Register{TunnelID: tunnelID, TunnelToken: tunnelToken})
	if err := ws.WriteMessage(websocket.BinaryMessage, tunnel.Encode(tunnel.Frame{Type: tunnel.TypeRegister, Payload: reg})); err != nil {
		t.Fatalf("register: %v", err)
	}
	_, data, err := ws.ReadMessage()
	if err != nil {
		t.Fatalf("ack: %v", err)
	}
	f, _ := tunnel.Decode(data)
	var ack tunnel.RegisterAck
	_ = json.Unmarshal(f.Payload, &ack)
	if !ack.OK {
		t.Fatalf("register rejected: %s", ack.Error)
	}
	return ws
}

// claimCode redeems a claim code and returns the tunnel id + token.
func claimCode(t *testing.T, ts *httptest.Server, code string) (id, token string) {
	t.Helper()
	resp, err := http.Post(ts.URL+"/connector/claim", "application/json", strings.NewReader(`{"code":"`+code+`"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("claim status = %d, want 200", resp.StatusCode)
	}
	var out struct {
		TunnelID    string `json:"tunnelId"`
		TunnelToken string `json:"tunnelToken"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&out)
	if out.TunnelID == "" || out.TunnelToken == "" {
		t.Fatal("claim returned an empty tunnel id/token")
	}
	return out.TunnelID, out.TunnelToken
}

func waitGone(t *testing.T, ts *httptest.Server) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if resp, err := http.Get(ts.URL + "/healthz"); err == nil {
			var h struct {
				Tunnels int `json:"tunnels"`
			}
			json.NewDecoder(resp.Body).Decode(&h)
			resp.Body.Close()
			if h.Tunnels == 0 {
				return
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("tunnel did not go offline in time")
}

// okBackend replies 200 with a small JSON body to one proxied request.
func okBackend(t *testing.T, ws *websocket.Conn) {
	go serveOnce(t, ws, func(_ tunnel.RequestHead, _ []byte, w func(tunnel.Frame)) {
		rh, _ := json.Marshal(tunnel.ResponseHead{Status: 200, Header: map[string][]string{"Content-Type": {"application/json"}}})
		w(tunnel.Frame{Type: tunnel.TypeResponseHead, Payload: rh})
		w(tunnel.Frame{Type: tunnel.TypeData, Payload: []byte(`{"ok":true}`)})
		w(tunnel.Frame{Type: tunnel.TypeEnd})
	})
}

// TestQA_E2E_PaidPath covers E2E-1: subscribe -> welcome install -> claim ->
// connector registers -> app reaches OpenCode through the relay -> cancel
// revokes access. Also exercises ONB-1, BILL-2/6, PAIR-3.
func TestQA_E2E_PaidPath(t *testing.T) {
	ts, _, pstore, _ := qaServer(t)

	// BILL-2: an activated subscription mints a claimable tunnel.
	postPayPal(t, ts.URL+"/paypal/webhook", `{"event_type":"BILLING.SUBSCRIPTION.ACTIVATED","resource":{"id":"I-QA"}}`)
	tun, ok := pstore.GetByCustomer("I-QA")
	if !ok || tun.ClaimCode == "" {
		t.Fatal("activated subscription did not mint a claimable tunnel")
	}

	// ONB-1: welcome shows the one-line install command.
	if _, body := getBody(t, ts.URL+"/welcome?subscription_id=I-QA"); !strings.Contains(body, "/i/") || !strings.Contains(body, "| sh") {
		t.Fatalf("welcome missing install command:\n%s", body)
	}

	// ONB-5 + PAIR-1/3: claim -> connector registers -> proxy round-trip.
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
		t.Fatalf("proxy round-trip failed: %d %s", resp.StatusCode, b)
	}

	// PAIR-3: a wrong tunnel token is rejected.
	if r2, _ := getTunnel(ts.URL+"/t/"+id+"/global/health", "nope"); r2 != nil {
		r2.Body.Close()
		if r2.StatusCode != http.StatusUnauthorized {
			t.Errorf("wrong token: want 401, got %d", r2.StatusCode)
		}
	}

	// BILL-6: cancellation revokes the tunnel and drops the connection.
	postPayPal(t, ts.URL+"/paypal/webhook", `{"event_type":"BILLING.SUBSCRIPTION.CANCELLED","resource":{"id":"I-QA"}}`)
	if _, ok := pstore.GetByCustomer("I-QA"); ok {
		t.Error("cancellation should revoke the tunnel")
	}
	waitGone(t, ts)
	if r3, _ := getTunnel(ts.URL+"/t/"+id+"/global/health", token); r3 != nil {
		r3.Body.Close()
		if r3.StatusCode != http.StatusBadGateway {
			t.Errorf("after revoke: want 502 tunnel-offline, got %d", r3.StatusCode)
		}
	}
}

// TestQA_E2E_FreePath covers E2E-2: a first-100 free account connects for free ->
// reaches OpenCode -> the reaper revokes access once entitlement lapses. Also
// exercises ENT-1/8/10.
func TestQA_E2E_FreePath(t *testing.T) {
	ts, srv, pstore, astore := qaServer(t)
	now := time.Now().Unix()

	// ENT-1: a fresh (first-100) account is free-active.
	acc, _, err := astore.AccountForEmail("free@qa.com", now)
	if err != nil {
		t.Fatal(err)
	}
	if !astore.FreeActive(acc, now) {
		t.Fatal("first-100 account should be free-active")
	}
	sid, _ := astore.CreateSession(acc, now, now+100000)
	ck := &http.Cookie{Name: sessionCookie, Value: sid}

	// ENT-8: Connect provisions a free, account-owned tunnel.
	if resp := postForm(t, ts.URL+"/account/connect", ck, url.Values{}); resp.StatusCode != http.StatusSeeOther {
		t.Fatalf("account/connect = %d, want 303", resp.StatusCode)
	}
	tun, ok := pstore.GetByCustomer(acc)
	if !ok || tun.ClaimCode == "" {
		t.Fatal("free connect did not mint a claimable tunnel")
	}

	// Claim -> register -> proxy works.
	id, token := claimCode(t, ts, tun.ClaimCode)
	ws := dialProvisionedConnector(t, ts, id, token)
	defer ws.Close()
	waitTunnel(t, ts)
	okBackend(t, ws)
	resp, err := getTunnel(ts.URL+"/t/"+id+"/global/health", token)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("free proxy round-trip = %d, want 200", resp.StatusCode)
	}

	// ENT-10: once the free window lapses, the reaper revokes the tunnel.
	if n := srv.reapExpiredFree(now + 2*monthSec); n != 1 {
		t.Errorf("reaper revoked %d, want 1", n)
	}
	if _, ok := pstore.GetByCustomer(acc); ok {
		t.Error("reaper should revoke the expired free tunnel")
	}
	waitGone(t, ts)
}
