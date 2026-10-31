package party.qwer.iris

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * gosuda: bounded daemon log. When `IRIS_LOG_PATH` is set, stdout and stderr go to a
 * rotating file set whose total size (current file plus backups) stays within
 * `IRIS_LOG_MAX_BYTES` (default 500 MiB) split over `IRIS_LOG_BACKUPS` + 1 files.
 * Must run before [RuntimeLog.captureStdout] so the /process-status tee wraps this stream.
 */
object DaemonLog {
    const val DEFAULT_MAX_BYTES = 500L * 1024 * 1024
    const val DEFAULT_BACKUPS = 4

    fun install(env: (String) -> String? = System::getenv): Boolean {
        val path = env("IRIS_LOG_PATH")?.takeIf { it.isNotBlank() } ?: return false
        val maxBytes = env("IRIS_LOG_MAX_BYTES")?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_MAX_BYTES
        val backups = env("IRIS_LOG_BACKUPS")?.toIntOrNull()?.takeIf { it in 1..20 } ?: DEFAULT_BACKUPS
        val stream = try {
            RotatingLogStream(File(path), maxBytes, backups)
        } catch (e: Exception) {
            System.err.println("DaemonLog: cannot open log file (${e.javaClass.simpleName}); keeping launcher output")
            return false
        }
        val out = PrintStream(stream, true, "UTF-8")
        System.setOut(out)
        System.setErr(out)
        return true
    }
}

/**
 * Size-bounded append log. A write that would push the current file past its share rotates
 * first; afterwards the oldest backups are deleted until backups plus one full current file
 * fit [maxTotalBytes]. Write errors surface as IOException, which PrintStream swallows, so a
 * full disk never stops the daemon.
 */
class RotatingLogStream(
    private val file: File,
    private val maxTotalBytes: Long,
    private val backups: Int
) : OutputStream() {
    private val maxFileBytes = maxTotalBytes / (backups + 1)
    private var out: FileOutputStream
    private var size: Long

    init {
        require(backups > 0 && maxFileBytes > 0)
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        prune()
        out = open(append = true)
        size = file.length()
    }

    private fun backup(n: Int) = File("${file.path}.$n")

    private fun open(append: Boolean): FileOutputStream {
        val created = !file.exists()
        val stream = FileOutputStream(file, append)
        if (created) {
            runCatching {
                file.setReadable(false, false)
                file.setWritable(false, false)
                file.setReadable(true, true)
                file.setWritable(true, true)
            }
        }
        return stream
    }

    @Synchronized
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        if (size > 0 && size + len > maxFileBytes) rotate()
        out.write(b, off, len)
        size += len
    }

    @Synchronized
    override fun flush() = out.flush()

    @Synchronized
    override fun close() = out.close()

    private fun rotate() {
        runCatching { out.close() }
        runCatching {
            backup(backups).delete()
            for (n in backups - 1 downTo 1) {
                val source = backup(n)
                if (source.exists()) source.renameTo(backup(n + 1))
            }
            file.renameTo(backup(1))
            prune()
        }
        // If the rename failed, truncate rather than grow past the budget.
        val keep = file.length() < maxFileBytes
        out = open(append = keep)
        size = if (keep) file.length() else 0
    }

    /** Delete oldest backups until backups plus one full current file fit the budget. */
    private fun prune() {
        // Backups numbered above the current setting (e.g. after lowering IRIS_LOG_BACKUPS).
        val prefix = file.name + "."
        file.absoluteFile.parentFile?.listFiles()?.forEach { candidate ->
            val n = candidate.name.removePrefix(prefix).toIntOrNull()
            if (candidate.name.startsWith(prefix) && n != null && n > backups) candidate.delete()
        }
        val existing = (1..backups).map(::backup).filter { it.exists() }
        var total = existing.sumOf { it.length() }
        for (old in existing.asReversed()) {
            if (total + maxFileBytes <= maxTotalBytes) break
            total -= old.length()
            old.delete()
        }
    }
}
