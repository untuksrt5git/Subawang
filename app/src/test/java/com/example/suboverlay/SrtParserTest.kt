package com.example.suboverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtParserTest {

    private val srt = """
        1
        00:00:01,000 --> 00:00:03,500
        Halo, ini subtitle pertama.

        2
        00:00:04,000 --> 00:00:06,000
        Baris kedua
        dengan dua baris.
    """.trimIndent()

    @Test
    fun parsesStandardSrt() {
        val cues = SrtParser.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(3500L, cues[0].endMs)
        assertEquals("Halo, ini subtitle pertama.", cues[0].text)
        assertEquals("Baris kedua\ndengan dua baris.", cues[1].text)
    }

    @Test
    fun parsesSrtWithoutMilliseconds() {
        val cues = SrtParser.parse(
            """
            1
            00:00:02 --> 00:00:04
            Tanpa milidetik
            """.trimIndent()
        )
        assertEquals(1, cues.size)
        assertEquals(2000L, cues[0].startMs)
        assertEquals(4000L, cues[0].endMs)
    }

    @Test
    fun parsesWebVttWithShortTimestamps() {
        val vtt = """
            WEBVTT

            00:01.500 --> 00:03.000
            VTT pendek

            01:02:03.250 --> 01:02:05.000
            VTT dengan jam
        """.trimIndent()
        val cues = SrtParser.parse(vtt)
        assertEquals(2, cues.size)
        assertEquals(1500L, cues[0].startMs)
        assertEquals(3000L, cues[0].endMs)
        assertEquals(3723250L, cues[1].startMs)
        assertEquals(3725000L, cues[1].endMs)
    }

    @Test
    fun parsesCrlfAndBomAndDotSeparator() {
        val raw = "\uFEFF1\r\n00:00:01.250 --> 00:00:02.500\r\nTitik dan CRLF\r\n\r\n"
        val cues = SrtParser.parse(raw)
        assertEquals(1, cues.size)
        assertEquals(1250L, cues[0].startMs)
        assertEquals("Titik dan CRLF", cues[0].text)
    }

    @Test
    fun decodesUtf16WithoutBom() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nHalo dunia\n"
        val bytes = text.toByteArray(Charsets.UTF_16LE)
        val cues = SrtParser.parse(SrtParser.decode(bytes))
        assertEquals(1, cues.size)
        assertEquals("Halo dunia", cues[0].text)
    }

    @Test
    fun decodesUtf8WithBomAndCp1252() {
        val utf8 = "\uFEFF1\n00:00:01,000 --> 00:00:02,000\nSudah\u2014dash\n"
        assertEquals(1, SrtParser.parse(SrtParser.decode(utf8.toByteArray(Charsets.UTF_8))).size)

        val cp1252 = byteArrayOf(
            '1'.code.toByte(), '\n'.code.toByte()
        ) + "00:00:01,000 --> 00:00:02,000\n".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x93.toByte(), 'a'.code.toByte(), 0x94.toByte(), '\n'.code.toByte())
        val decoded = SrtParser.parse(SrtParser.decode(cp1252))
        assertEquals(1, decoded.size)
        assertEquals("\u201Ca\u201D", decoded[0].text)
    }

    @Test
    fun stripsHtmlAndAssTags() {
        val cues = SrtParser.parse(
            """
            1
            00:00:01,000 --> 00:00:02,000
            <i>Miring</i> {\an8}normal
            """.trimIndent()
        )
        assertEquals("Miring normal", cues[0].text)
    }

    @Test
    fun sortsOutOfOrderCues() {
        val cues = SrtParser.parse(
            """
            2
            00:00:05,000 --> 00:00:06,000
            Kedua

            1
            00:00:01,000 --> 00:00:02,000
            Pertama
            """.trimIndent()
        )
        assertEquals(listOf("Pertama", "Kedua"), cues.map { it.text })
    }

    @Test
    fun rejectsPlainTextWithoutTimestamps() {
        val cues = SrtParser.parse("Ini hanya catatan biasa.\nTanpa baris waktu sama sekali.")
        assertTrue(cues.isEmpty())
        assertEquals(0, SrtParser.countTimestampLines("Ini hanya catatan biasa."))
    }
}
