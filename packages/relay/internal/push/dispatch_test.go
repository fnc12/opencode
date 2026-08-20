package push

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"
)

type mockPusher struct {
	provider Provider
	mu       sync.Mutex
	sent     []Notification
}

func (m *mockPusher) Provider() Provider { return m.provider }
func (m *mockPusher) Send(_ context.Context, _ string, n Notification) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.sent = append(m.sent, n)
	return nil
}
func (m *mockPusher) count() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.sent)
}

func newTestDispatcher(t *testing.T) (*Dispatcher, *MemoryStore, *mockPusher, *mockPusher) {
	t.Helper()
	store := NewMemoryStore()
	apns := &mockPusher{provider: APNs}
	fcm := &mockPusher{provider: FCM}
	d := NewDispatcher(store, slog.New(slog.NewTextHandler(io.Discard, nil)), apns, fcm)
	return d, store, apns, fcm
}

func TestDispatcherRoutesByProvider(t *testing.T) {
	d, store, apns, fcm := newTestDispatcher(t)
	_ = store.Add("tun", Device{Provider: APNs, Token: "ios1"})
	_ = store.Add("tun", Device{Provider: APNs, Token: "ios2"})
	_ = store.Add("tun", Device{Provider: FCM, Token: "android1"})

	sent := d.Notify(context.Background(), Notification{TunnelID: "tun", SessionID: "s1"})
	if sent != 3 {
		t.Fatalf("expected 3 sends, got %d", sent)
	}
	if apns.count() != 2 || fcm.count() != 1 {
		t.Fatalf("provider routing wrong: apns=%d fcm=%d", apns.count(), fcm.count())
	}
}

func TestDispatcherDedupes(t *testing.T) {
	d, store, apns, _ := newTestDispatcher(t)
	_ = store.Add("tun", Device{Provider: APNs, Token: "ios1"})

	n := Notification{TunnelID: "tun", SessionID: "s1"}
	d.Notify(context.Background(), n)
	d.Notify(context.Background(), n) // within dedupe window -> ignored
	if apns.count() != 1 {
		t.Fatalf("expected dedupe to 1, got %d", apns.count())
	}

	// A different session is not deduped.
	d.Notify(context.Background(), Notification{TunnelID: "tun", SessionID: "s2"})
	if apns.count() != 2 {
		t.Fatalf("expected 2 after new session, got %d", apns.count())
	}

	// Advancing past the window allows a repeat for s1.
	d.now = func() time.Time { return time.Now().Add(time.Minute) }
	d.Notify(context.Background(), n)
	if apns.count() != 3 {
		t.Fatalf("expected 3 after window, got %d", apns.count())
	}
}

// A permission prompt and an idle signal for the same session are different
// events and must not suppress each other, even back-to-back.
func TestDispatcherDedupeIsPerKind(t *testing.T) {
	d, store, apns, _ := newTestDispatcher(t)
	_ = store.Add("tun", Device{Provider: APNs, Token: "ios1"})

	d.Notify(context.Background(), Notification{TunnelID: "tun", SessionID: "s1", Kind: KindPermission})
	d.Notify(context.Background(), Notification{TunnelID: "tun", SessionID: "s1", Kind: KindIdle})
	if apns.count() != 2 {
		t.Fatalf("permission and idle should both fire, got %d", apns.count())
	}

	// But a repeat of the same kind within the window is still deduped.
	d.Notify(context.Background(), Notification{TunnelID: "tun", SessionID: "s1", Kind: KindPermission})
	if apns.count() != 2 {
		t.Fatalf("repeat permission should dedupe, got %d", apns.count())
	}
}

// A pusher reporting ErrTokenGone gets its device pruned from the store, so a
// dead token stops erroring on every dispatch.
func TestNotifyPrunesGoneTokens(t *testing.T) {
	store := NewMemoryStore()
	_ = store.Add("tun1", Device{Provider: APNs, Token: "dead"})
	_ = store.Add("tun1", Device{Provider: APNs, Token: "alive"})

	gone := &goneTokenPusher{provider: APNs, dead: "dead"}
	d := NewDispatcher(store, slog.New(slog.NewTextHandler(io.Discard, nil)), gone)
	sent := d.Notify(context.Background(), Notification{TunnelID: "tun1", SessionID: "s1", Kind: KindIdle})
	if sent != 1 {
		t.Fatalf("sent = %d, want 1", sent)
	}
	devices, _ := store.List("tun1")
	if len(devices) != 1 || devices[0].Token != "alive" {
		t.Fatalf("devices after prune = %+v, want only 'alive'", devices)
	}
}

// goneTokenPusher fails one token with ErrTokenGone and accepts the rest.
type goneTokenPusher struct {
	provider Provider
	dead     string
}

func (g *goneTokenPusher) Provider() Provider { return g.provider }
func (g *goneTokenPusher) Send(_ context.Context, token string, _ Notification) error {
	if token == g.dead {
		return fmt.Errorf("%w: apns: status 400", ErrTokenGone)
	}
	return nil
}
