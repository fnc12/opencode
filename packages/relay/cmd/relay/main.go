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

	"github.com/fnc12/opencode/packages/relay/internal/push"
	"github.com/fnc12/opencode/packages/relay/internal/relayserver"
)

func main() {
	addr := envOr("RELAY_ADDR", ":8080")
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))

	cfg := relayserver.Config{
		Secret: os.Getenv("RELAY_SHARED_SECRET"),
		Log:    logger,
	}
	if store, disp, err := buildPush(logger); err != nil {
		logger.Error("push config", "err", err)
		os.Exit(1)
	} else if disp != nil {
		cfg.Store, cfg.Dispatcher = store, disp
		logger.Info("push enabled")
	} else {
		logger.Warn("push disabled (no APNs/FCM configured)")
	}

	srv := relayserver.New(cfg)

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

// buildPush assembles the device store and dispatcher from env. It returns a
// nil dispatcher (push disabled) when neither APNs nor FCM is configured.
func buildPush(logger *slog.Logger) (push.Store, *push.Dispatcher, error) {
	var pushers []push.Pusher

	if path := os.Getenv("APNS_KEY_PATH"); path != "" {
		p, err := push.NewAPNsPusher(push.APNsConfig{
			KeyPath:  path,
			KeyID:    os.Getenv("APNS_KEY_ID"),
			TeamID:   os.Getenv("APNS_TEAM_ID"),
			Topic:    os.Getenv("APNS_TOPIC"),
			Endpoint: os.Getenv("APNS_ENDPOINT"),
		})
		if err != nil {
			return nil, nil, err
		}
		pushers = append(pushers, p)
	}

	if path := os.Getenv("FCM_SERVICE_ACCOUNT"); path != "" {
		p, err := push.NewFCMPusher(push.FCMConfig{ServiceAccountPath: path})
		if err != nil {
			return nil, nil, err
		}
		pushers = append(pushers, p)
	}

	if len(pushers) == 0 {
		return nil, nil, nil
	}

	store, err := push.NewFileStore(envOr("RELAY_STORE_PATH", "devices.json"))
	if err != nil {
		return nil, nil, err
	}
	return store, push.NewDispatcher(store, logger, pushers...), nil
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
