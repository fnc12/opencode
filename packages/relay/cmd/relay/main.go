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

	"github.com/fnc12/opencode/packages/relay/internal/account"
	"github.com/fnc12/opencode/packages/relay/internal/provision"
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
	// Claim-code onboarding: when an admin secret is set, mint/claim tunnels so
	// testers self-provision without a shared register secret.
	if admin := os.Getenv("ADMIN_SECRET"); admin != "" {
		dbPath := envOr("PROVISION_DB_PATH", "provision.db")
		pstore, err := provision.NewSQLiteStore(dbPath)
		if err != nil {
			logger.Error("provision store", "err", err)
			os.Exit(1)
		}
		// One-time migration: import the legacy JSON store into the DB the first
		// time (no-op once the DB has tunnels), so an existing deploy carries over.
		legacy := envOr("PROVISION_STORE_PATH", "tunnels.json")
		if n, err := pstore.ImportLegacyJSON(legacy); err != nil {
			logger.Error("provision import", "err", err)
		} else if n > 0 {
			logger.Info("provision: imported legacy tunnels", "count", n, "from", legacy)
		}
		cfg.Provision, cfg.AdminSecret = pstore, admin
		logger.Info("provisioning enabled", "db", dbPath)
		// User layer (accounts + magic-link + /account) shares the same DB.
		// Gated so it stays dormant until an email transport is wired.
		if os.Getenv("ACCOUNTS_ENABLED") == "1" {
			astore, err := account.NewStore(dbPath)
			if err != nil {
				logger.Error("account store", "err", err)
				os.Exit(1)
			}
			cfg.Accounts = astore
			// Magic-link email transport: SMTP (e.g. iCloud) when configured,
			// otherwise the LogSender default (dev — links only appear in logs).
			if host := os.Getenv("SMTP_HOST"); host != "" {
				cfg.Email = account.SMTPSender{
					Host: host,
					Port: envOr("SMTP_PORT", "587"),
					User: os.Getenv("SMTP_USER"),
					Pass: os.Getenv("SMTP_PASS"),
					From: envOr("EMAIL_FROM", os.Getenv("SMTP_USER")),
					Name: envOr("EMAIL_FROM_NAME", "Shubat"),
				}
				logger.Info("accounts enabled", "email", "smtp:"+host)
			} else {
				logger.Info("accounts enabled", "email", "log-only (no SMTP_HOST)")
			}
		}
		if sw := os.Getenv("STRIPE_WEBHOOK_SECRET"); sw != "" {
			cfg.StripeWebhookSecret = sw
			logger.Info("stripe billing enabled")
		}
		if id := os.Getenv("PAYPAL_CLIENT_ID"); id != "" && os.Getenv("PAYPAL_WEBHOOK_ID") != "" {
			cfg.PayPal = relayserver.PayPalConfig{
				ClientID:  id,
				Secret:    os.Getenv("PAYPAL_SECRET"),
				WebhookID: os.Getenv("PAYPAL_WEBHOOK_ID"),
				Live:      os.Getenv("PAYPAL_LIVE") == "1",
			}
			logger.Info("paypal billing enabled", "live", cfg.PayPal.Live)
		}
	}
	// One-command installer: serve connector binaries + install.sh from ASSET_DIR.
	if dir := os.Getenv("ASSET_DIR"); dir != "" {
		cfg.AssetDir = dir
		cfg.PublicURL = os.Getenv("RELAY_PUBLIC_URL")
		logger.Info("installer enabled", "assetDir", dir)
	}
	var pushCounter *push.Counter
	if store, disp, err := buildPush(logger); err != nil {
		logger.Error("push config", "err", err)
		os.Exit(1)
	} else if disp != nil {
		cfg.Store, cfg.Dispatcher = store, disp
		// Durable tally of delivered pushes for the public /stats counter.
		pushCounter = push.NewCounter(envOr("PUSH_COUNT_PATH", "push_count"))
		disp.SetCounter(pushCounter)
		logger.Info("push enabled", "count", pushCounter.Value())
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
	if pushCounter != nil {
		pushCounter.StartFlusher(ctx, 20*time.Second)
	}
	// Revoke free tunnels whose entitlement has lapsed.
	srv.StartReaper(ctx, time.Hour)
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
