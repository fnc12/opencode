package relayserver

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// stripeEvent is the slice of a Stripe webhook event we act on.
type stripeEvent struct {
	Type string `json:"type"`
	Data struct {
		Object struct {
			ID       string `json:"id"`
			Customer string `json:"customer"`
			Status   string `json:"status"`
			Email    string `json:"customer_email"`
		} `json:"object"`
	} `json:"data"`
}

// verifyStripeSignature checks the `Stripe-Signature` header per Stripe's scheme:
// signed_payload = "<t>.<body>", expected = HMAC-SHA256(secret, signed_payload).
// It also bounds the timestamp to guard against replay.
func verifyStripeSignature(secret, header string, body []byte, now time.Time) error {
	var ts string
	var sigs []string
	for _, part := range strings.Split(header, ",") {
		kv := strings.SplitN(part, "=", 2)
		if len(kv) != 2 {
			continue
		}
		switch kv[0] {
		case "t":
			ts = kv[1]
		case "v1":
			sigs = append(sigs, kv[1])
		}
	}
	if ts == "" || len(sigs) == 0 {
		return errors.New("missing timestamp or signature")
	}
	tsec, err := strconv.ParseInt(ts, 10, 64)
	if err != nil {
		return errors.New("bad timestamp")
	}
	if d := now.Unix() - tsec; d > 300 || d < -300 {
		return errors.New("timestamp outside tolerance")
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(ts + "." + string(body)))
	expected := hex.EncodeToString(mac.Sum(nil))
	for _, s := range sigs {
		if hmac.Equal([]byte(s), []byte(expected)) {
			return nil
		}
	}
	return errors.New("signature mismatch")
}

// handleStripeWebhook mints a tunnel when a subscription becomes active and
// revokes it when the subscription is cancelled — the server-side gate on
// paid access. Delivery of the claim code to the customer (email / success
// page) is a follow-up; it's logged and visible via GET /admin/tunnels.
func (s *Server) handleStripeWebhook(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1<<20))
	if err != nil {
		http.Error(w, "read error", http.StatusBadRequest)
		return
	}
	if err := verifyStripeSignature(s.stripeSecret, r.Header.Get("Stripe-Signature"), body, time.Now()); err != nil {
		s.log.Warn("stripe webhook rejected", "err", err)
		http.Error(w, "signature verification failed", http.StatusBadRequest)
		return
	}
	var ev stripeEvent
	if err := json.Unmarshal(body, &ev); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	cust := ev.Data.Object.Customer
	switch ev.Type {
	case "checkout.session.completed", "customer.subscription.created":
		if cust == "" {
			break
		}
		label := ev.Data.Object.Email
		if label == "" {
			label = cust
		}
		t, err := s.provision.MintFor(cust, label, time.Now().Unix())
		if err != nil {
			http.Error(w, "mint failed", http.StatusInternalServerError)
			return
		}
		// The claim code lets the customer run the installer. Logged for now;
		// wire email/success-page delivery next.
		s.log.Info("stripe: access granted", "customer", cust, "tunnel", t.ID, "claimCode", t.ClaimCode)
	case "customer.subscription.deleted":
		if cust == "" {
			break
		}
		if id := s.provision.DeleteByCustomer(cust); id != "" {
			if conn := s.reg.Get(id); conn != nil {
				conn.Close()
			}
			s.log.Info("stripe: access revoked", "customer", cust, "tunnel", id)
		}
	default:
		// Ignore other event types.
	}
	w.WriteHeader(http.StatusOK)
}
