package relayserver

import (
	"context"
	"strings"
	"time"
)

// StartReaper periodically revokes free, account-owned tunnels whose entitlement
// has lapsed — the free period ended and there's no paid subscription. Runs
// until ctx is done. Free tunnels are keyed by the account id ("acc_..."); paid
// tunnels are keyed by the PayPal subscription id and revoked by the cancel
// webhook instead, so they're left alone here.
func (s *Server) StartReaper(ctx context.Context, every time.Duration) {
	if s.provision == nil || s.accounts == nil {
		return
	}
	go func() {
		t := time.NewTicker(every)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				if n := s.reapExpiredFree(time.Now().Unix()); n > 0 {
					s.log.Info("reaper: revoked expired free tunnels", "count", n)
				}
			}
		}
	}()
}

// reapExpiredFree revokes free tunnels for accounts that are no longer entitled,
// closing any live connection. Returns the number revoked.
func (s *Server) reapExpiredFree(now int64) int {
	revoked := 0
	for _, t := range s.provision.List() {
		if !strings.HasPrefix(t.CustomerID, "acc_") {
			continue // not a free, account-owned tunnel
		}
		if s.accounts.FreeActive(t.CustomerID, now) || s.hasPaidTunnel(t.CustomerID) {
			continue // still entitled — keep it
		}
		if id := s.provision.DeleteByCustomer(t.CustomerID); id != "" {
			if conn := s.reg.Get(id); conn != nil {
				conn.Close()
			}
			revoked++
		}
	}
	return revoked
}
