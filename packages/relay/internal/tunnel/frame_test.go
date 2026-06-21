package tunnel

import (
	"bytes"
	"testing"
)

func TestFrameRoundTrip(t *testing.T) {
	cases := []Frame{
		{Type: TypePing},
		{Type: TypeData, StreamID: 1, Payload: []byte("hello")},
		{Type: TypeResponseHead, StreamID: 1<<40 + 7, Payload: bytes.Repeat([]byte{0xAB}, 5000)},
		{Type: TypeEnd, StreamID: 42},
	}
	for _, want := range cases {
		got, err := Decode(Encode(want))
		if err != nil {
			t.Fatalf("decode: %v", err)
		}
		if got.Type != want.Type || got.StreamID != want.StreamID || !bytes.Equal(got.Payload, want.Payload) {
			t.Fatalf("round trip mismatch: got %+v want %+v", got, want)
		}
	}
}

func TestRequestEncodeDecode(t *testing.T) {
	head := RequestHead{
		Method: "POST",
		Path:   "/session/abc/message?directory=/x",
		Header: map[string][]string{"Authorization": {"Basic zzz"}},
	}
	body := []byte(`{"text":"hi"}`)
	payload, err := EncodeRequest(head, body)
	if err != nil {
		t.Fatal(err)
	}
	gotHead, gotBody, err := DecodeRequest(payload)
	if err != nil {
		t.Fatal(err)
	}
	if gotHead.Method != head.Method || gotHead.Path != head.Path {
		t.Fatalf("head mismatch: %+v", gotHead)
	}
	if !bytes.Equal(gotBody, body) {
		t.Fatalf("body mismatch: %q", gotBody)
	}
}

func TestDecodeTruncated(t *testing.T) {
	if _, err := Decode([]byte{0x01, 0x00}); err == nil {
		t.Fatal("expected error on truncated frame")
	}
}
