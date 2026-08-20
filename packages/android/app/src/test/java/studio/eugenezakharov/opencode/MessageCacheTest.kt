package studio.eugenezakharov.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import studio.eugenezakharov.opencode.api.MessageCache

/**
 * The on-disk newest-page cache: a save→load round-trip parses back through the
 * production model path, a miss is null (not a crash), and a fresh save replaces
 * the prior page. Mirrors iOS `MessageCacheTests`.
 */
class MessageCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sessionID = "ses_cache_test"

    private fun rawArray(vararg ids: String): String {
        val objs = ids.map { id ->
            """{"info":{"id":"$id","sessionID":"$sessionID","role":"user","time":{"created":1}},
               "parts":[{"id":"prt_$id","sessionID":"$sessionID","messageID":"$id","type":"text","text":"hi"}]}"""
        }
        return "[${objs.joinToString(",")}]"
    }

    @Test
    fun saveThenLoadRoundTrips() {
        val cache = MessageCache(tmp.newFolder("mc"))
        cache.save(sessionID, rawArray("m1", "m2"))
        assertEquals(listOf("m1", "m2"), cache.load(sessionID)?.map { it.id })
    }

    @Test
    fun missReturnsNull() {
        val cache = MessageCache(tmp.newFolder("mc"))
        assertNull(cache.load("ses_never_written"))
    }

    @Test
    fun saveOverwritesPreviousPage() {
        val cache = MessageCache(tmp.newFolder("mc"))
        cache.save(sessionID, rawArray("old"))
        cache.save(sessionID, rawArray("new"))
        assertEquals(listOf("new"), cache.load(sessionID)?.map { it.id })
    }
}
