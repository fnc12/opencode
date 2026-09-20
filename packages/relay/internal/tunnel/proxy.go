package tunnel

import (
	"encoding/json"
	"io"
	"net/http"
	"time"
)

// mobileWriteWait bounds a single write to the mobile client. A backgrounded or
// disconnected phone can leave its TCP socket half-open (no FIN), so a plain
// Write blocks until the OS tears the connection down minutes later. Capping it
// lets us reclaim the stream promptly instead of stalling the goroutine (and,
// before the per-stream drop existed, the whole tunnel). A var so tests can
// shrink it.
var mobileWriteWait = 15 * time.Second

// hopByHop headers are connection-specific and must not be forwarded.
var hopByHop = map[string]bool{
	"Connection":          true,
	"Keep-Alive":          true,
	"Proxy-Authenticate":  true,
	"Proxy-Authorization": true,
	"Te":                  true,
	"Trailer":             true,
	"Transfer-Encoding":   true,
	"Upgrade":             true,
}

// Proxy forwards an HTTP request to the connector and streams the response back
// to w. The path passed is relative to the OpenCode server root (the /t/{id}
// prefix has already been stripped). It streams response chunks as they arrive
// and flushes after each, so Server-Sent Events pass through with no buffering.
func (c *Conn) Proxy(w http.ResponseWriter, r *http.Request, path string) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "read request body: "+err.Error(), http.StatusBadGateway)
		return
	}

	head := RequestHead{Method: r.Method, Path: path, Header: filterHeader(r.Header)}
	id, s, err := c.openRequest(head, body)
	if err != nil {
		http.Error(w, "tunnel unavailable: "+err.Error(), http.StatusBadGateway)
		return
	}

	flusher, _ := w.(http.Flusher)
	rc := http.NewResponseController(w)
	wroteHead := false
	ctx := r.Context()

	for {
		select {
		case <-ctx.Done():
			// Client went away; tell the connector to stop the upstream request
			// (otherwise an SSE stream would run forever) and drop the stream.
			_ = c.write(Frame{Type: TypeCancel, StreamID: id})
			c.removeStream(id)
			return
		case <-c.closed:
			if !wroteHead {
				http.Error(w, "tunnel closed", http.StatusBadGateway)
			}
			return
		case f := <-s.frames:
			// Note: no select on s.done here. The read loop only sheds a stream
			// whose buffer stayed full — i.e. this consumer was blocked in Write,
			// not waiting here — and that path is unblocked by the write deadline
			// below. Selecting on s.done would race the drain of a normally-ended
			// stream's buffered TypeEnd/TypeData and could truncate the response.
			switch f.Type {
			case TypeResponseHead:
				var rh ResponseHead
				if err := json.Unmarshal(f.Payload, &rh); err != nil {
					http.Error(w, "bad response head", http.StatusBadGateway)
					return
				}
				dst := w.Header()
				for k, vs := range rh.Header {
					if hopByHop[http.CanonicalHeaderKey(k)] {
						continue
					}
					for _, v := range vs {
						dst.Add(k, v)
					}
				}
				status := rh.Status
				if status == 0 {
					status = http.StatusOK
				}
				w.WriteHeader(status)
				wroteHead = true
				if flusher != nil {
					flusher.Flush()
				}
			case TypeData:
				if !wroteHead {
					w.WriteHeader(http.StatusOK)
					wroteHead = true
				}
				// Bound the write: a dead/backgrounded phone can block Write
				// indefinitely, backing frames up into the shared read loop. On
				// timeout (or any write error) tell the connector to stop the
				// upstream request and drop the stream so nothing leaks.
				_ = rc.SetWriteDeadline(time.Now().Add(mobileWriteWait))
				if _, err := w.Write(f.Payload); err != nil {
					_ = c.write(Frame{Type: TypeCancel, StreamID: id})
					c.removeStream(id)
					return
				}
				if flusher != nil {
					flusher.Flush()
				}
			case TypeEnd:
				return
			case TypeError:
				if !wroteHead {
					http.Error(w, "upstream error: "+string(f.Payload), http.StatusBadGateway)
				}
				return
			}
		}
	}
}

func filterHeader(h http.Header) map[string][]string {
	out := make(map[string][]string, len(h))
	for k, vs := range h {
		if hopByHop[http.CanonicalHeaderKey(k)] || k == "Host" {
			continue
		}
		out[k] = vs
	}
	return out
}
