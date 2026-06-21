package push

import (
	"bytes"
	"encoding/json"
)

// IdleScanner consumes a raw byte stream of Server-Sent Events from the
// OpenCode `/global/event` endpoint and reports the session ids that have gone
// idle. It is fed arbitrary chunks (which may split across event boundaries)
// and buffers until complete events (terminated by a blank line) are available.
type IdleScanner struct {
	buf bytes.Buffer
}

// opencode wraps every SSE event payload as {"payload":{type, properties}}.
type sseEnvelope struct {
	Payload struct {
		Type       string `json:"type"`
		Properties struct {
			SessionID string `json:"sessionID"`
			Status    struct {
				Type string `json:"type"`
			} `json:"status"`
		} `json:"properties"`
	} `json:"payload"`
}

// Feed appends a chunk and returns the session ids that became idle in any
// newly-completed events.
func (s *IdleScanner) Feed(chunk []byte) []string {
	s.buf.Write(chunk)
	var idle []string
	for {
		raw := s.buf.Bytes()
		i := bytes.Index(raw, []byte("\n\n"))
		if i < 0 {
			break
		}
		event := raw[:i]
		s.buf.Next(i + 2)
		if sid, ok := parseIdle(event); ok {
			idle = append(idle, sid)
		}
	}
	return idle
}

// parseIdle extracts the session id from an event block if it signals idle.
// It recognizes both the current `session.status` event (status.type == "idle")
// and the deprecated `session.idle` event.
func parseIdle(event []byte) (string, bool) {
	for _, line := range bytes.Split(event, []byte("\n")) {
		data, ok := bytes.CutPrefix(line, []byte("data:"))
		if !ok {
			continue
		}
		data = bytes.TrimSpace(data)
		var env sseEnvelope
		if err := json.Unmarshal(data, &env); err != nil {
			continue
		}
		p := env.Payload
		switch p.Type {
		case "session.idle":
			if p.Properties.SessionID != "" {
				return p.Properties.SessionID, true
			}
		case "session.status":
			if p.Properties.Status.Type == "idle" && p.Properties.SessionID != "" {
				return p.Properties.SessionID, true
			}
		}
	}
	return "", false
}
