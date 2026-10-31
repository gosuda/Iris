package party.qwer.iris

import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DaemonLogTest {
    private fun dir() = Files.createTempDirectory("iris-log").toFile()
    private fun files(d: File) = d.listFiles()!!.filter { it.name.startsWith("iris.log") }

    @Test fun `rotation keeps total within budget and newest lines in current file`() {
        val d = dir()
        val log = File(d, "iris.log")
        val stream = RotatingLogStream(log, maxTotalBytes = 500, backups = 4)
        repeat(200) { stream.write("line-%03d-xxxxxxxxxxxxxxxxxxx\n".format(it).toByteArray()) }
        stream.close()
        assertTrue(files(d).size <= 5)
        assertTrue(files(d).sumOf { it.length() } <= 500)
        assertTrue(log.length() <= 100)
        assertTrue(log.readText().contains("line-199"))
        assertTrue(File(d, "iris.log.1").readText().isNotEmpty())
    }

    @Test fun `oversized existing log and stray backups are brought within budget`() {
        val d = dir()
        val log = File(d, "iris.log")
        log.writeText("x".repeat(400))
        File(d, "iris.log.1").writeText("y".repeat(300))
        File(d, "iris.log.7").writeText("stale")
        val stream = RotatingLogStream(log, maxTotalBytes = 500, backups = 4)
        stream.write("after\n".toByteArray())
        stream.close()
        assertFalse(File(d, "iris.log.7").exists())
        assertEquals("after\n", log.readText())
        assertTrue(files(d).sumOf { it.length() } <= 500)
        assertEquals(400L, File(d, "iris.log.1").length())
    }

    @Test fun `install redirects stdout and stderr only when a path is configured`() {
        val d = dir()
        val log = File(d, "nested/iris.log")
        val (out, err) = System.out to System.err
        try {
            assertFalse(DaemonLog.install { null })
            assertTrue(DaemonLog.install { mapOf("IRIS_LOG_PATH" to log.path, "IRIS_LOG_MAX_BYTES" to "1000")[it] })
            println("to-stdout")
            System.err.println("to-stderr")
            System.out.flush()
        } finally {
            System.setOut(out); System.setErr(err)
        }
        assertEquals("to-stdout\nto-stderr\n", log.readText())
        assertTrue(log.canRead() && !Files.getPosixFilePermissions(log.toPath()).any { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") })
    }

    @Test fun `write failure does not throw through PrintStream`() {
        val d = dir()
        val log = File(d, "iris.log")
        val stream = RotatingLogStream(log, maxTotalBytes = 500, backups = 4)
        stream.close()
        val print = PrintStream(stream, true)
        print.println("ignored")
        assertTrue(print.checkError())
    }
}
