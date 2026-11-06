package dev.gaiusred.tuiosmobile.storage

import dev.gaiusred.tuiosmobile.identity.HostId
import kotlinx.serialization.Serializable

/** Current authoritative listing and generation pointers. No wire objects or presence proof. */
@Serializable
data class SyncCheckpoint(val hostId: HostId, val bootId: String, val eventSeq: ULong? = null,
    val sessions: List<CachedSession> = emptyList(), val agents: List<CachedAgent> = emptyList(),
    val nextGeneration: Long = 0, val receivedSeconds: Long = 0, val receivedNanos: Int = 0)

@Serializable
data class CachedSession(val id: String, val name: String, val windowCount: Int, val attached: Boolean)

@Serializable
data class CachedAgent(val sessionId: String, val windowId: String, val name: String,
    val state: String, val harnessId: String, val agentSessionId: String,
    val needsYou: Boolean, val ready: Boolean, val transcript: TranscriptCheckpoint? = null,
    val availability: TranscriptAvailability = TranscriptAvailability.NotLoaded)

/** All daemon cursors are opaque. Backward paging never changes the forward cursor. */
@Serializable
data class TranscriptCheckpoint(val generation: String, val forward: String?, val older: String?,
    val more: Boolean = false)

@Serializable
enum class TranscriptAvailability { NotLoaded, Available, Unavailable, Failed }
