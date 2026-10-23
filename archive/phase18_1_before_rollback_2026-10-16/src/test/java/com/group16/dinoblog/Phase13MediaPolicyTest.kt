package com.group16.dinoblog

import com.group16.dinoblog.model.MediaManifest
import com.group16.dinoblog.model.MediaPolicy
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.network.WireProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class Phase13MediaPolicyTest {
    @Test fun boundedEligibilityPreservesImageAndKeepsLargeVideoOnlineOnly() {
        assertTrue(MediaPolicy.dtnAllowed(MessageContentType.IMAGE, MediaPolicy.IMAGE_MAX))
        assertTrue(MediaPolicy.dtnAllowed(MessageContentType.AUDIO, MediaPolicy.AUDIO_DTN_MAX))
        assertFalse(MediaPolicy.dtnAllowed(MessageContentType.AUDIO, MediaPolicy.AUDIO_DTN_MAX + 1))
        assertTrue(MediaPolicy.dtnAllowed(MessageContentType.VIDEO, MediaPolicy.VIDEO_DTN_MAX))
        assertEquals("ONLINE_ONLY", MediaPolicy.deliveryMode(MessageContentType.VIDEO,
            MediaPolicy.VIDEO_DTN_MAX + 1))
        assertEquals(50L * 1024 * 1024, MediaPolicy.onlineMax(MessageContentType.VIDEO))
    }

    @Test fun onlineOnlyVideoManifestCannotBeOfferedAsDtnManifest() {
        val size = MediaPolicy.VIDEO_DTN_MAX + 1
        val manifest = MediaManifest(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            "video/mp4", "video", size, "a".repeat(64), MediaPolicy.CHUNK_BYTES,
            ((size + MediaPolicy.CHUNK_BYTES - 1) / MediaPolicy.CHUNK_BYTES).toInt(),
            MessageContentType.VIDEO, 1000L, 640, 480)
        WireProtocol.validateManifest(manifest, allowOnlineOnly = true)
        assertTrue(runCatching { WireProtocol.validateManifest(manifest) }.isFailure)
    }

    @Test fun bluetoothUsesExistingImageAudioLimitsAndSmallerVideoLimit() {
        assertTrue(MediaPolicy.bluetoothAllowed(MessageContentType.TEXT, 0))
        assertTrue(MediaPolicy.bluetoothAllowed(MessageContentType.IMAGE, MediaPolicy.IMAGE_MAX))
        assertTrue(MediaPolicy.bluetoothAllowed(MessageContentType.AUDIO, MediaPolicy.AUDIO_DTN_MAX))
        assertTrue(MediaPolicy.bluetoothAllowed(MessageContentType.VIDEO, MediaPolicy.VIDEO_BLUETOOTH_MAX))
        assertFalse(MediaPolicy.bluetoothAllowed(MessageContentType.VIDEO, MediaPolicy.VIDEO_BLUETOOTH_MAX + 1))
        assertTrue(MediaPolicy.dtnAllowed(MessageContentType.VIDEO, MediaPolicy.VIDEO_BLUETOOTH_MAX + 1))
    }
}
