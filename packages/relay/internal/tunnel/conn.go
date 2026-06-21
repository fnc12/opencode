package tunnel

import (
	"bytes"
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Encode serializes a frame into a single byte slice suitable for one
// WebSocket binary message.
func Encode(f Frame) []byte {
	var buf bytes.Buffer
	buf.Grow(13 + len(f.Payload))
	_, _ = f.WriteTo(&buf)
	return buf.Bytes()
}

// Decode parses a frame from a single WebSocket binary message.
func Decode(b []byte) (Frame, error) {
	return ReadFrame(bytes.NewReader(b))
}

// stream is the relay-side state for one in-flight request.
type stream struct {
	frames chan Frame
	once   sync.Once
}

func (s *stream) close() { s.once.Do(func() { close(s.frames) }) }

// Conn represents a single connector's multiplexed WebSocket connection as seen
// from the relay. It owns the read loop and routes inbound frames to the
// per-stream channel that the corresponding HTTP handler is waiting on.
type Conn struct {
	TunnelID string

	ws       *websocket.Conn
	writeMu  sync.Mutex // serializes all writes to the socket
	nextID   uint64
	mu       sync.Mutex
	streams  map[uint64]*stream
	closed   chan struct{}
	closeErr error
	once     sync.Once
}

const (
	pingInterval = 30 * time.Second
	writeWait    = 10 * time.Second
	streamBuffer = 32
)

// NewConn wraps an established WebSocket connection and starts its read loop.
func NewConn(tunnelID string, ws *websocket.Conn) *Conn {
	c := &Conn{
		TunnelID: tunnelID,
		ws:       ws,
		streams:  make(map[uint64]*stream),
		closed:   make(chan struct{}),
	}
	go c.readLoop()
	go c.pingLoop()
	return c
}

// Done returns a channel closed when the connection terminates.
func (c *Conn) Done() <-chan struct{} { return c.closed }

func (c *Conn) shutdown(err error) {
	c.once.Do(func() {
		c.closeErr = err
		close(c.closed)
		_ = c.ws.Close()
		c.mu.Lock()
		for id, s := range c.streams {
			s.close()
			delete(c.streams, id)
		}
		c.mu.Unlock()
	})
}

func (c *Conn) write(f Frame) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	_ = c.ws.SetWriteDeadline(time.Now().Add(writeWait))
	return c.ws.WriteMessage(websocket.BinaryMessage, Encode(f))
}

func (c *Conn) readLoop() {
	for {
		_, data, err := c.ws.ReadMessage()
		if err != nil {
			c.shutdown(err)
			return
		}
		f, err := Decode(data)
		if err != nil {
			c.shutdown(err)
			return
		}
		switch f.Type {
		case TypePong:
			continue
		case TypePing:
			_ = c.write(Frame{Type: TypePong})
			continue
		}
		c.mu.Lock()
		s := c.streams[f.StreamID]
		c.mu.Unlock()
		if s == nil {
			continue // unknown/closed stream; drop
		}
		select {
		case s.frames <- f:
		case <-c.closed:
			return
		}
		if f.Type == TypeEnd || f.Type == TypeError {
			c.removeStream(f.StreamID)
		}
	}
}

func (c *Conn) pingLoop() {
	t := time.NewTicker(pingInterval)
	defer t.Stop()
	for {
		select {
		case <-c.closed:
			return
		case <-t.C:
			if err := c.write(Frame{Type: TypePing}); err != nil {
				c.shutdown(err)
				return
			}
		}
	}
}

func (c *Conn) newStream() (uint64, *stream) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.nextID++
	id := c.nextID
	s := &stream{frames: make(chan Frame, streamBuffer)}
	c.streams[id] = s
	return id, s
}

func (c *Conn) removeStream(id uint64) {
	c.mu.Lock()
	s := c.streams[id]
	delete(c.streams, id)
	c.mu.Unlock()
	if s != nil {
		s.close()
	}
}

// ErrClosed is returned when an operation is attempted on a dead connection.
var ErrClosed = errors.New("tunnel: connection closed")

// openRequest sends a TypeRequest frame and returns the stream to read the
// response from.
func (c *Conn) openRequest(head RequestHead, body []byte) (uint64, *stream, error) {
	id, s := c.newStream()
	payload, err := encodeRequest(head, body)
	if err != nil {
		c.removeStream(id)
		return 0, nil, err
	}
	if err := c.write(Frame{Type: TypeRequest, StreamID: id, Payload: payload}); err != nil {
		c.removeStream(id)
		return 0, nil, err
	}
	return id, s, nil
}

func encodeRequest(head RequestHead, body []byte) ([]byte, error) {
	hb, err := json.Marshal(head)
	if err != nil {
		return nil, err
	}
	buf := make([]byte, 0, len(hb)+1+len(body))
	buf = append(buf, hb...)
	buf = append(buf, '\n')
	buf = append(buf, body...)
	return buf, nil
}

// decodeRequest splits a TypeRequest payload into its head and body.
func decodeRequest(payload []byte) (RequestHead, []byte, error) {
	i := bytes.IndexByte(payload, '\n')
	if i < 0 {
		return RequestHead{}, nil, errors.New("tunnel: malformed request frame")
	}
	var head RequestHead
	if err := json.Unmarshal(payload[:i], &head); err != nil {
		return RequestHead{}, nil, err
	}
	return head, payload[i+1:], nil
}
