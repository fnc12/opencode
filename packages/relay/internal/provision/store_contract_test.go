package provision

import (
	"errors"
	"path/filepath"
	"testing"
)

// storeFactory builds a fresh, empty Store for one subtest. Every Store
// implementation is run through the same contract below so FileStore and
// SQLiteStore are proven to behave identically.
type storeFactory struct {
	name string
	make func(t *testing.T) Store
}

func storeFactories() []storeFactory {
	return []storeFactory{
		{"file", func(t *testing.T) Store {
			s, err := NewFileStore(filepath.Join(t.TempDir(), "tunnels.json"))
			if err != nil {
				t.Fatal(err)
			}
			return s
		}},
		{"sqlite", func(t *testing.T) Store {
			s, err := NewSQLiteStore(filepath.Join(t.TempDir(), "tunnels.db"))
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { s.Close() })
			return s
		}},
	}
}

func eachStore(t *testing.T, fn func(t *testing.T, st Store)) {
	for _, f := range storeFactories() {
		t.Run(f.name, func(t *testing.T) { fn(t, f.make(t)) })
	}
}

func TestStoreMintGetList(t *testing.T) {
	eachStore(t, func(t *testing.T, st Store) {
		a, err := st.Mint("alice", 100)
		if err != nil {
			t.Fatal(err)
		}
		if a.ID == "" || a.Token == "" || a.ClaimCode == "" {
			t.Fatalf("mint returned incomplete tunnel: %+v", a)
		}
		got, ok := st.Get(a.ID)
		if !ok || got.ID != a.ID || got.Token != a.Token || got.Label != "alice" || got.CreatedAt != 100 {
			t.Fatalf("Get mismatch: %+v vs %+v", got, a)
		}
		if _, ok := st.Get("tun_missing"); ok {
			t.Error("Get of unknown id should be false")
		}
		st.Mint("bob", 200)
		if n := len(st.List()); n != 2 {
			t.Errorf("List = %d, want 2", n)
		}
	})
}

func TestStoreMintForIdempotent(t *testing.T) {
	eachStore(t, func(t *testing.T, st Store) {
		first, err := st.MintFor("cust_1", "buyer@x.com", 1)
		if err != nil {
			t.Fatal(err)
		}
		again, err := st.MintFor("cust_1", "buyer@x.com", 2)
		if err != nil {
			t.Fatal(err)
		}
		if first.ID != again.ID {
			t.Errorf("MintFor not idempotent: %s vs %s", first.ID, again.ID)
		}
		if n := len(st.List()); n != 1 {
			t.Errorf("idempotent MintFor left %d tunnels, want 1", n)
		}
		got, ok := st.GetByCustomer("cust_1")
		if !ok || got.ID != first.ID {
			t.Errorf("GetByCustomer mismatch: %+v", got)
		}
		if _, ok := st.GetByCustomer("cust_none"); ok {
			t.Error("GetByCustomer of unknown customer should be false")
		}
	})
}

func TestStoreClaimSingleUse(t *testing.T) {
	eachStore(t, func(t *testing.T, st Store) {
		m, err := st.Mint("c", 0)
		if err != nil {
			t.Fatal(err)
		}
		claimed, err := st.Claim(m.ClaimCode)
		if err != nil {
			t.Fatalf("first claim: %v", err)
		}
		if claimed.ID != m.ID || claimed.Token != m.Token {
			t.Errorf("claim returned wrong tunnel: %+v", claimed)
		}
		if !claimed.Claimed || claimed.ClaimCode != "" {
			t.Errorf("claimed tunnel should be marked claimed with code cleared: %+v", claimed)
		}
		// Re-using the same code must fail.
		if _, err := st.Claim(m.ClaimCode); !errors.Is(err, ErrBadCode) {
			t.Errorf("second claim err = %v, want ErrBadCode", err)
		}
		if _, err := st.Claim(""); !errors.Is(err, ErrBadCode) {
			t.Errorf("empty claim err = %v, want ErrBadCode", err)
		}
		// The persisted tunnel reflects the claim.
		if got, ok := st.Get(m.ID); !ok || !got.Claimed || got.ClaimCode != "" {
			t.Errorf("persisted tunnel not marked claimed: %+v", got)
		}
	})
}

func TestStoreDeleteAndByCustomer(t *testing.T) {
	eachStore(t, func(t *testing.T, st Store) {
		m, _ := st.Mint("x", 0)
		if err := st.Delete(m.ID); err != nil {
			t.Fatalf("delete: %v", err)
		}
		if _, ok := st.Get(m.ID); ok {
			t.Error("tunnel should be gone after Delete")
		}
		if err := st.Delete(m.ID); !errors.Is(err, ErrNotFound) {
			t.Errorf("second delete err = %v, want ErrNotFound", err)
		}
		if err := st.Delete("tun_never"); !errors.Is(err, ErrNotFound) {
			t.Errorf("delete unknown err = %v, want ErrNotFound", err)
		}

		c, _ := st.MintFor("cust_del", "y", 0)
		if id := st.DeleteByCustomer("cust_del"); id != c.ID {
			t.Errorf("DeleteByCustomer = %q, want %q", id, c.ID)
		}
		if _, ok := st.GetByCustomer("cust_del"); ok {
			t.Error("customer tunnel should be gone after DeleteByCustomer")
		}
		if id := st.DeleteByCustomer("cust_del"); id != "" {
			t.Errorf("DeleteByCustomer of missing customer = %q, want \"\"", id)
		}
	})
}

// SQLite-specific: importing a legacy FileStore JSON populates an empty DB once,
// and is a safe no-op once the DB already has tunnels.
func TestSQLiteImportLegacyJSON(t *testing.T) {
	dir := t.TempDir()
	jsonPath := filepath.Join(dir, "tunnels.json")

	// Seed a FileStore, then reuse its file as the legacy source.
	fs, err := NewFileStore(jsonPath)
	if err != nil {
		t.Fatal(err)
	}
	seed, _ := fs.MintFor("cust_import", "buyer@x.com", 42)
	claimTun, _ := fs.Mint("to-claim", 7)
	fs.Claim(claimTun.ClaimCode)

	ss, err := NewSQLiteStore(filepath.Join(dir, "tunnels.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ss.Close() })

	n, err := ss.ImportLegacyJSON(jsonPath)
	if err != nil {
		t.Fatalf("import: %v", err)
	}
	if n != 2 {
		t.Fatalf("imported %d, want 2", n)
	}
	// Imported data round-trips, including the claimed flag and customer link.
	if got, ok := ss.GetByCustomer("cust_import"); !ok || got.ID != seed.ID || got.CreatedAt != 42 {
		t.Errorf("imported customer tunnel mismatch: %+v", got)
	}
	if got, ok := ss.Get(claimTun.ID); !ok || !got.Claimed || got.ClaimCode != "" {
		t.Errorf("imported claimed tunnel should stay claimed: %+v", got)
	}

	// A second import into a now-populated DB is a no-op (never clobbers).
	if n, err := ss.ImportLegacyJSON(jsonPath); err != nil || n != 0 {
		t.Errorf("re-import = (%d, %v), want (0, nil)", n, err)
	}

	// A missing legacy file is not an error.
	if n, err := ss.ImportLegacyJSON(filepath.Join(dir, "nope.json")); err != nil || n != 0 {
		t.Errorf("import of missing file = (%d, %v), want (0, nil)", n, err)
	}
}
