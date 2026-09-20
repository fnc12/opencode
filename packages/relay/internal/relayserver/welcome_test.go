package relayserver

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func welcomeServer(t *testing.T) *httptest.Server {
	t.Helper()
	srv := New(Config{
		Log:       slog.New(slog.NewTextHandler(io.Discard, nil)),
		AssetDir:  t.TempDir(), // enables the /welcome + /i routes
		PublicURL: "https://relay.example.org",
	})
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	return ts
}

func getBody(t *testing.T, url string) (int, string) {
	t.Helper()
	resp, err := http.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b)
}

func TestWelcomeWithValidCodeShowsInstallCommand(t *testing.T) {
	ts := welcomeServer(t)
	status, body := getBody(t, ts.URL+"/welcome?code=AB12-CD34")
	if status != http.StatusOK {
		t.Fatalf("status = %d, want 200", status)
	}
	want := "curl -fsSL https://relay.example.org/i/AB12-CD34 | sh"
	if !strings.Contains(body, want) {
		t.Errorf("body missing install command %q", want)
	}
	if !strings.Contains(body, "AB12-CD34") {
		t.Errorf("body missing the code")
	}
}

func TestWelcomeWithoutCodeShowsForm(t *testing.T) {
	ts := welcomeServer(t)
	status, body := getBody(t, ts.URL+"/welcome")
	if status != http.StatusOK {
		t.Fatalf("status = %d, want 200", status)
	}
	if !strings.Contains(body, `name="code"`) {
		t.Errorf("expected a code input form, got:\n%s", body)
	}
	if strings.Contains(body, "| sh") {
		t.Errorf("no code → must not render an install command")
	}
}

func TestWelcomeRejectsMalformedCodeAsForm(t *testing.T) {
	ts := welcomeServer(t)
	// A code with characters outside the claim-code charset falls back to the
	// form rather than echoing an install command for junk.
	status, body := getBody(t, ts.URL+"/welcome?code=../etc/passwd")
	if status != http.StatusOK {
		t.Fatalf("status = %d, want 200", status)
	}
	if strings.Contains(body, "| sh") {
		t.Errorf("malformed code must not produce an install command")
	}
	if !strings.Contains(body, `name="code"`) {
		t.Errorf("malformed code should show the form")
	}
}
