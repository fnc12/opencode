package push

import (
	"bytes"
	"encoding/json"
	"strings"
)

// Kind classifies a notable event on the OpenCode stream.
type Kind string

const (
	// KindIdle: a session finished / went idle.
	KindIdle Kind = "idle"
	// KindPermission: the agent is waiting for a permission to be allowed.
	KindPermission Kind = "permission"
	// KindQuestion: the agent asked the user a question and is blocked on it.
	KindQuestion Kind = "question"
)

// Event is a notable occurrence extracted from the SSE stream.
type Event struct {
	SessionID string
	Kind      Kind
	// Title is the session's name, best-effort (learned from a `session.updated`
	// event earlier in the stream). Empty until one is seen.
	Title string
	// Preview is the start of the last assistant answer, best-effort (learned from
	// `message.part.updated` text snapshots). Empty when the turn produced no text.
	Preview string
}

// Scanner consumes a raw byte stream of Server-Sent Events from the OpenCode
// `/global/event` endpoint and reports notable events: a session going idle, the
// agent asking for a permission, or asking a question. It is fed arbitrary chunks
// (which may split across event boundaries) and buffers until complete events
// (terminated by a blank line) are available. Along the way it remembers each
// session's title and latest answer text so those can enrich the notifications.
type Scanner struct {
	buf    bytes.Buffer
	titles map[string]string // sessionID -> session title
	texts  map[string]string // sessionID -> latest text-part text (the answer so far)
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
			// session.updated carries the full session object.
			Info struct {
				ID    string `json:"id"`
				Title string `json:"title"`
			} `json:"info"`
			// message.part.updated carries a full snapshot of one part.
			Part struct {
				Type string `json:"type"`
				Text string `json:"text"`
			} `json:"part"`
		} `json:"properties"`
	} `json:"payload"`
}

// Feed appends a chunk and returns the notable events found in any
// newly-completed events.
func (s *Scanner) Feed(chunk []byte) []Event {
	if s.titles == nil {
		s.titles = map[string]string{}
		s.texts = map[string]string{}
	}
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
		if ev, ok := s.parseEvent(event); ok {
			events = append(events, ev)
		}
	}
	return events
}

// parseEvent extracts a notable Event from an SSE event block, if any, and folds
// title / answer-text updates into the scanner's per-session state along the way.
// It recognizes:
//   - session idle — both the current `session.status` (status.type == "idle")
//     and the deprecated `session.idle` event;
//   - permission asked — both `permission.v2.asked` and v1 `permission.asked`;
//   - question asked — both `question.v2.asked` and v1 `question.asked`.
func (s *Scanner) parseEvent(event []byte) (Event, bool) {
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
		case "session.updated":
			if id := p.Properties.Info.ID; id != "" && p.Properties.Info.Title != "" {
				s.titles[id] = p.Properties.Info.Title
			}
		case "message.part.updated":
			// A text snapshot IS the current full text of that part — the last one
			// before idle is (near enough) the assistant's answer.
			if sid != "" && p.Properties.Part.Type == "text" && p.Properties.Part.Text != "" {
				s.texts[sid] = p.Properties.Part.Text
			}
		case "session.idle":
			if sid != "" {
				return s.idle(sid), true
			}
		case "session.status":
			if p.Properties.Status.Type == "idle" && sid != "" {
				return s.idle(sid), true
			}
		case "permission.asked", "permission.v2.asked":
			if sid != "" {
				return Event{SessionID: sid, Kind: KindPermission, Title: s.titles[sid]}, true
			}
		case "question.asked", "question.v2.asked":
			if sid != "" {
				return Event{SessionID: sid, Kind: KindQuestion, Title: s.titles[sid]}, true
			}
		}
	}
	return Event{}, false
}

// idle builds the idle Event with the session's title + answer preview, then
// forgets the answer text so the next turn starts fresh (titles persist — small).
func (s *Scanner) idle(sid string) Event {
	ev := Event{SessionID: sid, Kind: KindIdle, Title: s.titles[sid], Preview: preview(s.texts[sid])}
	delete(s.texts, sid)
	return ev
}

// preview collapses the answer to a one-line notification snippet.
func preview(text string) string {
	f := strings.Join(strings.Fields(text), " ")
	const max = 140
	if len(f) <= max {
		return f
	}
	// Trim on a rune boundary.
	cut := max
	for cut > 0 && f[cut]&0xC0 == 0x80 {
		cut--
	}
	return strings.TrimSpace(f[:cut]) + "…"
}
