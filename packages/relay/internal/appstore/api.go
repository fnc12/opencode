package appstore

import (
	"context"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"time"
)

// App Store Server API hosts.
const (
	ProductionAPI = "https://api.storekit.itunes.apple.com"
	SandboxAPI    = "https://api.storekit-sandbox.itunes.apple.com"
)

// APIConfig is an App Store Connect In-App Purchase key. The key file lives on
// the server only (never in the repo).
type APIConfig struct {
	KeyPath  string // .p8 (PKCS#8 EC private key)
	KeyID    string
	IssuerID string
	BundleID string
}

// Client calls the App Store Server API to read a subscription's current state
// straight from Apple. Optional: without a key the relay trusts the signed
// transactions the app and the notifications deliver.
type Client struct {
	key      *ecdsa.PrivateKey
	keyID    string
	issuerID string
	bundleID string
	// BaseURL overrides both hosts (tests); "" → by environment.
	BaseURL string
	HC      *http.Client
}

// NewClient loads the key and returns a client.
func NewClient(cfg APIConfig) (*Client, error) {
	if cfg.KeyPath == "" || cfg.KeyID == "" || cfg.IssuerID == "" || cfg.BundleID == "" {
		return nil, errors.New("appstore: key path, key id, issuer id and bundle id are required")
	}
	raw, err := os.ReadFile(cfg.KeyPath)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(raw)
	if block == nil {
		return nil, errors.New("appstore: key is not PEM")
	}
	k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("appstore: key: %v", err)
	}
	ec, ok := k.(*ecdsa.PrivateKey)
	if !ok {
		return nil, errors.New("appstore: key is not ECDSA")
	}
	return &Client{key: ec, keyID: cfg.KeyID, issuerID: cfg.IssuerID, bundleID: cfg.BundleID}, nil
}

// token mints the short-lived ES256 bearer JWT the Server API expects.
func (c *Client) token(now time.Time) (string, error) {
	enc := func(v any) string {
		b, _ := json.Marshal(v)
		return base64.RawURLEncoding.EncodeToString(b)
	}
	input := enc(map[string]any{"alg": "ES256", "kid": c.keyID, "typ": "JWT"}) + "." + enc(map[string]any{
		"iss": c.issuerID,
		"iat": now.Unix(),
		"exp": now.Add(10 * time.Minute).Unix(),
		"aud": "appstoreconnect-v1",
		"bid": c.bundleID,
	})
	digest := sha256.Sum256([]byte(input))
	r, s, err := ecdsa.Sign(rand.Reader, c.key, digest[:])
	if err != nil {
		return "", err
	}
	sig := make([]byte, 64)
	r.FillBytes(sig[:32])
	s.FillBytes(sig[32:])
	return input + "." + base64.RawURLEncoding.EncodeToString(sig), nil
}

// SubscriptionTransactions returns the latest signed transaction of every
// subscription in the original transaction's group (Get All Subscription
// Statuses). environment is the transaction's ("Sandbox" or "Production").
// The JWS values still need VerifyTransaction.
func (c *Client) SubscriptionTransactions(ctx context.Context, environment, originalTransactionID string) ([]string, error) {
	base := ProductionAPI
	if environment == "Sandbox" {
		base = SandboxAPI
	}
	if c.BaseURL != "" {
		base = c.BaseURL
	}
	tok, err := c.token(time.Now())
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/inApps/v1/subscriptions/"+url.PathEscape(originalTransactionID), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	hc := c.HC
	if hc == nil {
		hc = &http.Client{Timeout: 15 * time.Second}
	}
	resp, err := hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("appstore: subscription status: HTTP %d", resp.StatusCode)
	}
	var out struct {
		Data []struct {
			LastTransactions []struct {
				SignedTransactionInfo string `json:"signedTransactionInfo"`
			} `json:"lastTransactions"`
		} `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, err
	}
	var jws []string
	for _, g := range out.Data {
		for _, t := range g.LastTransactions {
			if t.SignedTransactionInfo != "" {
				jws = append(jws, t.SignedTransactionInfo)
			}
		}
	}
	return jws, nil
}
