// Package tunnel implements the multiplexing protocol spoken between the relay
// and a connector over a single WebSocket connection.
//
// Many concurrent iOS client requests (including long-lived SSE streams) are
// multiplexed over one connector socket. Each logical request is a "stream"
// identified by a uint64 StreamID. Frames are length-prefixed binary records:
//
//	[Type:1][StreamID:8][Len:4][Payload:Len]   (big-endian)
package tunnel

import (
	"encoding/binary"
	"fmt"
	"io"
)

// FrameType identifies the purpose of a frame.
type FrameType uint8

const (
	// TypeRegister is the first frame a connector sends; payload is JSON
	// {tunnelId, token}. StreamID is 0.
	TypeRegister FrameType = iota + 1
	// TypeRegisterAck is the relay's reply to TypeRegister; payload is JSON
	// {ok, error}. StreamID is 0.
	TypeRegisterAck
	// TypeRequest (relay -> connector) opens a stream; payload is a JSON
	// RequestHead followed by the (possibly empty) request body.
	TypeRequest
	// TypeResponseHead (connector -> relay) carries JSON ResponseHead.
	TypeResponseHead
	// TypeData carries a raw body/SSE chunk for a stream (either direction).
	TypeData
	// TypeEnd signals the stream is complete (no error).
	TypeEnd
	// TypeError aborts a stream; payload is a UTF-8 error message.
	TypeError
	// TypePing / TypePong implement an application-level heartbeat. StreamID 0.
	TypePing
	TypePong
	// TypeCancel (relay -> connector) aborts an in-flight stream because the iOS
	// client disconnected. The connector cancels the upstream request so it does
	// not keep streaming (notably an SSE /event stream) into the void.
	TypeCancel
)

// maxPayload caps a single frame's payload to guard against malformed input.
const maxPayload = 16 << 20 // 16 MiB

// Frame is one record on the wire.
type Frame struct {
	Type     FrameType
	StreamID uint64
	Payload  []byte
}

// WriteTo encodes the frame to w. It is the caller's responsibility to
// serialize writes to a given connection.
func (f Frame) WriteTo(w io.Writer) (int64, error) {
	var hdr [13]byte
	hdr[0] = byte(f.Type)
	binary.BigEndian.PutUint64(hdr[1:9], f.StreamID)
	binary.BigEndian.PutUint32(hdr[9:13], uint32(len(f.Payload)))
	n, err := w.Write(hdr[:])
	if err != nil {
		return int64(n), err
	}
	m, err := w.Write(f.Payload)
	return int64(n + m), err
}

// ReadFrame decodes a single frame from r.
func ReadFrame(r io.Reader) (Frame, error) {
	var hdr [13]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return Frame{}, err
	}
	length := binary.BigEndian.Uint32(hdr[9:13])
	if length > maxPayload {
		return Frame{}, fmt.Errorf("tunnel: payload too large: %d", length)
	}
	f := Frame{
		Type:     FrameType(hdr[0]),
		StreamID: binary.BigEndian.Uint64(hdr[1:9]),
	}
	if length > 0 {
		f.Payload = make([]byte, length)
		if _, err := io.ReadFull(r, f.Payload); err != nil {
			return Frame{}, err
		}
	}
	return f, nil
}

// RequestHead is the JSON prelude of a TypeRequest payload. The raw request
// body follows the JSON, separated by a single newline.
type RequestHead struct {
	Method string              `json:"method"`
	Path   string              `json:"path"` // path + raw query, relative to the OpenCode server root
	Header map[string][]string `json:"header,omitempty"`
}

// ResponseHead is the JSON payload of a TypeResponseHead frame.
type ResponseHead struct {
	Status int                 `json:"status"`
	Header map[string][]string `json:"header,omitempty"`
}

// Register is the JSON payload of a TypeRegister frame.
type Register struct {
	TunnelID string `json:"tunnelId"`
	Token    string `json:"token"`
}

// RegisterAck is the JSON payload of a TypeRegisterAck frame.
type RegisterAck struct {
	OK    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
}
