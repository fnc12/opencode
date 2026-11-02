package appstore

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/fnc12/opencode/packages/relay/internal/appstore/appstoretest"
)

func tx(id string) map[string]any {
	return map[string]any{
		"transactionId":         id,
		"originalTransactionId": "1000",
		"bundleId":              "studio.eugenezakharov.shubat",
		"productId":             "studio.eugenezakharov.shubat.pro.monthly",
		"type":                  "Auto-Renewable Subscription",
		"environment":           "Sandbox",
		"expiresDate":           int64(1_800_000_000_000),
		"signedDate":            int64(1_700_000_000_000),
	}
}

func TestEmbeddedRootIsAppleG3(t *testing.T) {
	// Pinned fingerprint of Apple Root CA - G3 (apple.com/certificateauthority).
	const want = "63343abfb89a6a03ebb57e9b3f5fa7be7c4f5c756f3017b3a8c488c3653e9179"
	sum := sha256.Sum256(appleRootG3)
	if got := hex.EncodeToString(sum[:]); got != want {
		t.Fatalf("embedded root sha256 = %s, want %s", got, want)
	}
	AppleRoots() // must parse
}

func TestVerifyTransaction(t *testing.T) {
	ca := appstoretest.New(t)
	v := &Verifier{Roots: ca.Roots}
	got, err := v.VerifyTransaction(ca.Sign(t, tx("2000")))
	if err != nil {
		t.Fatal(err)
	}
	if got.TransactionID != "2000" || got.OriginalTransactionID != "1000" || got.ExpiresDate != 1_800_000_000_000 {
		t.Errorf("decoded %+v", got)
	}
}

func TestVerifyRejects(t *testing.T) {
	good := appstoretest.New(t)
	other := appstoretest.New(t)
	expired := appstoretest.NewWith(t, appstoretest.ChainOptions{LeafNotAfter: time.Now().Add(-time.Minute)})
	noLeafOID := appstoretest.NewWith(t, appstoretest.ChainOptions{NoLeafOID: true})
	noInterOID := appstoretest.NewWith(t, appstoretest.ChainOptions{NoIntermediateOID: true})

	signed := good.Sign(t, tx("2000"))
	parts := strings.Split(signed, ".")
	cases := map[string]struct {
		roots *Verifier
		jws   string
	}{
		"untrusted root":       {&Verifier{Roots: good.Roots}, other.Sign(t, tx("2000"))},
		"forged signature":     {&Verifier{Roots: good.Roots}, good.SignKey(t, tx("2000"), appstoretest.GenKey(t))},
		"tampered payload":     {&Verifier{Roots: good.Roots}, parts[0] + "." + strings.Split(good.Sign(t, tx("9999")), ".")[1] + "." + parts[2]},
		"expired leaf":         {&Verifier{Roots: expired.Roots}, expired.Sign(t, tx("2000"))},
		"leaf without OID":     {&Verifier{Roots: noLeafOID.Roots}, noLeafOID.Sign(t, tx("2000"))},
		"intermediate no OID":  {&Verifier{Roots: noInterOID.Roots}, noInterOID.Sign(t, tx("2000"))},
		"real Apple root only": {NewVerifier(), signed},
		"garbage":              {&Verifier{Roots: good.Roots}, "a.b.c"},
		"not a JWS":            {&Verifier{Roots: good.Roots}, "hello"},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := c.roots.VerifyTransaction(c.jws); !errors.Is(err, ErrInvalidJWS) {
				t.Fatalf("err = %v, want ErrInvalidJWS", err)
			}
		})
	}
}

func TestVerifyChecksTimeAgainstClock(t *testing.T) {
	ca := appstoretest.New(t)
	v := &Verifier{Roots: ca.Roots, Now: func() time.Time { return time.Now().Add(2 * 365 * 24 * time.Hour) }}
	if _, err := v.VerifyTransaction(ca.Sign(t, tx("2000"))); !errors.Is(err, ErrInvalidJWS) {
		t.Fatalf("leaf past NotAfter at verify time: err = %v, want ErrInvalidJWS", err)
	}
}

func TestVerifyNotification(t *testing.T) {
	ca := appstoretest.New(t)
	v := &Verifier{Roots: ca.Roots}
	inner := ca.Sign(t, tx("2001"))
	payload := map[string]any{
		"notificationType": "DID_RENEW",
		"notificationUUID": "u-1",
		"version":          "2.0",
		"signedDate":       int64(1_700_000_100_000),
		"data": map[string]any{
			"bundleId":              "studio.eugenezakharov.shubat",
			"environment":           "Sandbox",
			"signedTransactionInfo": inner,
		},
	}
	n, err := v.VerifyNotification(ca.Sign(t, payload))
	if err != nil {
		t.Fatal(err)
	}
	if n.NotificationType != "DID_RENEW" || n.Data.SignedTransactionInfo != inner {
		t.Fatalf("decoded %+v", n)
	}
	if _, err := v.VerifyTransaction(n.Data.SignedTransactionInfo); err != nil {
		t.Fatalf("inner transaction: %v", err)
	}
}
