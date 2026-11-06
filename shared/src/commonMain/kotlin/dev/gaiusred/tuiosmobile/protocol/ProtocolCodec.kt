package dev.gaiusred.tuiosmobile.protocol

import kotlinx.serialization.json.*

sealed interface WireMessage {
    data class Reply(val id: Long, val result: DaemonResult) : WireMessage
    data class Error(val id: Long, val failure: ProtocolFailure) : WireMessage
    data class Invalid(val failure: ProtocolFailure) : WireMessage
    data class Event(val event: DaemonEvent) : WireMessage
}

class ProtocolCodec(val maxLineBytes: Int = 4 * 1024 * 1024) {
    init { require(maxLineBytes > 0) }
    private var buffer = ByteArray(minOf(4096, maxLineBytes))
    private var bufferedBytes = 0
    private var discarding = false

    /** Buffer bytes until LF, so a split multi-byte UTF-8 character is decoded once. */
    fun feed(bytes: ByteArray): List<WireMessage> = buildList {
        for (byte in bytes) {
            if (byte == 10.toByte()) {
                if (!discarding) add(decode(buffer.copyOf(bufferedBytes)))
                bufferedBytes = 0
                discarding = false
            } else if (!discarding) {
                if (bufferedBytes == maxLineBytes) {
                    bufferedBytes = 0
                    discarding = true
                    add(WireMessage.Invalid(ProtocolFailure.Oversized(maxLineBytes)))
                } else {
                    if (bufferedBytes == buffer.size) buffer = buffer.copyOf(minOf(maxLineBytes, buffer.size * 2))
                    buffer[bufferedBytes++] = byte
                }
            }
        }
    }

    fun endOfInput(): List<WireMessage> {
        val incomplete = bufferedBytes > 0 || discarding
        bufferedBytes = 0
        discarding = false
        buffer = ByteArray(minOf(4096, maxLineBytes))
        return if (incomplete) listOf(WireMessage.Invalid(ProtocolFailure.Transport("Incomplete JSON line"))) else emptyList()
    }

    fun encode(id: Long, request: DaemonRequest): ByteArray = encodeRequest(id, request)

    private fun decode(bytes: ByteArray): WireMessage = try {
        val record = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        if ("id" in record) {
            val id = record.long("id")
            require(("result" in record) != ("error" in record))
            if ("error" in record) {
                val error = record.getValue("error").jsonObject
                WireMessage.Error(id, ProtocolFailure.Daemon(error.string("code"), error.string("message"),
                    error["hint"]?.let { if (it == JsonNull) null else it.jsonObject }))
            } else WireMessage.Reply(id, decodeResult(record.getValue("result").jsonObject))
        } else {
            WireMessage.Event(DaemonEvent.Record(record.string("type"), record,
                record.optionalULong("seq"), record.optionalString("boot_id"), record.optionalString("reason"),
                record.optionalString("cursor"), record.optionalString("session"), record.optionalString("window"),
                record.optionalLong("time")))
        }
    } catch (_: Exception) {
        // Parser exception text can include private message text or human nonces.
        WireMessage.Invalid(ProtocolFailure.Malformed("Invalid JSON record"))
    }
}
