package account

import (
	"errors"
	"fmt"
	"testing"
)

func mkAccount(t *testing.T, s *Store, email string, now int64) string {
	t.Helper()
	id, _, err := s.AccountForEmail(email, now)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func TestFirstHundredFreeMonth(t *testing.T) {
	s := newStore(t)
	const now = 1000

	acc := mkAccount(t, s, "first@x.com", now)
	until, _ := s.EntitledUntil(acc)
	if until != now+firstFreeMonths*monthSeconds {
		t.Errorf("first account free_until = %d, want %d", until, now+firstFreeMonths*monthSeconds)
	}
	if !s.FreeActive(acc, now) {
		t.Error("first account should be free-active")
	}
	if left, _ := s.FreeSpotsLeft(); left != firstFreeCap-1 {
		t.Errorf("FreeSpotsLeft = %d, want %d", left, firstFreeCap-1)
	}
}

func TestFreeCapExhausts(t *testing.T) {
	s := newStore(t)
	const now = 1000
	// Fill all free spots.
	for i := 0; i < firstFreeCap; i++ {
		mkAccount(t, s, fmt.Sprintf("u%d@x.com", i), now)
	}
	if left, _ := s.FreeSpotsLeft(); left != 0 {
		t.Errorf("FreeSpotsLeft after cap = %d, want 0", left)
	}
	// The next account gets no free month.
	over := mkAccount(t, s, "over@x.com", now)
	if until, _ := s.EntitledUntil(over); until != 0 {
		t.Errorf("account past the cap has free_until %d, want 0", until)
	}
	if s.FreeActive(over, now) {
		t.Error("account past the cap must not be free-active")
	}
}

func TestGrantMonthsStacks(t *testing.T) {
	s := newStore(t)
	// A fresh non-free account (past cap) — grant from `now`.
	for i := 0; i < firstFreeCap; i++ {
		mkAccount(t, s, fmt.Sprintf("f%d@x.com", i), 1)
	}
	acc := mkAccount(t, s, "g@x.com", 1)
	if err := s.GrantMonths(acc, 2, 1000); err != nil {
		t.Fatal(err)
	}
	if until, _ := s.EntitledUntil(acc); until != 1000+2*monthSeconds {
		t.Errorf("after grant from now: %d, want %d", until, 1000+2*monthSeconds)
	}
	// A second grant stacks onto the existing expiry, not `now`.
	if err := s.GrantMonths(acc, 1, 1000); err != nil {
		t.Fatal(err)
	}
	if until, _ := s.EntitledUntil(acc); until != 1000+3*monthSeconds {
		t.Errorf("second grant should stack: %d, want %d", until, 1000+3*monthSeconds)
	}
}

func TestReferral(t *testing.T) {
	s := newStore(t)
	const now = 1000
	referrer := mkAccount(t, s, "ref@x.com", now)
	code, _ := s.ReferralCode(referrer)
	if code == "" {
		t.Fatal("account has no referral code")
	}
	beforeUntil, _ := s.EntitledUntil(referrer)

	invited := mkAccount(t, s, "invited@x.com", now)
	ok, err := s.ApplyReferral(invited, code, now)
	if err != nil || !ok {
		t.Fatalf("ApplyReferral = (%v, %v), want (true, nil)", ok, err)
	}
	if afterUntil, _ := s.EntitledUntil(referrer); afterUntil != beforeUntil+referralMonths*monthSeconds {
		t.Errorf("referrer not credited: %d -> %d", beforeUntil, afterUntil)
	}
	if n, _ := s.ReferralCount(referrer); n != 1 {
		t.Errorf("ReferralCount = %d, want 1", n)
	}
	// Re-applying for the same invited account does nothing (already attributed).
	if ok, _ := s.ApplyReferral(invited, code, now); ok {
		t.Error("second ApplyReferral for the same account should be a no-op")
	}
	// Unknown code and self-referral are no-ops.
	if ok, _ := s.ApplyReferral(mkAccount(t, s, "x@x.com", now), "nope", now); ok {
		t.Error("unknown referral code should not credit")
	}
	self := mkAccount(t, s, "self@x.com", now)
	selfCode, _ := s.ReferralCode(self)
	if ok, _ := s.ApplyReferral(self, selfCode, now); ok {
		t.Error("self-referral should not credit")
	}
}

func TestReferralCap(t *testing.T) {
	s := newStore(t)
	const now = 1000
	referrer := mkAccount(t, s, "capref@x.com", now)
	code, _ := s.ReferralCode(referrer)
	base, _ := s.EntitledUntil(referrer)

	// Bring in more than the cap; only `referralCap` months should be credited.
	for i := 0; i < referralCap+3; i++ {
		inv := mkAccount(t, s, fmt.Sprintf("inv%d@x.com", i), now)
		s.ApplyReferral(inv, code, now)
	}
	got, _ := s.EntitledUntil(referrer)
	want := base + int64(referralCap)*monthSeconds
	if got != want {
		t.Errorf("referral bonus not capped: got %d, want %d (cap %d months)", got, want, referralCap)
	}
	if n, _ := s.ReferralCount(referrer); n != referralCap+3 {
		t.Errorf("all referrals should still be recorded: %d, want %d", n, referralCap+3)
	}
}

func TestPromoRedeem(t *testing.T) {
	s := newStore(t)
	const now = 1000
	acc := mkAccount(t, s, "promo@x.com", now)
	base, _ := s.EntitledUntil(acc)

	if err := s.CreatePromo("launch10", 3, 2, 0); err != nil { // 3 months, max 2 uses, never expires
		t.Fatal(err)
	}
	months, err := s.RedeemPromo(acc, "LAUNCH10", now) // case-insensitive
	if err != nil || months != 3 {
		t.Fatalf("redeem = (%d, %v), want (3, nil)", months, err)
	}
	if until, _ := s.EntitledUntil(acc); until != base+3*monthSeconds {
		t.Errorf("promo months not granted: %d, want %d", until, base+3*monthSeconds)
	}
	// Same account can't redeem twice.
	if _, err := s.RedeemPromo(acc, "launch10", now); !errors.Is(err, ErrPromoRedeemed) {
		t.Errorf("second redeem err = %v, want ErrPromoRedeemed", err)
	}
	// A second account uses the last remaining use; a third is refused.
	if _, err := s.RedeemPromo(mkAccount(t, s, "a2@x.com", now), "launch10", now); err != nil {
		t.Fatalf("second account redeem: %v", err)
	}
	if _, err := s.RedeemPromo(mkAccount(t, s, "a3@x.com", now), "launch10", now); !errors.Is(err, ErrPromoUsedUp) {
		t.Errorf("over-limit redeem err = %v, want ErrPromoUsedUp", err)
	}
}

func TestPromoUnknownAndExpired(t *testing.T) {
	s := newStore(t)
	const now = 1000
	acc := mkAccount(t, s, "p@x.com", now)
	if _, err := s.RedeemPromo(acc, "NOPE", now); !errors.Is(err, ErrBadPromo) {
		t.Errorf("unknown promo err = %v, want ErrBadPromo", err)
	}
	if err := s.CreatePromo("old", 1, 0, 500); err != nil { // expired at 500
		t.Fatal(err)
	}
	if _, err := s.RedeemPromo(acc, "old", now); !errors.Is(err, ErrBadPromo) {
		t.Errorf("expired promo err = %v, want ErrBadPromo", err)
	}
}
