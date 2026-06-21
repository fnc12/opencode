package push

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

// MemoryStore is an in-memory Store. It is safe for concurrent use and is the
// base for FileStore.
type MemoryStore struct {
	mu      sync.RWMutex
	tunnels map[string][]Device
}

// NewMemoryStore returns an empty in-memory store.
func NewMemoryStore() *MemoryStore {
	return &MemoryStore{tunnels: make(map[string][]Device)}
}

func (s *MemoryStore) Add(tunnelID string, d Device) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, existing := range s.tunnels[tunnelID] {
		if existing == d {
			return nil // idempotent
		}
	}
	s.tunnels[tunnelID] = append(s.tunnels[tunnelID], d)
	return nil
}

func (s *MemoryStore) Remove(tunnelID string, d Device) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	list := s.tunnels[tunnelID]
	out := list[:0]
	for _, existing := range list {
		if existing != d {
			out = append(out, existing)
		}
	}
	if len(out) == 0 {
		delete(s.tunnels, tunnelID)
	} else {
		s.tunnels[tunnelID] = out
	}
	return nil
}

func (s *MemoryStore) List(tunnelID string) ([]Device, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	list := s.tunnels[tunnelID]
	out := make([]Device, len(list))
	copy(out, list)
	return out, nil
}

func (s *MemoryStore) snapshot() map[string][]Device {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make(map[string][]Device, len(s.tunnels))
	for k, v := range s.tunnels {
		cp := make([]Device, len(v))
		copy(cp, v)
		out[k] = cp
	}
	return out
}

// FileStore persists registrations to a JSON file. Writes are atomic
// (write-temp-then-rename). For the central relay this can later be swapped for
// sqlite/postgres behind the same Store interface; a file keeps the self-host
// build dependency-free (public repo — no embedded DB driver needed yet).
type FileStore struct {
	*MemoryStore
	path string
	mu   sync.Mutex // serializes file writes
}

// NewFileStore loads (or creates) a store backed by path.
func NewFileStore(path string) (*FileStore, error) {
	fs := &FileStore{MemoryStore: NewMemoryStore(), path: path}
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return fs, nil
		}
		return nil, err
	}
	var loaded map[string][]Device
	if err := json.Unmarshal(data, &loaded); err != nil {
		return nil, err
	}
	if loaded != nil {
		fs.MemoryStore.tunnels = loaded
	}
	return fs, nil
}

func (fs *FileStore) Add(tunnelID string, d Device) error {
	if err := fs.MemoryStore.Add(tunnelID, d); err != nil {
		return err
	}
	return fs.persist()
}

func (fs *FileStore) Remove(tunnelID string, d Device) error {
	if err := fs.MemoryStore.Remove(tunnelID, d); err != nil {
		return err
	}
	return fs.persist()
}

func (fs *FileStore) persist() error {
	fs.mu.Lock()
	defer fs.mu.Unlock()
	data, err := json.MarshalIndent(fs.snapshot(), "", "  ")
	if err != nil {
		return err
	}
	tmp := fs.path + ".tmp"
	if err := os.MkdirAll(filepath.Dir(fs.path), 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, fs.path)
}
