package tunnel

import (
	"encoding/json"
	"io"
	"net/http"
)

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
	_, s, err := c.openRequest(head, body)
	if err != nil {
		http.Error(w, "tunnel unavailable: "+err.Error(), http.StatusBadGateway)
		return
	}

	flusher, _ := w.(http.Flusher)
	wroteHead := false
	ctx := r.Context()

	for {
		select {
		case <-ctx.Done():
			return // client went away; read loop will GC the stream on End
		case <-c.closed:
			if !wroteHead {
				http.Error(w, "tunnel closed", http.StatusBadGateway)
			}
			return
		case f, ok := <-s.frames:
			if !ok {
				return
			}
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
				if _, err := w.Write(f.Payload); err != nil {
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
