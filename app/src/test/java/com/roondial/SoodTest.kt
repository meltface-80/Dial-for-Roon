package com.roondial

import com.roondial.roon.Sood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class SoodTest {

    private val outDir = File("build/moo-samples").apply { mkdirs() }

    @Test
    fun buildsWellFormedQuery() {
        val q = Sood.buildQuery()
        File(outDir, "sood-query.bin").writeBytes(q)

        assertEquals("SOOD", String(q, 0, 4, Charsets.UTF_8))
        assertEquals(2, q[4].toInt())
        assertEquals('Q', q[5].toInt().toChar())

        // Walk the TLVs the same way sood.js does.
        val props = HashMap<String, String>()
        var pos = 6
        while (pos < q.size) {
            val nameLen = q[pos++].toInt() and 0xFF
            val name = String(q, pos, nameLen, Charsets.UTF_8)
            pos += nameLen
            val valLen = ((q[pos++].toInt() and 0xFF) shl 8) or (q[pos++].toInt() and 0xFF)
            props[name] = String(q, pos, valLen, Charsets.UTF_8)
            pos += valLen
        }
        assertEquals("00720724-5143-4a9b-abac-0e50cba674bb", props["query_service_id"])
        assertEquals(36, props["_tid"]?.length)
    }

    @Test
    fun parsesReply() {
        val reply = buildReply(
            "service_id" to "00720724-5143-4a9b-abac-0e50cba674bb",
            "unique_id" to "b2c1f0de-1111-2222-3333-444455556666",
            "http_port" to "9330",
            "name" to "Living Room Core"
        )
        File(outDir, "sood-reply.bin").writeBytes(reply)

        val props = Sood.parseReply(reply, reply.size)!!
        assertEquals("9330", props["http_port"])
        assertEquals("b2c1f0de-1111-2222-3333-444455556666", props["unique_id"])
        assertEquals("Living Room Core", props["name"])
    }

    @Test
    fun handlesNullAndEmptyValues() {
        val out = ByteArrayOutputStream()
        out.write("SOOD".toByteArray())
        out.write(2)
        out.write('R'.code)
        writeName(out, "empty"); out.write(0); out.write(0)
        writeName(out, "nothing"); out.write(0xFF); out.write(0xFF)
        val buf = out.toByteArray()

        val props = Sood.parseReply(buf, buf.size)!!
        assertEquals("", props["empty"])
        assertNull(props["nothing"])
    }

    @Test
    fun ignoresQueriesAndForeignPackets() {
        val query = Sood.buildQuery()
        assertNull(Sood.parseReply(query, query.size))
        assertNull(Sood.parseReply("NOPE".toByteArray(), 4))
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        val n = name.toByteArray()
        out.write(n.size)
        out.write(n)
    }

    private fun buildReply(vararg props: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("SOOD".toByteArray())
        out.write(2)
        out.write('R'.code)
        for ((name, value) in props) {
            writeName(out, name)
            val v = value.toByteArray()
            out.write((v.size shr 8) and 0xFF)
            out.write(v.size and 0xFF)
            out.write(v)
        }
        return out.toByteArray()
    }
}
