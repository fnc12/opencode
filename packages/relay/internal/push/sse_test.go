package push

import (
	"reflect"
	"testing"
)

func TestIdleScannerDetectsEvents(t *testing.T) {
	s := &IdleScanner{}
	stream := "" +
		`data: {"payload":{"type":"server.connected","properties":{}}}` + "\n\n" +
		`data: {"payload":{"type":"message.updated","properties":{"sessionID":"s1"}}}` + "\n\n" +
		`data: {"payload":{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"busy"}}}}` + "\n\n" +
		`data: {"payload":{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"idle"}}}}` + "\n\n" +
		`data: {"payload":{"type":"session.idle","properties":{"sessionID":"s2"}}}` + "\n\n"

	got := s.Feed([]byte(stream))
	want := []string{"s1", "s2"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v want %v", got, want)
	}
}

func TestIdleScannerHandlesSplitChunks(t *testing.T) {
	s := &IdleScanner{}
	full := `data: {"payload":{"type":"session.idle","properties":{"sessionID":"abc"}}}` + "\n\n"
	// Feed byte-by-byte; only the final byte completes the event.
	var got []string
	for i := 0; i < len(full); i++ {
		got = append(got, s.Feed([]byte{full[i]})...)
	}
	if len(got) != 1 || got[0] != "abc" {
		t.Fatalf("expected [abc], got %v", got)
	}
}

func TestIdleScannerIgnoresNonIdle(t *testing.T) {
	s := &IdleScanner{}
	stream := `data: {"payload":{"type":"session.status","properties":{"sessionID":"s","status":{"type":"busy"}}}}` + "\n\n"
	if got := s.Feed([]byte(stream)); len(got) != 0 {
		t.Fatalf("expected none, got %v", got)
	}
}
