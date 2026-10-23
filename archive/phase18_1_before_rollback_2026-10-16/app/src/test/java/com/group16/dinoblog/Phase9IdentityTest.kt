package com.group16.dinoblog

import com.group16.dinoblog.model.DisplayNames
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.network.WireProtocol
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

class Phase9IdentityTest {
    @Test fun `names are Unicode safe normalized and bounded`() {
        assertEquals("Nate", DisplayNames.normalize("  Nate  "))
        assertEquals("พี่ วิลเลียม 😊", DisplayNames.normalize("  พี่  วิลเลียม\n😊  "))
        assertNull(DisplayNames.normalize(" \n\t "))
        assertNull(DisplayNames.normalize("a".repeat(33)))
        assertEquals(32, DisplayNames.normalize("a".repeat(32))!!.length)
    }

    @Test fun `alias wins locally and removing it reveals latest remote name`() {
        val id = UUID.randomUUID().toString()
        val remote = Peer(id, "William", 1, "P'William")
        assertEquals("P'William", remote.displayName)
        assertEquals("P'William", remote.copy(name = "Will").displayName)
        assertEquals("Will", remote.copy(name = "Will", localAlias = null).displayName)
        assertEquals("DinoBlog User", Peer(id, "", 1).displayName)
    }

    @Test fun `HELLO carries app name while routing identity remains UUID`() {
        val id = UUID.randomUUID().toString()
        val packet = WirePacket(PacketType.HELLO, deviceId = id, displayName = "Nate")
        val buffer = ByteArrayOutputStream()
        WireProtocol.write(DataOutputStream(buffer), packet)
        val json = buffer.toByteArray().toString(Charsets.UTF_8)
        assertTrue(json.contains("\"displayName\":\"Nate\""))
        assertFalse(json.contains("profile.jpg"))
        assertFalse(json.contains("localAlias"))
        val decoded = WireProtocol.read(DataInputStream(ByteArrayInputStream(buffer.toByteArray())))
        assertEquals(id, decoded.deviceId)
        assertEquals("Nate", decoded.displayName)
        assertNull(decoded.deviceName)
        val malformed = """{"packetType":"HELLO","deviceId":"$id","displayName":123}""".toByteArray()
        val framed = ByteArrayOutputStream()
        DataOutputStream(framed).use { it.writeInt(malformed.size); it.write(malformed) }
        assertNull(WireProtocol.read(DataInputStream(ByteArrayInputStream(framed.toByteArray()))).displayName)
    }
}
