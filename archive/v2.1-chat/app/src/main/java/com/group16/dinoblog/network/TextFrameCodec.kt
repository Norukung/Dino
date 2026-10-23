package com.group16.dinoblog.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Only a UTF-8 string, with a byte-length prefix; no message envelope or control packets. */
object TextFrameCodec {
    const val PORT = 8988
    const val HELLO = "Hello Dinoblog"
    const val MAX_BYTES = 65_536

    fun write(output: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_BYTES) { "Text must contain 1–65536 UTF-8 bytes" }
        // TCP does not preserve write boundaries. Prefix the UTF-8 byte count (not
        // the character count) so split packets, Unicode and multiple sends work.
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    fun read(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 1..MAX_BYTES) { "Invalid text frame length: $size" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }
}
