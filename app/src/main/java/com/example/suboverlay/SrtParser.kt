package com.example.suboverlay

/**
 * Parser subtitle untuk format SRT dan WebVTT.
 *
 * Kedua format memakai baris waktu:
 *   SRT : 00:00:12,500 --> 00:00:15,000
 *   VTT : 00:12.500 --> 00:15.000  (jam opsional)
 * Perbedaan lain (header "WEBVTT", nomor cue, tag HTML) diabaikan.
 */
object SrtParser {

    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    private val TIME_LINE = Regex(
        """(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{1,3})\s*-->\s*(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{1,3})"""
    )
    private val HTML_TAG = Regex("<[^>]*>")
    private val ASS_TAG = Regex("""\{\\[^}]*}""")

    fun parse(raw: String): List<Cue> {
        val text = raw
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val cues = ArrayList<Cue>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val match = TIME_LINE.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val g = match.groupValues
            val start = toMs(g[1], g[2], g[3], g[4])
            val end = toMs(g[5], g[6], g[7], g[8])

            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i].trim())
                i++
            }
            val cleaned = sanitize(body.toString())
            if (cleaned.isNotEmpty() && end > start) {
                cues.add(Cue(start, end, cleaned))
            }
        }
        cues.sortBy { it.startMs }
        return cues
    }

    fun decode(bytes: ByteArray): String {
        // BOM UTF-16
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, Charsets.UTF_16BE)
        }
        // UTF-8 ketat, lalu cp1252 (umum untuk subtitle lama), lalu latin-1
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            try {
                String(bytes, java.nio.charset.Charset.forName("windows-1252"))
            } catch (_: Exception) {
                String(bytes, Charsets.ISO_8859_1)
            }
        }
    }

    private fun sanitize(text: String): String =
        text
            .replace(HTML_TAG, "")
            .replace(ASS_TAG, "")
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()

    private fun toMs(hours: String, minutes: String, seconds: String, fraction: String): Long {
        val h = hours.toLongOrNull() ?: 0L
        val m = minutes.toLongOrNull() ?: 0L
        val s = seconds.toLongOrNull() ?: 0L
        val millis = when (fraction.length) {
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).padEnd(3, '0').toLong()
        }
        return ((h * 60 + m) * 60 + s) * 1000 + millis
    }
}
