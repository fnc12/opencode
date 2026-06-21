package tunnel

import "sync"

// Registry tracks live connector connections by tunnel id. A new registration
// for an existing tunnel id replaces (and closes) the previous connection,
// which handles connector reconnects cleanly.
type Registry struct {
	mu      sync.RWMutex
	tunnels map[string]*Conn
}

// NewRegistry returns an empty registry.
func NewRegistry() *Registry {
	return &Registry{tunnels: make(map[string]*Conn)}
}

// Add registers c under its tunnel id, evicting any prior connection.
func (r *Registry) Add(c *Conn) {
	r.mu.Lock()
	old := r.tunnels[c.TunnelID]
	r.tunnels[c.TunnelID] = c
	r.mu.Unlock()
	if old != nil && old != c {
		old.shutdown(ErrClosed)
	}
}

// Remove deletes c from the registry only if it is still the active connection
// for its tunnel id (guards against removing a newer reconnect).
func (r *Registry) Remove(c *Conn) {
	r.mu.Lock()
	if r.tunnels[c.TunnelID] == c {
		delete(r.tunnels, c.TunnelID)
	}
	r.mu.Unlock()
}

// Get returns the active connection for a tunnel id, or nil.
func (r *Registry) Get(tunnelID string) *Conn {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.tunnels[tunnelID]
}

// Count returns the number of live tunnels.
func (r *Registry) Count() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.tunnels)
}
