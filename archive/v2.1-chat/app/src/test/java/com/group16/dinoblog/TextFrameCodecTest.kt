package com.group16.dinoblog

import com.group16.dinoblog.network.TextFrameCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.nio.charset.CharacterCodingException

class TextFrameCodecTest {
    @Test fun `Hello Dinoblog is encoded as standard UTF-8 bytes with a byte length`() {
        val bytes = ByteArrayOutputStream()
        TextFrameCodec.write(DataOutputStream(bytes), TextFrameCodec.HELLO)
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(14, input.readInt())
        val payload = ByteArray(14)
        input.readFully(payload)
        assertArrayEquals("Hello Dinoblog".toByteArray(Charsets.UTF_8), payload)
        assertEquals(-1, input.read())
    }

    @Test fun `Unicode newlines and coalesced frames work with single byte reads`() {
        val texts = listOf("Hello Dinoblog", "สวัสดี 🦖\nsecond line", "One more")
        val bytes = ByteArrayOutputStream()
        texts.forEach { TextFrameCodec.write(DataOutputStream(bytes), it) }
        val fragments = object : FilterInputStream(ByteArrayInputStream(bytes.toByteArray())) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 1))
        }
        val input = DataInputStream(fragments)
        texts.forEach { assertEquals(it, TextFrameCodec.read(input)) }
        assertEquals(-1, input.read())
    }

    @Test fun `invalid lengths and oversized UTF-8 payload are rejected`() {
        for (size in listOf(-1, 0, 65_537, Int.MAX_VALUE)) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).writeInt(size)
            assertThrows(IllegalArgumentException::class.java) { TextFrameCodec.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextFrameCodec.write(DataOutputStream(ByteArrayOutputStream()), "ก".repeat(30_000))
        }
    }

    @Test fun `truncated text cannot be displayed as a complete string`() {
        val bytes = ByteArrayOutputStream()
        TextFrameCodec.write(DataOutputStream(bytes), TextFrameCodec.HELLO)
        assertThrows(EOFException::class.java) {
            TextFrameCodec.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray().dropLast(1).toByteArray())))
        }
    }

    @Test fun `malformed UTF-8 is rejected`() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeInt(1); writeByte(0xFF) }
        assertThrows(CharacterCodingException::class.java) {
            TextFrameCodec.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        }
    }
}
