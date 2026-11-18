package party.qwer.iris

import java.io.File

/**
 * KakaoTalk checks `noti_referer` on every notification reply intent.
 *
 * KakaoTalk 26.10 moved the value from `shared_prefs/KakaoTalk.hw.perferences.xml` to the
 * Preferences DataStore file `files/datastore/Hardware.preferences_pb`. Read the DataStore
 * first and keep the XML file as a fallback for older KakaoTalk versions.
 */
object NotificationReferer {
    const val KEY = "NotificationReferer"
    private const val DATASTORE_PATH = "files/datastore/Hardware.preferences_pb"
    private const val XML_PATH = "shared_prefs/KakaoTalk.hw.perferences.xml"
    private val xmlPattern = Regex("""<string name="NotificationReferer">(.*?)</string>""")

    fun read(appPath: String): String? =
        fromDataStore(File(appPath + DATASTORE_PATH)) ?: fromXml(File(appPath + XML_PATH))

    fun fromXml(file: File): String? =
        if (file.isFile && file.canRead()) fromXmlText(file.readText()) else null

    fun fromXmlText(text: String): String? =
        xmlPattern.find(text)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

    fun fromDataStore(file: File): String? =
        if (file.isFile && file.canRead()) fromPreferencesProto(file.readBytes()) else null

    /**
     * Minimal reader for androidx.datastore `PreferenceMap`:
     * `map<string, Value> preferences = 1`, where a string `Value` uses field 5.
     * Returns null for a missing key, a non-string value, or malformed input.
     */
    fun fromPreferencesProto(bytes: ByteArray): String? = try {
        val map = Proto(bytes, 0, bytes.size)
        var found: String? = null
        while (map.hasMore()) {
            val tag = map.varint()
            if (field(tag) != 1 || wire(tag) != 2) { map.skip(wire(tag)); continue }
            val entry = map.message()
            var key: String? = null
            var value: String? = null
            while (entry.hasMore()) {
                val entryTag = entry.varint()
                when {
                    field(entryTag) == 1 && wire(entryTag) == 2 -> key = entry.message().text()
                    field(entryTag) == 2 && wire(entryTag) == 2 -> value = stringValue(entry.message())
                    else -> entry.skip(wire(entryTag))
                }
            }
            if (key == KEY) found = value
        }
        found?.takeIf { it.isNotEmpty() }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun stringValue(value: Proto): String? {
        var result: String? = null
        while (value.hasMore()) {
            val tag = value.varint()
            if (field(tag) == 5 && wire(tag) == 2) result = value.message().text()
            else value.skip(wire(tag))
        }
        return result
    }

    private fun field(tag: Long) = (tag ushr 3).toInt()
    private fun wire(tag: Long) = (tag and 7).toInt()

    private class Proto(private val bytes: ByteArray, private var position: Int, private val end: Int) {
        fun hasMore() = position < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                require(position < end) { "truncated varint" }
                val byte = bytes[position++].toInt() and 0xff
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("varint too long")
        }

        fun message(): Proto {
            val length = varint()
            require(length >= 0 && length <= end - position) { "truncated field" }
            val start = position
            position += length.toInt()
            return Proto(bytes, start, position)
        }

        fun text() = String(bytes, position, end - position, Charsets.UTF_8)

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> advance(8)
                2 -> message()
                5 -> advance(4)
                else -> throw IllegalArgumentException("unsupported wire type $wire")
            }
        }

        private fun advance(count: Int) {
            require(count <= end - position) { "truncated field" }
            position += count
        }
    }
}
