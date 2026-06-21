package push

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeServiceAccount(t *testing.T, projectID, tokenURL string) string {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	der := x509.MarshalPKCS1PrivateKey(key)
	keyPEM := string(pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: der}))
	sa := map[string]string{
		"client_email": "svc@example.iam.gserviceaccount.com",
		"private_key":  keyPEM,
		"project_id":   projectID,
		"token_uri":    tokenURL,
	}
	data, _ := json.Marshal(sa)
	path := filepath.Join(t.TempDir(), "sa.json")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestFCMSend(t *testing.T) {
	// OAuth token endpoint: verify the JWT-bearer grant, return an access token.
	var grantType string
	tokenSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		grantType = r.Form.Get("grant_type")
		if r.Form.Get("assertion") == "" {
			t.Error("missing assertion")
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"access_token":"ya29.test","expires_in":3600}`)
	}))
	defer tokenSrv.Close()

	// FCM send endpoint: verify bearer token, path and body.
	var gotAuth, gotPath string
	var gotMsg struct {
		Message struct {
			Token        string            `json:"token"`
			Notification map[string]string `json:"notification"`
			Data         map[string]string `json:"data"`
		} `json:"message"`
	}
	sendSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		gotPath = r.URL.Path
		_ = json.NewDecoder(r.Body).Decode(&gotMsg)
		w.WriteHeader(http.StatusOK)
	}))
	defer sendSrv.Close()

	saPath := writeServiceAccount(t, "proj-42", tokenSrv.URL)
	p, err := NewFCMPusher(FCMConfig{ServiceAccountPath: saPath, Endpoint: sendSrv.URL, TokenURL: tokenSrv.URL})
	if err != nil {
		t.Fatal(err)
	}

	err = p.Send(context.Background(), "androidtoken", Notification{Title: "Session finished", Body: "done", SessionID: "s9", DeepLink: "opencode://session/s9"})
	if err != nil {
		t.Fatal(err)
	}

	if grantType != "urn:ietf:params:oauth:grant-type:jwt-bearer" {
		t.Fatalf("grant_type = %q", grantType)
	}
	if gotAuth != "Bearer ya29.test" {
		t.Fatalf("auth = %q", gotAuth)
	}
	if gotPath != "/v1/projects/proj-42/messages:send" {
		t.Fatalf("path = %q", gotPath)
	}
	if gotMsg.Message.Token != "androidtoken" {
		t.Fatalf("token = %q", gotMsg.Message.Token)
	}
	if gotMsg.Message.Notification["title"] != "Session finished" {
		t.Fatalf("title = %q", gotMsg.Message.Notification["title"])
	}
	if !strings.HasSuffix(gotMsg.Message.Data["deepLink"], "/s9") {
		t.Fatalf("deepLink = %q", gotMsg.Message.Data["deepLink"])
	}
}
