// Command relay is the OpenCode Remote relay server. It accepts long-lived
// WebSocket connections from connectors (which dial out from the user's
// machine) and proxies iOS-client HTTP/SSE requests to the right connector.
//
// Endpoints:
//
//	GET  /healthz          liveness, reports live tunnel count
//	GET  /connector        WebSocket; connector registers a tunnel
//	*    /t/{id}/...        proxied to the connector for tunnel {id}
package main

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/fnc12/opencode/packages/relay/internal/tunnel"
	"github.com/gorilla/websocket"
)

func main() {
	addr := envOr("RELAY_ADDR", ":8080")
	// RELAY_SHARED_SECRET, if set, every connector token must equal it. Empty
	// means any non-empty token is accepted (dev only). Per-tunnel tokens land
	// with the device/token store in issue #3.
	secret := os.Getenv("RELAY_SHARED_SECRET")

	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	reg := tunnel.NewRegistry()
	srv := &server{reg: reg, secret: secret, log: logger}

	httpSrv := &http.Server{
		Addr:              addr,
		Handler:           srv.handler(),
		ReadHeaderTimeout: 10 * time.Second,
	}

	go func() {
		logger.Info("relay listening", "addr", addr)
		if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("server error", "err", err)
			os.Exit(1)
		}
	}()

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	<-ctx.Done()
	logger.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = httpSrv.Shutdown(shutdownCtx)
}

type server struct {
	reg    *tunnel.Registry
	secret string
	log    *slog.Logger
}

func (s *server) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.health)
	mux.HandleFunc("GET /connector", s.connector)
	mux.HandleFunc("/t/{id}/", s.proxy)
	return mux
}

var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	// Connectors are not browsers; origin checks don't apply.
	CheckOrigin: func(*http.Request) bool { return true },
}

func (s *server) health(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"status":  "ok",
		"tunnels": s.reg.Count(),
	})
}

// connector upgrades to WebSocket and performs the register handshake before
// handing the socket to a tunnel.Conn.
func (s *server) connector(w http.ResponseWriter, r *http.Request) {
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

func (s *server) authorize(token string) bool {
	if s.secret != "" {
		return token == s.secret
	}
	return token != ""
}

// proxy forwards /t/{id}/... to the connector for tunnel {id}.
func (s *server) proxy(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	conn := s.reg.Get(id)
	if conn == nil {
		http.Error(w, "tunnel offline", http.StatusBadGateway)
		return
	}
	// Reconstruct the path relative to the OpenCode server root.
	prefix := "/t/" + id
	path := strings.TrimPrefix(r.URL.Path, prefix)
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

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
