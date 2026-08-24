package com.roondial

import com.roondial.roon.Moo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class MooTest {

    private val outDir = File("build/moo-samples").apply { mkdirs() }

    @Test
    fun encodesRequestWithBody() {
        val body = """{"output_id":"1701","how":"relative_step","value":1}"""
        val bytes = Moo.encode(
            Moo.VERB_REQUEST,
            "com.roonlabs.transport:2/change_volume",
            7,
            body.toByteArray()
        )
        File(outDir, "request.bin").writeBytes(bytes)

        val text = bytes.toString(Charsets.UTF_8)
        assertEquals(
            "MOO/1 REQUEST com.roonlabs.transport:2/change_volume\n" +
                "Request-Id: 7\n" +
                "Content-Length: ${body.toByteArray().size}\n" +
                "Content-Type: application/json\n" +
                "\n" +
                body,
            text
        )
    }

    @Test
    fun encodesResponseWithoutBody() {
        val bytes = Moo.encode(Moo.VERB_COMPLETE, "Success", 3)
        File(outDir, "complete.bin").writeBytes(bytes)
        assertEquals("MOO/1 COMPLETE Success\nRequest-Id: 3\n\n", bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun roundTripsRequest() {
        val body = """{"a":1}""".toByteArray()
        val bytes = Moo.encode(Moo.VERB_REQUEST, "com.roonlabs.ping:1/ping", 42, body)
        val msg = Moo.parse(bytes)!!
        assertEquals(Moo.VERB_REQUEST, msg.verb)
        assertEquals("com.roonlabs.ping:1", msg.service)
        assertEquals("ping", msg.name)
        assertEquals("42", msg.requestId)
        assertArrayEquals(body, msg.body)
    }

    @Test
    fun parsesContinueChanged() {
        val body = """{"zones_changed":[{"zone_id":"16","state":"playing"}]}"""
        val raw = "MOO/1 CONTINUE Changed\n" +
            "Request-Id: 12\n" +
            "Content-Length: ${body.toByteArray().size}\n" +
            "Content-Type: application/json\n" +
            "\n" + body
        val msg = Moo.parse(raw.toByteArray())!!
        assertEquals(Moo.VERB_CONTINUE, msg.verb)
        assertEquals("Changed", msg.name)
        assertNull(msg.service)
        assertEquals("12", msg.requestId)
        assertEquals(body, msg.bodyText)
    }

    @Test
    fun respectsContentLengthOverEmbeddedNewlines() {
        // A JSON body may contain LF; the parser must trust Content-Length and
        // not treat those bytes as header boundaries.
        val body = "{\"line1\":\"a\\nb\",\"raw\":\"x\ny\"}"
        val bytes = Moo.encode(Moo.VERB_CONTINUE, "Changed", 1, body.toByteArray())
        val msg = Moo.parse(bytes)!!
        assertEquals(body, msg.bodyText)
    }

    @Test
    fun parsesBodylessResponse() {
        val msg = Moo.parse("MOO/1 COMPLETE Success\nRequest-Id: 9\n\n".toByteArray())!!
        assertEquals("Success", msg.name)
        assertEquals("9", msg.requestId)
        assertNull(msg.body)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Moo.parse(ByteArray(0)))
        assertNull(Moo.parse("hello\n\n".toByteArray()))
        assertNull(Moo.parse("MOO/1 COMPLETE Success\n".toByteArray()))
    }

    @Test
    fun keepsUnknownHeaders() {
        val msg = Moo.parse("MOO/1 COMPLETE Success\nRequest-Id: 4\nLogging: quiet\n\n".toByteArray())!!
        assertEquals("quiet", msg.headers["Logging"])
    }
}
