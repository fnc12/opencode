// Command connector runs next to a user's OpenCode server (typically behind
// NAT) and dials *out* to the relay over a single WebSocket. It bridges relay
// tunnel frames to the local OpenCode HTTP/SSE server and streams responses
// back, multiplexed per stream.
//
// Config (env):
//
//	RELAY_URL         ws(s):// URL of the relay's /connector endpoint (required)
//	TUNNEL_ID         tunnel id this connector serves (required)
//	TUNNEL_TOKEN      auth token presented to the relay (required)
//	OPENCODE_URL      local OpenCode server base URL (default http://127.0.0.1:4096)
//	OPENCODE_PASSWORD optional; injected as Basic admin:<pw> when the client sent no Authorization
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/fnc12/opencode/packages/relay/internal/tunnel"
	"github.com/gorilla/websocket"
)

type config struct {
	relayURL string
	tunnelID string
	token    string
	localURL string
	password string
}

func loadConfig() (config, error) {
	c := config{
		relayURL: os.Getenv("RELAY_URL"),
		tunnelID: os.Getenv("TUNNEL_ID"),
		token:    os.Getenv("TUNNEL_TOKEN"),
		localURL: envOr("OPENCODE_URL", "http://127.0.0.1:4096"),
		password: os.Getenv("OPENCODE_PASSWORD"),
	}
	if c.relayURL == "" || c.tunnelID == "" || c.token == "" {
		return c, errors.New("RELAY_URL, TUNNEL_ID and TUNNEL_TOKEN are required")
	}
	return c, nil
}

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	cfg, err := loadConfig()
	if err != nil {
		log.Error("config", "err", err)
		os.Exit(1)
	}

	ctx, stop := signalContext()
	defer stop()

	// Streaming responses (SSE) must not be cut off by a client timeout.
	httpClient := &http.Client{}

	const maxBackoff = 30 * time.Second
	backoff := time.Second
	for ctx.Err() == nil {
		start := time.Now()
		err := runSession(ctx, cfg, httpClient, log)
		if ctx.Err() != nil {
			break
		}
		// A connection that stayed up a while is "stable"; reset backoff.
		if time.Since(start) > time.Minute {
			backoff = time.Second
		}
		log.Warn("session ended, reconnecting", "err", err, "in", backoff)
		select {
		case <-ctx.Done():
		case <-time.After(backoff):
		}
		if backoff < maxBackoff {
			backoff *= 2
		}
	}
	log.Info("connector stopped")
}

// connector holds per-session state for one WebSocket connection.
type connector struct {
	cfg     config
	ws      *websocket.Conn
	client  *http.Client
	log     *slog.Logger
	writeMu sync.Mutex

	mu      sync.Mutex
	cancels map[uint64]context.CancelFunc
}

// runSession dials the relay, registers, and serves requests until the
// connection drops or ctx is cancelled.
func runSession(ctx context.Context, cfg config, client *http.Client, log *slog.Logger) error {
	dialCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	ws, _, err := websocket.DefaultDialer.DialContext(dialCtx, cfg.relayURL, nil)
	if err != nil {
		return err
	}
	defer ws.Close()

	c := &connector{cfg: cfg, ws: ws, client: client, log: log, cancels: map[uint64]context.CancelFunc{}}

	if err := c.register(); err != nil {
		return err
	}
	log.Info("registered with relay", "tunnel", cfg.tunnelID, "relay", cfg.relayURL)

	// Close the socket when ctx is cancelled to unblock ReadMessage.
	go func() {
		<-ctx.Done()
		_ = ws.Close()
	}()

	return c.readLoop(ctx)
}

func (c *connector) register() error {
	reg := tunnel.Register{TunnelID: c.cfg.tunnelID, Token: c.cfg.token}
	payload, _ := json.Marshal(reg)
	if err := c.write(tunnel.Frame{Type: tunnel.TypeRegister, Payload: payload}); err != nil {
		return err
	}
	_ = c.ws.SetReadDeadline(time.Now().Add(10 * time.Second))
	_, data, err := c.ws.ReadMessage()
	if err != nil {
		return err
	}
	_ = c.ws.SetReadDeadline(time.Time{})
	f, err := tunnel.Decode(data)
	if err != nil {
		return err
	}
	if f.Type != tunnel.TypeRegisterAck {
		return errors.New("expected register ack")
	}
	var ack tunnel.RegisterAck
	if err := json.Unmarshal(f.Payload, &ack); err != nil {
		return err
	}
	if !ack.OK {
		return errors.New("relay rejected registration: " + ack.Error)
	}
	return nil
}

func (c *connector) readLoop(ctx context.Context) error {
	for {
		_, data, err := c.ws.ReadMessage()
		if err != nil {
			return err
		}
		f, err := tunnel.Decode(data)
		if err != nil {
			return err
		}
		switch f.Type {
		case tunnel.TypePing:
			_ = c.write(tunnel.Frame{Type: tunnel.TypePong})
		case tunnel.TypeRequest:
			go c.handleRequest(ctx, f)
		case tunnel.TypeCancel:
			c.cancel(f.StreamID)
		}
	}
}

func (c *connector) handleRequest(ctx context.Context, f tunnel.Frame) {
	head, body, err := tunnel.DecodeRequest(f.Payload)
	if err != nil {
		c.streamError(f.StreamID, "bad request frame: "+err.Error())
		return
	}

	reqCtx, cancel := context.WithCancel(ctx)
	c.trackCancel(f.StreamID, cancel)
	defer c.untrackCancel(f.StreamID)

	req, err := http.NewRequestWithContext(reqCtx, head.Method, c.cfg.localURL+head.Path, bytes.NewReader(body))
	if err != nil {
		c.streamError(f.StreamID, "build request: "+err.Error())
		return
	}
	for k, vs := range head.Header {
		for _, v := range vs {
			req.Header.Add(k, v)
		}
	}
	// Inject local auth only if the client didn't supply any.
	if c.cfg.password != "" && req.Header.Get("Authorization") == "" {
		req.SetBasicAuth("admin", c.cfg.password)
	}

	resp, err := c.client.Do(req)
	if err != nil {
		if reqCtx.Err() == nil {
			c.streamError(f.StreamID, "upstream: "+err.Error())
		}
		return
	}
	defer resp.Body.Close()

	rh, _ := json.Marshal(tunnel.ResponseHead{Status: resp.StatusCode, Header: resp.Header})
	if err := c.write(tunnel.Frame{Type: tunnel.TypeResponseHead, StreamID: f.StreamID, Payload: rh}); err != nil {
		return
	}

	buf := make([]byte, 32*1024)
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			chunk := make([]byte, n)
			copy(chunk, buf[:n])
			if werr := c.write(tunnel.Frame{Type: tunnel.TypeData, StreamID: f.StreamID, Payload: chunk}); werr != nil {
				return
			}
		}
		if rerr != nil {
			if rerr == io.EOF || reqCtx.Err() != nil {
				_ = c.write(tunnel.Frame{Type: tunnel.TypeEnd, StreamID: f.StreamID})
			} else {
				c.streamError(f.StreamID, "read upstream: "+rerr.Error())
			}
			return
		}
	}
}

func (c *connector) streamError(id uint64, msg string) {
	_ = c.write(tunnel.Frame{Type: tunnel.TypeError, StreamID: id, Payload: []byte(msg)})
}

func (c *connector) trackCancel(id uint64, cancel context.CancelFunc) {
	c.mu.Lock()
	c.cancels[id] = cancel
	c.mu.Unlock()
}

func (c *connector) untrackCancel(id uint64) {
	c.mu.Lock()
	delete(c.cancels, id)
	c.mu.Unlock()
}

func (c *connector) cancel(id uint64) {
	c.mu.Lock()
	cancel := c.cancels[id]
	c.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func (c *connector) write(f tunnel.Frame) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	_ = c.ws.SetWriteDeadline(time.Now().Add(10 * time.Second))
	return c.ws.WriteMessage(websocket.BinaryMessage, tunnel.Encode(f))
}

func signalContext() (context.Context, context.CancelFunc) {
	return signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
