// Package relayserver implements the OpenCode Remote relay HTTP server: it
// accepts dial-out WebSocket connections from connectors and proxies
// iOS/Android client HTTP/SSE requests to the right connector.
package relayserver

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/fnc12/opencode/packages/relay/internal/provision"
	"github.com/fnc12/opencode/packages/relay/internal/push"
	"github.com/fnc12/opencode/packages/relay/internal/tunnel"
	"github.com/gorilla/websocket"
)

// Config configures a Server.
type Config struct {
	// Secret, if non-empty, is the shared secret every connector token must
	// match. Empty accepts any non-empty token (dev only).
	Secret string
	Log    *slog.Logger

	// Store and Dispatcher enable push. When both are set, the relay watches
	// each tunnel's OpenCode event stream and pushes on session idle, and the
	// /api/devices endpoints accept registrations. When nil, push is disabled.
	Store      push.Store
	Dispatcher *push.Dispatcher

	// Provision, when set, enables claim-code onboarding: /admin/tunnels mints
	// tunnels (guarded by AdminSecret) and /connector/claim redeems codes.
	// A connector registering a provisioned tunnel self-authenticates with its
	// stored token, so no shared register secret is needed.
	Provision   provision.Store
	AdminSecret string

	// AssetDir, when set, holds the connector binaries + install.sh; the relay
	// then serves `GET /dl/{name}` and the one-line installer `GET /i/{code}`.
	// PublicURL is the relay's externally-reachable base (e.g.
	// https://relay.shubat.org) baked into the installer.
	AssetDir  string
	PublicURL string

	// StripeWebhookSecret, when set (with Provision), enables POST /stripe/webhook
	// to mint access on subscription start and revoke it on cancellation.
	StripeWebhookSecret string
}

// Server is the relay. The zero value is not usable; call New.
type Server struct {
	reg         *tunnel.Registry
	secret      string
	log         *slog.Logger
	store       push.Store
	dispatcher  *push.Dispatcher
	provision   provision.Store
	adminSecret string
	assetDir     string
	publicURL    string
	stripeSecret string
}

// New constructs a Server.
func New(cfg Config) *Server {
	log := cfg.Log
	if log == nil {
		log = slog.Default()
	}
	return &Server{
		reg:         tunnel.NewRegistry(),
		secret:      cfg.Secret,
		log:         log,
		store:       cfg.Store,
		dispatcher:  cfg.Dispatcher,
		provision:   cfg.Provision,
		adminSecret: cfg.AdminSecret,
		assetDir:     cfg.AssetDir,
		publicURL:    cfg.PublicURL,
		stripeSecret: cfg.StripeWebhookSecret,
	}
}

// Handler returns the HTTP handler exposing all relay routes.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.health)
	mux.HandleFunc("GET /connector", s.connector)
	mux.HandleFunc("POST /api/devices", s.registerDevice)
	mux.HandleFunc("DELETE /api/devices", s.unregisterDevice)
	if s.provision != nil {
		mux.HandleFunc("POST /admin/tunnels", s.adminMintTunnel)
		mux.HandleFunc("GET /admin/tunnels", s.adminListTunnels)
		mux.HandleFunc("DELETE /admin/tunnels/{id}", s.adminDeleteTunnel)
		mux.HandleFunc("POST /connector/claim", s.claimTunnel)
		if s.stripeSecret != "" {
			mux.HandleFunc("POST /stripe/webhook", s.handleStripeWebhook)
		}
	}
	if s.assetDir != "" {
		mux.HandleFunc("GET /dl/{name}", s.serveBinary)
		mux.HandleFunc("GET /i/{code}", s.serveInstaller)
	}
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
	if reg.TunnelID == "" || !s.authorizeRegister(reg) {
		writeAck(ws, tunnel.RegisterAck{Error: "unauthorized"})
		_ = ws.Close()
		return
	}

	if err := writeAck(ws, tunnel.RegisterAck{OK: true}); err != nil {
		_ = ws.Close()
		return
	}

	// The app-facing token is the per-tunnel token; fall back to the register
	// token for older single-tenant connectors that don't send one.
	tunnelToken := reg.TunnelToken
	if tunnelToken == "" {
		tunnelToken = reg.Token
	}
	conn := tunnel.NewConn(reg.TunnelID, tunnelToken, ws)
	s.reg.Add(conn)
	s.log.Info("connector registered", "tunnel", reg.TunnelID, "tunnels", s.reg.Count())

	if s.dispatcher != nil {
		ctx, cancel := context.WithCancel(context.Background())
		go func() { <-conn.Done(); cancel() }()
		go s.watchEvents(ctx, conn)
	}

	<-conn.Done()
	s.reg.Remove(conn)
	s.log.Info("connector gone", "tunnel", reg.TunnelID, "tunnels", s.reg.Count())
}

// authorizeRegister decides whether a connector may register a tunnel. A
// provisioned tunnel self-authenticates with its stored token (no shared
// secret). Otherwise it falls back to the shared register secret (the owner's
// tunnel / legacy single-tenant); an empty secret accepts any token (dev only).
func (s *Server) authorizeRegister(reg tunnel.Register) bool {
	if s.provision != nil {
		if t, ok := s.provision.Get(reg.TunnelID); ok {
			return reg.TunnelToken != "" && reg.TunnelToken == t.Token
		}
	}
	if s.secret != "" {
		return reg.Token == s.secret
	}
	return reg.Token != ""
}

// proxy forwards /t/{id}/... to the connector for tunnel {id}.
func (s *Server) proxy(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	conn := s.reg.Get(id)
	if conn == nil {
		http.Error(w, "tunnel offline", http.StatusBadGateway)
		return
	}
	// Per-tunnel auth: knowing the tunnel id isn't enough — the caller must
	// present the tunnel token (handed out via the pairing payload).
	if conn.Token != "" && r.Header.Get("X-Tunnel-Token") != conn.Token {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
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

// watchEvents subscribes to the tunnel's OpenCode event stream and pushes a
// notification whenever a session goes idle. It resubscribes if the stream
// drops, until the connection closes.
func (s *Server) watchEvents(ctx context.Context, conn *tunnel.Conn) {
	for ctx.Err() == nil {
		ch, err := conn.Subscribe(ctx, "/global/event")
		if err != nil {
			if !sleepCtx(ctx, 2*time.Second) {
				return
			}
			continue
		}
		scanner := &push.IdleScanner{}
		for chunk := range ch {
			for _, sid := range scanner.Feed(chunk) {
				s.dispatcher.Notify(ctx, push.Notification{
					TunnelID:  conn.TunnelID,
					SessionID: sid,
					Title:     "Session finished",
					Body:      "Your OpenCode agent finished the task.",
					DeepLink:  "opencode://session/" + sid,
				})
			}
		}
		// Stream ended; pause briefly before resubscribing.
		if !sleepCtx(ctx, 2*time.Second) {
			return
		}
	}
}

func sleepCtx(ctx context.Context, d time.Duration) bool {
	select {
	case <-ctx.Done():
		return false
	case <-time.After(d):
		return true
	}
}

type deviceRequest struct {
	TunnelID string `json:"tunnelId"`
	Provider string `json:"provider"`
	Token    string `json:"token"`
}

func (s *Server) parseDevice(w http.ResponseWriter, r *http.Request) (string, push.Device, bool) {
	if s.store == nil {
		http.Error(w, "push not enabled", http.StatusServiceUnavailable)
		return "", push.Device{}, false
	}
	var body deviceRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&body); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return "", push.Device{}, false
	}
	prov := push.Provider(body.Provider)
	if body.TunnelID == "" || body.Token == "" || (prov != push.APNs && prov != push.FCM) {
		http.Error(w, "tunnelId, token and provider(apns|fcm) required", http.StatusBadRequest)
		return "", push.Device{}, false
	}
	// NOTE: registration is currently unauthenticated; proper per-device
	// pairing auth arrives with #4.
	return body.TunnelID, push.Device{Provider: prov, Token: body.Token}, true
}

func (s *Server) registerDevice(w http.ResponseWriter, r *http.Request) {
	tunnelID, dev, ok := s.parseDevice(w, r)
	if !ok {
		return
	}
	if err := s.store.Add(tunnelID, dev); err != nil {
		http.Error(w, "store error", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) unregisterDevice(w http.ResponseWriter, r *http.Request) {
	tunnelID, dev, ok := s.parseDevice(w, r)
	if !ok {
		return
	}
	if err := s.store.Remove(tunnelID, dev); err != nil {
		http.Error(w, "store error", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func writeAck(ws *websocket.Conn, ack tunnel.RegisterAck) error {
	payload, _ := json.Marshal(ack)
	_ = ws.SetWriteDeadline(time.Now().Add(5 * time.Second))
	return ws.WriteMessage(websocket.BinaryMessage, tunnel.Encode(tunnel.Frame{Type: tunnel.TypeRegisterAck, Payload: payload}))
}

var (
	binaryNameRe = regexp.MustCompile(`^connector-(darwin|linux)-(amd64|arm64)$`)
	claimCodeRe  = regexp.MustCompile(`^[A-Za-z0-9-]{4,40}$`)
)

// serveBinary serves a connector binary from the asset dir (name is strictly
// whitelisted, so no path traversal).
func (s *Server) serveBinary(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("name")
	if !binaryNameRe.MatchString(name) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	http.ServeFile(w, r, filepath.Join(s.assetDir, name))
}

// serveInstaller returns install.sh with the relay base + claim code injected,
// so `curl -fsSL <relay>/i/<code> | sh` fully onboards a tester.
func (s *Server) serveInstaller(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	if !claimCodeRe.MatchString(code) {
		http.Error(w, "bad code", http.StatusBadRequest)
		return
	}
	script, err := os.ReadFile(filepath.Join(s.assetDir, "install.sh"))
	if err != nil {
		http.Error(w, "installer unavailable", http.StatusServiceUnavailable)
		return
	}
	base := s.publicURL
	if base == "" {
		base = "https://" + r.Host
	}
	w.Header().Set("Content-Type", "text/x-shellscript")
	fmt.Fprintf(w, "#!/bin/sh\nRELAY_BASE=%q\nCLAIM_CODE=%q\nexport RELAY_BASE CLAIM_CODE\n%s", base, code, script)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// requireAdmin gates the /admin routes on the X-Admin-Secret header.
func (s *Server) requireAdmin(w http.ResponseWriter, r *http.Request) bool {
	if s.adminSecret == "" || r.Header.Get("X-Admin-Secret") != s.adminSecret {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return false
	}
	return true
}

// adminMintTunnel mints a tunnel + one-time claim code.
// POST /admin/tunnels {label?} -> 201 {id, claimCode}
func (s *Server) adminMintTunnel(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	var body struct {
		Label string `json:"label"`
	}
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&body)
	t, err := s.provision.Mint(body.Label, time.Now().Unix())
	if err != nil {
		http.Error(w, "mint failed", http.StatusInternalServerError)
		return
	}
	s.log.Info("tunnel minted", "tunnel", t.ID, "label", t.Label)
	writeJSON(w, http.StatusCreated, map[string]any{"id": t.ID, "claimCode": t.ClaimCode})
}

// adminListTunnels lists provisioned tunnels (tokens redacted).
func (s *Server) adminListTunnels(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	list := s.provision.List()
	out := make([]map[string]any, 0, len(list))
	for _, t := range list {
		out = append(out, map[string]any{
			"id": t.ID, "label": t.Label, "claimed": t.Claimed,
			"createdAt": t.CreatedAt, "online": s.reg.Get(t.ID) != nil,
		})
	}
	writeJSON(w, http.StatusOK, out)
}

// adminDeleteTunnel revokes a tunnel and drops any live connection.
func (s *Server) adminDeleteTunnel(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	id := r.PathValue("id")
	if err := s.provision.Delete(id); err != nil {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	if conn := s.reg.Get(id); conn != nil {
		conn.Close()
	}
	s.log.Info("tunnel revoked", "tunnel", id)
	w.WriteHeader(http.StatusNoContent)
}

// claimTunnel redeems a one-time claim code for a tunnel id + token.
// POST /connector/claim {code} -> {tunnelId, tunnelToken}
func (s *Server) claimTunnel(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Code string `json:"code"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&body); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	t, err := s.provision.Claim(body.Code)
	if err != nil {
		http.Error(w, "invalid or already-used code", http.StatusForbidden)
		return
	}
	s.log.Info("tunnel claimed", "tunnel", t.ID)
	writeJSON(w, http.StatusOK, map[string]any{"tunnelId": t.ID, "tunnelToken": t.Token})
}
