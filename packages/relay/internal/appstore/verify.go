// Package appstore verifies Apple's signed App Store data: the JWS transactions
// StoreKit 2 hands the app (Transaction.jwsRepresentation) and the
// signedPayload of App Store Server Notifications v2.
//
// Every such JWS is ES256-signed by a leaf certificate carried in the x5c
// header, which chains through Apple's WWDR intermediate to Apple Root CA - G3.
// Verification pins that root (embedded below), checks Apple's marker OIDs on
// the leaf and intermediate, and checks the signature with the leaf key. No App
// Store Connect key is needed for this; the key is only for the Server API
// (see api.go).
package appstore

import (
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	_ "embed"
	"encoding/asn1"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"strings"
	"time"
)

//go:embed AppleRootCA-G3.cer
var appleRootG3 []byte

var (
	// OID marking a certificate as Apple's App Store receipt-signing leaf.
	oidReceiptSigner = asn1.ObjectIdentifier{1, 2, 840, 113635, 100, 6, 11, 1}
	// OID marking Apple's WWDR intermediate CA.
	oidWWDRIntermediate = asn1.ObjectIdentifier{1, 2, 840, 113635, 100, 6, 2, 1}
)

// ErrInvalidJWS is returned for any JWS that fails parsing, chain or signature
// verification. The wrapped detail is for logs only.
var ErrInvalidJWS = errors.New("invalid App Store JWS")

// AppleRoots returns a pool holding only Apple Root CA - G3.
func AppleRoots() *x509.CertPool {
	cert, err := x509.ParseCertificate(appleRootG3)
	if err != nil {
		panic("appstore: embedded Apple root does not parse: " + err.Error())
	}
	pool := x509.NewCertPool()
	pool.AddCert(cert)
	return pool
}

// Verifier checks App Store JWS signatures. The zero value is not usable; use
// NewVerifier (production, Apple's root) or set Roots to a test root.
type Verifier struct {
	Roots *x509.CertPool
	// Now is the time certificates are checked against; nil → time.Now.
	Now func() time.Time
}

// NewVerifier returns a Verifier pinned to Apple Root CA - G3.
func NewVerifier() *Verifier { return &Verifier{Roots: AppleRoots()} }

// Verify checks token's certificate chain and signature and unmarshals its
// payload into out.
func (v *Verifier) Verify(token string, out any) error {
	payload, err := v.verify(token)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrInvalidJWS, err)
	}
	if err := json.Unmarshal(payload, out); err != nil {
		return fmt.Errorf("%w: payload: %v", ErrInvalidJWS, err)
	}
	return nil
}

func (v *Verifier) verify(token string) ([]byte, error) {
	parts := strings.Split(strings.TrimSpace(token), ".")
	if len(parts) != 3 {
		return nil, errors.New("not a compact JWS")
	}
	hb, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return nil, fmt.Errorf("header: %v", err)
	}
	var header struct {
		Alg string   `json:"alg"`
		X5c []string `json:"x5c"`
	}
	if err := json.Unmarshal(hb, &header); err != nil {
		return nil, fmt.Errorf("header: %v", err)
	}
	if header.Alg != "ES256" {
		return nil, fmt.Errorf("alg %q, want ES256", header.Alg)
	}
	if len(header.X5c) < 2 {
		return nil, errors.New("x5c needs leaf and intermediate")
	}
	certs := make([]*x509.Certificate, len(header.X5c))
	for i, c := range header.X5c {
		der, err := base64.StdEncoding.DecodeString(c)
		if err != nil {
			return nil, fmt.Errorf("x5c[%d]: %v", i, err)
		}
		if certs[i], err = x509.ParseCertificate(der); err != nil {
			return nil, fmt.Errorf("x5c[%d]: %v", i, err)
		}
	}
	leaf, inter := certs[0], certs[1]
	if !hasExt(leaf, oidReceiptSigner) {
		return nil, errors.New("leaf lacks Apple receipt-signing OID")
	}
	if !hasExt(inter, oidWWDRIntermediate) {
		return nil, errors.New("intermediate lacks Apple WWDR OID")
	}
	now := time.Now
	if v.Now != nil {
		now = v.Now
	}
	// Only the x5c intermediate is offered; a root carried in x5c is ignored —
	// trust comes from v.Roots alone.
	inters := x509.NewCertPool()
	inters.AddCert(inter)
	if _, err := leaf.Verify(x509.VerifyOptions{
		Roots:         v.Roots,
		Intermediates: inters,
		CurrentTime:   now(),
		KeyUsages:     []x509.ExtKeyUsage{x509.ExtKeyUsageAny},
	}); err != nil {
		return nil, fmt.Errorf("chain: %v", err)
	}
	pub, ok := leaf.PublicKey.(*ecdsa.PublicKey)
	if !ok {
		return nil, errors.New("leaf key is not ECDSA")
	}
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil || len(sig) != 64 {
		return nil, errors.New("malformed ES256 signature")
	}
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	r, s := new(big.Int).SetBytes(sig[:32]), new(big.Int).SetBytes(sig[32:])
	if !ecdsa.Verify(pub, digest[:], r, s) {
		return nil, errors.New("signature mismatch")
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, fmt.Errorf("payload: %v", err)
	}
	return payload, nil
}

func hasExt(c *x509.Certificate, oid asn1.ObjectIdentifier) bool {
	for _, e := range c.Extensions {
		if e.Id.Equal(oid) {
			return true
		}
	}
	return false
}

// Transaction is the decoded JWSTransaction payload (the fields we act on).
// Dates are unix milliseconds, as Apple sends them.
type Transaction struct {
	TransactionID         string `json:"transactionId"`
	OriginalTransactionID string `json:"originalTransactionId"`
	BundleID              string `json:"bundleId"`
	ProductID             string `json:"productId"`
	Type                  string `json:"type"`
	Environment           string `json:"environment"`
	PurchaseDate          int64  `json:"purchaseDate"`
	ExpiresDate           int64  `json:"expiresDate"`
	RevocationDate        int64  `json:"revocationDate"`
	RevocationReason      *int   `json:"revocationReason"`
	SignedDate            int64  `json:"signedDate"`
	AppAccountToken       string `json:"appAccountToken"`
}

// VerifyTransaction verifies a signed transaction JWS and decodes it.
func (v *Verifier) VerifyTransaction(jws string) (Transaction, error) {
	var t Transaction
	if err := v.Verify(jws, &t); err != nil {
		return Transaction{}, err
	}
	if t.OriginalTransactionID == "" || t.TransactionID == "" {
		return Transaction{}, fmt.Errorf("%w: transaction ids missing", ErrInvalidJWS)
	}
	return t, nil
}

// Notification is the decoded App Store Server Notification v2 payload.
type Notification struct {
	NotificationType string `json:"notificationType"`
	Subtype          string `json:"subtype"`
	NotificationUUID string `json:"notificationUUID"`
	Version          string `json:"version"`
	SignedDate       int64  `json:"signedDate"`
	Data             struct {
		BundleID              string `json:"bundleId"`
		Environment           string `json:"environment"`
		SignedTransactionInfo string `json:"signedTransactionInfo"`
		SignedRenewalInfo     string `json:"signedRenewalInfo"`
		Status                int    `json:"status"`
	} `json:"data"`
}

// VerifyNotification verifies a notification's signedPayload and decodes it.
// The nested signedTransactionInfo is a JWS of its own; verify it with
// VerifyTransaction.
func (v *Verifier) VerifyNotification(signedPayload string) (Notification, error) {
	var n Notification
	if err := v.Verify(signedPayload, &n); err != nil {
		return Notification{}, err
	}
	if n.NotificationType == "" {
		return Notification{}, fmt.Errorf("%w: notificationType missing", ErrInvalidJWS)
	}
	return n, nil
}
