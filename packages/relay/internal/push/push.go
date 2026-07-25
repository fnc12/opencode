// Package push delivers "session finished" notifications to mobile devices.
//
// It is provider-agnostic: the relay watches each tunnel's OpenCode event
// stream, and when a session goes idle it dispatches a normalized Notification
// to every device registered for that tunnel, via the Pusher for the device's
// platform (APNs for iOS, FCM for Android).
package push

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"
)

// Provider identifies a push platform.
type Provider string

const (
	APNs Provider = "apns"
	FCM  Provider = "fcm"
)

// Device is a registered push target.
type Device struct {
	Provider Provider `json:"provider"`
	Token    string   `json:"token"`
}

// Notification is the normalized, cross-platform payload.
type Notification struct {
	TunnelID  string
	SessionID string
	// Kind distinguishes what happened (session finished vs permission needed).
	// It participates in dedupe so a permission prompt and an idle signal for
	// the same session don't suppress each other.
	Kind  Kind
	Title string
	Body  string
	// DeepLink is an app URL that opens the relevant session, e.g.
	// opencode://session/<id>.
	DeepLink string
}

// ErrTokenGone marks a permanently dead device token (uninstalled app,
// rejected by every environment) — the dispatcher prunes it from the store so
// it stops erroring on every notification.
var ErrTokenGone = errors.New("push token gone")

// Pusher delivers a Notification to a single device of its platform.
type Pusher interface {
	Provider() Provider
	Send(ctx context.Context, token string, n Notification) error
}

// Store persists device registrations per tunnel.
type Store interface {
	Add(tunnelID string, d Device) error
	Remove(tunnelID string, d Device) error
	List(tunnelID string) ([]Device, error)
}

// Dispatcher fans a Notification out to all devices of a tunnel using the
// Pusher registered for each device's provider. It deduplicates repeated idle
// signals for the same session within a short window.
type Dispatcher struct {
	store   Store
	pushers map[Provider]Pusher
	log     *slog.Logger

	mu     sync.Mutex
	recent map[string]time.Time // key: tunnelID/sessionID
	dedupe time.Duration
	now    func() time.Time
}

// NewDispatcher builds a Dispatcher. Pushers are keyed by their Provider.
func NewDispatcher(store Store, log *slog.Logger, pushers ...Pusher) *Dispatcher {
	if log == nil {
		log = slog.Default()
	}
	m := make(map[Provider]Pusher, len(pushers))
	for _, p := range pushers {
		m[p.Provider()] = p
	}
	return &Dispatcher{
		store:   store,
		pushers: m,
		log:     log,
		recent:  make(map[string]time.Time),
		dedupe:  5 * time.Second,
		now:     time.Now,
	}
}

// Notify sends n to every registered device for n.TunnelID. Repeated calls for
// the same session within the dedupe window are ignored. It returns the number
// of devices actually notified.
func (d *Dispatcher) Notify(ctx context.Context, n Notification) int {
	if d.duplicate(n) {
		return 0
	}
	devices, err := d.store.List(n.TunnelID)
	if err != nil {
		d.log.Error("push: list devices", "tunnel", n.TunnelID, "err", err)
		return 0
	}
	sent := 0
	for _, dev := range devices {
		p := d.pushers[dev.Provider]
		if p == nil {
			d.log.Warn("push: no pusher for provider", "provider", dev.Provider)
			continue
		}
		if err := p.Send(ctx, dev.Token, n); err != nil {
			if errors.Is(err, ErrTokenGone) {
				// Dead token (uninstalled/replaced app): drop it so it stops
				// failing on every dispatch.
				if rmErr := d.store.Remove(n.TunnelID, dev); rmErr != nil {
					d.log.Error("push: prune dead device", "provider", dev.Provider, "err", rmErr)
				} else {
					d.log.Info("push: pruned dead device", "provider", dev.Provider)
				}
				continue
			}
			d.log.Error("push: send failed", "provider", dev.Provider, "err", err)
			continue
		}
		sent++
	}
	d.log.Info("push: dispatched", "tunnel", n.TunnelID, "session", n.SessionID, "kind", string(n.Kind), "devices", sent)
	return sent
}

func (d *Dispatcher) duplicate(n Notification) bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	key := n.TunnelID + "/" + n.SessionID + "/" + string(n.Kind)
	now := d.now()
	if last, ok := d.recent[key]; ok && now.Sub(last) < d.dedupe {
		return true
	}
	d.recent[key] = now
	// Opportunistically prune stale entries.
	for k, t := range d.recent {
		if now.Sub(t) > 10*d.dedupe {
			delete(d.recent, k)
		}
	}
	return false
}
