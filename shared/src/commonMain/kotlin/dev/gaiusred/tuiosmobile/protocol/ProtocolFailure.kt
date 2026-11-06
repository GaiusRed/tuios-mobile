package dev.gaiusred.tuiosmobile.protocol

import kotlinx.serialization.json.JsonObject

sealed interface ProtocolFailure {
    data class Daemon(val code: String, val message: String, val hint: JsonObject? = null) : ProtocolFailure
    data class Malformed(val reason: String) : ProtocolFailure
    data class Oversized(val limitBytes: Int) : ProtocolFailure
    data class Transport(val reason: String) : ProtocolFailure
    data object Timeout : ProtocolFailure
}

sealed interface MutationOutcome {
    data class Accepted(val receipt: MutationReceipt) : MutationOutcome
    data class Rejected(val failure: ProtocolFailure) : MutationOutcome
    data object Uncertain : MutationOutcome
}

data class MutationReceipt(val type: String, val raw: JsonObject,
    val messageId: ULong? = null, val verifiedHuman: Boolean? = null,
    val applied: Boolean? = null, val settledBy: String? = null)
