package account

import (
	"strings"
	"testing"
)

func TestBuildMessage(t *testing.T) {
	msg := string(buildMessage("noreply@shubat.org", "Shubat", "user@x.com", "Sign in", "Link:\nhttps://relay/verify?token=abc"))

	for _, want := range []string{
		"From: Shubat <noreply@shubat.org>\r\n",
		"To: user@x.com\r\n",
		"Subject: Sign in\r\n",
		"Content-Type: text/plain; charset=UTF-8\r\n",
		"\r\n\r\n", // header/body separator (last header CRLF + blank line)
		"Link:\r\nhttps://relay/verify?token=abc",
	} {
		if !strings.Contains(msg, want) {
			t.Errorf("message missing %q\n---\n%s", want, msg)
		}
	}
}

func TestBuildMessageStripsHeaderInjection(t *testing.T) {
	// A subject carrying CRLF + a forged header must not break out of the Subject.
	msg := string(buildMessage("a@b.com", "", "c@d.com", "Hi\r\nBcc: evil@x.com", "body"))
	// The forged CRLF must be stripped so no new "Bcc:" header line is created.
	if strings.Contains(msg, "\r\nBcc:") {
		t.Errorf("header injection not sanitised (a real Bcc header line appeared):\n%s", msg)
	}
	if !strings.Contains(msg, "Subject: HiBcc: evil@x.com\r\n") {
		t.Errorf("expected CRLF stripped from subject, got:\n%s", msg)
	}
}

func TestSMTPSenderSatisfiesInterface(t *testing.T) {
	var _ EmailSender = SMTPSender{}
	var _ EmailSender = LogSender{}
}
