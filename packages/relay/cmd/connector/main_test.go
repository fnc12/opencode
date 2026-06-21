package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/fnc12/opencode/packages/relay/internal/relayserver"
)

// fullStack wires a fake OpenCode server -> connector -> relay and returns the
// relay's base URL plus the fake OpenCode server (so tests can inspect it).
func fullStack(t *testing.T, opencode http.Handler) (relayURL string) {
	t.Helper()

	relay := relayserver.New(relayserver.Config{Secret: "secret", Log: slog.New(slog.NewTextHandler(io.Discard, nil))})
	relayTS := httptest.NewServer(relay.Handler())
	t.Cleanup(relayTS.Close)

	ocTS := httptest.NewServer(opencode)
	t.Cleanup(ocTS.Close)

	cfg := config{
		relayURL: "ws" + strings.TrimPrefix(relayTS.URL, "http") + "/connector",
		tunnelID: "tunZ",
		token:    "secret",
		localURL: ocTS.URL,
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	go func() {
		_ = runSession(ctx, cfg, &http.Client{}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	}()

	// Wait for the tunnel to register.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		resp, err := http.Get(relayTS.URL + "/healthz")
		if err == nil {
			var h struct {
				Tunnels int `json:"tunnels"`
			}
			_ = json.NewDecoder(resp.Body).Decode(&h)
			resp.Body.Close()
			if h.Tunnels > 0 {
				return relayTS.URL
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("tunnel never registered")
	return ""
}

func TestConnectorProxiesGET(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/global/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"version":"9.9.9"}`)
	})
	relayURL := fullStack(t, mux)

	resp, err := http.Get(relayURL + "/t/tunZ/global/health")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("status %d", resp.StatusCode)
	}
	b, _ := io.ReadAll(resp.Body)
	if string(b) != `{"version":"9.9.9"}` {
		t.Fatalf("body %q", b)
	}
}

func TestConnectorForwardsBody(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/echo", func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		fmt.Fprintf(w, "%s:%s", r.Method, b)
	})
	relayURL := fullStack(t, mux)

	resp, err := http.Post(relayURL+"/t/tunZ/echo", "text/plain", strings.NewReader("hello"))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	if string(b) != "POST:hello" {
		t.Fatalf("body %q", b)
	}
}

func TestConnectorSSEAndCancel(t *testing.T) {
	cancelled := make(chan struct{}, 1)
	mux := http.NewServeMux()
	mux.HandleFunc("/event", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		fl, _ := w.(http.Flusher)
		w.WriteHeader(200)
		// Stream until the client (transitively) goes away.
		tick := time.NewTicker(15 * time.Millisecond)
		defer tick.Stop()
		for {
			select {
			case <-r.Context().Done():
				cancelled <- struct{}{}
				return
			case <-tick.C:
				_, _ = io.WriteString(w, "data: tick\n\n")
				if fl != nil {
					fl.Flush()
				}
			}
		}
	})
	relayURL := fullStack(t, mux)

	ctx, cancel := context.WithCancel(context.Background())
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, relayURL+"/t/tunZ/event", nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.Header.Get("Content-Type") != "text/event-stream" {
		t.Fatalf("not an event stream: %q", resp.Header.Get("Content-Type"))
	}

	// Read at least one streamed event, then disconnect.
	buf := make([]byte, 64)
	if _, err := resp.Body.Read(buf); err != nil {
		t.Fatalf("read stream: %v", err)
	}
	cancel()
	resp.Body.Close()

	// The cancel must propagate relay -> connector -> upstream OpenCode request.
	select {
	case <-cancelled:
	case <-time.After(2 * time.Second):
		t.Fatal("upstream SSE request was not cancelled after client disconnect")
	}
}
