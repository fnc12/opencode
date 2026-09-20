package relayserver

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/fnc12/opencode/packages/relay/internal/push"
)

func TestStatsEndpoint(t *testing.T) {
	disp := push.NewDispatcher(push.NewMemoryStore(), slog.New(slog.NewTextHandler(io.Discard, nil)))
	c := push.NewCounter(filepath.Join(t.TempDir(), "count"))
	c.Add(42)
	disp.SetCounter(c)

	srv := New(Config{Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Dispatcher: disp})
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	resp, err := http.Get(ts.URL + "/stats")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	if got := resp.Header.Get("Access-Control-Allow-Origin"); got != "*" {
		t.Errorf("CORS header = %q, want *", got)
	}
	var out struct {
		Pushes int64 `json:"pushes"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	if out.Pushes != 42 {
		t.Errorf("pushes = %d, want 42", out.Pushes)
	}
}

func TestStatsEndpointZeroWithoutDispatcher(t *testing.T) {
	srv := New(Config{Log: slog.New(slog.NewTextHandler(io.Discard, nil))})
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	resp, err := http.Get(ts.URL + "/stats")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out struct {
		Pushes int64 `json:"pushes"`
	}
	json.NewDecoder(resp.Body).Decode(&out)
	if resp.StatusCode != http.StatusOK || out.Pushes != 0 {
		t.Errorf("no dispatcher → want 200 pushes=0, got %d pushes=%d", resp.StatusCode, out.Pushes)
	}
}
