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
    val MAX_IMAGE_BYTES = com.group16.dinoblog.model.MediaPolicy.IMAGE_MAX.toInt()
    const val IMAGE_CHUNK_BYTES = com.group16.dinoblog.model.MediaPolicy.CHUNK_BYTES
    const val MAX_IMAGE_EDGE = 2048
    const val MAX_RELAY_MEDIA_BYTES = 50L * 1024 * 1024
}
