# Daemon log rotation

Upstream Iris prints all daemon output, including webhook payloads, to stdout and stderr.
When the launcher appends that output to a file, the file grows without bound.

Set `IRIS_LOG_PATH` to make Iris write stdout and stderr to a bounded, rotating file set
instead. The tee that feeds `/process-status` logs still receives every line.

| Variable | Default | Meaning |
| --- | --- | --- |
| `IRIS_LOG_PATH` | unset (no rotation) | Current log file; backups are `.1` (newest) to `.N` |
| `IRIS_LOG_MAX_BYTES` | 524288000 (500 MiB) | Total for the current file and all backups |
| `IRIS_LOG_BACKUPS` | 4 | Number of backups (1-20) |

Each file gets an equal share of the total, so the defaults keep five files of 100 MiB. A write
that would push the current file past its share rotates first. Then the oldest backups are
deleted until the backups plus one full current file fit the total. This also covers an
oversized log left by an earlier launcher and backups numbered above the current setting. If a
rename fails, the current file is truncated so the total remains bounded. New files are
owner-readable/writable only.

Output written before `Main` installs the stream, and native runtime messages written directly
to file descriptors 1 and 2, still go to the launcher's redirect. Keep that redirect on a
separate small file.

Write errors do not stop the daemon. `PrintStream` records them and continues, so logging
resumes once the file can be written again.
