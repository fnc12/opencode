package studio.eugenezakharov.opencode.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Minimal Server-Sent Events reader over OkHttp's streaming response body.
 *
 * Emits the raw JSON payload of each `data:` frame as a [String]; the caller
 * decodes it into a [ServerEvent].
 *
 * The stream is parsed byte/line-by-line and a frame is dispatched **the moment
 * its terminating blank line arrives** — not via a buffered line reader that
 * withholds the blank line until more input lands (the exact bug that bit the
 * iOS client: events would surface one event late, or never while idle).
 */
class EventStream(
    private val url: String,
    private val authHeader: String?,
    /** In relay mode, the per-tunnel token the relay requires (`X-Tunnel-Token`); null in direct mode. */
    private val tunnelToken: String? = null,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Long-lived stream: no read timeout so it never trips while idle.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun frames(): Flow<String> = flow {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
        authHeader?.let { requestBuilder.header("Authorization", it) }
        tunnelToken?.let { requestBuilder.header("X-Tunnel-Token", it) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw ClientError.Http(response.code)
            val source = response.body?.source() ?: throw ClientError.Http(0)

            val frame = StringBuilder()
            // BufferedSource.readUtf8Line() returns one line per call as soon as
            // the line terminator (\n, tolerating \r\n) arrives, and returns an
            // empty string for a blank line — so a frame is dispatched exactly
            // when its terminating blank line lands.
            while (true) {
                val line = source.readUtf8Line() ?: break // stream closed
                if (line.isEmpty()) {
                    // Blank line terminates an event.
                    if (frame.isNotEmpty()) {
                        emit(frame.toString())
                        frame.setLength(0)
                    }
                    continue
                }
                // Ignore comments (`:`), `event:`, `id:`, `retry:` — only `data:` carries the payload.
                if (!line.startsWith("data:")) continue
                var payload = line.substring("data:".length)
                if (payload.startsWith(" ")) payload = payload.substring(1)
                if (frame.isNotEmpty()) frame.append('\n') // join multi-line data with \n
                frame.append(payload)
            }
        }
    }.flowOn(Dispatchers.IO)
}
