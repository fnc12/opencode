package relayserver

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/fnc12/opencode/packages/relay/internal/provision"
)

func stripeSig(secret string, body []byte, ts int64) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(fmt.Sprintf("%d.%s", ts, body)))
	return fmt.Sprintf("t=%d,v1=%s", ts, hex.EncodeToString(mac.Sum(nil)))
}

func postStripe(t *testing.T, url, secret, body string, ts int64) int {
	t.Helper()
	req, _ := http.NewRequest(http.MethodPost, url, strings.NewReader(body))
	if secret != "" {
		req.Header.Set("Stripe-Signature", stripeSig(secret, []byte(body), ts))
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	return resp.StatusCode
}

func TestStripeWebhookGrantsAndRevokes(t *testing.T) {
	ps, err := provision.NewFileStore(filepath.Join(t.TempDir(), "tunnels.json"))
	if err != nil {
		t.Fatal(err)
	}
	const secret = "whsec_test"
	srv := New(Config{
		Provision:           ps,
		AdminSecret:         "admin",
		StripeWebhookSecret: secret,
		Log:                 slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	url := ts.URL + "/stripe/webhook"
	now := time.Now().Unix()

	// A tampered/unsigned event is rejected.
	paid := `{"type":"checkout.session.completed","data":{"object":{"customer":"cus_1","customer_email":"a@b.c"}}}`
	if code := postStripe(t, url, "", paid, now); code != http.StatusBadRequest {
		t.Fatalf("unsigned webhook: got %d", code)
	}
	if code := postStripe(t, url, "wrong-secret", paid, now); code != http.StatusBadRequest {
		t.Fatalf("bad-signature webhook: got %d", code)
	}
	// A stale timestamp is rejected (replay guard).
	if code := postStripe(t, url, secret, paid, now-1000); code != http.StatusBadRequest {
		t.Fatalf("stale webhook: got %d", code)
	}

	// A valid paid event grants access.
	if code := postStripe(t, url, secret, paid, now); code != http.StatusOK {
		t.Fatalf("valid paid webhook: got %d", code)
	}
	tn, ok := ps.GetByCustomer("cus_1")
	if !ok || tn.ClaimCode == "" {
		t.Fatalf("no tunnel minted for customer: %+v", tn)
	}

	// Redelivery is idempotent (same tunnel).
	_ = postStripe(t, url, secret, paid, now)
	again, _ := ps.GetByCustomer("cus_1")
	if again.ID != tn.ID {
		t.Fatal("duplicate tunnel on webhook retry")
	}

	// Cancellation revokes access.
	cancel := `{"type":"customer.subscription.deleted","data":{"object":{"customer":"cus_1"}}}`
	if code := postStripe(t, url, secret, cancel, now); code != http.StatusOK {
		t.Fatalf("cancel webhook: got %d", code)
	}
	if _, ok := ps.GetByCustomer("cus_1"); ok {
		t.Fatal("tunnel not revoked after cancellation")
	}
}
