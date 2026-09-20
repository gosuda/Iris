# Query observability

`POST /query` preserves the existing request and successful `{ "data": [...] }`
response. Every request receives a server-generated UUID in `X-Iris-Request-ID`.
An optional `X-Request-ID` request header is recorded separately as
`client_request_id`, only if it matches `[A-Za-z0-9._-]{1,64}`. Do not place user
content or secrets in that header. Server UUIDs remain unique even if clients reuse IDs.

Each JSONL event has `schema_version: 1`, a UTC `at` timestamp, request ID,
endpoint, active query count, elapsed milliseconds, stage and stage elapsed
milliseconds, rows read/decrypted and decryption failure count. Durations use
`System.nanoTime()` and do not depend on wall-clock adjustments.

Events are `query_start`, `query_parsed`, `query_stage_start`, `query_stage_end`,
`query_progress`, `query_decrypt_error`, and `query_end`. Stages are:

- `parse`: receiving and decoding the request body.
- `db_cursor_open`: Android `rawQuery` returns a cursor. This does **not** mean the
  query has finished; SQLite work and connection waits can occur lazily.
- `db_read`: column discovery, cursor iteration/materialization and cursor close.
  This includes any lazy query execution, DB waits and row-copy overhead.
- `decrypt`: decrypting the materialized rows.
- `respond`: the Ktor `respond` call, including the processing it awaits. This is
  not proof that the peer received the complete response.

Progress is recorded every 50 rows. A stalled operation leaves a stage-start event
and the last completed row count. Query-end has `status` (`ok`, `error`,
`cancelled`, `aborted`) and only the exception class when available. `ok` means
handler completion; check `decrypt_failures` for errors that the existing
best-effort decryption behavior swallowed. At most one decryption-error record is
emitted per request, while the total count remains exact. Other requests proceed
independently; active counts are process-wide and decrease once per request.

SQL text, bindings, message contents, row IDs, ciphertext and exception messages
are excluded. Only SQL byte size, binding count and an allowlisted statement
kind are recorded. Query exceptions returned through the existing StatusPages
handler include a lookup ID instead of SQL or DB exception details.

By default logs go to stderr, captured by the existing process supervisor.
For bounded dedicated logs, set `IRIS_QUERY_LOG_PATH` before starting Iris:

```sh
export IRIS_QUERY_LOG_PATH=/data/local/tmp/asko-iris/query.jsonl
```

The optional file sink retains the current file and three backups, 10 MiB each.
New files are owner-readable/writable only. Use a private parent directory and
one Iris process per log path. In-process concurrent writes are serialized.
Logging exceptions do not change the query response. When disabled by an
unwritable file/path, logging fails silently rather than retrying application
work; check file creation and permissions during deployment. Unconfigured stderr
rotation is the responsibility of the supervisor.

This change observes cancellations delivered to the request coroutine. It does
not add a query timeout, interrupt a blocking SQLite operation, or guarantee that
a disconnected client cancels DB work. Those require a separate cancellation
implementation. It also does not distinguish SQLite lock wait from execution
inside `db_read`; use this first to identify the slow stage.

## Validation

With JDK 17 and Android SDK platform 35 installed:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Tests cover actual Ktor request/response handling with injected query functions,
parse/DB failures, cancellation, request correlation, metadata privacy, monotonic
timing, decryption-error counts, log failures, concurrency and rotation. Android
SQLite/device timing still needs a real device smoke test after building.
