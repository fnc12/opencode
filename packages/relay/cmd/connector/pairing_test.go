package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestPairingLink(t *testing.T) {
	id := identity{TunnelID: "tunZ", Token: "secret123"}
	link, err := pairingLink("wss://relay.shubat.org/connector", id)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		"opencode://pair?",
		"relay=https%3A%2F%2Frelay.shubat.org",
		"tunnel=tunZ",
		"token=secret123",
	} {
		if !strings.Contains(link, want) {
			t.Fatalf("link %q missing %q", link, want)
		}
	}
}

func TestRelayHTTPBase(t *testing.T) {
	cases := map[string]string{
		"wss://relay.shubat.org/connector": "https://relay.shubat.org",
		"ws://localhost:8080/connector":    "http://localhost:8080",
		"wss://r.example.com":              "https://r.example.com",
		"https://r.example.com/connector":  "https://r.example.com",
	}
	for in, want := range cases {
		got, err := relayHTTPBase(in)
		if err != nil {
			t.Fatalf("%s: %v", in, err)
		}
		if got != want {
			t.Fatalf("%s -> %s, want %s", in, got, want)
		}
	}
	if _, err := relayHTTPBase("ftp://nope"); err == nil {
		t.Fatal("expected error for non-ws scheme")
	}
}

func TestLoadOrCreateIdentityPersists(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("SHUBAT_CONFIG_DIR", dir)

	first, err := loadOrCreateIdentity("", "")
	if err != nil {
		t.Fatal(err)
	}
	if first.TunnelID == "" || first.Token == "" {
		t.Fatalf("empty identity generated: %+v", first)
	}
	if _, err := os.Stat(filepath.Join(dir, "connector.json")); err != nil {
		t.Fatalf("identity not persisted: %v", err)
	}

	second, err := loadOrCreateIdentity("", "")
	if err != nil {
		t.Fatal(err)
	}
	if second != first {
		t.Fatalf("identity not stable across runs: %+v != %+v", first, second)
	}
}

func TestEnvIdentityWins(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("SHUBAT_CONFIG_DIR", dir)

	id, err := loadOrCreateIdentity("envID", "envToken")
	if err != nil {
		t.Fatal(err)
	}
	if id.TunnelID != "envID" || id.Token != "envToken" {
		t.Fatalf("env not honored: %+v", id)
	}
	// A fully explicit identity must not be persisted.
	if _, err := os.Stat(filepath.Join(dir, "connector.json")); err == nil {
		t.Fatal("explicit env identity should not be persisted")
	}
}
