package provision

import (
	"database/sql"
	"encoding/json"
	"errors"
	"os"
	"sync"

	_ "modernc.org/sqlite"
)

// SQLiteStore is a SQLite-backed Store — the durable successor to FileStore.
// Uses the pure-Go modernc.org/sqlite driver so the relay still cross-compiles
// without cgo. It satisfies the exact same Store contract as FileStore; a
// contract test asserts behavioural parity.
//
// A process-level mutex guards the read-modify-write operations (MintFor's
// idempotency check, Claim's single-use flip) so they stay atomic regardless of
// SQLite's own locking; MaxOpenConns(1) keeps writers from tripping over each
// other at our volume.
type SQLiteStore struct {
	db *sql.DB
	mu sync.Mutex
}

const tunnelsSchema = `
CREATE TABLE IF NOT EXISTS tunnels (
  id          TEXT PRIMARY KEY,
  token       TEXT NOT NULL,
  claim_code  TEXT NOT NULL DEFAULT '',
  claimed     INTEGER NOT NULL DEFAULT 0,
  label       TEXT NOT NULL DEFAULT '',
  customer_id TEXT NOT NULL DEFAULT '',
  created_at  INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_tunnels_claim ON tunnels(claim_code);
CREATE INDEX IF NOT EXISTS idx_tunnels_customer ON tunnels(customer_id);`

// NewSQLiteStore opens (or creates) a SQLite database at path and ensures the
// schema exists.
func NewSQLiteStore(path string) (*SQLiteStore, error) {
	db, err := sql.Open("sqlite", path+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(tunnelsSchema); err != nil {
		db.Close()
		return nil, err
	}
	return &SQLiteStore{db: db}, nil
}

// Close closes the underlying database.
func (s *SQLiteStore) Close() error { return s.db.Close() }

// ImportLegacyJSON one-time-imports tunnels from a FileStore JSON file, but only
// when the DB has none yet (so it's safe to call on every boot). Returns the
// number imported. A missing file is not an error.
func (s *SQLiteStore) ImportLegacyJSON(path string) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM tunnels`).Scan(&n); err != nil {
		return 0, err
	}
	if n > 0 {
		return 0, nil // already populated — never clobber live data
	}
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return 0, nil
		}
		return 0, err
	}
	var list []Tunnel
	if err := json.Unmarshal(data, &list); err != nil {
		return 0, err
	}
	tx, err := s.db.Begin()
	if err != nil {
		return 0, err
	}
	for _, t := range list {
		if _, err := tx.Exec(insertTunnelSQL,
			t.ID, t.Token, t.ClaimCode, boolToInt(t.Claimed), t.Label, t.CustomerID, t.CreatedAt); err != nil {
			tx.Rollback()
			return 0, err
		}
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return len(list), nil
}

const insertTunnelSQL = `INSERT INTO tunnels(id, token, claim_code, claimed, label, customer_id, created_at)
	VALUES(?, ?, ?, ?, ?, ?, ?)`

const selectTunnelCols = `id, token, claim_code, claimed, label, customer_id, created_at`

func scanTunnel(row interface{ Scan(...any) error }) (Tunnel, error) {
	var t Tunnel
	var claimed int
	if err := row.Scan(&t.ID, &t.Token, &t.ClaimCode, &claimed, &t.Label, &t.CustomerID, &t.CreatedAt); err != nil {
		return Tunnel{}, err
	}
	t.Claimed = claimed != 0
	return t, nil
}

// Mint creates a fresh unclaimed tunnel with a one-time claim code.
func (s *SQLiteStore) Mint(label string, now int64) (Tunnel, error) {
	t := Tunnel{
		ID:        "tun_" + randHex(8),
		Token:     randHex(24),
		ClaimCode: randCode(),
		Label:     label,
		CreatedAt: now,
	}
	if _, err := s.db.Exec(insertTunnelSQL,
		t.ID, t.Token, t.ClaimCode, 0, t.Label, t.CustomerID, t.CreatedAt); err != nil {
		return Tunnel{}, err
	}
	return t, nil
}

// MintFor mints a tunnel bound to a billing customer, reusing an existing one if
// that customer already has a tunnel (idempotent for webhook retries).
func (s *SQLiteStore) MintFor(customerID, label string, now int64) (Tunnel, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if customerID != "" {
		if t, ok, err := s.getByCustomer(customerID); err != nil {
			return Tunnel{}, err
		} else if ok {
			return t, nil
		}
	}
	t := Tunnel{
		ID:         "tun_" + randHex(8),
		Token:      randHex(24),
		ClaimCode:  randCode(),
		Label:      label,
		CustomerID: customerID,
		CreatedAt:  now,
	}
	if _, err := s.db.Exec(insertTunnelSQL,
		t.ID, t.Token, t.ClaimCode, 0, t.Label, t.CustomerID, t.CreatedAt); err != nil {
		return Tunnel{}, err
	}
	return t, nil
}

// Claim redeems a one-time code, marking the tunnel claimed and clearing the
// code so it can't be reused.
func (s *SQLiteStore) Claim(code string) (Tunnel, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if code == "" {
		return Tunnel{}, ErrBadCode
	}
	row := s.db.QueryRow(`SELECT `+selectTunnelCols+` FROM tunnels WHERE claim_code = ? AND claimed = 0 LIMIT 1`, code)
	t, err := scanTunnel(row)
	if errors.Is(err, sql.ErrNoRows) {
		return Tunnel{}, ErrBadCode
	} else if err != nil {
		return Tunnel{}, err
	}
	if _, err := s.db.Exec(`UPDATE tunnels SET claimed = 1, claim_code = '' WHERE id = ?`, t.ID); err != nil {
		return Tunnel{}, err
	}
	t.Claimed = true
	t.ClaimCode = ""
	return t, nil
}

// Get returns a tunnel by id.
func (s *SQLiteStore) Get(id string) (Tunnel, bool) {
	row := s.db.QueryRow(`SELECT `+selectTunnelCols+` FROM tunnels WHERE id = ?`, id)
	t, err := scanTunnel(row)
	if err != nil {
		return Tunnel{}, false
	}
	return t, true
}

// GetByCustomer returns a customer's tunnel, if any.
func (s *SQLiteStore) GetByCustomer(customerID string) (Tunnel, bool) {
	t, ok, err := s.getByCustomer(customerID)
	if err != nil {
		return Tunnel{}, false
	}
	return t, ok
}

// getByCustomer is the shared lookup; callers that mutate hold s.mu.
func (s *SQLiteStore) getByCustomer(customerID string) (Tunnel, bool, error) {
	if customerID == "" {
		return Tunnel{}, false, nil
	}
	row := s.db.QueryRow(`SELECT `+selectTunnelCols+` FROM tunnels WHERE customer_id = ? LIMIT 1`, customerID)
	t, err := scanTunnel(row)
	if errors.Is(err, sql.ErrNoRows) {
		return Tunnel{}, false, nil
	} else if err != nil {
		return Tunnel{}, false, err
	}
	return t, true, nil
}

// Delete revokes a tunnel.
func (s *SQLiteStore) Delete(id string) error {
	res, err := s.db.Exec(`DELETE FROM tunnels WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// DeleteByCustomer revokes a customer's tunnel and returns its id ("" if none).
func (s *SQLiteStore) DeleteByCustomer(customerID string) string {
	s.mu.Lock()
	defer s.mu.Unlock()

	t, ok, err := s.getByCustomer(customerID)
	if err != nil || !ok {
		return ""
	}
	if _, err := s.db.Exec(`DELETE FROM tunnels WHERE id = ?`, t.ID); err != nil {
		return ""
	}
	return t.ID
}

// List returns all tunnels (tokens included; callers redact as needed).
func (s *SQLiteStore) List() []Tunnel {
	rows, err := s.db.Query(`SELECT ` + selectTunnelCols + ` FROM tunnels`)
	if err != nil {
		return nil
	}
	defer rows.Close()
	var out []Tunnel
	for rows.Next() {
		t, err := scanTunnel(rows)
		if err != nil {
			return out
		}
		out = append(out, t)
	}
	return out
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}
