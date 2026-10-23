package com.group16.dinoblog.model

/** Developer controlled limits shared by preparation, DTN admission and online sync. */
object MediaPolicy {
    const val CHUNK_BYTES = 128 * 1024
    const val IMAGE_MAX = 5L * 1024 * 1024
    const val AUDIO_DTN_MAX = 10L * 1024 * 1024
    const val AUDIO_ONLINE_MAX = 25L * 1024 * 1024
    const val VIDEO_DTN_MAX = 15L * 1024 * 1024
    const val VIDEO_BLUETOOTH_MAX = 10L * 1024 * 1024
    const val VIDEO_ONLINE_MAX = 50L * 1024 * 1024
    val imageMimes = setOf("image/jpeg", "image/png", "image/webp")
    val audioMimes = setOf("audio/mp4", "audio/aac", "audio/mpeg", "audio/ogg")
    val videoMimes = setOf("video/mp4")

    fun isMedia(type: MessageContentType) = type != MessageContentType.TEXT
    fun mimes(type: MessageContentType) = when (type) {
        MessageContentType.IMAGE -> imageMimes
        MessageContentType.AUDIO -> audioMimes
        MessageContentType.VIDEO -> videoMimes
        MessageContentType.TEXT -> emptySet()
    }
    fun onlineMax(type: MessageContentType) = when (type) {
        MessageContentType.IMAGE -> IMAGE_MAX
        MessageContentType.AUDIO -> AUDIO_ONLINE_MAX
        MessageContentType.VIDEO -> VIDEO_ONLINE_MAX
        MessageContentType.TEXT -> 0L
    }
    fun dtnMax(type: MessageContentType) = when (type) {
        MessageContentType.IMAGE -> IMAGE_MAX
        MessageContentType.AUDIO -> AUDIO_DTN_MAX
        MessageContentType.VIDEO -> VIDEO_DTN_MAX
        MessageContentType.TEXT -> 0L
    }
    fun dtnAllowed(type: MessageContentType, size: Long) = type == MessageContentType.TEXT ||
        size in 1..dtnMax(type)
    fun bluetoothAllowed(type: MessageContentType, size: Long) = type == MessageContentType.TEXT ||
        size in 1..minOf(dtnMax(type), if (type == MessageContentType.VIDEO) VIDEO_BLUETOOTH_MAX else Long.MAX_VALUE)
    fun deliveryMode(type: MessageContentType, size: Long) =
        if (dtnAllowed(type, size)) "HYBRID" else "ONLINE_ONLY"
}
