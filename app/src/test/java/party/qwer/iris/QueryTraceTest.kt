package party.qwer.iris

import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QueryTraceTest {
    @Test fun `monotonic durations and errors exclude source material`() {
        val events = mutableListOf<JsonObject>()
        var now = 0L
        val trace = QueryTrace("safe-id", events::add, { now })
        trace.begin("db_read")
        trace.parsed("SELECT 'private SQL value'", 1)
        now = 5_000_000L
        repeat(100) { trace.readRow() }
        trace.end()
        trace.finish("error", IllegalStateException("private exception message"))
        trace.finish("ok")
        assertEquals(1, events.count { it["event"]?.jsonPrimitive?.content == "query_end" })
        val end = events.last()
        assertEquals(5.0, end["elapsed_ms"]!!.jsonPrimitive.double, 0.001)
        assertEquals(100, end["rows_read"]!!.jsonPrimitive.int)
        assertEquals("IllegalStateException", end["error_class"]!!.jsonPrimitive.content)
        assertEquals("safe-id", end["client_request_id"]!!.jsonPrimitive.content)
        assertFalse(events.toString().contains("private"))
        assertEquals(2, events.count { it["event"]?.jsonPrimitive?.content == "query_progress" })
    }

    @Test fun `invalid client identifiers are discarded and trace IDs are independent`() {
        val events = mutableListOf<JsonObject>()
        val one = QueryTrace("token?secret=example\n", events::add)
        val two = QueryTrace("token?secret=example\n", events::add)
        assertNotEquals(one.requestId, two.requestId)
        assertTrue(events.all { "client_request_id" !in it })
        one.finish("ok")
        two.finish("ok")
        assertEquals(0, events.last()["active_queries"]!!.jsonPrimitive.int)
    }

    @Test fun `sink failure cannot fail query work or leak active counts`() {
        val trace = QueryTrace(output = { throw IllegalStateException("disk unavailable") })
        trace.begin("db_read")
        trace.readRow()
        trace.end()
        trace.finish("ok")
        val events = mutableListOf<JsonObject>()
        QueryTrace(output = events::add).finish("ok")
        assertEquals(0, events.last()["active_queries"]!!.jsonPrimitive.int)
    }

    @Test fun `decryption failures counted without flooding exception records`() {
        val events = mutableListOf<JsonObject>()
        val trace = QueryTrace(output = events::add)
        trace.begin("decrypt")
        repeat(200) { trace.decryptFailure(IllegalArgumentException("secret ciphertext")) }
        trace.finish("ok")
        assertEquals(200, events.last()["decrypt_failures"]!!.jsonPrimitive.int)
        assertEquals(1, events.count { it["event"]?.jsonPrimitive?.content == "query_decrypt_error" })
        assertFalse(events.toString().contains("ciphertext"))
    }

    @Test fun `concurrent file writes are complete and rotation is bounded`() {
        val dir = Files.createTempDirectory("iris-query-log").toFile()
        try {
            val file = dir.resolve("query.jsonl")
            val sink = RotatingQueryLog(file, 100_000, 2)
            val pool = Executors.newFixedThreadPool(4)
            repeat(100) { n -> pool.submit { sink.write("{\"n\":$n}") } }
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            val rows = file.readLines().map { Json.parseToJsonElement(it).jsonObject }
            assertEquals(100, rows.size)
            assertEquals(100, rows.map { it["n"] }.toSet().size)
            val small = RotatingQueryLog(file, 30, 2)
            repeat(30) { small.write("{\"n\":$it}") }
            assertTrue(dir.resolve("query.jsonl.2").exists())
            assertFalse(dir.resolve("query.jsonl.3").exists())
            assertEquals(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file.toPath()))
        } finally { dir.deleteRecursively() }
    }
}
