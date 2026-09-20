package relayserver

import (
	"encoding/base64"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/fnc12/opencode/packages/relay/internal/provision"
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

// welcomeServerWithStore adds a provision store so subscription-driven states
// (pending / install / already-connected) can be exercised over /welcome.
func welcomeServerWithStore(t *testing.T) (*httptest.Server, provision.Store) {
	t.Helper()
	pstore, err := provision.NewFileStore(t.TempDir() + "/tunnels.json")
	if err != nil {
		t.Fatal(err)
	}
	srv := New(Config{
		Log:       slog.New(slog.NewTextHandler(io.Discard, nil)),
		Provision: pstore,
		AssetDir:  t.TempDir(),
		PublicURL: "https://relay.example.org",
	})
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	return ts, pstore
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

// A returning subscriber whose connector already claimed its code must NOT loop
// on the "Activating…" page (the old bug): once ClaimCode is cleared we show the
// re-pair page instead, with a QR and the pairing link for the live tunnel.
func TestWelcomeAlreadyConnectedShowsRepair(t *testing.T) {
	ts, store := welcomeServerWithStore(t)
	tun, err := store.MintFor("I-SUB-DONE", "buyer@x.com", 0)
	if err != nil {
		t.Fatal(err)
	}
	claimed, err := store.Claim(tun.ClaimCode) // connector installs → code consumed
	if err != nil {
		t.Fatalf("claim: %v", err)
	}

	status, body := getBody(t, ts.URL+"/welcome?subscription_id=I-SUB-DONE")
	if status != http.StatusOK {
		t.Fatalf("status = %d, want 200", status)
	}
	if strings.Contains(body, "Activating") {
		t.Error("already-connected subscriber must not see the Activating spinner (the bug)")
	}
	if strings.Contains(body, "| sh") {
		t.Error("already-connected subscriber must not be handed a (dead) install command")
	}
	if !strings.Contains(body, "You're connected") {
		t.Errorf("expected the connected page, got:\n%s", body)
	}
	if !strings.Contains(body, "opencode://pair") || !strings.Contains(body, claimed.ID) {
		t.Error("connected page must carry a re-pair link for the live tunnel")
	}
	if !strings.Contains(body, "data:image/png;base64,") {
		t.Error("connected page must embed a scannable QR")
	}
}

// Minted-but-not-yet-claimed → the install command still shows (the connector
// hasn't been set up yet), not the connected page.
func TestWelcomeMintedNotClaimedShowsInstall(t *testing.T) {
	ts, store := welcomeServerWithStore(t)
	if _, err := store.MintFor("I-SUB-NEW", "buyer@x.com", 0); err != nil {
		t.Fatal(err)
	}
	status, body := getBody(t, ts.URL+"/welcome?subscription_id=I-SUB-NEW")
	if status != http.StatusOK {
		t.Fatalf("status = %d, want 200", status)
	}
	if !strings.Contains(body, "/i/") || !strings.Contains(body, "| sh") {
		t.Errorf("minted, unclaimed subscription should render the install command, got:\n%s", body)
	}
	if strings.Contains(body, "You're connected") {
		t.Error("unclaimed subscription must not show the connected page")
	}
}

// Unknown subscription (webhook not landed) → the auto-refreshing pending page.
func TestWelcomeUnknownSubShowsPending(t *testing.T) {
	ts, _ := welcomeServerWithStore(t)
	status, body := getBody(t, ts.URL+"/welcome?subscription_id=I-DOES-NOT-EXIST")
	if status != http.StatusOK {
		t.Fatalf("status = %d, want 200", status)
	}
	if !strings.Contains(body, "Activating") {
		t.Errorf("unknown subscription should show the pending page, got:\n%s", body)
	}
}

// pairingDeepLink must match, byte for byte, the connector's own pairingLink and
// what the app parses: opencode://pair with relay/token/tunnel (Encode sorts keys).
func TestPairingDeepLinkFormat(t *testing.T) {
	got := pairingDeepLink("https://relay.example.org", "tun_abc", "tok_xyz")
	want := "opencode://pair?relay=https%3A%2F%2Frelay.example.org&token=tok_xyz&tunnel=tun_abc"
	if got != want {
		t.Errorf("pairing link\n got: %s\nwant: %s", got, want)
	}
}

// pairingQRDataURI returns an inline PNG data URI (valid PNG magic), so the page
// needs no external script to render the QR.
func TestPairingQRDataURI(t *testing.T) {
	uri := pairingQRDataURI("opencode://pair?relay=https%3A%2F%2Fr.example&tunnel=t&token=k")
	const prefix = "data:image/png;base64,"
	if !strings.HasPrefix(uri, prefix) {
		t.Fatalf("want a %q data URI, got %q", prefix, uri)
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(uri, prefix))
	if err != nil {
		t.Fatalf("payload is not valid base64: %v", err)
	}
	if len(raw) < 8 || string(raw[1:4]) != "PNG" {
		t.Errorf("payload is not a PNG image")
	}
}
