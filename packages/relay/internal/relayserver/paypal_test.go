package relayserver

import (
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/fnc12/opencode/packages/relay/internal/provision"
)

// paypalServer builds a relay with an injected verifier so the mint/revoke path
// is tested without live PayPal API calls.
func paypalServer(t *testing.T, verify func(http.Header, []byte) error) (*httptest.Server, provision.Store) {
	t.Helper()
	pstore, err := provision.NewFileStore(t.TempDir() + "/tunnels.json")
	if err != nil {
		t.Fatal(err)
	}
	srv := New(Config{
		Log:          slog.New(slog.NewTextHandler(io.Discard, nil)),
		Provision:    pstore,
		AdminSecret:  "admin",
		VerifyPayPal: verify,
	})
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	return ts, pstore
}

func postPayPal(t *testing.T, url, body string) int {
	t.Helper()
	resp, err := http.Post(url, "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	return resp.StatusCode
}

const subActivated = `{"event_type":"BILLING.SUBSCRIPTION.ACTIVATED","resource":{"id":"I-SUB123","status":"ACTIVE","subscriber":{"email_address":"buyer@x.com"}}}`
const subCancelled = `{"event_type":"BILLING.SUBSCRIPTION.CANCELLED","resource":{"id":"I-SUB123"}}`

func TestPayPalActivatedMintsTunnel(t *testing.T) {
	ok := func(http.Header, []byte) error { return nil }
	ts, store := paypalServer(t, ok)

	if code := postPayPal(t, ts.URL+"/paypal/webhook", subActivated); code != http.StatusOK {
		t.Fatalf("status = %d, want 200", code)
	}
	tun, found := store.GetByCustomer("I-SUB123")
	if !found {
		t.Fatal("subscription did not mint a tunnel")
	}
	if tun.ClaimCode == "" {
		t.Error("minted tunnel has no claim code to deliver")
	}
}

func TestPayPalActivatedIsIdempotent(t *testing.T) {
	ok := func(http.Header, []byte) error { return nil }
	ts, store := paypalServer(t, ok)
	postPayPal(t, ts.URL+"/paypal/webhook", subActivated)
	first, _ := store.GetByCustomer("I-SUB123")
	postPayPal(t, ts.URL+"/paypal/webhook", subActivated) // retry
	again, _ := store.GetByCustomer("I-SUB123")
	if first.ID != again.ID {
		t.Errorf("webhook retry minted a second tunnel (%s vs %s)", first.ID, again.ID)
	}
}

func TestPayPalCancelledRevokes(t *testing.T) {
	ok := func(http.Header, []byte) error { return nil }
	ts, store := paypalServer(t, ok)
	postPayPal(t, ts.URL+"/paypal/webhook", subActivated)
	if _, found := store.GetByCustomer("I-SUB123"); !found {
		t.Fatal("precondition: tunnel should exist")
	}
	if code := postPayPal(t, ts.URL+"/paypal/webhook", subCancelled); code != http.StatusOK {
		t.Fatalf("cancel status = %d, want 200", code)
	}
	if _, found := store.GetByCustomer("I-SUB123"); found {
		t.Error("cancellation did not revoke the tunnel")
	}
}

func TestPayPalRejectsBadSignature(t *testing.T) {
	fail := func(http.Header, []byte) error { return errors.New("bad sig") }
	ts, store := paypalServer(t, fail)
	if code := postPayPal(t, ts.URL+"/paypal/webhook", subActivated); code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400 for a failed verification", code)
	}
	if _, found := store.GetByCustomer("I-SUB123"); found {
		t.Error("a webhook that fails verification must not mint")
	}
}

func TestWelcomeBySubscriptionShowsCodeThenPending(t *testing.T) {
	ok := func(http.Header, []byte) error { return nil }
	ts, _ := paypalServer(t, ok)
	// AssetDir is unset here, so /welcome isn't mounted on this server; drive the
	// lookup directly through a second server that has both provision + assets.
	// Simpler: reuse the provision store via a fresh server with AssetDir set.
	_ = ts

	pstore, _ := provision.NewFileStore(t.TempDir() + "/t.json")
	srv := New(Config{
		Log:          slog.New(slog.NewTextHandler(io.Discard, nil)),
		Provision:    pstore,
		AdminSecret:  "admin",
		AssetDir:     t.TempDir(),
		PublicURL:    "https://relay.example.org",
		VerifyPayPal: ok,
	})
	wt := httptest.NewServer(srv.Handler())
	t.Cleanup(wt.Close)

	// Before activation: pending page (not the form).
	_, body := getBody(t, wt.URL+"/welcome?sub=I-XYZ")
	if !strings.Contains(body, "Activating") {
		t.Errorf("unknown subscription should show the activating page, got:\n%s", body)
	}

	// Activate → welcome?sub= now yields the install command.
	postPayPal(t, wt.URL+"/paypal/webhook", `{"event_type":"BILLING.SUBSCRIPTION.ACTIVATED","resource":{"id":"I-XYZ"}}`)
	_, body = getBody(t, wt.URL+"/welcome?sub=I-XYZ")
	if !strings.Contains(body, "/i/") || !strings.Contains(body, "| sh") {
		t.Errorf("activated subscription should render the install command, got:\n%s", body)
	}
}
