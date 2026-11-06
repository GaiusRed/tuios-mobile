package dev.gaiusred.tuiosmobile.settings

import dev.gaiusred.tuiosmobile.storage.HistoryStore
import dev.gaiusred.tuiosmobile.storage.RetentionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant

class RetentionSettings(private val history: HistoryStore, initial: RetentionPolicy = RetentionPolicy.Default,
    private val persist: (RetentionPolicy) -> Unit, private val now: () -> Instant) {
    private val selected = MutableStateFlow(initial)
    val policy = selected.asStateFlow()
    suspend fun change(policy: RetentionPolicy) {
        persist(policy)
        selected.value = policy
        maintain()
    }
    suspend fun maintain() { history.prune(selected.value, now()) }
}
