package account

import (
	"fmt"
	"net/smtp"
	"strings"
)

// SMTPSender delivers mail over SMTP with STARTTLS (e.g. iCloud's
// smtp.mail.me.com:587). net/smtp's SendMail upgrades the connection to TLS when
// the server advertises STARTTLS and only then sends the PLAIN credentials, so
// the app-specific password is never exposed in the clear.
type SMTPSender struct {
	Host string // e.g. smtp.mail.me.com
	Port string // e.g. 587
	User string // SMTP auth user (an iCloud address)
	Pass string // app-specific password
	From string // From address; defaults to User (iCloud only allows own addrs)
	Name string // optional From display name
}

// Send delivers a plain-text message.
func (m SMTPSender) Send(to, subject, body string) error {
	from := m.From
	if from == "" {
		from = m.User
	}
	auth := smtp.PlainAuth("", m.User, m.Pass, m.Host)
	msg := buildMessage(from, m.Name, to, subject, body)
	return smtp.SendMail(m.Host+":"+m.Port, auth, from, []string{to}, msg)
}

// buildMessage assembles a minimal RFC 5322 plain-text message with CRLF line
// endings (required by SMTP). Header values are sanitised of CR/LF to prevent
// header injection via a crafted subject or address.
func buildMessage(from, name, to, subject, body string) []byte {
	fromHeader := sanitizeHeader(from)
	if name != "" {
		fromHeader = fmt.Sprintf("%s <%s>", sanitizeHeader(name), sanitizeHeader(from))
	}
	var b strings.Builder
	b.WriteString("From: " + fromHeader + "\r\n")
	b.WriteString("To: " + sanitizeHeader(to) + "\r\n")
	b.WriteString("Subject: " + sanitizeHeader(subject) + "\r\n")
	b.WriteString("MIME-Version: 1.0\r\n")
	b.WriteString("Content-Type: text/plain; charset=UTF-8\r\n")
	b.WriteString("\r\n")
	// Normalise the body to CRLF.
	b.WriteString(strings.ReplaceAll(strings.ReplaceAll(body, "\r\n", "\n"), "\n", "\r\n"))
	return []byte(b.String())
}

// sanitizeHeader strips CR and LF so a value can't inject extra headers.
func sanitizeHeader(v string) string {
	return strings.NewReplacer("\r", "", "\n", "").Replace(v)
}
