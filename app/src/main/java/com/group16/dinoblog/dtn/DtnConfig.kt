package com.group16.dinoblog.dtn

/** Fixed demonstration policy; branches persist their own budget in Room. */
object DtnConfig {
    const val DEFAULT_MESSAGE_LIFETIME_SECONDS = 86_400L
    const val DEFAULT_HOP_LIMIT = 8
    const val INITIAL_SPRAY_COPIES = 4
    const val MAX_RELAY_BUFFER_MESSAGES = 100
    const val MAX_SPRAY_COPIES = 255
    const val P_INIT = 0.75
    const val BETA = 0.25
    const val GAMMA = 0.98
    const val AGING_INTERVAL_MS = 3_600_000L
    const val FORWARDING_MARGIN = 0.10
    const val MAX_PREDICTABILITY_ENTRIES = 64
    const val MAX_LOCAL_PREDICTABILITIES = 512
    const val MIN_PREDICTABILITY = 0.001
    const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    const val MAX_AUDIO_BYTES = 10 * 1024 * 1024
    const val MAX_VIDEO_BYTES = 15 * 1024 * 1024
    const val MAX_MEDIA_BYTES = MAX_VIDEO_BYTES
    fun maxMediaBytes(type: com.group16.dinoblog.model.MessageContentType): Int = when (type) {
        com.group16.dinoblog.model.MessageContentType.IMAGE -> MAX_IMAGE_BYTES
        com.group16.dinoblog.model.MessageContentType.AUDIO -> MAX_AUDIO_BYTES
        com.group16.dinoblog.model.MessageContentType.VIDEO -> MAX_VIDEO_BYTES
        com.group16.dinoblog.model.MessageContentType.TEXT -> 0
    }
    const val IMAGE_CHUNK_BYTES = 128 * 1024
    const val MAX_IMAGE_EDGE = 2048
    const val MAX_RELAY_MEDIA_BYTES = 50L * 1024 * 1024
    const val EMPTY_MEDIA_PART_TIMEOUT_MS = 5 * 60_000L
}
