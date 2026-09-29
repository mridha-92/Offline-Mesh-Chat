package com.example.core.model

enum class TransportType {
    AUTO,
    BLUETOOTH,
    WI_FI_DIRECT
}

enum class DeliveryStatus {
    QUEUED,
    DISCOVERING,
    ROUTING,
    SENDING,
    RELAYED,
    DELIVERED,
    READ,
    FAILED,
    EXPIRED
}

enum class PacketType(val code: Byte) {
    HELLO(1),
    PEER_ADVERTISEMENT(2),
    MESSAGE(3),
    ACK(4),
    ROUTE_DISCOVERY(5);

    companion object {
        fun fromCode(code: Byte): PacketType = entries.firstOrNull { it.code == code } ?: MESSAGE
    }
}

enum class AckType {
    RECEIVED,
    RELAYED,
    DELIVERED,
    READ
}
