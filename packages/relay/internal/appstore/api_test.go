package appstore

import (
	"context"
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"math/big"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fnc12/opencode/packages/relay/internal/appstore/appstoretest"
)

func writeKey(t *testing.T, k *ecdsa.PrivateKey) string {
	t.Helper()
	der, err := x509.MarshalPKCS8PrivateKey(k)
	if err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(t.TempDir(), "SubscriptionKey_TEST.p8")
	if err := os.WriteFile(p, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestSubscriptionTransactions(t *testing.T) {
	key := appstoretest.GenKey(t)
	ca := appstoretest.New(t)
	signed := ca.Sign(t, tx("2002"))

	var auth, path string
	api := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth, path = r.Header.Get("Authorization"), r.URL.Path
		json.NewEncoder(w).Encode(map[string]any{"data": []any{map[string]any{
			"lastTransactions": []any{map[string]any{"originalTransactionId": "1000", "status": 1, "signedTransactionInfo": signed}},
		}}})
	}))
	defer api.Close()

	c, err := NewClient(APIConfig{KeyPath: writeKey(t, key), KeyID: "KEY123", IssuerID: "iss-1", BundleID: "studio.eugenezakharov.shubat"})
	if err != nil {
		t.Fatal(err)
	}
	c.BaseURL = api.URL
	got, err := c.SubscriptionTransactions(context.Background(), "Sandbox", "1000")
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0] != signed {
		t.Fatalf("transactions = %v", got)
	}
	if path != "/inApps/v1/subscriptions/1000" {
		t.Errorf("path = %s", path)
	}

	// The bearer is an ES256 JWT for the App Store Server API, signed by our key.
	jwt := strings.TrimPrefix(auth, "Bearer ")
	parts := strings.Split(jwt, ".")
	if len(parts) != 3 {
		t.Fatalf("bearer is not a JWT: %q", auth)
	}
	var hdr, claims map[string]any
	hb, _ := base64.RawURLEncoding.DecodeString(parts[0])
	cb, _ := base64.RawURLEncoding.DecodeString(parts[1])
	json.Unmarshal(hb, &hdr)
	json.Unmarshal(cb, &claims)
	if hdr["kid"] != "KEY123" || hdr["alg"] != "ES256" {
		t.Errorf("header = %v", hdr)
	}
	if claims["iss"] != "iss-1" || claims["aud"] != "appstoreconnect-v1" || claims["bid"] != "studio.eugenezakharov.shubat" {
		t.Errorf("claims = %v", claims)
	}
	sig, _ := base64.RawURLEncoding.DecodeString(parts[2])
	d := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	if !ecdsa.Verify(&key.PublicKey, d[:], new(big.Int).SetBytes(sig[:32]), new(big.Int).SetBytes(sig[32:])) {
		t.Error("bearer signature does not verify with the configured key")
	}
}

func TestSubscriptionTransactionsHTTPError(t *testing.T) {
	api := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer api.Close()
	c, err := NewClient(APIConfig{KeyPath: writeKey(t, appstoretest.GenKey(t)), KeyID: "K", IssuerID: "I", BundleID: "B"})
	if err != nil {
		t.Fatal(err)
	}
	c.BaseURL = api.URL
	if _, err := c.SubscriptionTransactions(context.Background(), "Production", "1000"); err == nil {
		t.Fatal("want error on HTTP 401")
	}
}

func TestNewClientRequiresConfig(t *testing.T) {
	if _, err := NewClient(APIConfig{KeyID: "K"}); err == nil {
		t.Fatal("want error without key path / issuer / bundle")
	}
}
