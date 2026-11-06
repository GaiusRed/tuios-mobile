package dev.gaiusred.tuiosmobile.identity

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class HostId(val value: String) {
    init { require(value.isNotBlank()) { "Host identity must be nonempty" } }
}

/** Stable IDs only; names and process IDs must never be used as these dimensions.
 * A session-wide record (Mail or session metadata) has no pane or transcript ID.
 * Callers allocate a new transcript ID on reset and must never reuse it.
 */
@Serializable
data class RecordKey(val hostId: HostId, val sessionId: String, val paneId: String?,
    val daemonBootId: String, val transcriptId: String?) {
    init {
        require(sessionId.isNotBlank()) { "Session identity must be nonempty" }
        require(daemonBootId.isNotBlank()) { "Daemon generation must be nonempty" }
        require(paneId == null || paneId.isNotBlank()) { "Pane identity must be nonempty" }
        require(transcriptId == null || (paneId != null && transcriptId.isNotBlank())) {
            "Transcript generation requires a pane identity"
        }
    }
}
