package account

import (
	"database/sql"
	"errors"
	"strings"
)

const (
	firstFreeCap    = 100 // first N accounts get a free month
	firstFreeMonths = 1
	referralMonths  = 1  // free months a referrer earns per signup
	referralCap     = 12 // max total referral-bonus months per referrer
	monthSeconds    = 30 * 24 * 60 * 60
)

var (
	// ErrBadPromo is an unknown or expired promo code.
	ErrBadPromo = errors.New("unknown or expired promo code")
	// ErrPromoUsedUp is a promo code that has hit its redemption limit.
	ErrPromoUsedUp = errors.New("promo code has no uses left")
	// ErrPromoRedeemed is a promo code the account already redeemed.
	ErrPromoRedeemed = errors.New("promo code already redeemed by this account")
)

// GrantMonths extends an account's free entitlement by n months, from the later
// of now and its current expiry — so first-100, referral and promo months stack.
func (s *Store) GrantMonths(accountID string, n int, now int64) error {
	if n <= 0 {
		return nil
	}
	var cur int64
	if err := s.db.QueryRow(`SELECT free_until FROM accounts WHERE id = ?`, accountID).Scan(&cur); err != nil {
		return err
	}
	base := cur
	if now > base {
		base = now
	}
	_, err := s.db.Exec(`UPDATE accounts SET free_until = ? WHERE id = ?`, base+int64(n)*monthSeconds, accountID)
	return err
}

// EntitledUntil returns the account's free-access expiry (0 if none/unknown).
func (s *Store) EntitledUntil(accountID string) (int64, error) {
	var v int64
	err := s.db.QueryRow(`SELECT free_until FROM accounts WHERE id = ?`, accountID).Scan(&v)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, nil
	}
	return v, err
}

// FreeActive reports whether the account currently has free entitlement.
func (s *Store) FreeActive(accountID string, now int64) bool {
	until, err := s.EntitledUntil(accountID)
	return err == nil && until > now
}

// ReferralCode returns the account's shareable referral code.
func (s *Store) ReferralCode(accountID string) (string, error) {
	var code sql.NullString
	if err := s.db.QueryRow(`SELECT referral_code FROM accounts WHERE id = ?`, accountID).Scan(&code); err != nil {
		return "", err
	}
	return code.String, nil
}

// RegisteredCount returns the total number of accounts.
func (s *Store) RegisteredCount() (int, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM accounts`).Scan(&n)
	return n, err
}

// FreeSpotsLeft returns how many first-N-free spots remain (never negative).
func (s *Store) FreeSpotsLeft() (int, error) {
	n, err := s.RegisteredCount()
	if err != nil {
		return 0, err
	}
	if left := firstFreeCap - n; left > 0 {
		return left, nil
	}
	return 0, nil
}

// ReferralCount returns how many accounts a referrer has brought in.
func (s *Store) ReferralCount(accountID string) (int, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM accounts WHERE referred_by = ?`, accountID).Scan(&n)
	return n, err
}

// ApplyReferral credits a referrer when newAccountID signed up via refCode. It's
// a no-op (returns false) if the code is unknown, is the account's own, the
// account was already referred, or the referrer is at the bonus cap. Call it
// only for a freshly created account.
func (s *Store) ApplyReferral(newAccountID, refCode string, now int64) (bool, error) {
	refCode = strings.TrimSpace(refCode)
	if refCode == "" {
		return false, nil
	}
	var referrer string
	err := s.db.QueryRow(`SELECT id FROM accounts WHERE referral_code = ?`, refCode).Scan(&referrer)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if referrer == newAccountID {
		return false, nil
	}
	var existing string
	if err := s.db.QueryRow(`SELECT referred_by FROM accounts WHERE id = ?`, newAccountID).Scan(&existing); err != nil {
		return false, err
	}
	if existing != "" {
		return false, nil // already attributed
	}
	if _, err := s.db.Exec(`UPDATE accounts SET referred_by = ? WHERE id = ?`, referrer, newAccountID); err != nil {
		return false, err
	}
	// Cap the bonus: count includes the one just recorded, so grant while <= cap.
	var earned int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM accounts WHERE referred_by = ?`, referrer).Scan(&earned); err != nil {
		return false, err
	}
	if earned > referralCap {
		return false, nil
	}
	if err := s.GrantMonths(referrer, referralMonths, now); err != nil {
		return false, err
	}
	return true, nil
}

// CreatePromo creates or updates a promo code granting `months` free, with an
// optional max redemptions (0 = unlimited) and expiry unix time (0 = never).
func (s *Store) CreatePromo(code string, months, maxUses int, expiresAt int64) error {
	code = normalizePromo(code)
	if code == "" || months <= 0 {
		return errors.New("promo needs a code and positive months")
	}
	_, err := s.db.Exec(
		`INSERT INTO promo_codes(code, months, max_uses, uses, expires_at) VALUES(?, ?, ?, 0, ?)
		 ON CONFLICT(code) DO UPDATE SET months=excluded.months, max_uses=excluded.max_uses, expires_at=excluded.expires_at`,
		code, months, maxUses, expiresAt)
	return err
}

// RedeemPromo applies a promo code to an account (once per account), granting
// its months. Enforces expiry and max-uses atomically.
func (s *Store) RedeemPromo(accountID, code string, now int64) (int, error) {
	code = normalizePromo(code)
	tx, err := s.db.Begin()
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()

	var months, maxUses, uses int
	var expiresAt int64
	err = tx.QueryRow(`SELECT months, max_uses, uses, expires_at FROM promo_codes WHERE code = ?`, code).
		Scan(&months, &maxUses, &uses, &expiresAt)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, ErrBadPromo
	}
	if err != nil {
		return 0, err
	}
	if expiresAt != 0 && now > expiresAt {
		return 0, ErrBadPromo
	}
	if maxUses != 0 && uses >= maxUses {
		return 0, ErrPromoUsedUp
	}
	if _, err := tx.Exec(`INSERT INTO promo_redemptions(code, account_id, redeemed_at) VALUES(?, ?, ?)`, code, accountID, now); err != nil {
		if strings.Contains(err.Error(), "UNIQUE constraint failed") {
			return 0, ErrPromoRedeemed
		}
		return 0, err
	}
	if _, err := tx.Exec(`UPDATE promo_codes SET uses = uses + 1 WHERE code = ?`, code); err != nil {
		return 0, err
	}
	var cur int64
	if err := tx.QueryRow(`SELECT free_until FROM accounts WHERE id = ?`, accountID).Scan(&cur); err != nil {
		return 0, err
	}
	base := cur
	if now > base {
		base = now
	}
	if _, err := tx.Exec(`UPDATE accounts SET free_until = ? WHERE id = ?`, base+int64(months)*monthSeconds, accountID); err != nil {
		return 0, err
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return months, nil
}

func normalizePromo(code string) string {
	return strings.ToUpper(strings.TrimSpace(code))
}
