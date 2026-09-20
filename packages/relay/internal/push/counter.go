package push

import (
	"context"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Counter is a durable, lock-free tally of pushes delivered. Reads and
// increments are atomic (hot path stays cheap); the value is persisted to a
// small file periodically and on shutdown so the public counter survives
// restarts. Serving the value never touches disk.
type Counter struct {
	n    atomic.Int64
	path string
	mu   sync.Mutex // serialises file writes
}

// NewCounter returns a counter seeded from path (missing/garbage file → 0).
func NewCounter(path string) *Counter {
	c := &Counter{path: path}
	if data, err := os.ReadFile(path); err == nil {
		if v, err := strconv.ParseInt(strings.TrimSpace(string(data)), 10, 64); err == nil {
			c.n.Store(v)
		}
	}
	return c
}

// Add increments the counter by delta (no-op for delta <= 0).
func (c *Counter) Add(delta int) {
	if delta > 0 {
		c.n.Add(int64(delta))
	}
}

// Value returns the current total.
func (c *Counter) Value() int64 { return c.n.Load() }

// Save atomically writes the current value to disk.
func (c *Counter) Save() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	tmp := c.path + ".tmp"
	if err := os.WriteFile(tmp, []byte(strconv.FormatInt(c.n.Load(), 10)), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, c.path)
}

// StartFlusher persists the counter every `every` (only when it changed) until
// ctx is cancelled, then saves a final time. Run it once in a goroutine.
func (c *Counter) StartFlusher(ctx context.Context, every time.Duration) {
	go func() {
		t := time.NewTicker(every)
		defer t.Stop()
		last := int64(-1)
		for {
			select {
			case <-ctx.Done():
				_ = c.Save()
				return
			case <-t.C:
				if v := c.n.Load(); v != last {
					if c.Save() == nil {
						last = v
					}
				}
			}
		}
	}()
}
