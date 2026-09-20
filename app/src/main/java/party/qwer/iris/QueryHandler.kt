package party.qwer.iris

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.JsonObject
import party.qwer.iris.model.QueryRequest
import party.qwer.iris.model.QueryResponse

class QueryFailure(requestId: String, cause: Exception) :
    Exception("Query failed; request_id=$requestId", cause)

suspend fun observedQuery(
    call: ApplicationCall,
    execute: (String, Array<String?>, QueryTrace) -> List<Map<String, String?>>,
    decrypt: (Map<String, String?>, (Exception) -> Unit) -> Map<String, String?> = KakaoDB::decryptRow,
    output: (JsonObject) -> Unit = QueryLog::write
) {
    val trace = QueryTrace(call.request.headers["X-Request-ID"], output)
    try {
        call.response.headers.append("X-Iris-Request-ID", trace.requestId)
        trace.begin("parse")
        val request = call.receive<QueryRequest>()
        val bindings = (request.bind?.map { it.content } ?: emptyList()).toTypedArray<String?>()
        trace.end()
        trace.parsed(request.query, bindings.size)
        val rows = execute(request.query, bindings, trace)
        trace.begin("decrypt")
        val decrypted = rows.map {
            decrypt(it, trace::decryptFailure).also { trace.decryptedRow() }
        }
        trace.end()
        trace.begin("respond")
        call.respond(QueryResponse(data = decrypted))
        trace.end()
        trace.finish("ok")
    } catch (e: CancellationException) {
        trace.finish("cancelled", e)
        throw e
    } catch (e: Exception) {
        trace.finish("error", e)
        // The upstream StatusPages handler returns exception.message to the caller.
        // Do not echo SQL, bound values or database exception messages there.
        throw QueryFailure(trace.requestId, e)
    } finally {
        // Includes unusual fatal exits without swallowing the original throwable.
        trace.finish("aborted")
    }
}
