package push

import (
	"context"
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
