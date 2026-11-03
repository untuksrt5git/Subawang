package com.example.suboverlay

import org.junit.Assert.assertEquals
import org.junit.Test

/** Uji regresi: tag gaya harus dibuang tanpa membuat pola regex gagal dikompilasi. */
class SrtParserTagTest {

    @Test
    fun membuang_tag_ass() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            {\an8}{\i1}Halo{\i0} dunia
        """.trimIndent()

        val cues = SrtParser.parse(srt)
        assertEquals(1, cues.size)
        assertEquals("Halo dunia", cues[0].text)
    }

    @Test
    fun membuang_tag_html() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            <i>Miring</i> dan <b>tebal</b>
        """.trimIndent()

        val cues = SrtParser.parse(srt)
        assertEquals("Miring dan tebal", cues[0].text)
    }

    @Test
    fun kurung_kurawal_tanpa_tag_tetap_utuh() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            Catatan {penting} hari ini
        """.trimIndent()

        val cues = SrtParser.parse(srt)
        assertEquals("Catatan hari ini", cues[0].text)
    }
}
