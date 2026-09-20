package account

import "log/slog"

// EmailSender delivers transactional email (the magic-link sign-in). It is
// transport only — callers compose the subject and body. A production
// implementation (Resend, SMTP) plugs in here; tests and unconfigured deploys
// use LogSender.
type EmailSender interface {
	Send(to, subject, body string) error
}

// LogSender logs the message (including the magic link) instead of delivering
// it, so the whole sign-in flow is exercisable in dev and tests without a real
// email provider. Never use it in production — the link would only appear in
// logs, not reach the user.
type LogSender struct{ Log *slog.Logger }

// Send logs the email at info level and returns nil.
func (l LogSender) Send(to, subject, body string) error {
	log := l.Log
	if log == nil {
		log = slog.Default()
	}
	log.Info("email (log sender — not actually delivered)", "to", to, "subject", subject, "body", body)
	return nil
}
