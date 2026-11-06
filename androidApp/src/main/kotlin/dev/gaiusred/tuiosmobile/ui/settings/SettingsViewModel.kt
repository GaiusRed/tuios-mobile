package dev.gaiusred.tuiosmobile.ui.settings

import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.storage.RetentionPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class CapabilitySummary(val hostId: HostId, val daemonVersion: String, val verbs: Set<String>)
enum class SettingsResult { Saved, Cleared, Removed, Paired, InvalidInvitation, Rejected, VerificationFailed, Uncertain, Failed }

interface SettingsActions {
    val hosts: StateFlow<List<HostProfile>>
    val selectedHostId: StateFlow<HostId?>
    val hostLoadProblem: StateFlow<HostLoadProblem?>
    val connection: StateFlow<ConnectionState>
    val retention: StateFlow<RetentionPolicy>
    val pairingProgress: StateFlow<PairingProgress>
    val pairingUncertain: StateFlow<Boolean>
    val capabilities: StateFlow<CapabilitySummary?>
    val cachedHostIds: StateFlow<List<HostId>>
    fun selectHost(id: HostId)
    suspend fun reload()
    suspend fun changeRetention(policy: RetentionPolicy): SettingsResult
    suspend fun clearHistory(id: HostId): SettingsResult
    suspend fun removeHost(id: HostId, credentials: Boolean, cache: Boolean): SettingsResult
    suspend fun pair(invitation: PairInvitation, repair: HostId?): SettingsResult
    suspend fun checkCapabilities(): SettingsResult
    suspend fun clearAllHistory(): SettingsResult
    suspend fun resetConnections(): SettingsResult
    suspend fun removeAllLocalData(): SettingsResult
}

class SettingsViewModel(val actions: SettingsActions, private val scope: CoroutineScope) {
    private val working = MutableStateFlow(false)
    val busy = working.asStateFlow()
    private val notice = MutableStateFlow<SettingsResult?>(null)
    private var pairingJob: Job? = null
    val result = notice.asStateFlow()
    fun reportFailure() { notice.value = SettingsResult.Failed }
    fun pair(invitation: PairInvitation, repair: HostId?) {
        pairingJob = launchAction { pair(invitation, repair) } ?: return
        pairingJob?.invokeOnCompletion { if (it is CancellationException) notice.value = SettingsResult.Uncertain }
    }
    fun onBackground() { pairingJob?.cancel() }
    fun run(action: suspend SettingsActions.() -> SettingsResult) {
        launchAction(action)
    }
    private fun launchAction(action: suspend SettingsActions.() -> SettingsResult): Job? {
        if (working.value) return null
        working.value = true; notice.value = null
        return scope.launch {
            try { notice.value = actions.action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice.value = SettingsResult.Failed }
            finally { working.value = false }
        }
    }
}
