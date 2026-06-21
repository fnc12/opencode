package push

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"sync"
	"time"
)

// APNsConfig configures token-based (.p8) APNs authentication.
type APNsConfig struct {
	KeyPath  string // path to the .p8 auth key
	KeyID    string // 10-char key id
	TeamID   string // 10-char team id
	Topic    string // app bundle id (apns-topic)
	Endpoint string // override; default api.push.apple.com. Use the sandbox host for dev.
}

// APNsPusher sends notifications via APNs HTTP/2.
type APNsPusher struct {
	cfg    APNsConfig
	key    *ecdsa.PrivateKey
	client *http.Client

	mu       sync.Mutex
	token    string
	tokenExp time.Time
}

// NewAPNsPusher loads the auth key and returns a pusher. Go's http.Client
// negotiates HTTP/2 over TLS automatically, which APNs requires.
func NewAPNsPusher(cfg APNsConfig) (*APNsPusher, error) {
	pem, err := os.ReadFile(cfg.KeyPath)
	if err != nil {
		return nil, fmt.Errorf("apns: read key: %w", err)
	}
	key, err := parseECPrivateKey(pem)
	if err != nil {
		return nil, err
	}
	if cfg.Endpoint == "" {
		cfg.Endpoint = "https://api.push.apple.com"
	}
	return &APNsPusher{cfg: cfg, key: key, client: &http.Client{Timeout: 15 * time.Second}}, nil
}

func (p *APNsPusher) Provider() Provider { return APNs }

// authToken returns a cached provider JWT, refreshing it before APNs's ~60min
// limit.
func (p *APNsPusher) authToken() (string, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if time.Now().Before(p.tokenExp) {
		return p.token, nil
	}
	now := time.Now()
	tok, err := signES256(p.key,
		map[string]any{"alg": "ES256", "kid": p.cfg.KeyID, "typ": "JWT"},
		map[string]any{"iss": p.cfg.TeamID, "iat": now.Unix()},
	)
	if err != nil {
		return "", err
	}
	p.token = tok
	p.tokenExp = now.Add(50 * time.Minute)
	return tok, nil
}

func (p *APNsPusher) Send(ctx context.Context, token string, n Notification) error {
	payload, err := json.Marshal(map[string]any{
		"aps": map[string]any{
			"alert": map[string]any{"title": n.Title, "body": n.Body},
			"sound": "default",
		},
		"sessionId": n.SessionID,
		"deepLink":  n.DeepLink,
	})
	if err != nil {
		return err
	}

	jwt, err := p.authToken()
	if err != nil {
		return err
	}

	url := p.cfg.Endpoint + "/3/device/" + token
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("authorization", "bearer "+jwt)
	req.Header.Set("apns-topic", p.cfg.Topic)
	req.Header.Set("apns-push-type", "alert")

	resp, err := p.client.Do(req)
	if err != nil {
		return fmt.Errorf("apns: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
		return fmt.Errorf("apns: status %d: %s", resp.StatusCode, body)
	}
	return nil
}
