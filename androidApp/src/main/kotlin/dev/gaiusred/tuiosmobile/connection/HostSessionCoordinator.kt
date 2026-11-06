package dev.gaiusred.tuiosmobile.connection

import dev.gaiusred.tuiosmobile.identity.HostId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

enum class HostLoadProblem { Unavailable }

/** The app's single owner of saved-host selection and ordered foreground binding. */
internal class HostSessionCoordinator(private val profiles: HostProfileStore,
    private val connection: ForegroundHostConnection, private val syncForeground: suspend (Boolean) -> Unit,
    private val selected: MutableStateFlow<HostId?>, scope: CoroutineScope,
    private val persistSelection: (HostId?) -> Unit) {
    private val saved = MutableStateFlow<List<HostProfile>>(emptyList())
    val hosts: StateFlow<List<HostProfile>> = saved.asStateFlow()
    val selectedHostId: StateFlow<HostId?> = selected.asStateFlow()
    private val loadProblem = MutableStateFlow<HostLoadProblem?>(null)
    val hostLoadProblem: StateFlow<HostLoadProblem?> = loadProblem.asStateFlow()
    private val visible = MutableStateFlow(false)
    private val retry = MutableStateFlow(0L)
    private val reloadGate = Mutex()
    private val maintenanceGate = Mutex()
    private val paused = MutableStateFlow<Long?>(null)
    private val stoppedForMaintenance = MutableStateFlow<Long?>(null)
    private var nextMaintenance = 0L

    init {
        scope.launch { reloadHosts() }
        scope.launch {
            combine(visible, selected, saved, paused) { foreground, id, profiles, paused ->
                Triple(foreground && paused == null, profiles.find { it.id == id }, paused)
            }.distinctUntilChanged().combine(retry) { binding, _ -> binding }.collectLatest { (foreground, profile, paused) ->
                if (foreground && profile != null) {
                    try {
                        connection.setForeground(true)
                        syncForeground(true)
                        var retryDelay = 1_000L
                        while (currentCoroutineContext().isActive) {
                            connection.connect(profile)
                            if (connection.state.value is ConnectionState.Connected) {
                                retryDelay = 1_000L
                                connection.state.first { it !is ConnectionState.Connected }
                            }
                            when (connection.state.value) {
                                ConnectionState.Disconnected, ConnectionState.Failed -> {
                                    delay(retryDelay)
                                    retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
                                }
                                else -> awaitCancellation() // Authentication and host-key review require a deliberate action.
                            }
                        }
                    } finally {
                        withContext(NonCancellable) {
                            syncForeground(false)
                            connection.setForeground(false)
                        }
                    }
                } else {
                    syncForeground(false)
                    connection.setForeground(false)
                    stoppedForMaintenance.value = paused
                }
            }
        }
    }

    fun setForeground(foreground: Boolean) { visible.value = foreground }

    fun retryConnection() { retry.value++ }

    fun selectHost(id: HostId) {
        if (saved.value.none { it.id == id }) return
        selected.value = id
        persistSelection(id)
    }

    suspend fun reloadHosts() = reloadGate.withLock {
        val loaded = try { profiles.list() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { loadProblem.value = HostLoadProblem.Unavailable; return@withLock }
        catch (_: IllegalArgumentException) { loadProblem.value = HostLoadProblem.Unavailable; return@withLock }
        saved.value = loaded
        // Successful reload keeps a valid choice, otherwise selects the first saved host, or none.
        val next = selected.value?.takeIf { id -> loaded.any { it.id == id } } ?: loaded.firstOrNull()?.id
        if (selected.value != next) {
            selected.value = next
            persistSelection(next)
        }
        loadProblem.value = null
    }

    /** Pauses the one foreground binding and waits for stream cleanup before touching storage. */
    suspend fun <T> maintain(block: suspend () -> T): T = maintenanceGate.withLock {
        val request = ++nextMaintenance
        paused.value = request
        try {
            stoppedForMaintenance.first { it == request }
            block()
        } finally {
            paused.value = null
        }
    }
}
