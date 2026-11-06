package dev.gaiusred.tuiosmobile.connection

import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.storage.*
import dev.gaiusred.tuiosmobile.settings.RetentionSettings
import dev.gaiusred.tuiosmobile.ui.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import dev.gaiusred.tuiosmobile.protocol.*

internal class SettingsManager(private val coordinator: HostSessionCoordinator,
    private val profiles: HostProfileStore, private val keys: PhoneKeyStore,
    private val hostConnection: ForegroundHostConnection, private val history: HistoryStore,
    private val retentionSettings: RetentionSettings, private val pairing: PairingClient,
    private val scope: CoroutineScope, private val settleWriters: suspend (suspend () -> Unit) -> Unit,
    private val pendingPairing: MutableStateFlow<Boolean> = MutableStateFlow(false),
    private val persistPendingPairing: (Boolean) -> Unit = {}) : SettingsActions {
    override val hosts = coordinator.hosts
    override val selectedHostId = coordinator.selectedHostId
    override val hostLoadProblem = coordinator.hostLoadProblem
    override val connection = hostConnection.state
    override val retention = retentionSettings.policy
    override val pairingProgress = pairing.progress
    override val pairingUncertain = pendingPairing.asStateFlow()
    private val discovered = MutableStateFlow<CapabilitySummary?>(null)
    override val capabilities = discovered.asStateFlow()
    override val cachedHostIds = history.observeHosts().stateIn(scope, SharingStarted.Eagerly, emptyList())
    private var discoveredGeneration: ConnectionGeneration? = null
    init {
        scope.launch {
            combine(connection, selectedHostId) { state, selected -> (state as? ConnectionState.Connected)?.takeIf { it.host.id == selected } }
                .collect { if (it?.generation !== discoveredGeneration) discovered.value = null }
        }
    }
    override fun selectHost(id: HostId) { coordinator.selectHost(id) }
    override suspend fun reload() { coordinator.reloadHosts() }
    override suspend fun changeRetention(policy: RetentionPolicy): SettingsResult {
        coordinator.maintain { settleWriters { retentionSettings.change(policy) } }
        return SettingsResult.Saved
    }
    override suspend fun clearHistory(id: HostId): SettingsResult {
        coordinator.maintain { settleWriters { history.clearHost(id) } }
        return SettingsResult.Cleared
    }
    override suspend fun removeHost(id: HostId, credentials: Boolean, cache: Boolean): SettingsResult {
        coordinator.maintain { settleWriters { withContext(NonCancellable) removal@{
            val profile = profiles.list().find { it.id == id } ?: return@removal
            if (cache) history.clearHost(id)
            if (credentials && profiles.list().none { it.id != id && it.keyAlias == profile.keyAlias }) {
                withContext(Dispatchers.IO) { keys.delete(profile.keyAlias) }
            }
            profiles.remove(id)
            coordinator.reloadHosts()
        } } }
        return SettingsResult.Removed
    }
    override suspend fun pair(invitation: PairInvitation, repair: HostId?): SettingsResult {
        val previous = repair?.let { id -> profiles.list().find { it.id == id } ?: return SettingsResult.Failed }
        val earlierUncertainty = pendingPairing.value
        // Persist only the fact of an attempt. No invitation, token, body or fingerprint is retained.
        persistPendingPairing(true); pendingPairing.value = true
        return when (val outcome = withContext(Dispatchers.IO) { pairing.pair(invitation) }) {
            is PairingOutcome.Paired -> {
                val profile = previous?.let { outcome.host.copy(id = it.id) } ?: outcome.host
                coordinator.maintain { settleWriters { withContext(NonCancellable) {
                    profiles.save(profile)
                    coordinator.reloadHosts()
                    coordinator.selectHost(profile.id)
                    if (previous != null && profiles.list().none { it.keyAlias == previous.keyAlias }) {
                        withContext(Dispatchers.IO) { keys.delete(previous.keyAlias) }
                    }
                } } }
                finishPairing(SettingsResult.Paired, earlierUncertainty)
            }
            PairingOutcome.InvalidInvitation -> finishPairing(SettingsResult.InvalidInvitation, earlierUncertainty)
            is PairingOutcome.Rejected -> finishPairing(SettingsResult.Rejected, earlierUncertainty)
            PairingOutcome.VerificationFailed -> finishPairing(SettingsResult.VerificationFailed, earlierUncertainty)
            is PairingOutcome.Uncertain -> SettingsResult.Uncertain
        }
    }
    private fun finishPairing(result: SettingsResult, earlierUncertainty: Boolean): SettingsResult {
        persistPendingPairing(earlierUncertainty); pendingPairing.value = earlierUncertainty
        return result
    }
    override suspend fun checkCapabilities(): SettingsResult {
        val active = connection.value as? ConnectionState.Connected ?: return SettingsResult.Failed
        fun current() = (connection.value as? ConnectionState.Connected)?.generation === active.generation && selectedHostId.value == active.host.id
        val client = JsonDaemonClient(ProtocolChannelFactory {
            check(current())
            hostConnection.openChannel(ChannelKind.HUMAN).also { if (!current()) { it.close(); error("Connection changed") } }
        }, scope)
        discovered.value = null
        return try {
            val hello = ((client.request(DaemonRequest.Hello()) as? DaemonReply.Success)?.result as? DaemonResult.Hello)
                ?: return SettingsResult.Failed
            if (!current()) return SettingsResult.Failed
            val verbs = ((client.request(DaemonRequest.ListVerbs) as? DaemonReply.Success)?.result as? DaemonResult.Verbs)
                ?: return SettingsResult.Failed
            if (!current()) return SettingsResult.Failed
            discoveredGeneration = active.generation
            discovered.value = CapabilitySummary(active.host.id, hello.daemonVersion, verbs.names)
            SettingsResult.Saved
        } finally { withContext(NonCancellable) { client.close() } }
    }
    override suspend fun clearAllHistory(): SettingsResult {
        coordinator.maintain { settleWriters {
            history.observeHosts().first().forEach { history.clearHost(it) }
        } }
        return SettingsResult.Cleared
    }
    override suspend fun resetConnections(): SettingsResult {
        coordinator.maintain { settleWriters { withContext(NonCancellable) {
            profiles.clear()
            coordinator.reloadHosts()
        } } }
        return SettingsResult.Removed
    }
    override suspend fun removeAllLocalData(): SettingsResult {
        coordinator.maintain { settleWriters { withContext(NonCancellable) {
            val aliases = withContext(Dispatchers.IO) { keys.aliases() }
            history.observeHosts().first().forEach { history.clearHost(it) }
            withContext(Dispatchers.IO) { aliases.forEach(keys::delete) }
            profiles.clear()
            coordinator.reloadHosts()
            retentionSettings.change(RetentionPolicy.Default)
            persistPendingPairing(false); pendingPairing.value = false
            discovered.value = null
        } } }
        return SettingsResult.Removed
    }
}
