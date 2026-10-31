package party.qwer.iris

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.JsonObject
import party.qwer.iris.model.BulkQueryResponse
import party.qwer.iris.model.QueryRequest
import party.qwer.iris.model.QueryResponse

class QueryFailure(requestId: String, cause: Exception) :
    Exception("Query failed; request_id=$requestId", cause)

typealias QueryExecutor = (String, Array<String?>, QueryTrace) -> List<Map<String, String?>>
typealias RowDecryptor = (Map<String, String?>, (Exception) -> Unit) -> Map<String, String?>

/**
 * Upstream /query contract: `{query, bind}` returns `{data}`; non-empty `queries` returns
 * `{data: [[...], ...]}`; a query failure returns HTTP 200 with empty `data` and `error`.
 * The error carries only a lookup ID, never SQL, bound values or DB exception messages.
 */
suspend fun observedQuery(
    call: ApplicationCall,
    execute: QueryExecutor,
    decrypt: RowDecryptor = KakaoDB::decryptRow,
    output: (JsonObject) -> Unit = QueryLog::write
) {
    val trace = QueryTrace(call.request.headers["X-Request-ID"], output)
    try {
        call.response.headers.append("X-Iris-Request-ID", trace.requestId)
        trace.begin("parse")
        val request = call.receive<QueryRequest>()
        trace.end()
        val bulk = request.queries?.takeIf { it.isNotEmpty() }
        val results = try {
            (bulk ?: listOf(request)).map { runQuery(it, execute, decrypt, trace) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Record the failing stage before the response replaces it.
            trace.finish("error", e)
            call.respond(QueryResponse(data = emptyList(), error = "Query failed; request_id=${trace.requestId}"))
            return
        }
        trace.begin("respond")
        if (bulk != null) call.respond(BulkQueryResponse(data = results))
        else call.respond(QueryResponse(data = results.single()))
        trace.end()
        trace.finish("ok")
    } catch (e: CancellationException) {
        trace.finish("cancelled", e)
        throw e
    } catch (e: Exception) {
        trace.finish("error", e)
        // The StatusPages handler returns exception.message to the caller.
        throw QueryFailure(trace.requestId, e)
    } finally {
        // Includes unusual fatal exits without swallowing the original throwable.
        trace.finish("aborted")
    }
}

private fun runQuery(
    request: QueryRequest,
    execute: QueryExecutor,
    decrypt: RowDecryptor,
    trace: QueryTrace
): List<Map<String, String?>> {
    val sql = request.query?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("missing query")
    val bindings = (request.bind?.map { it.content } ?: emptyList()).toTypedArray<String?>()
    trace.parsed(sql, bindings.size)
    val rows = execute(sql, bindings, trace)
    trace.begin("decrypt")
    val decrypted = rows.map { decrypt(it, trace::decryptFailure).also { trace.decryptedRow() } }
    trace.end()
    return decrypted
}
