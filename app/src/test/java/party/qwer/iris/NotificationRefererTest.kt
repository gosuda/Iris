package party.qwer.iris

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationRefererTest {
    private fun ByteArrayOutputStream.varint(value: Long) {
        var rest = value
        while (rest >= 0x80) { write(((rest and 0x7f) or 0x80).toInt()); rest = rest ushr 7 }
        write(rest.toInt())
    }

    private fun ByteArrayOutputStream.bytes(field: Int, value: ByteArray) {
        varint(((field shl 3) or 2).toLong()); varint(value.size.toLong()); write(value)
    }

    private fun message(build: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(build).toByteArray()

    private fun entry(key: String, value: ByteArray) = message {
        bytes(1, key.toByteArray())
        bytes(2, value)
    }

    private fun stringValue(text: String) = message { bytes(5, text.toByteArray()) }
    private fun longValue(number: Long) = message { varint((4 shl 3).toLong()); varint(number) }
    private fun boolValue() = message { varint((1 shl 3).toLong()); varint(1) }

    private fun preferences(vararg entries: ByteArray) = message { entries.forEach { bytes(1, it) } }

    @Test fun `reads the referer among other DataStore preferences`() {
        val proto = preferences(
            entry("HardwareInfoVersion", longValue(300_000_000_000L)),
            entry("UsingTablet", boolValue()),
            entry("NotificationReferer", stringValue("ref-1234567890")),
            entry("DeviceUUID", stringValue("not-the-referer")),
        )
        assertEquals("ref-1234567890", NotificationReferer.fromPreferencesProto(proto))
    }

    @Test fun `missing, non-string, and truncated values return null`() {
        assertNull(NotificationReferer.fromPreferencesProto(preferences(entry("DeviceUUID", stringValue("x")))))
        assertNull(NotificationReferer.fromPreferencesProto(preferences(entry("NotificationReferer", longValue(7)))))
        val proto = preferences(entry("NotificationReferer", stringValue("ref-1234567890")))
        assertNull(NotificationReferer.fromPreferencesProto(proto.copyOf(proto.size - 3)))
    }

    @Test fun `DataStore takes precedence and legacy XML remains a fallback`() {
        val app = Files.createTempDirectory("kakao").toFile()
        val xml = app.resolve("shared_prefs/KakaoTalk.hw.perferences.xml")
        xml.parentFile.mkdirs()
        xml.writeText("""<map><string name="NotificationReferer">legacy-ref</string></map>""")
        val prefix = app.path + "/"
        assertEquals("legacy-ref", NotificationReferer.read(prefix))

        val store = app.resolve("files/datastore/Hardware.preferences_pb")
        store.parentFile.mkdirs()
        store.writeBytes(preferences(entry("NotificationReferer", stringValue("datastore-ref"))))
        assertEquals("datastore-ref", NotificationReferer.read(prefix))
    }
}
