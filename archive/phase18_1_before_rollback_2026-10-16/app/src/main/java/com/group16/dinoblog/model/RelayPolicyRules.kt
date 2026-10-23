package com.group16.dinoblog.model

/** Rules for original user payloads only. Receipts and recall controls are independent. */
object RelayPolicyRules {
    fun mayOfferToPeer(message: Message, localDeviceId: String, peerId: String): Boolean =
        message.relayPolicy == RelayPolicy.ALLOW_RELAY ||
            (message.sourceId == localDeviceId && message.destinationId == peerId)

    /** A DIRECT_ONLY peer transfer must be source -> final destination on the authenticated session. */
    fun mayAcceptFromPeer(message: Message, authenticatedPeerId: String, localDeviceId: String): Boolean =
        message.relayPolicy == RelayPolicy.ALLOW_RELAY ||
            (message.destinationId == localDeviceId && message.sourceId == authenticatedPeerId)
}
