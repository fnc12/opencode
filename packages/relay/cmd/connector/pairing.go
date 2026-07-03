package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
)

// identity is the persisted tunnel id + token a connector uses to register with
// the relay. The same pair is handed to the mobile app via the pairing payload,
// so the app can both address the tunnel (/t/{id}) and authenticate to it
// (X-Tunnel-Token).
type identity struct {
	TunnelID string `json:"tunnelId"`
	Token    string `json:"token"`
}

// loadOrCreateIdentity resolves the tunnel id and token. Explicit env values
// win field-by-field (and a fully explicit pair is never persisted); otherwise
// it reads connector.json from the config dir, generating and saving a fresh
// random identity the first time the connector runs.
func loadOrCreateIdentity(envID, envToken string) (identity, error) {
	if envID != "" && envToken != "" {
		return identity{TunnelID: envID, Token: envToken}, nil
	}
	path, err := identityPath()
	if err != nil {
		return identity{}, err
	}
	if data, err := os.ReadFile(path); err == nil {
		var id identity
		if json.Unmarshal(data, &id) == nil && id.TunnelID != "" && id.Token != "" {
			if envID != "" {
				id.TunnelID = envID
			}
			if envToken != "" {
				id.Token = envToken
			}
			return id, nil
		}
	}
	id := identity{TunnelID: "tun_" + randHex(8), Token: randHex(24)}
	if envID != "" {
		id.TunnelID = envID
	}
	if envToken != "" {
		id.Token = envToken
	}
	if err := saveIdentity(path, id); err != nil {
		return identity{}, err
	}
	return id, nil
}

// identityPath is where the connector persists its identity. SHUBAT_CONFIG_DIR
// overrides the location (used by tests); otherwise it's ~/.config/shubat on
// Linux/macOS via os.UserConfigDir.
func identityPath() (string, error) {
	if dir := os.Getenv("SHUBAT_CONFIG_DIR"); dir != "" {
		return filepath.Join(dir, "connector.json"), nil
	}
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "shubat", "connector.json"), nil
}

func saveIdentity(path string, id identity) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, _ := json.MarshalIndent(id, "", "  ")
	return os.WriteFile(path, data, 0o600)
}

func randHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		// crypto/rand failing means the OS RNG is broken; nothing safe to do.
		panic(err)
	}
	return hex.EncodeToString(b)
}

// pairingLink builds the opencode://pair deep link the mobile app consumes. The
// relay base is derived from the connector's ws(s) relay URL.
func pairingLink(relayWSURL string, id identity) (string, error) {
	base, err := relayHTTPBase(relayWSURL)
	if err != nil {
		return "", err
	}
	q := url.Values{}
	q.Set("relay", base)
	q.Set("tunnel", id.TunnelID)
	q.Set("token", id.Token)
	return "opencode://pair?" + q.Encode(), nil
}

// relayHTTPBase converts the connector's ws(s)://host/connector dial URL into
// the https://host base the mobile app uses to reach /t/{id}. The ws->http /
// wss->https mapping is reversed and the trailing /connector path is dropped.
func relayHTTPBase(relayWSURL string) (string, error) {
	u, err := url.Parse(relayWSURL)
	if err != nil {
		return "", err
	}
	switch u.Scheme {
	case "ws":
		u.Scheme = "http"
	case "wss":
		u.Scheme = "https"
	case "http", "https":
		// already an HTTP base
	default:
		return "", errors.New("relay URL must be ws:// or wss://")
	}
	u.Path = strings.TrimSuffix(u.Path, "/connector")
	u.Path = strings.TrimSuffix(u.Path, "/")
	u.RawQuery = ""
	u.Fragment = ""
	return u.String(), nil
}

// printPairing writes a human-friendly pairing block to stderr so the operator
// can pair the mobile app right after starting the connector. The deep link can
// be pasted into the app's "paste pairing link" field (or encoded as a QR).
func printPairing(cfg config) {
	link, err := pairingLink(cfg.relayURL, identity{TunnelID: cfg.tunnelID, Token: cfg.token})
	if err != nil {
		return
	}
	fmt.Fprint(os.Stderr, "\n"+
		"  ┌──────────────────────────────────────────────┐\n"+
		"  │  Pair the Shubat app with this server         │\n"+
		"  └──────────────────────────────────────────────┘\n\n"+
		"  Paste this link into the app (Add server → Paste link):\n\n"+
		"    "+link+"\n\n"+
		"  tunnel: "+cfg.tunnelID+"\n"+
		"  token:  "+cfg.token+"\n\n")

	// Also persist it next to the identity so a service-managed connector (whose
	// stdout goes to a log) can still surface the link — the installer reads it.
	if path, err := identityPath(); err == nil {
		_ = os.WriteFile(filepath.Join(filepath.Dir(path), "pairing.txt"), []byte(link+"\n"), 0o600)
	}
}
