package push

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
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
)

func writeECKey(t *testing.T) (string, *ecdsa.PublicKey) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "AuthKey.p8")
	pemBytes := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})
	if err := os.WriteFile(path, pemBytes, 0o600); err != nil {
		t.Fatal(err)
	}
	return path, &key.PublicKey
}

// verifyES256 checks a compact JWT against pub and returns its claims.
func verifyES256(t *testing.T, jwt string, pub *ecdsa.PublicKey) map[string]any {
	t.Helper()
	parts := strings.Split(jwt, ".")
	if len(parts) != 3 {
		t.Fatalf("jwt parts = %d", len(parts))
	}
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil || len(sig) != 64 {
		t.Fatalf("bad sig: %v len=%d", err, len(sig))
	}
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	r := new(big.Int).SetBytes(sig[:32])
	s := new(big.Int).SetBytes(sig[32:])
	if !ecdsa.Verify(pub, digest[:], r, s) {
		t.Fatal("signature did not verify")
	}
	claimsJSON, _ := base64.RawURLEncoding.DecodeString(parts[1])
	var claims map[string]any
	_ = json.Unmarshal(claimsJSON, &claims)
	return claims
}

func TestAPNsSend(t *testing.T) {
	keyPath, pub := writeECKey(t)

	var gotPath, gotTopic, gotAuth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotTopic = r.Header.Get("apns-topic")
		gotAuth = r.Header.Get("authorization")
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	p, err := NewAPNsPusher(APNsConfig{
		KeyPath:  keyPath,
		KeyID:    "ABC123DEFG",
		TeamID:   "TEAM123456",
		Topic:    "com.example.app",
		Endpoint: srv.URL,
	})
	if err != nil {
		t.Fatal(err)
	}

	err = p.Send(context.Background(), "devtoken", Notification{Title: "Session finished", Body: "done", SessionID: "s1"})
	if err != nil {
		t.Fatal(err)
	}

	if gotPath != "/3/device/devtoken" {
		t.Fatalf("path = %q", gotPath)
	}
	if gotTopic != "com.example.app" {
		t.Fatalf("topic = %q", gotTopic)
	}
	jwt, ok := strings.CutPrefix(gotAuth, "bearer ")
	if !ok {
		t.Fatalf("auth = %q", gotAuth)
	}
	claims := verifyES256(t, jwt, pub)
	if claims["iss"] != "TEAM123456" {
		t.Fatalf("iss = %v", claims["iss"])
	}
}
