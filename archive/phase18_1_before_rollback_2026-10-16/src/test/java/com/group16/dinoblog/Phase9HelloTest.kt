package com.group16.dinoblog

import com.group16.dinoblog.network.TcpTransport
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class Phase9HelloTest {
    @Test fun `both peers use UUID identity and exchange current app names`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val aId = UUID.randomUUID().toString()
        val bId = UUID.randomUUID().toString()
        var aName = "Nate"
        var bName = "William"
        val a = TcpTransport(aId, aName, scope, NetworkLogger()) { aName }
        val b = TcpTransport(bId, bName, scope, NetworkLogger()) { bName }
        try {
            a.start(true, "127.0.0.1")
            withTimeout(5_000) { a.state.first { it.status.startsWith("Listening") } }
            b.start(false, "127.0.0.1")
            val seenA = withTimeout(5_000) { a.state.first { it.peerId == bId } }
            val seenB = withTimeout(5_000) { b.state.first { it.peerId == aId } }
            assertEquals("William", seenA.peerName)
            assertEquals("Nate", seenB.peerName)
            assertEquals(bId, seenA.connectedPeerDeviceId)
            assertEquals(aId, seenB.connectedPeerDeviceId)

            a.stop(); b.stop()
            bName = "Will"
            a.start(true, "127.0.0.1")
            withTimeout(5_000) { a.state.first { it.status.startsWith("Listening") } }
            b.start(false, "127.0.0.1")
            assertEquals("Will", withTimeout(5_000) { a.state.first { it.peerId == bId } }.peerName)
        } finally { a.stop(); b.stop(); scope.cancel() }
    }
}
