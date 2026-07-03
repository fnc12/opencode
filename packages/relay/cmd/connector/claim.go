package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"
)

// maybeClaim redeems a CLAIM_CODE for a tunnel identity the first time the
// connector runs, persisting it so later runs skip the claim. A tester gets a
// one-time code from the relay operator and never sees a shared secret.
func maybeClaim(relayWSURL string) error {
	code := os.Getenv("CLAIM_CODE")
	if code == "" {
		return nil
	}
	// Already have a persisted identity? Then the code was used on a prior run.
	if path, err := identityPath(); err == nil {
		if data, err := os.ReadFile(path); err == nil {
			var id identity
			if json.Unmarshal(data, &id) == nil && id.TunnelID != "" && id.Token != "" {
				return nil
			}
		}
	}
	id, err := claimIdentity(relayWSURL, code)
	if err != nil {
		return err
	}
	path, err := identityPath()
	if err != nil {
		return err
	}
	return saveIdentity(path, id)
}

// claimIdentity exchanges a one-time claim code for a tunnel id + token.
func claimIdentity(relayWSURL, code string) (identity, error) {
	base, err := relayHTTPBase(relayWSURL)
	if err != nil {
		return identity{}, err
	}
	body, _ := json.Marshal(map[string]string{"code": code})
	req, err := http.NewRequest(http.MethodPost, base+"/connector/claim", bytes.NewReader(body))
	if err != nil {
		return identity{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := (&http.Client{Timeout: 15 * time.Second}).Do(req)
	if err != nil {
		return identity{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return identity{}, fmt.Errorf("claim failed (%d): %s", resp.StatusCode, string(b))
	}
	var out struct {
		TunnelID    string `json:"tunnelId"`
		TunnelToken string `json:"tunnelToken"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return identity{}, err
	}
	if out.TunnelID == "" || out.TunnelToken == "" {
		return identity{}, errors.New("claim response missing tunnelId/tunnelToken")
	}
	return identity{TunnelID: out.TunnelID, Token: out.TunnelToken}, nil
}
