package relayserver

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fnc12/opencode/packages/relay/internal/provision"
)

func newProvisionServer(t *testing.T) *httptest.Server {
	t.Helper()
	ps, err := provision.NewFileStore(filepath.Join(t.TempDir(), "tunnels.json"))
	if err != nil {
		t.Fatal(err)
	}
	srv := New(Config{
		Secret:      "owner-secret",
		Provision:   ps,
		AdminSecret: "admin-sec",
		Log:         slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	return ts
}

func postJSON(t *testing.T, url, adminSecret, body string) (int, map[string]any) {
	t.Helper()
	req, _ := http.NewRequest(http.MethodPost, url, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	if adminSecret != "" {
		req.Header.Set("X-Admin-Secret", adminSecret)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	b, _ := io.ReadAll(resp.Body)
	_ = json.Unmarshal(b, &out)
	return resp.StatusCode, out
}

func TestProvisioningClaimRegisterRevoke(t *testing.T) {
	ts := newProvisionServer(t)

	// Minting requires the admin secret.
	if code, _ := postJSON(t, ts.URL+"/admin/tunnels", "", `{"label":"x"}`); code != http.StatusUnauthorized {
		t.Fatalf("mint without admin secret: got %d", code)
	}
	if code, _ := postJSON(t, ts.URL+"/admin/tunnels", "wrong", `{"label":"x"}`); code != http.StatusUnauthorized {
		t.Fatalf("mint with wrong admin secret: got %d", code)
	}

	// Mint a tunnel + claim code.
	code, mint := postJSON(t, ts.URL+"/admin/tunnels", "admin-sec", `{"label":"alice"}`)
	if code != http.StatusCreated {
		t.Fatalf("mint: got %d", code)
	}
	tunnelID, _ := mint["id"].(string)
	claimCode, _ := mint["claimCode"].(string)
	if tunnelID == "" || claimCode == "" {
		t.Fatalf("mint missing fields: %v", mint)
	}

	// Redeem the claim code for the tunnel token.
	code, claim := postJSON(t, ts.URL+"/connector/claim", "", `{"code":"`+claimCode+`"}`)
	if code != http.StatusOK {
		t.Fatalf("claim: got %d", code)
	}
	gotID, _ := claim["tunnelId"].(string)
	token, _ := claim["tunnelToken"].(string)
	if gotID != tunnelID || token == "" {
		t.Fatalf("claim mismatch: %v", claim)
	}

	// The claimed connector self-authenticates with its token (no shared secret).
	ws := dialConnectorTokens(t, ts, tunnelID, "", token)
	defer ws.Close()
	waitTunnel(t, ts)

	// The owner secret is not this tunnel's token → rejected at the proxy
	// (auth fails before reaching the connector, so no serveOnce needed).
	if got := statusFor(t, ts.URL+"/t/"+tunnelID+"/global/health", "owner-secret"); got != http.StatusUnauthorized {
		t.Fatalf("owner secret must not reach a provisioned tunnel: got %d", got)
	}
	if got := statusFor(t, ts.URL+"/t/"+tunnelID+"/global/health", "wrong-token"); got != http.StatusUnauthorized {
		t.Fatalf("wrong token must be rejected: got %d", got)
	}

	// Revoking removes it; a re-claim of the used code fails.
	req, _ := http.NewRequest(http.MethodDelete, ts.URL+"/admin/tunnels/"+tunnelID, nil)
	req.Header.Set("X-Admin-Secret", "admin-sec")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("revoke: got %d", resp.StatusCode)
	}
	if code, _ := postJSON(t, ts.URL+"/connector/claim", "", `{"code":"`+claimCode+`"}`); code != http.StatusForbidden {
		t.Fatalf("claim after revoke should fail: got %d", code)
	}
}

func statusFor(t *testing.T, url, token string) int {
	t.Helper()
	resp, err := getTunnel(url, token)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	return resp.StatusCode
}
