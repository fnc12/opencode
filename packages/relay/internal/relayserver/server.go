// Package relayserver implements the OpenCode Remote relay HTTP server: it
// accepts dial-out WebSocket connections from connectors and proxies
// iOS/Android client HTTP/SSE requests to the right connector.
package relayserver

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/fnc12/opencode/packages/relay/internal/tunnel"
	"github.com/gorilla/websocket"
)

// Config configures a Server.
type Config struct {
	// Secret, if non-empty, is the shared secret every connector token must
	// match. Empty accepts any non-empty token (dev only). Per-tunnel tokens
	// land with the device/token store in issue #3.
	Secret string
	Log    *slog.Logger
}

// Server is the relay. The zero value is not usable; call New.
type Server struct {
	reg    *tunnel.Registry
	secret string
	log    *slog.Logger
}

// New constructs a Server.
func New(cfg Config) *Server {
	log := cfg.Log
	if log == nil {
		log = slog.Default()
	}
	return &Server{reg: tunnel.NewRegistry(), secret: cfg.Secret, log: log}
}

// Handler returns the HTTP handler exposing all relay routes.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.health)
	mux.HandleFunc("GET /connector", s.connector)
	mux.HandleFunc("/t/{id}/", s.proxy)
	return mux
}

// Tunnels returns the number of live tunnels.
func (s *Server) Tunnels() int { return s.reg.Count() }

var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	// Connectors are not browsers; origin checks don't apply.
	CheckOrigin: func(*http.Request) bool { return true },
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"status":  "ok",
		"tunnels": s.reg.Count(),
	})
}

// connector upgrades to WebSocket and performs the register handshake before
// handing the socket to a tunnel.Conn.
func (s *Server) connector(w http.ResponseWriter, r *http.Request) {
	ws, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return // Upgrade already wrote an error response
	}

	_ = ws.SetReadDeadline(time.Now().Add(10 * time.Second))
	_, data, err := ws.ReadMessage()
	if err != nil {
		_ = ws.Close()
		return
	}
	_ = ws.SetReadDeadline(time.Time{})

	f, err := tunnel.Decode(data)
	if err != nil || f.Type != tunnel.TypeRegister {
		writeAck(ws, tunnel.RegisterAck{Error: "expected register frame"})
		_ = ws.Close()
		return
	}
	var reg tunnel.Register
	if err := json.Unmarshal(f.Payload, &reg); err != nil {
		writeAck(ws, tunnel.RegisterAck{Error: "bad register payload"})
		_ = ws.Close()
		return
	}
	if reg.TunnelID == "" || !s.authorize(reg.Token) {
		writeAck(ws, tunnel.RegisterAck{Error: "unauthorized"})
		_ = ws.Close()
		return
	}

	if err := writeAck(ws, tunnel.RegisterAck{OK: true}); err != nil {
		_ = ws.Close()
		return
	}

	conn := tunnel.NewConn(reg.TunnelID, ws)
	s.reg.Add(conn)
	s.log.Info("connector registered", "tunnel", reg.TunnelID, "tunnels", s.reg.Count())

	<-conn.Done()
	s.reg.Remove(conn)
	s.log.Info("connector gone", "tunnel", reg.TunnelID, "tunnels", s.reg.Count())
}

func (s *Server) authorize(token string) bool {
	if s.secret != "" {
		return token == s.secret
	}
	return token != ""
}

// proxy forwards /t/{id}/... to the connector for tunnel {id}.
func (s *Server) proxy(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	conn := s.reg.Get(id)
	if conn == nil {
		http.Error(w, "tunnel offline", http.StatusBadGateway)
		return
	}
	// Reconstruct the path relative to the OpenCode server root.
	path := strings.TrimPrefix(r.URL.Path, "/t/"+id)
	if path == "" {
		path = "/"
	}
	if r.URL.RawQuery != "" {
		path += "?" + r.URL.RawQuery
	}
	conn.Proxy(w, r, path)
}

func writeAck(ws *websocket.Conn, ack tunnel.RegisterAck) error {
	payload, _ := json.Marshal(ack)
	_ = ws.SetWriteDeadline(time.Now().Add(5 * time.Second))
	return ws.WriteMessage(websocket.BinaryMessage, tunnel.Encode(tunnel.Frame{Type: tunnel.TypeRegisterAck, Payload: payload}))
}
