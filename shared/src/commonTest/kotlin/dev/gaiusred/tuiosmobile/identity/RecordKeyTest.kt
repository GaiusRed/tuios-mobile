package dev.gaiusred.tuiosmobile.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import dev.gaiusred.tuiosmobile.storage.CachedContent
import dev.gaiusred.tuiosmobile.storage.CachedEntry
import dev.gaiusred.tuiosmobile.storage.HistoryBatch
import kotlin.time.Instant

class RecordKeyTest {
    @Test fun reusedPaneDoesNotJoinOldTranscript() {
        val old = key("boot-1", "transcript-1")
        assertNotEquals(old, key("boot-1", "transcript-2"))
    }
    @Test fun daemonRestartDoesNotJoinOldTranscript() {
        assertNotEquals(key("boot-1", "transcript-1"), key("boot-2", "transcript-1"))
    }
    @Test fun sameIdentifiersRemainStableAndHostsStaySeparate() {
        assertEquals(key("boot-1", "transcript-1"), key("boot-1", "transcript-1"))
        assertNotEquals(key("boot-1", "transcript-1"),
            RecordKey(HostId("host-2"), "stable-session-id", "pane-1", "boot-1", "transcript-1"))
    }
    @Test fun transcriptBatchRequiresExplicitGeneration() {
        val key = RecordKey(HostId("host-1"), "session-id", "pane-id", "boot-1", null)
        assertFailsWith<IllegalArgumentException> {
            HistoryBatch(key, listOf(CachedEntry("1", CachedContent.Transcript("assistant", "text", "hello"),
                Instant.fromEpochMilliseconds(0))))
        }
    }
    private fun key(boot: String, transcript: String) =
        RecordKey(HostId("host-1"), "stable-session-id", "pane-1", boot, transcript)
}
