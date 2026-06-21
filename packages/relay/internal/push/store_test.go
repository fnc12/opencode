package push

import (
	"path/filepath"
	"testing"
)

func TestMemoryStoreAddRemoveList(t *testing.T) {
	s := NewMemoryStore()
	d1 := Device{Provider: APNs, Token: "t1"}
	d2 := Device{Provider: FCM, Token: "t2"}
	_ = s.Add("tun", d1)
	_ = s.Add("tun", d1) // idempotent
	_ = s.Add("tun", d2)

	list, _ := s.List("tun")
	if len(list) != 2 {
		t.Fatalf("expected 2 devices, got %d", len(list))
	}

	_ = s.Remove("tun", d1)
	list, _ = s.List("tun")
	if len(list) != 1 || list[0] != d2 {
		t.Fatalf("unexpected list after remove: %v", list)
	}

	_ = s.Remove("tun", d2)
	if list, _ := s.List("tun"); len(list) != 0 {
		t.Fatalf("expected empty, got %v", list)
	}
}

func TestFileStorePersistsAcrossReload(t *testing.T) {
	path := filepath.Join(t.TempDir(), "devices.json")
	s, err := NewFileStore(path)
	if err != nil {
		t.Fatal(err)
	}
	d := Device{Provider: APNs, Token: "tok"}
	if err := s.Add("tun", d); err != nil {
		t.Fatal(err)
	}

	// Reload from disk.
	s2, err := NewFileStore(path)
	if err != nil {
		t.Fatal(err)
	}
	list, _ := s2.List("tun")
	if len(list) != 1 || list[0] != d {
		t.Fatalf("did not persist: %v", list)
	}
}
