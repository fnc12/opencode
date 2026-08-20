package tunnel

import "context"

// Subscribe opens a GET request to path through the tunnel and streams the
// response body back as raw chunks. It is used by the relay to watch the
// OpenCode event stream (an SSE endpoint) for itself, independently of any
// mobile client.
//
// The returned channel is closed when the upstream stream ends, errors, or ctx
// is cancelled. On cancellation the connector is told to abort the upstream
// request (see TypeCancel).
func (c *Conn) Subscribe(ctx context.Context, path string) (<-chan []byte, error) {
	id, s, err := c.openRequest(RequestHead{Method: "GET", Path: path}, nil)
	if err != nil {
		return nil, err
	}
	out := make(chan []byte, streamBuffer)
	go func() {
		defer close(out)
		for {
			select {
			case <-ctx.Done():
				_ = c.write(Frame{Type: TypeCancel, StreamID: id})
				c.removeStream(id)
				return
			case <-c.closed:
				return
			case <-s.done:
				return
			case f := <-s.frames:
				switch f.Type {
				case TypeData:
					select {
					case out <- f.Payload:
					case <-s.done:
						// Stream was shed by the read loop while we were behind on
						// out; stop so `defer close(out)` unblocks our reader.
						return
					case <-ctx.Done():
						_ = c.write(Frame{Type: TypeCancel, StreamID: id})
						c.removeStream(id)
						return
					}
				case TypeEnd, TypeError:
					return
				}
			}
		}
	}()
	return out, nil
}
