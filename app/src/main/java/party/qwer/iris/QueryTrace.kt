package party.qwer.iris

import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*

/** Metadata only: never pass SQL, bindings, rows, or exception messages here. */
class QueryTrace(
    clientId: String? = null,
    private val output: (JsonObject) -> Unit = QueryLog::write,
    private val clock: () -> Long = System::nanoTime
) {
    val requestId: String = UUID.randomUUID().toString()
    private val clientId = clientId?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,64}")) }
    private val started = clock()
    private var stage = "request"
    private var stageStarted = started
    private var finished = false
    private var rowsRead = 0
    private var rowsDecrypted = 0
    private var decryptFailures = 0

    init {
        active.incrementAndGet()
        emit("query_start")
    }

    private fun elapsed(since: Long) = (clock() - since).coerceAtLeast(0) / 1_000_000.0
    private fun emit(event: String, extra: JsonObjectBuilder.() -> Unit = {}) {
        try {
            output(buildJsonObject {
                put("schema_version", 1)
                put("event", event)
                put("at", Instant.now().toString())
                put("request_id", requestId)
                clientId?.let { put("client_request_id", it) }
                put("endpoint", "/query")
                put("active_queries", active.get())
                put("elapsed_ms", elapsed(started))
                put("stage", stage)
                put("stage_elapsed_ms", elapsed(stageStarted))
                put("rows_read", rowsRead)
                put("rows_decrypted", rowsDecrypted)
                put("decrypt_failures", decryptFailures)
                extra()
            })
        } catch (_: Exception) {
            // A failed log write must not change query results or cause retries.
        }
    }

    fun begin(name: String) {
        stage = name
        stageStarted = clock()
        emit("query_stage_start")
    }

    fun end() = emit("query_stage_end")

    fun parsed(sql: String, bindCount: Int) = emit("query_parsed") {
        val kind = sql.trimStart().takeWhile { it.isLetter() }.uppercase()
        put("query_kind", if (kind in setOf("SELECT", "EXPLAIN", "PRAGMA", "WITH")) kind else "other")
        put("sql_bytes", sql.toByteArray(Charsets.UTF_8).size)
        put("binding_count", bindCount)
    }

    fun readRow() {
        rowsRead++
        if (rowsRead % 50 == 0) emit("query_progress")
    }

    fun decryptedRow() {
        rowsDecrypted++
        if (rowsDecrypted % 50 == 0) emit("query_progress")
    }

    fun decryptFailure(error: Exception) {
        decryptFailures++
        // Counts remain exact; at most one error record per request avoids log floods.
        if (decryptFailures == 1) emit("query_decrypt_error") { put("error_class", error.javaClass.simpleName) }
    }

    fun finish(status: String, error: Throwable? = null) {
        if (finished) return
        finished = true
        active.decrementAndGet()
        emit("query_end") {
            put("status", status)
            error?.let { put("error_class", it.javaClass.simpleName) }
        }
    }

    companion object {
        private val active = AtomicInteger()
    }
}

/** Optional dedicated file; otherwise the process supervisor captures stderr. */
object QueryLog {
    private val file = System.getenv("IRIS_QUERY_LOG_PATH")?.takeIf { it.isNotBlank() }?.let(::File)
    private val rotating = file?.let { RotatingQueryLog(it) }
    @Synchronized fun write(event: JsonObject) {
        val line = event.toString()
        if (rotating != null) rotating.write(line) else System.err.println(line)
    }
}

/** Single Iris process owns this log. Current file plus three 10 MiB backups. */
class RotatingQueryLog(private val file: File, private val maxBytes: Long = 10L * 1024 * 1024,
                       private val backups: Int = 3) {
    init { require(maxBytes > 0 && backups > 0) }
    @Synchronized fun write(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        file.parentFile?.let { if (!it.exists()) check(it.mkdirs()) }
        if (file.exists() && file.length() > 0 && file.length() + bytes.size > maxBytes) {
            val last = File("${file.path}.$backups")
            if (last.exists()) check(last.delete())
            for (n in backups - 1 downTo 1) {
                val source = File("${file.path}.$n")
                if (source.exists()) check(source.renameTo(File("${file.path}.${n + 1}")))
            }
            check(file.renameTo(File("${file.path}.1")))
        }
        if (!file.exists()) {
            check(file.createNewFile())
            check(file.setReadable(false, false))
            check(file.setWritable(false, false))
            check(file.setReadable(true, true))
            check(file.setWritable(true, true))
        }
        file.appendBytes(bytes)
    }
}
