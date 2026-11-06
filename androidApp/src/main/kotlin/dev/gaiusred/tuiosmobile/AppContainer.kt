package dev.gaiusred.tuiosmobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.core.content.edit
import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.mail.MailRepository
import dev.gaiusred.tuiosmobile.sessions.SessionRepository
import dev.gaiusred.tuiosmobile.storage.RoomHistoryStore
import dev.gaiusred.tuiosmobile.sync.ForegroundSync
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock
import dev.gaiusred.tuiosmobile.settings.RetentionSettings
import dev.gaiusred.tuiosmobile.storage.RetentionPolicy
import dev.gaiusred.tuiosmobile.ui.settings.SettingsViewModel

/** Activity-retained dependencies. Only this owner binds the two foreground lifecycles. */
class AppContainer(application: Application) : AndroidViewModel(application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = application.getSharedPreferences("selected-host", 0)
    val hostProfiles: HostProfileStore = SavedHostProfiles(application)
    val phoneKeys: PhoneKeyStore = DeviceKeyStore(application)
    val history = RoomHistoryStore(application)
    val connection = ForegroundHostConnection(SshProxyTransport(phoneKeys, scope), scope)
    private val selected = MutableStateFlow(preferences.getString("id", null)?.let(::HostId))
    private val sync = ForegroundSync(connection, history, scope, selected) { Clock.System.now() }
    val sessions: SessionRepository = sync
    private val hostCoordinator = HostSessionCoordinator(hostProfiles, connection, sync::setForeground,
        selected, scope) { id ->
        preferences.edit {
            if (id == null) remove("id") else putString("id", id.value)
        }
    }
    val selectedHostId: StateFlow<HostId?> = hostCoordinator.selectedHostId
    val mail = MailRepository(connection, history, sessions, selectedHostId, scope) { Clock.System.now() }
    val attention = dev.gaiusred.tuiosmobile.attention.AttentionRepository(connection, history, sessions, selectedHostId, scope) { Clock.System.now() }
    val hosts: StateFlow<List<HostProfile>> = hostCoordinator.hosts
    val hostLoadProblem: StateFlow<HostLoadProblem?> = hostCoordinator.hostLoadProblem
    val cachedHostIds = history.observeHosts().stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val settingsPreferences = application.getSharedPreferences("local-settings", 0)
    private val retention = RetentionSettings(history,
        RetentionPolicy.entries.find { it.name == settingsPreferences.getString("retention", null) } ?: RetentionPolicy.Default,
        persist = { policy -> check(settingsPreferences.edit().putString("retention", policy.name).commit()) },
        now = { Clock.System.now() })
    private val settingsManager = SettingsManager(hostCoordinator, hostProfiles, phoneKeys, connection, history,
        retention, PairingClient(HttpPairingClient(), phoneKeys), scope,
        settleWriters = { action -> mail.maintainHistory { attention.maintainHistory(action) } },
        pendingPairing = MutableStateFlow(settingsPreferences.getBoolean("pairing-pending", false)),
        persistPendingPairing = { pending -> check(settingsPreferences.edit().putBoolean("pairing-pending", pending).commit()) })
    val settings = SettingsViewModel(settingsManager, scope)

    private var maintenance: Job? = null
    private var foregroundRequested = false
    fun setForeground(foreground: Boolean) {
        foregroundRequested = foreground
        if (!foreground) {
            settings.onBackground()
            hostCoordinator.setForeground(false)
            return
        }
        if (maintenance?.isActive == true) return
        maintenance = scope.launch {
            try { hostCoordinator.maintain { mail.maintainHistory { attention.maintainHistory { retention.maintain() } } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { settings.reportFailure() }
            finally { hostCoordinator.setForeground(foregroundRequested) }
        }
    }

    fun retryConnection() = hostCoordinator.retryConnection()

    fun selectHost(id: HostId) {
        hostCoordinator.selectHost(id)
    }

    suspend fun reloadHosts() {
        hostCoordinator.reloadHosts()
    }

    override fun onCleared() {
        scope.launch {
            try {
                sync.setForeground(false)
                connection.setForeground(false)
            } finally {
                history.close()
                scope.cancel()
            }
        }
    }
}
