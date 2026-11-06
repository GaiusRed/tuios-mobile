package dev.gaiusred.tuiosmobile.ui.mail

import dev.gaiusred.tuiosmobile.connection.HostProfile
import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.mail.*
import dev.gaiusred.tuiosmobile.protocol.MutationOutcome
import dev.gaiusred.tuiosmobile.storage.MailDraft
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class MailboxRow(val hostName: String, val snapshot: MailSnapshot)
data class SavedDraftRow(val hostName: String, val draft: MailDraft)

@OptIn(ExperimentalCoroutinesApi::class)
class MailViewModel(private val repository: MailRepository, private val scope: CoroutineScope) {
    private val hosts = MutableStateFlow<List<HostProfile>>(emptyList())
    private val cachedHostIds = MutableStateFlow<List<HostId>>(emptyList())
    private val mutableSending = MutableStateFlow(false)
    val sending = mutableSending.asStateFlow()
    private val mutableProblem = MutableStateFlow<String?>(null)
    val problem = mutableProblem.asStateFlow()
    val mailboxes: StateFlow<List<MailboxRow>> = combine(hosts, cachedHostIds) { profiles, cached -> profiles to cached }
        .flatMapLatest { (profiles, cached) ->
        val names = cached.associateWith { "Saved host" } + profiles.associate { it.id to it.machine }
        if (names.isEmpty()) flowOf(emptyList()) else combine(names.map { (id, name) ->
            repository.mailboxes(id).flatMapLatest { boxes ->
                if (boxes.isEmpty()) flowOf(emptyList()) else combine(boxes.map { box ->
                    repository.observe(box.target).map {
                        MailboxRow(name, if (profiles.none { profile -> profile.id == id })
                            it.copy(readOnly = true, offline = true, recipients = emptyList()) else it)
                    }
                }) { it.toList() }
            }
        }) { it.flatMap { rows -> rows } }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val localDrafts = combine(hosts, cachedHostIds) { profiles, cached ->
        cached.associateWith { "Saved host" } + profiles.associate { it.id to it.machine }
    }.flatMapLatest { names ->
        if (names.isEmpty()) flowOf(emptyList()) else combine(names.map { (id, name) ->
            repository.savedDrafts(id).map { drafts -> drafts.map { SavedDraftRow(name, it) } }
        }) { it.flatMap { rows -> rows } }
    }
    /** Current drafts retain their existing editor/adoption route; every other draft has a local read-only route. */
    val savedDrafts = combine(localDrafts, mailboxes) { drafts, boxes ->
        drafts.filter { row -> row.draft.text.isNotBlank() && boxes.none { box ->
            val target = box.snapshot.target
            !box.snapshot.readOnly && row.draft.hostId == target.hostId && row.draft.sessionId == target.sessionId &&
                (row.draft.daemonBootId == null || row.draft.daemonBootId == target.daemonBootId)
        } }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun savedDraft(target: MailDraftTarget) = repository.savedDraft(target)

    fun setHosts(value: List<HostProfile>) { hosts.value = value }
    fun setCachedHosts(ids: List<HostId>) { cachedHostIds.value = ids }
    fun refresh() { scope.launch { mailboxes.value.filter { !it.snapshot.readOnly && !it.snapshot.offline }
        .forEach { repository.refresh(it.snapshot.target) } } }
    fun markRead(target: MailTarget, ids: Set<String>) { scope.launch { repository.markRead(target, ids) } }
    fun save(draft: MailDraft) { scope.launch {
        try { repository.saveDraft(draft) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableProblem.value = "Draft could not be saved on this phone." }
    } }
    fun discard(draft: MailDraft) { scope.launch { repository.discardDraft(draft) } }
    fun adopt(draft: MailDraft, target: MailTarget, recipient: String, onAdopted: () -> Unit) { scope.launch {
        try {
            if (repository.adoptLegacyDraft(draft, target, recipient)) { mutableProblem.value = null; onAdopted() }
            else mutableProblem.value = "Draft could not be moved. Check the current session, recipient, and any existing draft."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableProblem.value = "Draft could not be moved. Your saved draft is still on this phone." }
    } }
    fun send(draft: MailDraft) {
        if (mutableSending.value) return
        mutableSending.value = true
        mutableProblem.value = null
        scope.launch {
            try {
                when (repository.send(draft)) {
                    is MutationOutcome.Accepted -> draft.daemonBootId?.let {
                        repository.refresh(MailTarget(draft.hostId, draft.sessionId, it))
                    }
                    is MutationOutcome.Rejected -> mutableProblem.value = "Mail was rejected. Check the current recipient and human Mail permission."
                    MutationOutcome.Uncertain -> Unit
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableProblem.value = "Mail could not be completed. Check the saved attempt before sending again." }
            finally { mutableSending.value = false }
        }
    }
}
