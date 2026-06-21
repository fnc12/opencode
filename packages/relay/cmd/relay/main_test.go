package main

import (
	"bufio"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/fnc12/opencode/packages/relay/internal/tunnel"
	"github.com/gorilla/websocket"
)

func newTestServer(t *testing.T) (*httptest.Server, *server) {
	t.Helper()
	srv := &server{
		reg:    tunnel.NewRegistry(),
		secret: "secret",
		log:    slog.New(slog.NewTextHandler(io.Discard, nil)),
	}
	ts := httptest.NewServer(srv.handler())
	t.Cleanup(ts.Close)
	return ts, srv
}

func dialConnector(t *testing.T, ts *httptest.Server, tunnelID, token string) *websocket.Conn {
	t.Helper()
	url := "ws" + strings.TrimPrefix(ts.URL, "http") + "/connector"
	ws, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	reg, _ := json.Marshal(tunnel.Register{TunnelID: tunnelID, Token: token})
	if err := ws.WriteMessage(websocket.BinaryMessage, tunnel.Encode(tunnel.Frame{Type: tunnel.TypeRegister, Payload: reg})); err != nil {
		t.Fatalf("send register: %v", err)
	}
	_, data, err := ws.ReadMessage()
	if err != nil {
		t.Fatalf("read ack: %v", err)
	}
	f, _ := tunnel.Decode(data)
	var ack tunnel.RegisterAck
	_ = json.Unmarshal(f.Payload, &ack)
	if !ack.OK {
		t.Fatalf("register rejected: %s", ack.Error)
	}
	return ws
}

// serveOnce reads one request frame and replies via the provided handler.
func serveOnce(t *testing.T, ws *websocket.Conn, reply func(head tunnel.RequestHead, body []byte, w func(tunnel.Frame))) {
	t.Helper()
	_, data, err := ws.ReadMessage()
	if err != nil {
		t.Errorf("connector read: %v", err)
		return
	}
	f, _ := tunnel.Decode(data)
	if f.Type != tunnel.TypeRequest {
		t.Errorf("expected request frame, got %d", f.Type)
		return
	}
	i := strings.IndexByte(string(f.Payload), '\n')
	var head tunnel.RequestHead
	_ = json.Unmarshal(f.Payload[:i], &head)
	body := f.Payload[i+1:]
	write := func(fr tunnel.Frame) {
		fr.StreamID = f.StreamID
		_ = ws.WriteMessage(websocket.BinaryMessage, tunnel.Encode(fr))
	}
	reply(head, body, write)
}

func TestProxyRoundTrip(t *testing.T) {
	ts, _ := newTestServer(t)
	ws := dialConnector(t, ts, "tun1", "secret")
	defer ws.Close()

	go serveOnce(t, ws, func(head tunnel.RequestHead, body []byte, w func(tunnel.Frame)) {
		if head.Path != "/global/health" {
			t.Errorf("unexpected path %q", head.Path)
		}
		rh, _ := json.Marshal(tunnel.ResponseHead{Status: 200, Header: map[string][]string{"Content-Type": {"application/json"}}})
		w(tunnel.Frame{Type: tunnel.TypeResponseHead, Payload: rh})
		w(tunnel.Frame{Type: tunnel.TypeData, Payload: []byte(`{"version":"1.2.3"}`)})
		w(tunnel.Frame{Type: tunnel.TypeEnd})
	})

	// Give the registry a moment to record the tunnel.
	waitTunnel(t, ts)
	resp, err := http.Get(ts.URL + "/t/tun1/global/health")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("status %d", resp.StatusCode)
	}
	if ct := resp.Header.Get("Content-Type"); ct != "application/json" {
		t.Fatalf("content-type %q", ct)
	}
	b, _ := io.ReadAll(resp.Body)
	if string(b) != `{"version":"1.2.3"}` {
		t.Fatalf("body %q", b)
	}
}

func TestProxySSEStreaming(t *testing.T) {
	ts, _ := newTestServer(t)
	ws := dialConnector(t, ts, "tun2", "secret")
	defer ws.Close()

	go serveOnce(t, ws, func(head tunnel.RequestHead, body []byte, w func(tunnel.Frame)) {
		rh, _ := json.Marshal(tunnel.ResponseHead{Status: 200, Header: map[string][]string{"Content-Type": {"text/event-stream"}}})
		w(tunnel.Frame{Type: tunnel.TypeResponseHead, Payload: rh})
		for i := 0; i < 3; i++ {
			w(tunnel.Frame{Type: tunnel.TypeData, Payload: []byte("data: event" + string(rune('0'+i)) + "\n\n")})
			time.Sleep(20 * time.Millisecond)
		}
		w(tunnel.Frame{Type: tunnel.TypeEnd})
	})

	waitTunnel(t, ts)
	resp, err := http.Get(ts.URL + "/t/tun2/event")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.Header.Get("Content-Type") != "text/event-stream" {
		t.Fatalf("not an event stream: %q", resp.Header.Get("Content-Type"))
	}
	sc := bufio.NewScanner(resp.Body)
	var got []string
	for sc.Scan() {
		if line := sc.Text(); strings.HasPrefix(line, "data:") {
			got = append(got, line)
		}
	}
	if len(got) != 3 {
		t.Fatalf("expected 3 events, got %d: %v", len(got), got)
	}
}

func TestProxyOfflineTunnel(t *testing.T) {
	ts, _ := newTestServer(t)
	resp, err := http.Get(ts.URL + "/t/nope/global/health")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadGateway {
		t.Fatalf("expected 502, got %d", resp.StatusCode)
	}
}

func TestRegisterRejectsBadToken(t *testing.T) {
	ts, _ := newTestServer(t)
	url := "ws" + strings.TrimPrefix(ts.URL, "http") + "/connector"
	ws, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer ws.Close()
	reg, _ := json.Marshal(tunnel.Register{TunnelID: "x", Token: "wrong"})
	_ = ws.WriteMessage(websocket.BinaryMessage, tunnel.Encode(tunnel.Frame{Type: tunnel.TypeRegister, Payload: reg}))
	_, data, err := ws.ReadMessage()
	if err != nil {
		t.Fatal(err)
	}
	f, _ := tunnel.Decode(data)
	var ack tunnel.RegisterAck
	_ = json.Unmarshal(f.Payload, &ack)
	if ack.OK {
		t.Fatal("expected rejection")
	}
}

// waitTunnel blocks until at least one tunnel is registered.
func waitTunnel(t *testing.T, ts *httptest.Server) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		resp, err := http.Get(ts.URL + "/healthz")
		if err == nil {
			var h struct {
				Tunnels int `json:"tunnels"`
			}
			_ = json.NewDecoder(resp.Body).Decode(&h)
			resp.Body.Close()
			if h.Tunnels > 0 {
				return
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("tunnel never registered")
}
