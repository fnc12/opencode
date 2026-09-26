// Package account is the relay's user layer: accounts, third-party identities
// (email magic-link, Google, GitHub), server-side sessions, and the binding
// between a paying PayPal subscription and the account that owns it.
//
// It deliberately keeps login identity separate from the PayPal payer email: a
// user may sign in with one address and pay with another, so subscriptions are
// linked to an account by an explicit, logged-in binding step — never by
// matching email addresses.
package account

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"net/mail"
	"strings"

	_ "modernc.org/sqlite"
)

var (
	// ErrBadEmail is returned when an email address doesn't parse.
	ErrBadEmail = errors.New("invalid email address")
	// ErrBadToken is returned for an unknown, expired, or already-used login token.
	ErrBadToken = errors.New("invalid or expired login token")
	// ErrNoSession is returned when a session id is unknown or expired.
	ErrNoSession = errors.New("no such session")
)

// Store persists accounts and their auth state in SQLite.
type Store struct {
	db *sql.DB
}

const schema = `
CREATE TABLE IF NOT EXISTS accounts (
  id         TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS identities (
  provider   TEXT NOT NULL,
  subject    TEXT NOT NULL,
  account_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (provider, subject)
);
CREATE INDEX IF NOT EXISTS idx_identities_account ON identities(account_id);
CREATE TABLE IF NOT EXISTS login_tokens (
  token      TEXT PRIMARY KEY,
  email      TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  used       INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS sessions (
  id         TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_account ON sessions(account_id);
CREATE TABLE IF NOT EXISTS subscriptions (
  customer_id TEXT PRIMARY KEY,
  account_id  TEXT NOT NULL,
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_subs_account ON subscriptions(account_id);
CREATE TABLE IF NOT EXISTS promo_codes (
  code       TEXT PRIMARY KEY,
  months     INTEGER NOT NULL,
  max_uses   INTEGER NOT NULL DEFAULT 0,
  uses       INTEGER NOT NULL DEFAULT 0,
  expires_at INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS promo_redemptions (
  code        TEXT NOT NULL,
  account_id  TEXT NOT NULL,
  redeemed_at INTEGER NOT NULL,
  PRIMARY KEY (code, account_id)
);`

// NewStore opens (or creates) the account database at path and ensures the
// schema exists. It's safe to point at the same file as the provision store.
func NewStore(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, err
	}
	if err := migrateAccounts(db); err != nil {
		db.Close()
		return nil, err
	}
	return &Store{db: db}, nil
}

// migrateAccounts adds the entitlement/referral columns to an existing accounts
// table (idempotent — a fresh DB from `schema` gets them here too).
func migrateAccounts(db *sql.DB) error {
	rows, err := db.Query(`PRAGMA table_info(accounts)`)
	if err != nil {
		return err
	}
	have := map[string]bool{}
	for rows.Next() {
		var cid int
		var name, ctype string
		var notnull, pk int
		var dflt any
		if err := rows.Scan(&cid, &name, &ctype, &notnull, &dflt, &pk); err != nil {
			rows.Close()
			return err
		}
		have[name] = true
	}
	rows.Close()

	for _, c := range []struct{ name, ddl string }{
		{"free_until", `ALTER TABLE accounts ADD COLUMN free_until INTEGER NOT NULL DEFAULT 0`},
		{"referral_code", `ALTER TABLE accounts ADD COLUMN referral_code TEXT`},
		{"referred_by", `ALTER TABLE accounts ADD COLUMN referred_by TEXT NOT NULL DEFAULT ''`},
	} {
		if !have[c.name] {
			if _, err := db.Exec(c.ddl); err != nil {
				return err
			}
		}
	}
	_, err = db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_refcode ON accounts(referral_code) WHERE referral_code IS NOT NULL`)
	return err
}

// Close closes the underlying database.
func (s *Store) Close() error { return s.db.Close() }

// NormalizeEmail lowercases and trims an address, returning ErrBadEmail if it
// doesn't parse. Exported so handlers validate the same way the store stores.
func NormalizeEmail(email string) (string, error) {
	addr, err := mail.ParseAddress(strings.TrimSpace(email))
	if err != nil {
		return "", ErrBadEmail
	}
	// mail.ParseAddress accepts "Name <a@b>"; keep only the address, lowercased.
	return strings.ToLower(addr.Address), nil
}

// IssueLoginToken mints a single-use magic-link token for email, valid until
// expiresAt. The email is normalized; the raw token is returned to embed in the
// link.
func (s *Store) IssueLoginToken(email string, expiresAt int64) (string, error) {
	norm, err := NormalizeEmail(email)
	if err != nil {
		return "", err
	}
	token := randHex(32)
	if _, err := s.db.Exec(
		`INSERT INTO login_tokens(token, email, expires_at, used) VALUES(?, ?, ?, 0)`,
		token, norm, expiresAt); err != nil {
		return "", err
	}
	return token, nil
}

// RedeemLoginToken consumes a login token, returning the email it was issued for.
// It is single-use (marked used) and rejects expired or already-used tokens.
// now is the current unix time.
func (s *Store) RedeemLoginToken(token string, now int64) (string, error) {
	var email string
	var expiresAt int64
	var used int
	err := s.db.QueryRow(
		`SELECT email, expires_at, used FROM login_tokens WHERE token = ?`, token).
		Scan(&email, &expiresAt, &used)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrBadToken
	} else if err != nil {
		return "", err
	}
	if used != 0 || now > expiresAt {
		return "", ErrBadToken
	}
	if _, err := s.db.Exec(`UPDATE login_tokens SET used = 1 WHERE token = ?`, token); err != nil {
		return "", err
	}
	return email, nil
}

// AccountForIdentity resolves the account for a (provider, subject) identity,
// creating both the account and the identity on first sight. Use provider
// "email" with the address as subject for magic-link, or "google"/"github" with
// the provider user id for OAuth.
// AccountForIdentity resolves the account for a (provider, subject) identity,
// creating both on first sight. The bool result reports whether a new account
// was created — the caller applies referral credit only for new accounts. A new
// account gets a referral code and, while the free cap isn't reached, one free
// month (first-N-free launch promo).
func (s *Store) AccountForIdentity(provider, subject string, now int64) (string, bool, error) {
	var accountID string
	err := s.db.QueryRow(
		`SELECT account_id FROM identities WHERE provider = ? AND subject = ?`,
		provider, subject).Scan(&accountID)
	if err == nil {
		return accountID, false, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return "", false, err
	}
	accountID = "acc_" + randHex(12)
	refCode := randHex(6)
	tx, err := s.db.Begin()
	if err != nil {
		return "", false, err
	}
	var count int
	if err := tx.QueryRow(`SELECT COUNT(*) FROM accounts`).Scan(&count); err != nil {
		tx.Rollback()
		return "", false, err
	}
	var freeUntil int64
	if count < firstFreeCap {
		freeUntil = now + firstFreeMonths*monthSeconds
	}
	if _, err := tx.Exec(
		`INSERT INTO accounts(id, created_at, free_until, referral_code, referred_by) VALUES(?, ?, ?, ?, '')`,
		accountID, now, freeUntil, refCode); err != nil {
		tx.Rollback()
		return "", false, err
	}
	if _, err := tx.Exec(
		`INSERT INTO identities(provider, subject, account_id, created_at) VALUES(?, ?, ?, ?)`,
		provider, subject, accountID, now); err != nil {
		tx.Rollback()
		return "", false, err
	}
	if err := tx.Commit(); err != nil {
		return "", false, err
	}
	return accountID, true, nil
}

// AccountForEmail is AccountForIdentity for the email provider (normalizing the
// address first).
func (s *Store) AccountForEmail(email string, now int64) (string, bool, error) {
	norm, err := NormalizeEmail(email)
	if err != nil {
		return "", false, err
	}
	return s.AccountForIdentity("email", norm, now)
}

// CreateSession opens a session for accountID valid until expiresAt and returns
// its opaque id (used as the cookie value).
func (s *Store) CreateSession(accountID string, now, expiresAt int64) (string, error) {
	id := randHex(32)
	if _, err := s.db.Exec(
		`INSERT INTO sessions(id, account_id, created_at, expires_at) VALUES(?, ?, ?, ?)`,
		id, accountID, now, expiresAt); err != nil {
		return "", err
	}
	return id, nil
}

// AccountBySession returns the account for a live session id, or ErrNoSession if
// unknown or expired.
func (s *Store) AccountBySession(sessionID string, now int64) (string, error) {
	var accountID string
	var expiresAt int64
	err := s.db.QueryRow(
		`SELECT account_id, expires_at FROM sessions WHERE id = ?`, sessionID).
		Scan(&accountID, &expiresAt)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNoSession
	} else if err != nil {
		return "", err
	}
	if now > expiresAt {
		return "", ErrNoSession
	}
	return accountID, nil
}

// DeleteSession revokes a session (logout). Unknown ids are a no-op.
func (s *Store) DeleteSession(sessionID string) error {
	_, err := s.db.Exec(`DELETE FROM sessions WHERE id = ?`, sessionID)
	return err
}

// BindSubscription links a PayPal subscription (customer id) to an account. It's
// idempotent and re-points the subscription if bound again (last binder wins).
func (s *Store) BindSubscription(accountID, customerID string, now int64) error {
	_, err := s.db.Exec(
		`INSERT INTO subscriptions(customer_id, account_id, created_at) VALUES(?, ?, ?)
		 ON CONFLICT(customer_id) DO UPDATE SET account_id = excluded.account_id`,
		customerID, accountID, now)
	return err
}

// SubscriptionsForAccount returns the PayPal subscription (customer) ids bound to
// an account.
func (s *Store) SubscriptionsForAccount(accountID string) ([]string, error) {
	rows, err := s.db.Query(`SELECT customer_id FROM subscriptions WHERE account_id = ?`, accountID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return out, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// AccountForSubscription returns the account a subscription is bound to.
func (s *Store) AccountForSubscription(customerID string) (string, bool, error) {
	var accountID string
	err := s.db.QueryRow(`SELECT account_id FROM subscriptions WHERE customer_id = ?`, customerID).Scan(&accountID)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	} else if err != nil {
		return "", false, err
	}
	return accountID, true, nil
}

func randHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}
