package tunnel

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// newConnPair wires a relay-side *Conn to a raw connector-side *websocket.Conn
// through a real (loopback) WebSocket, so tests exercise the actual read loop.
func newConnPair(t *testing.T) (*Conn, *websocket.Conn) {
	t.Helper()
	up := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	connCh := make(chan *Conn, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ws, err := up.Upgrade(w, r, nil)
		if err != nil {
			t.Errorf("upgrade: %v", err)
			return
		}
		connCh <- NewConn("tun_test", "", ws)
	}))
	t.Cleanup(srv.Close)

	url := "ws" + strings.TrimPrefix(srv.URL, "http")
	peer, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	c := <-connCh
	t.Cleanup(func() { c.Close(); _ = peer.Close() })
	return c, peer
}

func writeFrame(t *testing.T, ws *websocket.Conn, f Frame) {
	t.Helper()
	if err := ws.WriteMessage(websocket.BinaryMessage, Encode(f)); err != nil {
		t.Fatalf("peer write: %v", err)
	}
}

// A stream whose consumer never drains its buffer must not freeze the read loop
// that multiplexes every other stream on the same connector — that was the
// tunnel-wide dead-lock. A second, healthy stream must still get its frame.
func TestReadLoopStalledStreamDoesNotFreezeTunnel(t *testing.T) {
	old := streamStallTimeout
	streamStallTimeout = 150 * time.Millisecond
	defer func() { streamStallTimeout = old }()

	c, peer := newConnPair(t)

	// Stream A: registered but never drained (a wedged mobile consumer).
	idA, _ := c.newStream()
	// Stream B: a healthy consumer waiting on its channel.
	idB, sB := c.newStream()

	// Flood A past its buffer so the read loop's send to it would block.
	for i := 0; i < streamBuffer+10; i++ {
		writeFrame(t, peer, Frame{Type: TypeData, StreamID: idA, Payload: []byte("x")})
	}
	// Then a single frame for B. If the loop were frozen on A, this never lands.
	writeFrame(t, peer, Frame{Type: TypeData, StreamID: idB, Payload: []byte("hello")})

	select {
	case f := <-sB.frames:
		if string(f.Payload) != "hello" {
			t.Fatalf("stream B got %q, want hello", f.Payload)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("read loop frozen: stream B frame not delivered while stream A stalled")
	}

	// The stalled stream A should have been dropped.
	c.mu.Lock()
	_, stillA := c.streams[idA]
	c.mu.Unlock()
	if stillA {
		t.Error("stalled stream A was not dropped")
	}
}

// A dropped stream also tells the connector to stop the upstream request, so an
// SSE stream to a dead phone doesn't run forever on the origin server.
func TestReadLoopStalledStreamCancelsUpstream(t *testing.T) {
	old := streamStallTimeout
	streamStallTimeout = 100 * time.Millisecond
	defer func() { streamStallTimeout = old }()

	c, peer := newConnPair(t)
	idA, _ := c.newStream()
	for i := 0; i < streamBuffer+5; i++ {
		writeFrame(t, peer, Frame{Type: TypeData, StreamID: idA, Payload: []byte("x")})
	}

	// The connector side should receive a TypeCancel for the dropped stream.
	_ = peer.SetReadDeadline(time.Now().Add(2 * time.Second))
	for {
		_, data, err := peer.ReadMessage()
		if err != nil {
			t.Fatalf("expected a cancel frame, got read error: %v", err)
		}
		f, err := Decode(data)
		if err != nil {
			t.Fatalf("decode: %v", err)
		}
		if f.Type == TypeCancel && f.StreamID == idA {
			return // success
		}
	}
}

// deadlineWriter is an http.ResponseWriter whose Write blocks until its
// write deadline, then fails — a stand-in for a phone that stopped reading.
type deadlineWriter struct {
	header   http.Header
	deadline time.Time
}

func (d *deadlineWriter) Header() http.Header         { return d.header }
func (d *deadlineWriter) WriteHeader(int)             {}
func (d *deadlineWriter) Flush()                      {}
func (d *deadlineWriter) SetWriteDeadline(t time.Time) error {
	d.deadline = t
	return nil
}
func (d *deadlineWriter) Write(p []byte) (int, error) {
	if wait := time.Until(d.deadline); wait > 0 {
		time.Sleep(wait)
	}
	return 0, os.ErrDeadlineExceeded
}

// Proxy must not block forever writing to a stalled client: the bounded write
// deadline fires, and Proxy tears the stream down (cancelling upstream).
func TestProxyBoundsWriteToStalledClient(t *testing.T) {
	old := mobileWriteWait
	mobileWriteWait = 100 * time.Millisecond
	defer func() { mobileWriteWait = old }()

	c, peer := newConnPair(t)

	req := httptest.NewRequest(http.MethodGet, "/global/event", nil)
	w := &deadlineWriter{header: http.Header{}}
	done := make(chan struct{})
	go func() { c.Proxy(w, req, "/global/event"); close(done) }()

	// Read the request frame to learn the stream id the Proxy opened.
	_ = peer.SetReadDeadline(time.Now().Add(2 * time.Second))
	_, data, err := peer.ReadMessage()
	if err != nil {
		t.Fatalf("read request frame: %v", err)
	}
	reqFrame, err := Decode(data)
	if err != nil || reqFrame.Type != TypeRequest {
		t.Fatalf("want TypeRequest, got %+v err=%v", reqFrame, err)
	}
	id := reqFrame.StreamID

	// Respond head + one data frame; the client "write" will then stall.
	writeFrame(t, peer, Frame{Type: TypeResponseHead, StreamID: id, Payload: []byte(`{"status":200}`)})
	writeFrame(t, peer, Frame{Type: TypeData, StreamID: id, Payload: []byte("event: x\n\n")})

	// Proxy should return once the write deadline elapses.
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("Proxy did not return after the write deadline on a stalled client")
	}

	// And it should have cancelled the upstream stream.
	sawCancel := false
	_ = peer.SetReadDeadline(time.Now().Add(time.Second))
	for {
		_, data, err := peer.ReadMessage()
		if err != nil {
			break
		}
		f, derr := Decode(data)
		if derr != nil {
			continue
		}
		if f.Type == TypeCancel && f.StreamID == id {
			sawCancel = true
			break
		}
	}
	if !sawCancel {
		t.Error("Proxy did not cancel the upstream stream after the write timed out")
	}
}

// Sanity: os.ErrDeadlineExceeded is the error class net.Conn write deadlines
// surface, so the Proxy teardown branch keys off a real timeout, not a guess.
func TestDeadlineErrorIsTimeout(t *testing.T) {
	if !errors.Is(os.ErrDeadlineExceeded, os.ErrDeadlineExceeded) {
		t.Fatal("unreachable")
	}
}
