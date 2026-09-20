package relayserver

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// paypalEvent is the slice of a PayPal webhook event we act on. Subscription
// events carry the subscription id in resource.id; that id is our stable
// customer key (the same id arrives on cancellation, so revoke can match it).
type paypalEvent struct {
	EventType string `json:"event_type"`
	Resource  struct {
		ID         string `json:"id"`
		Status     string `json:"status"`
		Subscriber struct {
			Email   string `json:"email_address"`
			PayerID string `json:"payer_id"`
		} `json:"subscriber"`
	} `json:"resource"`
}

// handlePayPalWebhook mints a tunnel when a subscription activates and revokes
// it on cancel/expire/suspend — the paid-access gate, PayPal side. The claim
// code is delivered by the /welcome?sub=<subscription-id> page (which looks the
// code up once this webhook has minted it). Mirrors handleStripeWebhook.
func (s *Server) handlePayPalWebhook(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1<<20))
	if err != nil {
		http.Error(w, "read error", http.StatusBadRequest)
		return
	}
	if err := s.verifyPayPal(r.Header, body); err != nil {
		s.log.Warn("paypal webhook rejected", "err", err)
		http.Error(w, "signature verification failed", http.StatusBadRequest)
		return
	}
	var ev paypalEvent
	if err := json.Unmarshal(body, &ev); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	sub := ev.Resource.ID
	switch ev.EventType {
	case "BILLING.SUBSCRIPTION.ACTIVATED", "BILLING.SUBSCRIPTION.CREATED", "PAYMENT.SALE.COMPLETED":
		if sub == "" {
			break
		}
		label := ev.Resource.Subscriber.Email
		if label == "" {
			label = sub
		}
		t, err := s.provision.MintFor(sub, label, time.Now().Unix())
		if err != nil {
			http.Error(w, "mint failed", http.StatusInternalServerError)
			return
		}
		s.log.Info("paypal: access granted", "subscription", sub, "tunnel", t.ID, "claimCode", t.ClaimCode)
	case "BILLING.SUBSCRIPTION.CANCELLED", "BILLING.SUBSCRIPTION.EXPIRED", "BILLING.SUBSCRIPTION.SUSPENDED":
		if sub == "" {
			break
		}
		if id := s.provision.DeleteByCustomer(sub); id != "" {
			if conn := s.reg.Get(id); conn != nil {
				conn.Close()
			}
			s.log.Info("paypal: access revoked", "subscription", sub, "tunnel", id)
		}
	default:
		// Ignore other event types.
	}
	w.WriteHeader(http.StatusOK)
}

// PayPalConfig holds the credentials needed to verify PayPal webhooks. Live
// toggles the API base between sandbox and production.
type PayPalConfig struct {
	ClientID  string
	Secret    string
	WebhookID string
	Live      bool
}

func (c PayPalConfig) apiBase() string {
	if c.Live {
		return "https://api-m.paypal.com"
	}
	return "https://api-m.sandbox.paypal.com"
}

// newPayPalVerifier returns a verifier that authenticates a webhook against
// PayPal's verify-webhook-signature API (the recommended method): fetch an OAuth
// token with the app credentials, then ask PayPal whether the transmission
// headers + our webhook id + the raw event body form a valid, untampered
// delivery. Returns nil only on verification_status == "SUCCESS".
func newPayPalVerifier(cfg PayPalConfig, hc *http.Client) func(http.Header, []byte) error {
	if hc == nil {
		hc = &http.Client{Timeout: 15 * time.Second}
	}
	return func(h http.Header, body []byte) error {
		token, err := paypalToken(cfg, hc)
		if err != nil {
			return err
		}
		payload := map[string]any{
			"auth_algo":         h.Get("Paypal-Auth-Algo"),
			"cert_url":          h.Get("Paypal-Cert-Url"),
			"transmission_id":   h.Get("Paypal-Transmission-Id"),
			"transmission_sig":  h.Get("Paypal-Transmission-Sig"),
			"transmission_time": h.Get("Paypal-Transmission-Time"),
			"webhook_id":        cfg.WebhookID,
			"webhook_event":     json.RawMessage(body),
		}
		buf, _ := json.Marshal(payload)
		req, _ := http.NewRequest(http.MethodPost, cfg.apiBase()+"/v1/notifications/verify-webhook-signature", bytes.NewReader(buf))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer "+token)
		resp, err := hc.Do(req)
		if err != nil {
			return err
		}
		defer resp.Body.Close()
		var out struct {
			VerificationStatus string `json:"verification_status"`
		}
		if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
			return err
		}
		if out.VerificationStatus != "SUCCESS" {
			return fmt.Errorf("paypal verification_status=%q", out.VerificationStatus)
		}
		return nil
	}
}

// paypalToken fetches an OAuth2 client-credentials access token.
func paypalToken(cfg PayPalConfig, hc *http.Client) (string, error) {
	form := url.Values{"grant_type": {"client_credentials"}}
	req, _ := http.NewRequest(http.MethodPost, cfg.apiBase()+"/v1/oauth2/token", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.SetBasicAuth(cfg.ClientID, cfg.Secret)
	resp, err := hc.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", errors.New("paypal token request failed")
	}
	var out struct {
		AccessToken string `json:"access_token"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", err
	}
	if out.AccessToken == "" {
		return "", errors.New("paypal token empty")
	}
	return out.AccessToken, nil
}
