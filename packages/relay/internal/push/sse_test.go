package push

import (
	"reflect"
	"strings"
	"testing"
)

func TestScannerDetectsIdleEvents(t *testing.T) {
	s := &Scanner{}
	stream := "" +
		`data: {"payload":{"type":"server.connected","properties":{}}}` + "\n\n" +
		`data: {"payload":{"type":"message.updated","properties":{"sessionID":"s1"}}}` + "\n\n" +
		`data: {"payload":{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"busy"}}}}` + "\n\n" +
		`data: {"payload":{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"idle"}}}}` + "\n\n" +
		`data: {"payload":{"type":"session.idle","properties":{"sessionID":"s2"}}}` + "\n\n"

	got := s.Feed([]byte(stream))
	want := []Event{
		{SessionID: "s1", Kind: KindIdle},
		{SessionID: "s2", Kind: KindIdle},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v want %v", got, want)
	}
}

func TestScannerDetectsPermissionEvents(t *testing.T) {
	s := &Scanner{}
	stream := "" +
		// v2 event name
		`data: {"payload":{"type":"permission.v2.asked","properties":{"id":"per_1","sessionID":"s1","action":"read","resources":["/etc"]}}}` + "\n\n" +
		// deprecated v1 event name
		`data: {"payload":{"type":"permission.asked","properties":{"id":"per_2","sessionID":"s2","permission":"edit","patterns":["*.ts"]}}}` + "\n\n" +
		// replied is NOT a trigger
		`data: {"payload":{"type":"permission.v2.replied","properties":{"sessionID":"s1","requestID":"per_1"}}}` + "\n\n"

	got := s.Feed([]byte(stream))
	want := []Event{
		{SessionID: "s1", Kind: KindPermission},
		{SessionID: "s2", Kind: KindPermission},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v want %v", got, want)
	}
}

func TestScannerDetectsQuestionEvents(t *testing.T) {
	s := &Scanner{}
	stream := "" +
		`data: {"payload":{"type":"question.v2.asked","properties":{"id":"que_1","sessionID":"s1","questions":[{"question":"Which?"}]}}}` + "\n\n" +
		`data: {"payload":{"type":"question.asked","properties":{"id":"que_2","sessionID":"s2","questions":[]}}}` + "\n\n" +
		// resolved is NOT a trigger
		`data: {"payload":{"type":"question.v2.resolved","properties":{"sessionID":"s1","requestID":"que_1"}}}` + "\n\n"

	got := s.Feed([]byte(stream))
	want := []Event{
		{SessionID: "s1", Kind: KindQuestion},
		{SessionID: "s2", Kind: KindQuestion},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v want %v", got, want)
	}
}

func TestScannerEnrichesWithTitleAndPreview(t *testing.T) {
	s := &Scanner{}
	stream := "" +
		// learn the session title
		`data: {"payload":{"type":"session.updated","properties":{"info":{"id":"s1","title":"Fix the parser"}}}}` + "\n\n" +
		// stream some answer text (snapshots)
		`data: {"payload":{"type":"message.part.updated","properties":{"sessionID":"s1","part":{"type":"text","text":"Done — I split the tokenizer\nand added a test."}}}}` + "\n\n" +
		// a permission mid-turn carries the title too
		`data: {"payload":{"type":"permission.v2.asked","properties":{"id":"per_1","sessionID":"s1"}}}` + "\n\n" +
		// idle carries title + the answer preview
		`data: {"payload":{"type":"session.idle","properties":{"sessionID":"s1"}}}` + "\n\n"

	got := s.Feed([]byte(stream))
	want := []Event{
		{SessionID: "s1", Kind: KindPermission, Title: "Fix the parser"},
		{SessionID: "s1", Kind: KindIdle, Title: "Fix the parser", Preview: "Done — I split the tokenizer and added a test."},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %#v want %#v", got, want)
	}

	// After idle the answer text is forgotten (next turn starts fresh), but the
	// title persists.
	got2 := s.Feed([]byte(`data: {"payload":{"type":"session.idle","properties":{"sessionID":"s1"}}}` + "\n\n"))
	want2 := []Event{{SessionID: "s1", Kind: KindIdle, Title: "Fix the parser", Preview: ""}}
	if !reflect.DeepEqual(got2, want2) {
		t.Fatalf("after-idle got %#v want %#v", got2, want2)
	}
}

func TestPreviewStripsMarkdown(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		{"bold and italic", "The **tokenizer** was *rewritten*.", "The tokenizer was rewritten."},
		{"heading", "## Summary\nAll good.", "Summary All good."},
		{"link", "See [the docs](https://example.com/x) for details.", "See the docs for details."},
		{"image", "Here: ![a screenshot](data:image/png;base64,AAAA)", "Here: a screenshot"},
		{"bullet list", "- split the tokenizer\n- added a test", "split the tokenizer added a test"},
		{"numbered list", "1. first\n2. second", "first second"},
		{"inline code + fence", "Run `make` then:\n```sh\nmake test\n```\nDone.", "Run make then: make test Done."},
		{"blockquote", "> a quote line", "a quote line"},
		{"strikethrough", "~~old~~ new", "old new"},
		{"snake_case kept intact", "renamed to qualified_column_ref", "renamed to qualified_column_ref"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := preview(c.in); got != c.want {
				t.Fatalf("preview(%q) = %q, want %q", c.in, got, c.want)
			}
		})
	}
}

func TestPreviewTruncatesAfterStripping(t *testing.T) {
	// A long **bold** run must strip first, THEN truncate — the star markers
	// mustn't eat into the 140-char budget.
	long := "**" + strings.Repeat("word ", 60) + "**"
	got := preview(long)
	if strings.Contains(got, "*") {
		t.Fatalf("markdown leaked into preview: %q", got)
	}
	if len([]rune(got)) > 141 { // 140 + ellipsis
		t.Fatalf("preview too long (%d runes): %q", len([]rune(got)), got)
	}
}

func TestScannerHandlesSplitChunks(t *testing.T) {
	s := &Scanner{}
	full := `data: {"payload":{"type":"session.idle","properties":{"sessionID":"abc"}}}` + "\n\n"
	// Feed byte-by-byte; only the final byte completes the event.
	var got []Event
	for i := 0; i < len(full); i++ {
		got = append(got, s.Feed([]byte{full[i]})...)
	}
	if len(got) != 1 || got[0].SessionID != "abc" || got[0].Kind != KindIdle {
		t.Fatalf("expected [{abc idle}], got %v", got)
	}
}

func TestScannerIgnoresNonNotable(t *testing.T) {
	s := &Scanner{}
	stream := `data: {"payload":{"type":"session.status","properties":{"sessionID":"s","status":{"type":"busy"}}}}` + "\n\n"
	if got := s.Feed([]byte(stream)); len(got) != 0 {
		t.Fatalf("expected none, got %v", got)
	}
}
