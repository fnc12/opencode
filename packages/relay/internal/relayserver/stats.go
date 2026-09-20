package relayserver

import (
	"encoding/json"
	"net/http"
)

// stats serves the public counters the landing page polls — currently the total
// pushes delivered. It reads straight from an in-memory atomic (never disk or a
// DB) and is CORS-open so shubat.org can fetch it without taxing the backend.
func (s *Server) stats(w http.ResponseWriter, r *http.Request) {
	var pushes int64
	if s.dispatcher != nil {
		pushes = s.dispatcher.Count()
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]any{"pushes": pushes})
}
