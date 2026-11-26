package com.taphive.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

class DnsNameParserTest {
    private val parser = DnsNameParser()

    @Test
    fun parsesAAnswer() {
        val response = dnsResponse(
            question = "example.com",
            answerName = null,
            answerIp = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        )
        assertEquals(mapOf("93.184.216.34" to "example.com"), parser.parse(response, response.size))
    }

    @Test
    fun ignoresTruncatedAnswerAtPayloadBoundary() {
        val header = header(answers = 1)
        val question = question("example.com")
        // A root label is the only byte in the answer name. The old parser
        // read the type/length fields past `length` after readName returned end.
        val truncated = header + question + byteArrayOf(0)
        assertTrue(parser.parse(truncated, truncated.size).isEmpty())
    }

    @Test
    fun ignoresTruncatedQuestion() {
        val response = header(answers = 1) + byteArrayOf(3, 'w'.code.toByte())
        assertTrue(parser.parse(response, response.size).isEmpty())
    }

    @Test
    fun ignoresQueryPackets() {
        val query = header(answers = 1)
        query[2] = 0x01
        query[3] = 0x00
        assertTrue(parser.parse(query, query.size).isEmpty())
    }

    @Test
    fun randomInputNeverThrows() {
        val random = Random(0x5EED)
        repeat(5_000) {
            val bytes = ByteArray(random.nextInt(512))
            random.nextBytes(bytes)
            parser.parse(bytes, bytes.size)
        }
    }

    private fun header(answers: Int): ByteArray {
        val out = ByteArrayOutputStream()
        u16(out, 0x1234)
        u16(out, 0x8180)
        u16(out, 1)
        u16(out, answers)
        u16(out, 0)
        u16(out, 0)
        return out.toByteArray()
    }

    private fun question(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        writeName(out, name)
        u16(out, 1)
        u16(out, 1)
        return out.toByteArray()
    }

    private fun dnsResponse(question: String, answerName: String?, answerIp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header(answers = 1))
        out.write(question(question))
        if (answerName == null) {
            out.write(byteArrayOf(0xC0.toByte(), 0x0C))
        } else {
            writeName(out, answerName)
        }
        u16(out, 1)
        u16(out, 1)
        out.write(byteArrayOf(0, 0, 0, 60))
        u16(out, answerIp.size)
        out.write(answerIp)
        return out.toByteArray()
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
    }

    private fun u16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }
}
