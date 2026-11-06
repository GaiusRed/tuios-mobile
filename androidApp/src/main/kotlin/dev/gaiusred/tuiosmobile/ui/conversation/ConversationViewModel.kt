package dev.gaiusred.tuiosmobile.ui.conversation

import dev.gaiusred.tuiosmobile.sessions.*
import dev.gaiusred.tuiosmobile.storage.TranscriptAvailability
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Instant

sealed interface ConversationSource {
    val agentName: String
    val sessionName: String
    val hostName: String
    data class Live(val target: AgentTarget, override val agentName: String,
        override val sessionName: String, override val hostName: String) : ConversationSource
    data class Saved(val target: RetainedConversationTarget, override val agentName: String,
        override val sessionName: String, override val hostName: String, val agentSessionId: String) : ConversationSource
}

data class ConversationUiState(val entries: List<ConversationEntry> = emptyList(),
    val availability: TranscriptAvailability = TranscriptAvailability.NotLoaded, val offline: Boolean = true,
    val canLoadOlder: Boolean = false, val savedExists: Boolean = true, val loadingOlder: Boolean = false,
    val pagingFailed: Boolean = false, val lastDownloadedAt: Instant? = null)

class ConversationViewModel(private val repository: SessionRepository, val source: ConversationSource,
    private val scope: CoroutineScope) {
    private val loading = MutableStateFlow(false)
    private val pagingFailed = MutableStateFlow(false)
    private val conversation = when (source) {
        is ConversationSource.Live -> repository.conversation(source.target).map {
            ConversationUiState(it.entries, it.availability, it.offline, it.canLoadOlder)
        }
        is ConversationSource.Saved -> repository.retainedConversation(source.target).map {
            ConversationUiState(it.entries, TranscriptAvailability.Available, savedExists = it.conversation != null,
                lastDownloadedAt = it.conversation?.lastDownloadedAt)
        }
    }
    val state = combine(conversation, loading, pagingFailed) { state, loading, failed ->
        state.copy(loadingOlder = loading, pagingFailed = failed)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

    fun loadOlder() {
        val target = (source as? ConversationSource.Live)?.target ?: return
        if (state.value.offline || !state.value.canLoadOlder || loading.value) return
        loading.value = true
        pagingFailed.value = false
        scope.launch {
            try { repository.loadOlder(target) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { pagingFailed.value = true }
            finally { loading.value = false }
        }
    }
}
