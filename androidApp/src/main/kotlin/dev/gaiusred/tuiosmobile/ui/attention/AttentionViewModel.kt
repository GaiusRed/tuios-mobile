package dev.gaiusred.tuiosmobile.ui.attention

import dev.gaiusred.tuiosmobile.attention.*
import dev.gaiusred.tuiosmobile.protocol.*
import dev.gaiusred.tuiosmobile.sessions.AgentTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AttentionViewModel(private val repository: AttentionRepository, val target: AgentTarget,
    private val scope: CoroutineScope) {
    val items = repository.observe(target).stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val message = MutableStateFlow<String?>(null)
    val problem = combine(message, repository.problem(target)) { action, read -> action ?: read }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)
    private val busy = MutableStateFlow(false)
    val sending = busy.asStateFlow()

    fun refresh() { scope.launch { repository.refresh(target) } }
    fun respond(response: HumanResponse) {
        if (busy.value) return
        busy.value = true
        message.value = null
        scope.launch {
            try {
                message.value = when (val outcome = repository.respond(response)) {
                    is MutationOutcome.Accepted -> if (outcome.receipt.settledBy == "timeout")
                        "Response sent. The agent has not settled yet. Refresh to check its state." else "Response applied."
                    is MutationOutcome.Rejected -> (outcome.failure as? ProtocolFailure.Daemon)?.message
                        ?: "Response was rejected. Refresh the current request."
                    MutationOutcome.Uncertain -> "The response may have been applied. Refresh to check; it will not be sent again."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message.value = "Response could not be completed. Check the saved attempt before answering again." }
            finally { busy.value = false }
        }
    }
}
