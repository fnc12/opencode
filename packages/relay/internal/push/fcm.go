package push

import (
	"bytes"
	"context"
	"crypto/rsa"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

// FCMConfig configures Firebase Cloud Messaging using a service-account JSON.
type FCMConfig struct {
	ServiceAccountPath string // path to the Google service-account JSON
	Endpoint           string // override base for messages:send (default fcm.googleapis.com)
	TokenURL           string // override OAuth token endpoint (for tests)
}

type serviceAccount struct {
	ClientEmail string `json:"client_email"`
	PrivateKey  string `json:"private_key"`
	ProjectID   string `json:"project_id"`
	TokenURI    string `json:"token_uri"`
}

// FCMPusher sends notifications via the FCM HTTP v1 API.
type FCMPusher struct {
	sa       serviceAccount
	key      *rsa.PrivateKey
	client   *http.Client
	sendURL  string
	tokenURL string

	mu       sync.Mutex
	token    string
	tokenExp time.Time
}

const fcmScope = "https://www.googleapis.com/auth/firebase.messaging"

// NewFCMPusher loads the service account and returns a pusher.
func NewFCMPusher(cfg FCMConfig) (*FCMPusher, error) {
	data, err := os.ReadFile(cfg.ServiceAccountPath)
	if err != nil {
		return nil, fmt.Errorf("fcm: read service account: %w", err)
	}
	var sa serviceAccount
	if err := json.Unmarshal(data, &sa); err != nil {
		return nil, fmt.Errorf("fcm: parse service account: %w", err)
	}
	if sa.ProjectID == "" || sa.ClientEmail == "" || sa.PrivateKey == "" {
		return nil, fmt.Errorf("fcm: service account missing project_id/client_email/private_key")
	}
	key, err := parseRSAPrivateKey([]byte(sa.PrivateKey))
	if err != nil {
		return nil, err
	}

	base := cfg.Endpoint
	if base == "" {
		base = "https://fcm.googleapis.com"
	}
	tokenURL := cfg.TokenURL
	if tokenURL == "" {
		tokenURL = sa.TokenURI
	}
	if tokenURL == "" {
		tokenURL = "https://oauth2.googleapis.com/token"
	}

	return &FCMPusher{
		sa:       sa,
		key:      key,
		client:   &http.Client{Timeout: 15 * time.Second},
		sendURL:  fmt.Sprintf("%s/v1/projects/%s/messages:send", base, sa.ProjectID),
		tokenURL: tokenURL,
	}, nil
}

func (p *FCMPusher) Provider() Provider { return FCM }

// accessToken returns a cached OAuth2 access token, minting a new one via the
// service-account JWT grant when needed.
func (p *FCMPusher) accessToken(ctx context.Context) (string, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if time.Now().Before(p.tokenExp) {
		return p.token, nil
	}
	now := time.Now()
	assertion, err := signRS256(p.key,
		map[string]any{"alg": "RS256", "typ": "JWT"},
		map[string]any{
			"iss":   p.sa.ClientEmail,
			"scope": fcmScope,
			"aud":   p.tokenURL,
			"iat":   now.Unix(),
			"exp":   now.Add(time.Hour).Unix(),
		},
	)
	if err != nil {
		return "", err
	}

	form := url.Values{
		"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"},
		"assertion":  {assertion},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.tokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := p.client.Do(req)
	if err != nil {
		return "", fmt.Errorf("fcm: token request: %w", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("fcm: token status %d: %s", resp.StatusCode, body)
	}
	var tok struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.Unmarshal(body, &tok); err != nil {
		return "", err
	}
	p.token = tok.AccessToken
	ttl := time.Duration(tok.ExpiresIn) * time.Second
	if ttl <= 0 {
		ttl = time.Hour
	}
	p.tokenExp = now.Add(ttl - time.Minute)
	return p.token, nil
}

func (p *FCMPusher) Send(ctx context.Context, token string, n Notification) error {
	at, err := p.accessToken(ctx)
	if err != nil {
		return err
	}
	payload, err := json.Marshal(map[string]any{
		"message": map[string]any{
			"token": token,
			"notification": map[string]any{
				"title": n.Title,
				"body":  n.Body,
			},
			"data": map[string]any{
				"sessionId": n.SessionID,
				"deepLink":  n.DeepLink,
			},
		},
	})
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.sendURL, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+at)
	req.Header.Set("Content-Type", "application/json")
	resp, err := p.client.Do(req)
	if err != nil {
		return fmt.Errorf("fcm: send: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
		return fmt.Errorf("fcm: status %d: %s", resp.StatusCode, body)
	}
	return nil
}
