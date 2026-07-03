// Package provision manages provisioned tunnels: the relay mints a claim code
// for each tester, the tester's connector redeems it for a tunnel id + token,
// and the relay authorizes that connector's registration against the stored
// token — so no shared register secret is handed out, and access is revocable.
package provision

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
)

var (
	// ErrNotFound is returned when no tunnel matches an id.
	ErrNotFound = errors.New("tunnel not found")
	// ErrBadCode is returned when a claim code matches no unclaimed tunnel.
	ErrBadCode = errors.New("invalid or already-used claim code")
)

// Tunnel is one provisioned tunnel. Token is the per-tunnel, app-facing token
// (also what the connector registers with). ClaimCode is one-time — cleared
// once redeemed.
type Tunnel struct {
	ID        string `json:"id"`
	Token     string `json:"token"`
	ClaimCode string `json:"claimCode,omitempty"`
	Claimed   bool   `json:"claimed"`
	Label     string `json:"label,omitempty"`
	CreatedAt int64  `json:"createdAt"`
}

// Store persists provisioned tunnels. Implementations must be safe for
// concurrent use.
type Store interface {
	Mint(label string, now int64) (Tunnel, error)
	Claim(code string) (Tunnel, error)
	Get(id string) (Tunnel, bool)
	Delete(id string) error
	List() []Tunnel
}

// FileStore is a JSON-backed Store. It keeps the whole set in memory and
// rewrites the file on every change — fine for the tunnel counts this serves;
// the interface lets a sqlite/postgres backend drop in later.
type FileStore struct {
	path string
	mu   sync.Mutex
	byID map[string]Tunnel
}

// NewFileStore loads (or starts) a store at path.
func NewFileStore(path string) (*FileStore, error) {
	s := &FileStore{path: path, byID: map[string]Tunnel{}}
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return s, nil
		}
		return nil, err
	}
	var list []Tunnel
	if err := json.Unmarshal(data, &list); err != nil {
		return nil, err
	}
	for _, t := range list {
		s.byID[t.ID] = t
	}
	return s, nil
}

// flush writes the current set to disk. Caller holds mu.
func (s *FileStore) flush() error {
	list := make([]Tunnel, 0, len(s.byID))
	for _, t := range s.byID {
		list = append(list, t)
	}
	data, _ := json.MarshalIndent(list, "", "  ")
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	return os.WriteFile(s.path, data, 0o600)
}

// Mint creates a fresh unclaimed tunnel with a one-time claim code.
func (s *FileStore) Mint(label string, now int64) (Tunnel, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	t := Tunnel{
		ID:        "tun_" + randHex(8),
		Token:     randHex(24),
		ClaimCode: randCode(),
		Label:     label,
		CreatedAt: now,
	}
	s.byID[t.ID] = t
	if err := s.flush(); err != nil {
		delete(s.byID, t.ID)
		return Tunnel{}, err
	}
	return t, nil
}

// Claim redeems a one-time code, returning the tunnel's id + token and marking
// it claimed (the code is cleared so it can't be reused).
func (s *FileStore) Claim(code string) (Tunnel, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if code == "" {
		return Tunnel{}, ErrBadCode
	}
	for id, t := range s.byID {
		if t.ClaimCode == code && !t.Claimed {
			t.Claimed = true
			t.ClaimCode = ""
			s.byID[id] = t
			if err := s.flush(); err != nil {
				return Tunnel{}, err
			}
			return t, nil
		}
	}
	return Tunnel{}, ErrBadCode
}

// Get returns a tunnel by id (used to authorize registration).
func (s *FileStore) Get(id string) (Tunnel, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	t, ok := s.byID[id]
	return t, ok
}

// Delete revokes a tunnel.
func (s *FileStore) Delete(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.byID[id]; !ok {
		return ErrNotFound
	}
	delete(s.byID, id)
	return s.flush()
}

// List returns all tunnels (tokens included; callers redact as needed).
func (s *FileStore) List() []Tunnel {
	s.mu.Lock()
	defer s.mu.Unlock()
	list := make([]Tunnel, 0, len(s.byID))
	for _, t := range s.byID {
		list = append(list, t)
	}
	return list
}

func randHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

// randCode returns a short, human-typeable one-time code like "AB12-CD34"
// (Crockford-ish alphabet, no ambiguous 0/O/1/I/L).
func randCode() string {
	const alphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	out := make([]byte, 0, 9)
	for i, x := range b {
		if i == 4 {
			out = append(out, '-')
		}
		out = append(out, alphabet[int(x)%len(alphabet)])
	}
	return string(out)
}
