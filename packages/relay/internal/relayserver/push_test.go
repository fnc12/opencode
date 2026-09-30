package relayserver

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/fnc12/opencode/packages/relay/internal/provision"
	"github.com/fnc12/opencode/packages/relay/internal/push"
	"github.com/fnc12/opencode/packages/relay/internal/tunnel"
	"github.com/gorilla/websocket"
)

type chanPusher struct {
	provider push.Provider
	got      chan push.Notification
}

func (c *chanPusher) Provider() push.Provider { return c.provider }
func (c *chanPusher) Send(_ context.Context, _ string, n push.Notification) error {
	c.got <- n
	return nil
}

func newPushServer(t *testing.T, p push.Pusher) (*httptest.Server, push.Store) {
	t.Helper()
	store := push.NewMemoryStore()
	disp := push.NewDispatcher(store, slog.New(slog.NewTextHandler(io.Discard, nil)), p)
	srv := New(Config{Secret: "secret", Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Store: store, Dispatcher: disp})
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	return ts, store
}

func TestDeviceRegistrationEndpoint(t *testing.T) {
	ts, store := newPushServer(t, &chanPusher{provider: push.APNs, got: make(chan push.Notification, 1)})

	body, _ := json.Marshal(map[string]string{"tunnelId": "tun", "provider": "apns", "token": "tok"})
	resp, err := http.Post(ts.URL+"/api/devices", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("status %d", resp.StatusCode)
	}
	if list, _ := store.List("tun"); len(list) != 1 {
		t.Fatalf("device not stored: %v", list)
	}

	// Bad provider rejected.
	bad, _ := json.Marshal(map[string]string{"tunnelId": "tun", "provider": "x", "token": "tok"})
	resp2, _ := http.Post(ts.URL+"/api/devices", "application/json", bytes.NewReader(bad))
	resp2.Body.Close()
	if resp2.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", resp2.StatusCode)
	}
}

// TestDeviceRegistrationRequiresTunnelToken: when provisioning is on, only a
// caller holding the tunnel's token may register a device for it — so a stranger
// who learns a tunnel id can't subscribe to its push notifications.
func TestDeviceRegistrationRequiresTunnelToken(t *testing.T) {
	pstore, err := provision.NewFileStore(t.TempDir() + "/tunnels.json")
	if err != nil {
		t.Fatal(err)
	}
	tun, err := pstore.Mint("qa", time.Now().Unix())
	if err != nil {
		t.Fatal(err)
	}
	store := push.NewMemoryStore()
	disp := push.NewDispatcher(store, slog.New(slog.NewTextHandler(io.Discard, nil)), &chanPusher{provider: push.APNs, got: make(chan push.Notification, 1)})
	srv := New(Config{Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Store: store, Dispatcher: disp, Provision: pstore, AdminSecret: "adm"})
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)

	reg := func(token string) int {
		body, _ := json.Marshal(map[string]string{"tunnelId": tun.ID, "provider": "apns", "token": "devtok"})
		req, _ := http.NewRequest(http.MethodPost, ts.URL+"/api/devices", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		if token != "" {
			req.Header.Set("X-Tunnel-Token", token)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}

	if code := reg(tun.Token); code != http.StatusNoContent {
		t.Errorf("correct token: got %d, want 204", code)
	}
	if code := reg("wrong-token"); code != http.StatusUnauthorized {
		t.Errorf("wrong token: got %d, want 401", code)
	}
	if code := reg(""); code != http.StatusUnauthorized {
		t.Errorf("no token: got %d, want 401", code)
	}
	// An unknown tunnel id is rejected even with a plausible token.
	body, _ := json.Marshal(map[string]string{"tunnelId": "tun_unknown", "provider": "apns", "token": "devtok"})
	req, _ := http.NewRequest(http.MethodPost, ts.URL+"/api/devices", bytes.NewReader(body))
	req.Header.Set("X-Tunnel-Token", tun.Token)
	resp, _ := http.DefaultClient.Do(req)
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("unknown tunnel: got %d, want 401", resp.StatusCode)
	}
}

// TestWatcherPushesOnIdle wires a fake connector that streams a session.idle
// event when the relay's watcher subscribes to /global/event, and asserts a
// push is dispatched to the registered device.
func TestWatcherPushesOnIdle(t *testing.T) {
	pusher := &chanPusher{provider: push.APNs, got: make(chan push.Notification, 4)}
	ts, store := newPushServer(t, pusher)
	_ = store.Add("tunW", push.Device{Provider: push.APNs, Token: "tok"})

	ws := dialConnector(t, ts, "tunW", "secret")
	defer ws.Close()

	// Answer the watcher's GET /global/event with an SSE idle event.
	go func() {
		_, data, err := ws.ReadMessage()
		if err != nil {
			return
		}
		f, _ := tunnel.Decode(data)
		if f.Type != tunnel.TypeRequest {
			t.Errorf("expected request, got %d", f.Type)
			return
		}
		head, _, _ := tunnel.DecodeRequest(f.Payload)
		if head.Path != "/global/event" {
			t.Errorf("watcher path = %q", head.Path)
		}
		write := func(fr tunnel.Frame) {
			fr.StreamID = f.StreamID
			_ = ws.WriteMessage(websocket.BinaryMessage, tunnel.Encode(fr))
		}
		rh, _ := json.Marshal(tunnel.ResponseHead{Status: 200, Header: map[string][]string{"Content-Type": {"text/event-stream"}}})
		write(tunnel.Frame{Type: tunnel.TypeResponseHead, Payload: rh})
		event := `data: {"payload":{"type":"session.idle","properties":{"sessionID":"sess-123"}}}` + "\n\n"
		write(tunnel.Frame{Type: tunnel.TypeData, Payload: []byte(event)})
	}()

	select {
	case n := <-pusher.got:
		if n.SessionID != "sess-123" {
			t.Fatalf("sessionID = %q", n.SessionID)
		}
		if !strings.Contains(n.DeepLink, "sess-123") {
			t.Fatalf("deepLink = %q", n.DeepLink)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("no push dispatched on session idle")
	}
}
