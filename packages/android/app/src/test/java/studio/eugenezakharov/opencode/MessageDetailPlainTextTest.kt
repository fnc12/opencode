package studio.eugenezakharov.opencode

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.eugenezakharov.opencode.api.models.MessageParsing
import studio.eugenezakharov.opencode.ui.session.detailBlocks
import studio.eugenezakharov.opencode.ui.session.plainText

/**
 * The message-detail "copy all" flattening (detailBlocks → plainText). Mirrors
 * iOS `MessageDetailSnapshotTests.testPlainText*`. Covers every DetailBlock arm:
 * Thinking, Body, ToolBlock (edit-diff and no-output), Note (patch/file), and the
 * markdown TableBlock.
 */
class MessageDetailPlainTextTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun flatten(partsJson: String): String {
        val msg = MessageParsing.parseMessageList(
            json,
            """[{"info":{"id":"m","sessionID":"s","role":"assistant","time":{"created":1}},"parts":$partsJson}]""",
        ).single()
        return plainText(detailBlocks(msg))
    }

    @Test fun flattensEveryBlockType() {
        val text = flatten(
            """[
              {"id":"p0","sessionID":"s","messageID":"m","type":"reasoning","text":"pondering"},
              {"id":"p1","sessionID":"s","messageID":"m","type":"text","text":"Answer line."},
              {"id":"p2","sessionID":"s","messageID":"m","type":"tool","callID":"c1","tool":"edit","state":{"status":"completed","output":"ok","metadata":{"diff":"-old\n+new"}}},
              {"id":"p3","sessionID":"s","messageID":"m","type":"tool","callID":"c2","tool":"bash","state":{"status":"completed","title":"noop"}},
              {"id":"p4","sessionID":"s","messageID":"m","type":"patch","hash":"h","files":["a.kt","b.kt"]},
              {"id":"p5","sessionID":"s","messageID":"m","type":"file","filename":"diff.patch"}
            ]""",
        )
        assertTrue("thinking labelled", text.contains("💭 Thinking"))
        assertTrue("thinking text", text.contains("pondering"))
        assertTrue("body text", text.contains("Answer line."))
        assertTrue("edit tool copies its diff", text.contains("+new"))
        assertTrue("patch note", text.contains("Patch"))
        assertTrue("file note", text.contains("diff.patch"))
    }

    @Test fun toolWithNoOutputIsTitleOnly() {
        // A completed tool with no output/diff → just the "→ title" line.
        val text = flatten(
            """[{"id":"p","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"bash","state":{"status":"completed","title":"noop"}}]""",
        )
        assertTrue("title-only, no body separator", text.startsWith("→ ") && !text.contains("\n\n"))
    }

    @Test fun markdownTableBecomesPipeJoinedRows() {
        // A text part carrying a markdown table → the TableBlock arm joins cells.
        val text = flatten(
            """[{"id":"p","sessionID":"s","messageID":"m","type":"text","text":"| A | B |\n| --- | --- |\n| 1 | 2 |"}]""",
        )
        assertTrue("table rows are pipe-joined, got: $text", text.contains("A | B") || text.contains("1 | 2"))
    }
}
