package push

import (
	"reflect"
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
