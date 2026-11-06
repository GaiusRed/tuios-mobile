package dev.gaiusred.tuiosmobile.settings

import dev.gaiusred.tuiosmobile.storage.*
import dev.gaiusred.tuiosmobile.sync.TestHistoryStore
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Instant

class RetentionSettingsTest {
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    @Test fun retentionDefaultsToThirtyDays() = runTest {
        val pruned = mutableListOf<Pair<RetentionPolicy, Instant>>()
        val store = object : HistoryStore by TestHistoryStore() {
            override suspend fun prune(policy: RetentionPolicy, now: Instant) { pruned += policy to now }
        }
        val settings = RetentionSettings(store, persist = {}, now = { now })
        settings.maintain()
        assertEquals(listOf(RetentionPolicy.ThirtyDays to now), pruned)
    }
    @Test fun retentionChangeNeverSendsNetworkMutation() = runTest {
        val pruned = mutableListOf<RetentionPolicy>()
        val persisted = mutableListOf<RetentionPolicy>()
        val store = object : HistoryStore by TestHistoryStore() {
            override suspend fun prune(policy: RetentionPolicy, now: Instant) { pruned += policy }
        }
        // No transport is supplied: changing retention can only affect local storage.
        val settings = RetentionSettings(store, persist = { persisted += it }, now = { now })
        settings.change(RetentionPolicy.SevenDays)
        settings.change(RetentionPolicy.Forever)
        assertEquals(listOf(RetentionPolicy.SevenDays, RetentionPolicy.Forever), pruned)
        assertEquals(pruned, persisted)
        assertEquals(RetentionPolicy.Forever, settings.policy.value)
    }
}
