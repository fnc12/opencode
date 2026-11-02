// Package appstoretest builds a throwaway Apple-shaped certificate chain (root →
// WWDR-like intermediate → receipt-signing leaf, with Apple's marker OIDs) and
// signs App Store JWS fixtures with it, so verification is tested end to end
// without Apple's real keys.
package appstoretest

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/asn1"
	"encoding/base64"
	"encoding/json"
	"math/big"
	"testing"
	"time"
)

var (
	oidReceiptSigner    = asn1.ObjectIdentifier{1, 2, 840, 113635, 100, 6, 11, 1}
	oidWWDRIntermediate = asn1.ObjectIdentifier{1, 2, 840, 113635, 100, 6, 2, 1}
)

// ChainOptions tweak the generated chain for negative tests.
type ChainOptions struct {
	NoLeafOID         bool      // leaf without the receipt-signing OID
	NoIntermediateOID bool      // intermediate without the WWDR OID
	LeafNotAfter      time.Time // zero → valid for a year
}

// CA is a generated chain plus its leaf signing key.
type CA struct {
	Roots *x509.CertPool
	Root  *x509.Certificate
	inter *x509.Certificate
	leaf  *x509.Certificate
	key   *ecdsa.PrivateKey
}

// New builds a valid chain.
func New(t testing.TB) *CA { return NewWith(t, ChainOptions{}) }

// NewWith builds a chain with the given deviations.
func NewWith(t testing.TB, o ChainOptions) *CA {
	t.Helper()
	now := time.Now()
	rootKey := genKey(t)
	root := mkCert(t, &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "Test Apple Root"},
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.Add(10 * 365 * 24 * time.Hour),
		IsCA:                  true,
		BasicConstraintsValid: true,
		KeyUsage:              x509.KeyUsageCertSign,
	}, nil, rootKey, rootKey)

	interKey := genKey(t)
	interTmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(2),
		Subject:               pkix.Name{CommonName: "Test WWDR"},
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.Add(5 * 365 * 24 * time.Hour),
		IsCA:                  true,
		BasicConstraintsValid: true,
		KeyUsage:              x509.KeyUsageCertSign,
	}
	if !o.NoIntermediateOID {
		interTmpl.ExtraExtensions = []pkix.Extension{{Id: oidWWDRIntermediate, Value: []byte{5, 0}}}
	}
	inter := mkCert(t, interTmpl, root, interKey, rootKey)

	leafKey := genKey(t)
	notAfter := o.LeafNotAfter
	if notAfter.IsZero() {
		notAfter = now.Add(365 * 24 * time.Hour)
	}
	leafTmpl := &x509.Certificate{
		SerialNumber: big.NewInt(3),
		Subject:      pkix.Name{CommonName: "Test Receipt Signer"},
		NotBefore:    now.Add(-2 * time.Hour),
		NotAfter:     notAfter,
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	if !o.NoLeafOID {
		leafTmpl.ExtraExtensions = []pkix.Extension{{Id: oidReceiptSigner, Value: []byte{5, 0}}}
	}
	leaf := mkCert(t, leafTmpl, inter, leafKey, interKey)

	pool := x509.NewCertPool()
	pool.AddCert(root)
	return &CA{Roots: pool, Root: root, inter: inter, leaf: leaf, key: leafKey}
}

// Sign returns a compact ES256 JWS of payload with the chain in x5c.
func (c *CA) Sign(t testing.TB, payload any) string {
	t.Helper()
	return c.SignKey(t, payload, c.key)
}

// SignKey signs with an arbitrary key while still presenting the CA's chain —
// a forged signature for negative tests.
func (c *CA) SignKey(t testing.TB, payload any, key *ecdsa.PrivateKey) string {
	t.Helper()
	header := map[string]any{
		"alg": "ES256",
		"x5c": []string{
			base64.StdEncoding.EncodeToString(c.leaf.Raw),
			base64.StdEncoding.EncodeToString(c.inter.Raw),
			base64.StdEncoding.EncodeToString(c.Root.Raw),
		},
	}
	hb, _ := json.Marshal(header)
	pb, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	input := b64(hb) + "." + b64(pb)
	digest := sha256.Sum256([]byte(input))
	r, s, err := ecdsa.Sign(rand.Reader, key, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	sig := make([]byte, 64)
	r.FillBytes(sig[:32])
	s.FillBytes(sig[32:])
	return input + "." + b64(sig)
}

// GenKey returns a fresh P-256 key.
func GenKey(t testing.TB) *ecdsa.PrivateKey { return genKey(t) }

func b64(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

func genKey(t testing.TB) *ecdsa.PrivateKey {
	t.Helper()
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func mkCert(t testing.TB, tmpl, parent *x509.Certificate, key, parentKey *ecdsa.PrivateKey) *x509.Certificate {
	t.Helper()
	if parent == nil {
		parent = tmpl
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, parent, &key.PublicKey, parentKey)
	if err != nil {
		t.Fatal(err)
	}
	c, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return c
}
