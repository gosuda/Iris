package party.qwer.iris

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class QueryHandlerTest {
    @Test fun `successful route preserves payload and correlates stage logs`() = testApplication {
        val events = mutableListOf<JsonObject>()
        application {
            install(ContentNegotiation) { json() }
            routing {
                post("/query") {
                    observedQuery(call, { sql, bindings, trace ->
                        assertEquals("SELECT private_text", sql)
                        assertEquals("private-binding", bindings.single())
                        trace.begin("db_cursor_open"); trace.end()
                        trace.begin("db_read"); trace.readRow(); trace.end()
                        listOf(mapOf("message" to "private-row"))
                    }, { row, _ -> row }, events::add)
                }
            }
        }
        val response = client.post("/query") {
            contentType(ContentType.Application.Json)
            header("X-Request-ID", "asko-123")
            setBody("""{"query":"SELECT private_text","bind":["private-binding"]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("private-row", Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!
            .jsonArray.single().jsonObject["message"]!!.jsonPrimitive.content)
        val id = response.headers["X-Iris-Request-ID"]
        assertNotNull(id)
        assertTrue(events.all { it["request_id"]!!.jsonPrimitive.content == id })
        val stages = events.filter { it["event"]!!.jsonPrimitive.content == "query_stage_end" }
            .map { it["stage"]!!.jsonPrimitive.content }
        assertEquals(listOf("parse", "db_cursor_open", "db_read", "decrypt", "respond"), stages)
        assertEquals("ok", events.last()["status"]!!.jsonPrimitive.content)
        assertFalse(events.toString().contains("private"))
    }

    @Test fun `DB exception exposes request ID but no query or exception content`() = testApplication {
        val events = mutableListOf<JsonObject>()
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) {
                exception<Throwable> { call, cause -> call.respond(HttpStatusCode.InternalServerError,
                    buildJsonObject { put("message", cause.message) }) }
            }
            routing {
                post("/query") {
                    observedQuery(call, { _, _, trace ->
                        trace.begin("db_read")
                        throw IllegalStateException("private database path and SQL")
                    }, { row, _ -> row }, events::add)
                }
            }
        }
        val response = client.post("/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"query":"SELECT private_text"}""")
        }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(response.bodyAsText().contains(response.headers["X-Iris-Request-ID"]!!))
        assertFalse(response.bodyAsText().contains("private"))
        assertFalse(events.toString().contains("private"))
        assertEquals("db_read", events.last()["stage"]!!.jsonPrimitive.content)
        assertEquals("error", events.last()["status"]!!.jsonPrimitive.content)
        assertEquals(0, events.last()["active_queries"]!!.jsonPrimitive.int)
    }

    @Test fun `parse errors have a terminal event before DB work`() = testApplication {
        val events = mutableListOf<JsonObject>()
        var queried = false
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) {
                exception<Throwable> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
            }
            routing { post("/query") {
                observedQuery(call, { _, _, _ -> queried = true; emptyList() }, { row, _ -> row }, events::add)
            } }
        }
        client.post("/query") { contentType(ContentType.Application.Json); setBody("invalid-json") }
        assertFalse(queried)
        assertEquals("parse", events.last()["stage"]!!.jsonPrimitive.content)
        assertEquals("error", events.last()["status"]!!.jsonPrimitive.content)
    }

    @Test fun `cancellation is logged once and not wrapped as query failure`() = testApplication {
        val events = mutableListOf<JsonObject>()
        var cancelled = false
        application {
            install(ContentNegotiation) { json() }
            routing { post("/query") {
                try {
                    observedQuery(call, { _, _, trace ->
                        trace.begin("db_read")
                        throw CancellationException("private cancellation detail")
                    }, { row, _ -> row }, events::add)
                } catch (e: CancellationException) {
                    cancelled = true
                    call.respond(HttpStatusCode.ServiceUnavailable)
                }
            } }
        }
        client.post("/query") { contentType(ContentType.Application.Json); setBody("""{"query":"SELECT 1"}""") }
        assertTrue(cancelled)
        assertEquals("cancelled", events.last()["status"]!!.jsonPrimitive.content)
        assertEquals(1, events.count { it["event"]!!.jsonPrimitive.content == "query_end" })
        assertFalse(events.toString().contains("private"))
    }
}
