package provision

import (
	"path/filepath"
	"testing"
)

func TestMintClaimRevoke(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tunnels.json")
	s, err := NewFileStore(path)
	if err != nil {
		t.Fatal(err)
	}

	minted, err := s.Mint("alice", 100)
	if err != nil {
		t.Fatal(err)
	}
	if minted.ID == "" || minted.Token == "" || minted.ClaimCode == "" {
		t.Fatalf("mint returned empty fields: %+v", minted)
	}

	// A fresh store reads it back (persisted).
	s2, err := NewFileStore(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := s2.Get(minted.ID); !ok {
		t.Fatal("minted tunnel not persisted")
	}

	// Claim redeems the code and returns the id + token.
	claimed, err := s2.Claim(minted.ClaimCode)
	if err != nil {
		t.Fatalf("claim: %v", err)
	}
	if claimed.ID != minted.ID || claimed.Token != minted.Token {
		t.Fatal("claim returned a different tunnel")
	}

	// The code is one-time.
	if _, err := s2.Claim(minted.ClaimCode); err != ErrBadCode {
		t.Fatalf("reused code should fail with ErrBadCode, got %v", err)
	}

	// Revoke removes it.
	if err := s2.Delete(minted.ID); err != nil {
		t.Fatal(err)
	}
	if _, ok := s2.Get(minted.ID); ok {
		t.Fatal("tunnel still present after delete")
	}
	if err := s2.Delete(minted.ID); err != ErrNotFound {
		t.Fatalf("deleting missing tunnel should be ErrNotFound, got %v", err)
	}
}

func TestClaimCodesAndTokensAreUnique(t *testing.T) {
	s, _ := NewFileStore(filepath.Join(t.TempDir(), "t.json"))
	seen := map[string]bool{}
	for i := 0; i < 50; i++ {
		tn, err := s.Mint("", int64(i))
		if err != nil {
			t.Fatal(err)
		}
		for _, v := range []string{tn.ID, tn.Token, tn.ClaimCode} {
			if seen[v] {
				t.Fatalf("duplicate value: %q", v)
			}
			seen[v] = true
		}
	}
}
