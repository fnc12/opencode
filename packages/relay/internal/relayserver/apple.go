package relayserver

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"slices"
	"strings"
	"time"

	"github.com/fnc12/opencode/packages/relay/internal/account"
	"github.com/fnc12/opencode/packages/relay/internal/appstore"
	"github.com/fnc12/opencode/packages/relay/internal/provision"
)

// AppleIAPConfig enables Apple in-app subscriptions (with Provision and
// Accounts): POST /api/iap/apple takes the StoreKit 2 transaction JWS from the
// iOS app, and POST /apple/notifications takes App Store Server Notifications
// v2 (renewals, expiry, refunds). Enabled when ProductIDs is non-empty.
type AppleIAPConfig struct {
	BundleID   string
	ProductIDs []string
	// AllowSandbox accepts Sandbox-environment transactions (TestFlight and App
	// Review buy in the sandbox, so production must accept them).
	AllowSandbox bool
	// Verifier overrides the JWS verifier (tests inject a test root); nil →
	// Apple Root CA - G3.
	Verifier *appstore.Verifier
	// API, when set (an In-App Purchase key is configured), refreshes a
	// subscription from the App Store Server API when the app uploads it.
	API *appstore.Client
}

func (c AppleIAPConfig) enabled() bool { return len(c.ProductIDs) > 0 }

var errAppleRejected = errors.New("transaction is not for this app's subscription")

// checkApple rejects a verified transaction that isn't for our bundle and one
// of our subscription products, or comes from a disallowed environment.
func (s *Server) checkApple(t appstore.Transaction) error {
	c := s.apple
	if t.BundleID != c.BundleID || !slices.Contains(c.ProductIDs, t.ProductID) {
		return errAppleRejected
	}
	if t.Environment == "Sandbox" && !c.AllowSandbox {
		return errAppleRejected
	}
	return nil
}

func appleTx(t appstore.Transaction) account.AppleTransaction {
	return account.AppleTransaction{
		OriginalTransactionID: t.OriginalTransactionID,
		TransactionID:         t.TransactionID,
		ProductID:             t.ProductID,
		Environment:           t.Environment,
		ExpiresAt:             t.ExpiresDate / 1000,
		RevokedAt:             t.RevocationDate / 1000,
		SignedAt:              t.SignedDate,
	}
}

// appleOwner is the account an Apple purchase made on a tunnel's app belongs
// to: the account owning a free account-keyed tunnel, or the account a PayPal
// subscription tunnel is bound to. A tunnel with no account (admin-minted, e.g.
// the App Review demo tunnel) gets a tunnel-scoped owner key so the purchase is
// still recorded and acknowledged.
func (s *Server) appleOwner(t provision.Tunnel) string {
	if strings.HasPrefix(t.CustomerID, "acc_") {
		return t.CustomerID
	}
	if t.CustomerID != "" {
		if acc, ok, _ := s.accounts.AccountForSubscription(t.CustomerID); ok {
			return acc
		}
	}
	return "tunnel:" + t.ID
}

type appleUploadRequest struct {
	TunnelID          string `json:"tunnelId"`
	SignedTransaction string `json:"signedTransaction"`
}

// handleAppleIAP binds a StoreKit 2 subscription to the account behind the
// paired tunnel. Auth is the tunnel token (X-Tunnel-Token), as for
// /api/devices. Answers {"entitledUntil": <unix s>, "active": bool}.
func (s *Server) handleAppleIAP(w http.ResponseWriter, r *http.Request) {
	var body appleUploadRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&body); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	if body.TunnelID == "" || body.SignedTransaction == "" {
		http.Error(w, "tunnelId and signedTransaction required", http.StatusBadRequest)
		return
	}
	tun, found := s.provision.Get(body.TunnelID)
	if !found || tun.Token == "" || r.Header.Get("X-Tunnel-Token") != tun.Token {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	t, err := s.appleVerifier.VerifyTransaction(body.SignedTransaction)
	if err != nil {
		s.log.Warn("apple iap: transaction rejected", "tunnel", tun.ID, "err", err)
		http.Error(w, "invalid transaction", http.StatusBadRequest)
		return
	}
	if err := s.checkApple(t); err != nil {
		s.log.Warn("apple iap: foreign transaction", "tunnel", tun.ID, "bundle", t.BundleID, "product", t.ProductID, "env", t.Environment)
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	owner := s.appleOwner(tun)
	now := time.Now().Unix()
	sub, err := s.accounts.ApplyAppleTransaction(owner, appleTx(t), now)
	if err != nil {
		http.Error(w, "store error", http.StatusInternalServerError)
		return
	}
	if s.apple.API != nil {
		if fresh, ok := s.refreshApple(r.Context(), t); ok {
			sub = fresh
		}
	}
	s.log.Info("apple iap: subscription bound", "account", owner, "originalTransactionId", sub.OriginalTransactionID,
		"expires", sub.ExpiresAt, "revoked", sub.RevokedAt != 0, "env", sub.Environment)
	var until int64
	if sub.Active(now) {
		until = sub.ExpiresAt
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"entitledUntil": until, "active": until > 0})
}

// refreshApple applies Apple's current view of the subscription (Server API).
// Failures are logged and the uploaded transaction stands.
func (s *Server) refreshApple(ctx context.Context, t appstore.Transaction) (account.AppleSubscription, bool) {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	jws, err := s.apple.API.SubscriptionTransactions(ctx, t.Environment, t.OriginalTransactionID)
	if err != nil {
		s.log.Warn("apple iap: server API refresh failed", "originalTransactionId", t.OriginalTransactionID, "err", err)
		return account.AppleSubscription{}, false
	}
	var out account.AppleSubscription
	var ok bool
	for _, j := range jws {
		ft, err := s.appleVerifier.VerifyTransaction(j)
		if err != nil || ft.OriginalTransactionID != t.OriginalTransactionID || s.checkApple(ft) != nil {
			continue
		}
		if sub, err := s.accounts.ApplyAppleTransaction("", appleTx(ft), time.Now().Unix()); err == nil {
			out, ok = sub, true
		}
	}
	return out, ok
}

// handleAppleNotification applies an App Store Server Notification v2. The
// transaction inside is Apple-signed, so the subscription's state (renewed
// expiry, revocation) is taken from it; a refund or revocation is recorded even
// if the transaction itself carries no revocationDate. A non-2xx answer makes
// Apple retry, so only malformed or unverifiable deliveries get one.
func (s *Server) handleAppleNotification(w http.ResponseWriter, r *http.Request) {
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 256<<10))
	if err != nil {
		http.Error(w, "read error", http.StatusBadRequest)
		return
	}
	var body struct {
		SignedPayload string `json:"signedPayload"`
	}
	if err := json.Unmarshal(raw, &body); err != nil || body.SignedPayload == "" {
		http.Error(w, "signedPayload required", http.StatusBadRequest)
		return
	}
	n, err := s.appleVerifier.VerifyNotification(body.SignedPayload)
	if err != nil {
		s.log.Warn("apple notification rejected", "err", err)
		http.Error(w, "signature verification failed", http.StatusBadRequest)
		return
	}
	if n.Data.BundleID != s.apple.BundleID {
		s.log.Warn("apple notification for another bundle", "bundle", n.Data.BundleID)
		w.WriteHeader(http.StatusOK)
		return
	}
	if n.Data.SignedTransactionInfo == "" { // TEST, CONSUMPTION_REQUEST, …
		s.log.Info("apple notification", "type", n.NotificationType, "subtype", n.Subtype, "uuid", n.NotificationUUID)
		w.WriteHeader(http.StatusOK)
		return
	}
	t, err := s.appleVerifier.VerifyTransaction(n.Data.SignedTransactionInfo)
	if err != nil {
		s.log.Warn("apple notification: transaction rejected", "type", n.NotificationType, "err", err)
		http.Error(w, "signature verification failed", http.StatusBadRequest)
		return
	}
	if err := s.checkApple(t); err != nil {
		s.log.Info("apple notification ignored", "type", n.NotificationType, "product", t.ProductID, "env", t.Environment)
		w.WriteHeader(http.StatusOK)
		return
	}
	at := appleTx(t)
	if n.SignedDate > at.SignedAt {
		at.SignedAt = n.SignedDate
	}
	if (n.NotificationType == "REFUND" || n.NotificationType == "REVOKE") && at.RevokedAt == 0 {
		at.RevokedAt = n.SignedDate / 1000
	}
	sub, err := s.accounts.ApplyAppleTransaction("", at, time.Now().Unix())
	if err != nil {
		http.Error(w, "store error", http.StatusInternalServerError)
		return
	}
	s.log.Info("apple notification applied", "type", n.NotificationType, "subtype", n.Subtype,
		"originalTransactionId", sub.OriginalTransactionID, "account", sub.AccountID,
		"expires", sub.ExpiresAt, "revoked", sub.RevokedAt != 0)
	w.WriteHeader(http.StatusOK)
}

// entitled reports whether an account may hold a tunnel: free months (first-N,
// referral, promo), a bound PayPal subscription, or a live Apple subscription.
func (s *Server) entitled(acc string, now int64) bool {
	return s.accounts.FreeActive(acc, now) || s.hasPaidTunnel(acc) || s.accounts.AppleActive(acc, now)
}
