package push

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
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

// APNs environments. A device token is only valid against the environment its
// build was signed for: dev/Xcode builds get sandbox tokens, TestFlight/App
// Store builds get production ones.
const (
	apnsProduction = "https://api.push.apple.com"
	apnsSandbox    = "https://api.sandbox.push.apple.com"
)

// APNsPusher sends notifications via APNs HTTP/2.
type APNsPusher struct {
	cfg    APNsConfig
	key    *ecdsa.PrivateKey
	client *http.Client
	// prod/sandbox are the environment pair used for the BadDeviceToken
	// fallback (overridable in tests).
	prod, sandbox string
	// envCache remembers which environment last accepted each token, so the
	// fallback costs one failed request per token, once.
	envCache sync.Map // token -> endpoint

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
		cfg.Endpoint = apnsProduction
	}
	return &APNsPusher{
		cfg: cfg, key: key, client: &http.Client{Timeout: 15 * time.Second},
		prod: apnsProduction, sandbox: apnsSandbox,
	}, nil
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

	// Start from the environment that last accepted this token (or the
	// configured default).
	endpoint := p.cfg.Endpoint
	if v, ok := p.envCache.Load(token); ok {
		endpoint = v.(string)
	}
	err = p.post(ctx, endpoint, token, payload, jwt)
	if err == nil {
		p.envCache.Store(token, endpoint)
		return nil
	}

	// BadDeviceToken usually means the token belongs to the OTHER environment:
	// dev-signed builds register sandbox tokens while the relay is configured
	// for production (TestFlight), or vice versa. Retry the other environment
	// so both build types receive pushes simultaneously. If BOTH reject the
	// token, it is dead — report it as gone so the dispatcher can prune it.
	if isAPNsBadToken(err) {
		other := p.sandbox
		if endpoint == p.sandbox {
			other = p.prod
		}
		err2 := p.post(ctx, other, token, payload, jwt)
		if err2 == nil {
			p.envCache.Store(token, other)
			return nil
		}
		if isAPNsBadToken(err2) {
			return fmt.Errorf("%w: %v", ErrTokenGone, err)
		}
		return err2
	}
	// 410 Unregistered: the app was uninstalled — prune.
	if apnsStatus(err) == http.StatusGone {
		return fmt.Errorf("%w: %v", ErrTokenGone, err)
	}
	return err
}

// apnsError carries the APNs HTTP status + body for fallback/prune decisions.
type apnsError struct {
	status int
	body   string
}

func (e *apnsError) Error() string { return fmt.Sprintf("apns: status %d: %s", e.status, e.body) }

func isAPNsBadToken(err error) bool {
	var ae *apnsError
	return errors.As(err, &ae) && ae.status == http.StatusBadRequest && strings.Contains(ae.body, "BadDeviceToken")
}

func apnsStatus(err error) int {
	var ae *apnsError
	if errors.As(err, &ae) {
		return ae.status
	}
	return 0
}

func (p *APNsPusher) post(ctx context.Context, endpoint, token string, payload []byte, jwt string) error {
	url := endpoint + "/3/device/" + token
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
		return &apnsError{status: resp.StatusCode, body: string(body)}
	}
	return nil
}
