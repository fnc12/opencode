package account

import (
	"errors"
	"path/filepath"
	"testing"
)

func newStore(t *testing.T) *Store {
	t.Helper()
	s, err := NewStore(filepath.Join(t.TempDir(), "accounts.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func TestNormalizeEmail(t *testing.T) {
	cases := []struct {
		in, want string
		ok       bool
	}{
		{"Foo@Bar.COM", "foo@bar.com", true},
		{"  a@b.io ", "a@b.io", true},
		{"not-an-email", "", false},
		{"", "", false},
	}
	for _, c := range cases {
		got, err := NormalizeEmail(c.in)
		if c.ok && (err != nil || got != c.want) {
			t.Errorf("NormalizeEmail(%q) = (%q, %v), want (%q, nil)", c.in, got, err, c.want)
		}
		if !c.ok && !errors.Is(err, ErrBadEmail) {
			t.Errorf("NormalizeEmail(%q) err = %v, want ErrBadEmail", c.in, err)
		}
	}
}

func TestLoginTokenSingleUse(t *testing.T) {
	s := newStore(t)
	tok, err := s.IssueLoginToken("User@Example.com", 1000)
	if err != nil {
		t.Fatal(err)
	}
	// Redeem returns the normalized email.
	email, err := s.RedeemLoginToken(tok, 500)
	if err != nil {
		t.Fatalf("redeem: %v", err)
	}
	if email != "user@example.com" {
		t.Errorf("redeem email = %q, want normalized", email)
	}
	// Second redeem must fail (single-use).
	if _, err := s.RedeemLoginToken(tok, 500); !errors.Is(err, ErrBadToken) {
		t.Errorf("second redeem err = %v, want ErrBadToken", err)
	}
}

func TestLoginTokenExpiryAndUnknown(t *testing.T) {
	s := newStore(t)
	tok, _ := s.IssueLoginToken("a@b.io", 1000)
	if _, err := s.RedeemLoginToken(tok, 1001); !errors.Is(err, ErrBadToken) {
		t.Errorf("expired redeem err = %v, want ErrBadToken", err)
	}
	if _, err := s.RedeemLoginToken("deadbeef", 0); !errors.Is(err, ErrBadToken) {
		t.Errorf("unknown token err = %v, want ErrBadToken", err)
	}
	if _, err := s.IssueLoginToken("bad", 1000); !errors.Is(err, ErrBadEmail) {
		t.Errorf("issue for bad email err = %v, want ErrBadEmail", err)
	}
}

func TestAccountForEmailIsStable(t *testing.T) {
	s := newStore(t)
	a1, _, err := s.AccountForEmail("me@x.com", 1)
	if err != nil {
		t.Fatal(err)
	}
	// Same address (any case) → same account.
	a2, _, _ := s.AccountForEmail("ME@X.com", 2)
	if a1 != a2 {
		t.Errorf("same email produced different accounts: %s vs %s", a1, a2)
	}
	// Different address → different account.
	b, _, _ := s.AccountForEmail("other@x.com", 3)
	if b == a1 {
		t.Errorf("different email should be a different account")
	}
	// The email identity and AccountForIdentity("email", ...) agree.
	a3, _, _ := s.AccountForIdentity("email", "me@x.com", 4)
	if a3 != a1 {
		t.Errorf("AccountForIdentity(email) disagrees with AccountForEmail")
	}
}

func TestAccountForIdentityProviders(t *testing.T) {
	s := newStore(t)
	g, _, err := s.AccountForIdentity("google", "google-uid-1", 1)
	if err != nil {
		t.Fatal(err)
	}
	if again, _, _ := s.AccountForIdentity("google", "google-uid-1", 2); again != g {
		t.Errorf("same google identity should map to same account")
	}
	// Different provider, same subject string → different account (namespaced).
	h, _, _ := s.AccountForIdentity("github", "google-uid-1", 3)
	if h == g {
		t.Errorf("identities must be namespaced by provider")
	}
}

func TestSessions(t *testing.T) {
	s := newStore(t)
	acc, _, _ := s.AccountForEmail("s@x.com", 1)
	sid, err := s.CreateSession(acc, 1, 1000)
	if err != nil {
		t.Fatal(err)
	}
	got, err := s.AccountBySession(sid, 500)
	if err != nil || got != acc {
		t.Fatalf("AccountBySession = (%q, %v), want (%q, nil)", got, err, acc)
	}
	// Expired.
	if _, err := s.AccountBySession(sid, 1001); !errors.Is(err, ErrNoSession) {
		t.Errorf("expired session err = %v, want ErrNoSession", err)
	}
	// Unknown.
	if _, err := s.AccountBySession("nope", 0); !errors.Is(err, ErrNoSession) {
		t.Errorf("unknown session err = %v, want ErrNoSession", err)
	}
	// Logout revokes.
	if err := s.DeleteSession(sid); err != nil {
		t.Fatal(err)
	}
	if _, err := s.AccountBySession(sid, 500); !errors.Is(err, ErrNoSession) {
		t.Errorf("deleted session err = %v, want ErrNoSession", err)
	}
}

func TestSubscriptionBinding(t *testing.T) {
	s := newStore(t)
	acc, _, _ := s.AccountForEmail("pay@x.com", 1)
	if err := s.BindSubscription(acc, "I-SUB-1", 1); err != nil {
		t.Fatal(err)
	}
	subs, _ := s.SubscriptionsForAccount(acc)
	if len(subs) != 1 || subs[0] != "I-SUB-1" {
		t.Errorf("SubscriptionsForAccount = %v, want [I-SUB-1]", subs)
	}
	if owner, ok, _ := s.AccountForSubscription("I-SUB-1"); !ok || owner != acc {
		t.Errorf("AccountForSubscription = (%q, %v), want (%q, true)", owner, ok, acc)
	}
	if _, ok, _ := s.AccountForSubscription("I-UNKNOWN"); ok {
		t.Errorf("unknown subscription should not resolve")
	}
	// Re-binding to a different account re-points (last binder wins).
	acc2, _, _ := s.AccountForEmail("pay2@x.com", 2)
	if err := s.BindSubscription(acc2, "I-SUB-1", 3); err != nil {
		t.Fatal(err)
	}
	if owner, _, _ := s.AccountForSubscription("I-SUB-1"); owner != acc2 {
		t.Errorf("re-bind should re-point subscription to acc2, got %q", owner)
	}
	if subs, _ := s.SubscriptionsForAccount(acc); len(subs) != 0 {
		t.Errorf("original account should no longer own the subscription, got %v", subs)
	}
}
