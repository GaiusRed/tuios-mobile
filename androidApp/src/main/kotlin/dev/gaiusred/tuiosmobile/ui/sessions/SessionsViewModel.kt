package dev.gaiusred.tuiosmobile.ui.sessions

import dev.gaiusred.tuiosmobile.connection.HostProfile
import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.sessions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Instant

data class AgentPreview(val text: String, val serverAt: Instant?)
data class SavedConversationRow(val conversation: RetainedConversation, val hostName: String)
data class SessionsUiState(val snapshot: SessionSnapshot = SessionSnapshot(),
    val sessions: List<SessionSummary> = emptyList(),
    val previews: Map<AgentTarget, AgentPreview> = emptyMap(), val saved: List<SavedConversationRow> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModel(private val repository: SessionRepository, private val scope: CoroutineScope) {
    private val hosts = MutableStateFlow<List<HostProfile>>(emptyList())
    private val cachedHostIds = MutableStateFlow<List<HostId>>(emptyList())
    private val query = MutableStateFlow("")
    private val previews = repository.state.map { state -> state.sessions.flatMap { it.agents }.map { it.target } }
        .distinctUntilChanged().flatMapLatest { targets ->
            if (targets.isEmpty()) flowOf(emptyMap()) else combine(targets.map { target ->
                repository.conversation(target).map { conversation ->
                    target to conversation.entries.lastOrNull()?.let { AgentPreview(it.content.text, it.serverAt) }
                }
            }) { rows -> rows.mapNotNull { (target, preview) -> preview?.let { target to it } }.toMap() }
        }
    private val saved = combine(hosts, cachedHostIds, repository.state.map { it.hostId }.distinctUntilChanged()) { profiles, cached, selected ->
        cached.associateWith { "Saved host" } + profiles.map { it.id to it.machine }.toMap() +
            if (selected != null && profiles.none { it.id == selected }) mapOf(selected to "Saved host") else emptyMap()
    }.distinctUntilChanged().flatMapLatest { hostNames ->
        if (hostNames.isEmpty()) flowOf(emptyList()) else combine(hostNames.map { (id, name) ->
            repository.retainedConversations(id).map { rows -> rows.map { SavedConversationRow(it, name) } }
        }) { it.flatMap { rows -> rows } }
    }

    val state = combine(repository.state, previews, saved, query) { snapshot, previews, saved, query ->
        val search = query.trim()
        val sessions = snapshot.sessions.mapNotNull { session ->
            if (search.isBlank() || session.name.contains(search, true)) session else {
                val agents = session.agents.filter {
                    listOf(it.name, it.state, it.harnessId, previews[it.target]?.text.orEmpty()).any { field -> field.contains(search, true) }
                }
                session.copy(agents = agents).takeIf { agents.isNotEmpty() }
            }
        }
        SessionsUiState(snapshot, sessions, previews, saved.filter { row ->
            search.isBlank() || listOf(row.hostName, row.conversation.sessionName, row.conversation.agentName)
                .any { it.contains(search, true) }
        })
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), SessionsUiState(repository.state.value))

    fun setHosts(profiles: List<HostProfile>) { hosts.value = profiles }
    fun setCachedHosts(ids: List<HostId>) { cachedHostIds.value = ids }
    fun search(text: String) { query.value = text }
    fun refresh() { scope.launch { repository.refresh() } }
}
