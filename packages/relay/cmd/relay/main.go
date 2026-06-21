// Command relay is the OpenCode Remote relay server. It accepts long-lived
// WebSocket connections from connectors (which dial out from the user's
// machine) and proxies mobile-client HTTP/SSE requests to the right connector.
//
// Endpoints:
//
//	GET  /healthz          liveness, reports live tunnel count
//	GET  /connector        WebSocket; connector registers a tunnel
//	*    /t/{id}/...        proxied to the connector for tunnel {id}
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/fnc12/opencode/packages/relay/internal/relayserver"
)

func main() {
	addr := envOr("RELAY_ADDR", ":8080")
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))

	srv := relayserver.New(relayserver.Config{
		Secret: os.Getenv("RELAY_SHARED_SECRET"),
		Log:    logger,
	})

	httpSrv := &http.Server{
		Addr:              addr,
		Handler:           srv.Handler(),
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

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
