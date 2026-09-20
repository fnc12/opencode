package push

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestCounterSaveLoad(t *testing.T) {
	path := filepath.Join(t.TempDir(), "count")
	c := NewCounter(path) // missing file → 0
	if c.Value() != 0 {
		t.Fatalf("fresh counter = %d, want 0", c.Value())
	}
	c.Add(5)
	c.Add(3)
	c.Add(0)  // no-op
	c.Add(-2) // no-op
	if c.Value() != 8 {
		t.Fatalf("value = %d, want 8", c.Value())
	}
	if err := c.Save(); err != nil {
		t.Fatal(err)
	}
	// A new counter over the same file reloads the persisted total.
	if reloaded := NewCounter(path).Value(); reloaded != 8 {
		t.Errorf("reloaded = %d, want 8", reloaded)
	}
}

func TestCounterFlusherSavesOnCancel(t *testing.T) {
	path := filepath.Join(t.TempDir(), "count")
	c := NewCounter(path)
	ctx, cancel := context.WithCancel(context.Background())
	c.StartFlusher(ctx, time.Hour) // long tick → only the ctx.Done save fires
	c.Add(4)
	cancel()

	// Wait (bounded) for the flusher goroutine to persist on shutdown.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if data, err := os.ReadFile(path); err == nil && strings.TrimSpace(string(data)) == "4" {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Errorf("flusher did not persist the counter on ctx cancel")
}

func TestNotifyCountsDeliveries(t *testing.T) {
	d, store, _, _ := newTestDispatcher(t)
	c := NewCounter(filepath.Join(t.TempDir(), "count"))
	d.SetCounter(c)

	_ = store.Add("tun", Device{Provider: APNs, Token: "a"})
	_ = store.Add("tun", Device{Provider: APNs, Token: "b"})

	if sent := d.Notify(context.Background(), Notification{TunnelID: "tun", SessionID: "s1"}); sent != 2 {
		t.Fatalf("sent = %d, want 2", sent)
	}
	if c.Value() != 2 || d.Count() != 2 {
		t.Errorf("counter=%d Count()=%d, want 2/2", c.Value(), d.Count())
	}
	// A deduped repeat delivers nothing and must not increment.
	d.Notify(context.Background(), Notification{TunnelID: "tun", SessionID: "s1"})
	if c.Value() != 2 {
		t.Errorf("deduped notify incremented the counter to %d", c.Value())
	}
}

func TestDispatcherCountZeroWithoutCounter(t *testing.T) {
	d, _, _, _ := newTestDispatcher(t)
	if d.Count() != 0 {
		t.Errorf("Count() without a counter = %d, want 0", d.Count())
	}
}
