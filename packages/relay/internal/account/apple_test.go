package account

import "testing"

func appleTx(txID string, expires, signed int64) AppleTransaction {
	return AppleTransaction{
		OriginalTransactionID: "1000",
		TransactionID:         txID,
		ProductID:             "pro.monthly",
		Environment:           "Sandbox",
		ExpiresAt:             expires,
		SignedAt:              signed,
	}
}

func TestAppleSubscriptionEntitles(t *testing.T) {
	s := newStore(t)
	acc := mkAccount(t, s, "a@x.com", 1)
	if s.AppleActive(acc, 100) {
		t.Fatal("no Apple subscription yet")
	}
	if _, err := s.ApplyAppleTransaction(acc, appleTx("t1", 500, 10), 100); err != nil {
		t.Fatal(err)
	}
	if !s.AppleActive(acc, 100) {
		t.Error("live subscription should entitle")
	}
	if until, _ := s.AppleEntitledUntil(acc); until != 500 {
		t.Errorf("AppleEntitledUntil = %d, want 500", until)
	}
	if s.AppleActive(acc, 500) {
		t.Error("subscription past expiresDate must not entitle")
	}
}

func TestAppleRenewalExtendsAndStaleIgnored(t *testing.T) {
	s := newStore(t)
	acc := mkAccount(t, s, "a@x.com", 1)
	s.ApplyAppleTransaction(acc, appleTx("t1", 500, 10), 100)
	s.ApplyAppleTransaction("", appleTx("t2", 900, 20), 101) // DID_RENEW notification
	if until, _ := s.AppleEntitledUntil(acc); until != 900 {
		t.Fatalf("after renewal until = %d, want 900", until)
	}
	// The device re-uploads its older cached JWS: must not shorten the expiry.
	sub, _ := s.ApplyAppleTransaction(acc, appleTx("t1", 500, 10), 102)
	if sub.ExpiresAt != 900 || sub.TransactionID != "t2" {
		t.Errorf("stale upload changed state: %+v", sub)
	}
}

func TestAppleRefundRevokesAndReplayCantUndo(t *testing.T) {
	s := newStore(t)
	acc := mkAccount(t, s, "a@x.com", 1)
	s.ApplyAppleTransaction(acc, appleTx("t1", 500, 10), 100)
	refund := appleTx("t1", 500, 30)
	refund.RevokedAt = 150
	s.ApplyAppleTransaction("", refund, 150)
	if s.AppleActive(acc, 200) {
		t.Fatal("refunded subscription must not entitle")
	}
	if until, _ := s.AppleEntitledUntil(acc); until != 0 {
		t.Errorf("refunded until = %d, want 0", until)
	}
	// Replaying the pre-refund JWS (signed earlier) must not restore access.
	s.ApplyAppleTransaction(acc, appleTx("t1", 500, 10), 210)
	if s.AppleActive(acc, 220) {
		t.Error("replayed pre-refund JWS restored access")
	}
	// A refund reversal (newer, no revocation) restores it.
	s.ApplyAppleTransaction("", appleTx("t1", 500, 40), 230)
	if !s.AppleActive(acc, 240) {
		t.Error("REFUND_REVERSED should restore access")
	}
}

func TestAppleNotificationBeforeUploadThenBind(t *testing.T) {
	s := newStore(t)
	acc := mkAccount(t, s, "a@x.com", 1)
	// The server notification can beat the app's upload: stored unbound.
	sub, _ := s.ApplyAppleTransaction("", appleTx("t1", 500, 10), 100)
	if sub.AccountID != "" {
		t.Fatalf("notification bound an account: %+v", sub)
	}
	sub, _ = s.ApplyAppleTransaction(acc, appleTx("t1", 500, 10), 101)
	if sub.AccountID != acc || !s.AppleActive(acc, 200) {
		t.Errorf("upload didn't bind: %+v", sub)
	}
}

func TestAppleSubscriptionMovesToLastBinder(t *testing.T) {
	s := newStore(t)
	a := mkAccount(t, s, "a@x.com", 1)
	b := mkAccount(t, s, "b@x.com", 1)
	s.ApplyAppleTransaction(a, appleTx("t1", 500, 10), 100)
	s.ApplyAppleTransaction(b, appleTx("t1", 500, 10), 101)
	if s.AppleActive(a, 200) || !s.AppleActive(b, 200) {
		t.Error("one Apple subscription must entitle only its last binder")
	}
}

func TestDeleteAccountDropsAppleSubscription(t *testing.T) {
	s := newStore(t)
	acc := mkAccount(t, s, "a@x.com", 1)
	s.ApplyAppleTransaction(acc, appleTx("t1", 500, 10), 100)
	if err := s.DeleteAccount(acc); err != nil {
		t.Fatal(err)
	}
	if _, found, _ := s.AppleSubscription("1000"); found {
		t.Error("account deletion left the Apple subscription row")
	}
}

func TestAppleRefundOfPastPeriodKeepsCurrent(t *testing.T) {
	s := newStore(t)
	acc := mkAccount(t, s, "a@x.com", 1)
	s.ApplyAppleTransaction(acc, appleTx("t1", 500, 10), 100)
	s.ApplyAppleTransaction("", appleTx("t2", 900, 20), 101) // DID_RENEW
	// Apple refunds the first, already-ended period.
	refund := appleTx("t1", 500, 30)
	refund.RevokedAt = 600
	sub, _ := s.ApplyAppleTransaction("", refund, 600)
	if sub.TransactionID != "t2" || sub.ExpiresAt != 900 || sub.RevokedAt != 0 || sub.SignedAt != 20 {
		t.Fatalf("refund of a past period changed the current one: %+v", sub)
	}
	if !s.AppleActive(acc, 700) {
		t.Error("refund of a past period cut off the paid current period")
	}
	// The app's upload of the current transaction (signed after the stored one
	// but before the refund notification) still applies.
	sub, _ = s.ApplyAppleTransaction(acc, appleTx("t2", 900, 25), 610)
	if sub.SignedAt != 25 {
		t.Errorf("current-period upload ignored after past refund: %+v", sub)
	}
	// Refunding the current period itself still revokes.
	cur := appleTx("t2", 900, 40)
	cur.RevokedAt = 650
	s.ApplyAppleTransaction("", cur, 650)
	if s.AppleActive(acc, 700) {
		t.Error("refund of the current period must revoke")
	}
}

func TestAppleLaterPeriodReplacesPastRefund(t *testing.T) {
	s := newStore(t)
	acc := mkAccount(t, s, "a@x.com", 1)
	s.ApplyAppleTransaction(acc, appleTx("t1", 500, 10), 100)
	// DID_RENEW for t2 is lost; the REFUND of t1 arrives first and stores
	// the notification date as SignedAt.
	refund := appleTx("t1", 500, 600)
	refund.RevokedAt = 600
	s.ApplyAppleTransaction("", refund, 600)
	// The app uploads t2, signed on the renewal day, before the refund.
	sub, _ := s.ApplyAppleTransaction(acc, appleTx("t2", 900, 20), 610)
	if sub.TransactionID != "t2" || sub.ExpiresAt != 900 || sub.RevokedAt != 0 {
		t.Fatalf("later period ignored after a past-period refund: %+v", sub)
	}
	if !s.AppleActive(acc, 700) {
		t.Error("paid current period denied after a past-period refund")
	}
	// Replaying the pre-refund JWS of t1 changes nothing.
	sub, _ = s.ApplyAppleTransaction(acc, appleTx("t1", 500, 10), 620)
	if sub.TransactionID != "t2" || sub.ExpiresAt != 900 || sub.SignedAt != 20 {
		t.Errorf("replayed past-period JWS replaced the current one: %+v", sub)
	}
}
