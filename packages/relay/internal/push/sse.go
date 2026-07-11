package push

import (
	"bytes"
	"encoding/json"
)

// Kind classifies a notable event on the OpenCode stream.
type Kind string

const (
	// KindIdle: a session finished / went idle.
	KindIdle Kind = "idle"
	// KindPermission: the agent is waiting for a permission to be allowed.
	KindPermission Kind = "permission"
)

// Event is a notable occurrence extracted from the SSE stream.
type Event struct {
	SessionID string
	Kind      Kind
}

// Scanner consumes a raw byte stream of Server-Sent Events from the OpenCode
// `/global/event` endpoint and reports notable events: a session going idle, or
// the agent asking for a permission. It is fed arbitrary chunks (which may split
// across event boundaries) and buffers until complete events (terminated by a
// blank line) are available.
type Scanner struct {
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

// Feed appends a chunk and returns the notable events found in any
// newly-completed events.
func (s *Scanner) Feed(chunk []byte) []Event {
	s.buf.Write(chunk)
	var events []Event
	for {
		raw := s.buf.Bytes()
		i := bytes.Index(raw, []byte("\n\n"))
		if i < 0 {
			break
		}
		event := raw[:i]
		s.buf.Next(i + 2)
		if ev, ok := parseEvent(event); ok {
			events = append(events, ev)
		}
	}
	return events
}

// parseEvent extracts a notable Event from an SSE event block, if any. It
// recognizes:
//   - session idle — both the current `session.status` (status.type == "idle")
//     and the deprecated `session.idle` event;
//   - permission asked — both the v2 `permission.v2.asked` and the v1
//     `permission.asked` event (the properties carry the session id in both).
func parseEvent(event []byte) (Event, bool) {
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
		sid := p.Properties.SessionID
		switch p.Type {
		case "session.idle":
			if sid != "" {
				return Event{SessionID: sid, Kind: KindIdle}, true
			}
		case "session.status":
			if p.Properties.Status.Type == "idle" && sid != "" {
				return Event{SessionID: sid, Kind: KindIdle}, true
			}
		case "permission.asked", "permission.v2.asked":
			if sid != "" {
				return Event{SessionID: sid, Kind: KindPermission}, true
			}
		}
	}
	return Event{}, false
}
