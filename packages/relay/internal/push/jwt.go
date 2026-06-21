package push

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
)

func b64url(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

func signingInput(header, claims map[string]any) (string, error) {
	hb, err := json.Marshal(header)
	if err != nil {
		return "", err
	}
	cb, err := json.Marshal(claims)
	if err != nil {
		return "", err
	}
	return b64url(hb) + "." + b64url(cb), nil
}

// signES256 produces a compact JWT signed with an ECDSA P-256 key (APNs).
func signES256(key *ecdsa.PrivateKey, header, claims map[string]any) (string, error) {
	input, err := signingInput(header, claims)
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256([]byte(input))
	r, s, err := ecdsa.Sign(rand.Reader, key, digest[:])
	if err != nil {
		return "", err
	}
	// JOSE ES256 signature is R||S, each left-padded to the curve byte size.
	size := (key.Curve.Params().BitSize + 7) / 8
	sig := make([]byte, 2*size)
	r.FillBytes(sig[:size])
	s.FillBytes(sig[size:])
	return input + "." + b64url(sig), nil
}

// signRS256 produces a compact JWT signed with an RSA key (Google OAuth).
func signRS256(key *rsa.PrivateKey, header, claims map[string]any) (string, error) {
	input, err := signingInput(header, claims)
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256([]byte(input))
	sig, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, digest[:])
	if err != nil {
		return "", err
	}
	return input + "." + b64url(sig), nil
}

// parseECPrivateKey parses an APNs .p8 (PKCS#8, EC P-256) private key.
func parseECPrivateKey(pemBytes []byte) (*ecdsa.PrivateKey, error) {
	block, _ := pem.Decode(pemBytes)
	if block == nil {
		return nil, errors.New("push: no PEM block in EC key")
	}
	key, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("push: parse PKCS8: %w", err)
	}
	ec, ok := key.(*ecdsa.PrivateKey)
	if !ok {
		return nil, errors.New("push: key is not ECDSA")
	}
	return ec, nil
}

// parseRSAPrivateKey parses a PEM RSA private key (PKCS#1 or PKCS#8), as found
// in a Google service-account JSON's private_key field.
func parseRSAPrivateKey(pemBytes []byte) (*rsa.PrivateKey, error) {
	block, _ := pem.Decode(pemBytes)
	if block == nil {
		return nil, errors.New("push: no PEM block in RSA key")
	}
	if k, err := x509.ParsePKCS1PrivateKey(block.Bytes); err == nil {
		return k, nil
	}
	key, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("push: parse RSA PKCS8: %w", err)
	}
	rk, ok := key.(*rsa.PrivateKey)
	if !ok {
		return nil, errors.New("push: key is not RSA")
	}
	return rk, nil
}
