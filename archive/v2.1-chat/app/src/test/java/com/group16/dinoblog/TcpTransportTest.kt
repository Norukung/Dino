package com.group16.dinoblog

import com.group16.dinoblog.network.TcpTransport
import com.group16.dinoblog.network.TextFrameCodec
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.DataOutputStream
import java.net.Socket
import java.net.SocketTimeoutException

class TcpTransportTest {
    private val TcpTransport.received get() = messages.map { history -> history.filterNot { it.outgoing } }

    @Test fun `free text chat preserves Thai emoji newlines and both sides of the conversation`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val a = TcpTransport(scope, NetworkLogger())
        val b = TcpTransport(scope, NetworkLogger())
        try {
            withTimeout(10_000) {
                a.start(true, "127.0.0.1")
                a.state.first { it.status.startsWith("Listening") }
                b.start(false, "127.0.0.1")
                a.state.first { it.connected }
                b.state.first { it.connected }
                val question = "สวัสดี 🦖\nวันนี้เป็นอย่างไรบ้าง?  "
                val answer = "สบายดีครับ! 😊"
                assertTrue(a.sendText(question))
                b.messages.first { it.size == 1 }
                assertTrue(b.sendText(answer))
                a.messages.first { it.size == 2 }
                // Sending the same text twice is allowed and produces distinct bubbles.
                assertTrue(a.sendText(question))
                b.messages.first { it.size == 3 }
                assertEquals(listOf(question, answer, question), a.messages.value.map { it.text })
                assertEquals(listOf(question, answer, question), b.messages.value.map { it.text })
                assertEquals(listOf(true, false, true), a.messages.value.map { it.outgoing })
                assertEquals(listOf(false, true, false), b.messages.value.map { it.outgoing })
                assertEquals(3, a.messages.value.map { it.id }.distinct().size)
                assertTrue(a.messages.value.all { it.timestamp > 0 })
            }
        } finally { a.stop(); b.stop(); scope.cancel() }
    }

    @Test fun `invalid input preserves connection and failed sends create no sent bubble`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val a = TcpTransport(scope, NetworkLogger())
        val b = TcpTransport(scope, NetworkLogger())
        try {
            withTimeout(10_000) {
                a.start(true, "127.0.0.1")
                a.state.first { it.status.startsWith("Listening") }
                b.start(false, "127.0.0.1")
                a.state.first { it.connected }
                b.state.first { it.connected }
                for (invalid in listOf("   \n", "ก".repeat(30_000))) {
                    try { a.sendText(invalid); fail("Expected invalid text to be rejected") }
                    catch (_: IllegalArgumentException) { }
                }
                assertTrue(a.state.value.connected)
                assertTrue(a.messages.value.isEmpty())
                assertTrue(a.sendText("Still connected"))
                b.messages.first { it.isNotEmpty() }
                b.stop()
                a.state.first { !it.connected }
                assertFalse(a.sendText("Must not appear as sent"))
                assertEquals(listOf("Still connected"), a.messages.value.map { it.text })
            }
        } finally { a.stop(); b.stop(); scope.cancel() }
    }

    @Test fun `two real sockets send Hello Dinoblog in either direction`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val a = TcpTransport(scope, NetworkLogger())
        val b = TcpTransport(scope, NetworkLogger())
        try {
            withTimeout(10_000) {
                a.start(true, "127.0.0.1")
                a.state.first { it.status.startsWith("Listening") }
                b.start(false, "127.0.0.1")
                a.state.first { it.connected }
                b.state.first { it.connected }
                assertTrue(a.sendText(TextFrameCodec.HELLO))
                assertEquals("Hello Dinoblog", b.received.first { it.isNotEmpty() }.single().text)
                assertTrue(b.sendText(TextFrameCodec.HELLO))
                assertEquals("Hello Dinoblog", a.received.first { it.isNotEmpty() }.single().text)
                b.stop()
                a.state.first { !it.connected }
                assertFalse(a.sendText("Disconnected"))
            }
        } finally { a.stop(); b.stop(); scope.cancel() }
    }

    @Test fun `receiving displays text without writing any ACK or handshake`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val receiver = TcpTransport(scope, NetworkLogger())
        try {
            withTimeout(10_000) {
                receiver.start(true, "127.0.0.1")
                receiver.state.first { it.status.startsWith("Listening") }
                Socket("127.0.0.1", TextFrameCodec.PORT).use { sender ->
                    sender.soTimeout = 250
                    TextFrameCodec.write(DataOutputStream(sender.getOutputStream()), "Hello Dinoblog")
                    assertEquals("Hello Dinoblog", receiver.received.first { it.isNotEmpty() }.single().text)
                    assertThrows(SocketTimeoutException::class.java) { sender.getInputStream().read() }
                }
            }
        } finally { receiver.stop(); scope.cancel() }
    }

    @Test fun `failed connection stays failed until explicit manual start and offline text is never queued`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = TcpTransport(scope, NetworkLogger())
        val server = TcpTransport(scope, NetworkLogger())
        try {
            withTimeout(15_000) {
                assertFalse(client.sendText("Offline text"))
                client.start(false, "127.0.0.1")
                client.state.first { it.status.startsWith("TCP error") }
                server.start(true, "127.0.0.1")
                server.state.first { it.status.startsWith("Listening") }
                // Even another identical Android connection event must not retry.
                client.start(false, "127.0.0.1")
                delay(300)
                assertFalse(client.state.value.connected)
                assertFalse(server.state.value.connected)
                client.start(false, "127.0.0.1", force = true)
                client.state.first { it.connected }
                server.state.first { it.connected }
                assertTrue(client.sendText(TextFrameCodec.HELLO))
                assertEquals(listOf("Hello Dinoblog"), server.received.first { it.isNotEmpty() }.map { it.text })
            }
        } finally { client.stop(); server.stop(); scope.cancel() }
    }

    @Test fun `stopping a listening server releases the port for explicit restart`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = TcpTransport(scope, NetworkLogger())
        val client = TcpTransport(scope, NetworkLogger())
        try {
            withTimeout(10_000) {
                server.start(true, "127.0.0.1")
                server.state.first { it.status.startsWith("Listening") }
                server.stop()
                server.start(true, "127.0.0.1")
                server.state.first { it.status.startsWith("Listening") }
                client.start(false, "127.0.0.1")
                server.state.first { it.connected }
                client.state.first { it.connected }
                assertTrue(client.sendText(TextFrameCodec.HELLO))
                server.received.first { it.isNotEmpty() }
            }
        } finally { server.stop(); client.stop(); scope.cancel() }
    }
}
