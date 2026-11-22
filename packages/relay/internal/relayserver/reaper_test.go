package relayserver

import (
	"io"
	"log/slog"
	"path/filepath"
	"testing"

	"github.com/fnc12/opencode/packages/relay/internal/account"
	"github.com/fnc12/opencode/packages/relay/internal/provision"
)

func reaperServer(t *testing.T) (*Server, provision.Store, *account.Store) {
	t.Helper()
	pstore, err := provision.NewSQLiteStore(filepath.Join(t.TempDir(), "p.db"))
	if err != nil {
		t.Fatal(err)
	}
	astore, err := account.NewStore(filepath.Join(t.TempDir(), "a.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { astore.Close() })
	srv := New(Config{Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Provision: pstore, Accounts: astore})
	return srv, pstore, astore
}

const monthSec = 30 * 24 * 60 * 60

func TestReaperKeepsFreeTunnels(t *testing.T) {
	srv, pstore, astore := reaperServer(t)
	const now = 1000

	acc, _, _ := astore.AccountForEmail("free@x.com", now) // first-100 → free until now+1mo
	pstore.MintFor(acc, "account:"+acc, now)               // free tunnel keyed by account id

	// Shubat is free: even long after the free window, without any
	// subscription, the reaper leaves the tunnel alone.
	future := int64(now + 2*monthSec)
	if n := srv.reapExpiredFree(future); n != 0 {
		t.Errorf("reaped %d after the free window, want 0", n)
	}
	if _, ok := pstore.GetByCustomer(acc); !ok {
		t.Error("free tunnel must not be revoked: no subscription is required")
	}
}

func TestReaperLeavesPaidAndBoundTunnels(t *testing.T) {
	srv, pstore, astore := reaperServer(t)
	const now = 1000
	future := int64(now + 2*monthSec)

	// A paid tunnel keyed by a PayPal subscription id — not account-owned.
	pstore.MintFor("I-SUB-PAID", "buyer@paypal.com", now)

	// A free (account-owned) tunnel whose account also has a bound paid sub.
	acc, _, _ := astore.AccountForEmail("mixed@x.com", now)
	astore.BindSubscription(acc, "I-SUB-PAID", now)
	pstore.MintFor(acc, "account:"+acc, now)

	// Even past the free window, neither is reaped (one is paid; the other's
	// account has a paid subscription).
	if n := srv.reapExpiredFree(future); n != 0 {
		t.Errorf("reaped %d, want 0 (paid + paid-account tunnels must survive)", n)
	}
	if _, ok := pstore.GetByCustomer("I-SUB-PAID"); !ok {
		t.Error("paid subscription tunnel must never be reaped")
	}
	if _, ok := pstore.GetByCustomer(acc); !ok {
		t.Error("free tunnel of a paying account must not be reaped")
	}
}
