package studio.eugenezakharov.opencode

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import studio.eugenezakharov.opencode.api.EventStream

/**
 * The SSE parser. Regression guard for the bug that only surfaced live: an
 * event must be dispatched the moment its terminating blank line arrives, and
 * CRLF / multi-line `data:` / comments must be handled. Mirrors iOS
 * `EventStreamTests`, but drives a real OkHttp request via MockWebServer.
 */
class EventStreamTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun frames(body: String, expected: Int): List<String> = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )
        val url = server.url("/global/event").toString()
        EventStream(url, authHeader = null).frames().take(expected).toList()
    }

    @Test
    fun dispatchesEachFrameOnBlankLine() {
        val result = frames("data: {\"a\":1}\n\ndata: {\"b\":2}\n\n", expected = 2)
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), result)
    }

    @Test
    fun toleratesCRLF() {
        val result = frames("data: {\"a\":1}\r\n\r\n", expected = 1)
        assertEquals(listOf("{\"a\":1}"), result)
    }

    @Test
    fun joinsMultiLineData() {
        val result = frames("data: line1\ndata: line2\n\n", expected = 1)
        assertEquals(listOf("line1\nline2"), result)
    }

    @Test
    fun ignoresCommentsAndOtherFields() {
        val result = frames(": heartbeat\nevent: message\ndata: {\"ok\":true}\n\n", expected = 1)
        assertEquals(listOf("{\"ok\":true}"), result)
    }
}
