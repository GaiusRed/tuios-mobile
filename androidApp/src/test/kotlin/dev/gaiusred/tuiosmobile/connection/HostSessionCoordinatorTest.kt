package dev.gaiusred.tuiosmobile.connection

import dev.gaiusred.tuiosmobile.identity.HostId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HostSessionCoordinatorTest {
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun close() { scopes.forEach { it.cancel() } }

    private fun host(id: String) = HostProfile(HostId(id), id, "fixture", "phone", emptyList(), "fixture-only", "fixture")

    private class Profiles(var values: List<HostProfile>) : HostProfileStore {
        var failure: Exception? = null
        override suspend fun list(): List<HostProfile> { failure?.let { throw it }; return values }
        override suspend fun save(profile: HostProfile) { values = values + profile }
        override suspend fun remove(id: HostId) { values = values.filterNot { it.id == id } }
    }

    private inner class Fixture(test: TestScope, initial: HostId?, values: List<HostProfile>, pending: Boolean = false) {
        val events = mutableListOf<String>()
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler) +
            CoroutineExceptionHandler { _, error -> uncaught += error }).also { scopes += it }
        val profiles = Profiles(values)
        val selected = MutableStateFlow(initial)
        val persisted = mutableListOf<HostId?>()
        var pendingCancelled = false
        var failure: ConnectionState? = null
        var currentLink: ProxyLink? = null
        val connection = ForegroundHostConnection(ProxyTransport { profile ->
            events += "connect:${profile.id.value}"
            failure?.let { throw ConnectionException(it) }
            if (pending) {
                try { awaitCancellation() } finally { pendingCancelled = true }
            }
            object : ProxyLink {
                override val isOpen = MutableStateFlow(true)
                override suspend fun open(human: Boolean): ProxyChannel = error("No protocol in composition tests")
                override fun close() { events += "close:${profile.id.value}"; isOpen.value = false }
            }.also { currentLink = it }
        }, scope)
        val coordinator = HostSessionCoordinator(profiles, connection, { events += "sync:$it" }, selected, scope,
            { persisted += it })
    }

    @Test fun initialTransientFailureRecoversWithoutChangingSelectionOrVisibility() = runTest {
        val a = host("A"); val f = Fixture(this, a.id, listOf(a))
        f.failure = ConnectionState.Failed
        f.coordinator.setForeground(true); runCurrent()
        assertEquals(ConnectionState.Failed, f.connection.state.value)
        f.failure = null
        advanceTimeBy(1_000); runCurrent()
        assertTrue(f.connection.state.value is ConnectionState.Connected)
    }

    @Test fun droppedLinkRecoversAndBackgroundCancelsBackoff() = runTest {
        val a = host("A"); val f = Fixture(this, a.id, listOf(a))
        f.coordinator.setForeground(true); runCurrent()
        val old = (f.connection.state.value as ConnectionState.Connected).generation
        f.currentLink!!.close(); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertNotSame(old, (f.connection.state.value as ConnectionState.Connected).generation)
        f.failure = ConnectionState.Failed
        f.currentLink!!.close(); runCurrent()
        f.coordinator.setForeground(false); runCurrent()
        val attempts = f.events.count { it == "connect:A" }
        advanceTimeBy(60_000); runCurrent()
        assertEquals(attempts, f.events.count { it == "connect:A" })
    }

    @Test fun reviewFailuresNeverAutomaticallyReconnect() = runTest {
        for (failure in listOf(ConnectionState.AuthenticationFailed, ConnectionState.HostKeyReviewRequired,
            ConnectionState.RepairRequired, ConnectionState.PermissionDenied)) {
            val a = host("A"); val f = Fixture(this, a.id, listOf(a))
            f.failure = failure; f.coordinator.setForeground(true); runCurrent()
            advanceTimeBy(120_000); runCurrent()
            assertEquals(failure, f.connection.state.value)
            assertEquals(1, f.events.count { it == "connect:A" })
            f.coordinator.setForeground(false); runCurrent()
        }
    }

    @Test fun deliberateRetryInterruptsTransientBackoffAndKeepsHostBinding() = runTest {
        val a = host("A"); val f = Fixture(this, a.id, listOf(a))
        f.failure = ConnectionState.Failed; f.coordinator.setForeground(true); runCurrent()
        advanceTimeBy(61_000); runCurrent()
        val count = f.events.count { it == "connect:A" }
        assertTrue(count in 5..7) // Exponential delay prevents a tight loop.
        f.failure = null; f.coordinator.retryConnection(); runCurrent()
        assertEquals(a, (f.connection.state.value as ConnectionState.Connected).host)
        assertEquals(count + 1, f.events.count { it == "connect:A" })
    }

    @Test fun connectDeadlineIsTransientAndDoesNotKillForegroundOwner() = runTest {
        val a = host("A"); val f = Fixture(this, a.id, listOf(a), pending = true)
        f.coordinator.setForeground(true); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(ConnectionState.Failed, f.connection.state.value)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, f.events.count { it == "connect:A" })
        assertTrue(f.uncaught.isEmpty())
        f.coordinator.setForeground(false); runCurrent()
    }

    @Test fun removingConnectedSelectedProfileStopsSyncBeforeDisconnecting() = runTest {
        val a = host("A"); val f = Fixture(this, a.id, listOf(a))
        f.coordinator.setForeground(true); runCurrent()
        assertEquals(a, (f.connection.state.value as ConnectionState.Connected).host)
        f.events.clear()
        f.profiles.remove(a.id); f.coordinator.reloadHosts(); runCurrent()
        assertEquals(ConnectionState.Disconnected, f.connection.state.value)
        assertNull(f.coordinator.selectedHostId.value)
        assertEquals(listOf<HostId?>(null), f.persisted)
        assertTrue(f.events.indexOf("sync:false") in 0 until f.events.indexOf("close:A"))
    }

    @Test fun removingProfileDuringConnectCancelsTheAttemptWithoutRetry() = runTest {
        val a = host("A"); val f = Fixture(this, a.id, listOf(a), pending = true)
        f.coordinator.setForeground(true); runCurrent()
        assertEquals(ConnectionState.Connecting, f.connection.state.value)
        f.profiles.remove(a.id); f.coordinator.reloadHosts(); runCurrent()
        assertTrue(f.pendingCancelled)
        assertEquals(ConnectionState.Disconnected, f.connection.state.value)
        assertNull(f.coordinator.selectedHostId.value)
        assertEquals(1, f.events.count { it == "connect:A" })
        assertEquals("sync:false", f.events.last())
    }

    @Test fun successfulReloadReplacesStaleSelectionWithFirstSavedHost() = runTest {
        val b = host("B"); val f = Fixture(this, HostId("removed"), listOf(b, host("C")))
        f.coordinator.setForeground(true); runCurrent()
        assertEquals(b.id, f.coordinator.selectedHostId.value)
        assertEquals(listOf(b.id), f.persisted)
        assertEquals(b, (f.connection.state.value as ConnectionState.Connected).host)
    }

    @Test fun removingConnectedHostSelectsRemainingHostAfterOrderedShutdown() = runTest {
        val a = host("A"); val b = host("B"); val f = Fixture(this, a.id, listOf(a, b))
        f.coordinator.setForeground(true); runCurrent(); f.events.clear()
        f.profiles.remove(a.id); f.coordinator.reloadHosts(); runCurrent()
        assertEquals(b.id, f.coordinator.selectedHostId.value)
        assertEquals(b, (f.connection.state.value as ConnectionState.Connected).host)
        assertTrue(f.events.indexOf("sync:false") in 0 until f.events.indexOf("close:A"))
        assertTrue(f.events.indexOf("close:A") < f.events.indexOf("connect:B"))
    }

    @Test fun unreadableProfileStoreOnStartupKeepsCachedSelectionAndReportsSafeProblem() = runTest {
        val a = host("cached"); val f = Fixture(this, a.id, emptyList())
        f.profiles.failure = IOException("sensitive filesystem detail")
        f.coordinator.setForeground(true); runCurrent()
        assertTrue("Startup must not throw from a root launch", f.uncaught.isEmpty())
        assertEquals(HostLoadProblem.Unavailable, f.coordinator.hostLoadProblem.value)
        assertEquals(a.id, f.coordinator.selectedHostId.value)
        assertTrue(f.persisted.isEmpty())
        assertEquals(ConnectionState.Disconnected, f.connection.state.value)
        assertFalse(f.events.contains("sync:true"))
    }

    @Test fun malformedReloadKeepsKnownHostsAndSuccessfulRetryClearsProblem() = runTest {
        val a = host("A"); val f = Fixture(this, a.id, listOf(a)); runCurrent()
        f.profiles.failure = IllegalArgumentException("invalid saved format")
        f.coordinator.reloadHosts(); runCurrent()
        assertEquals(listOf(a), f.coordinator.hosts.value)
        assertEquals(a.id, f.coordinator.selectedHostId.value)
        assertEquals(HostLoadProblem.Unavailable, f.coordinator.hostLoadProblem.value)
        f.profiles.failure = null
        f.coordinator.reloadHosts(); runCurrent()
        assertNull(f.coordinator.hostLoadProblem.value)
    }

    @Test fun cancelledProfileLoadStillPropagatesCancellation() = runTest {
        val f = Fixture(this, null, emptyList()); runCurrent()
        f.profiles.failure = CancellationException("test cancellation")
        try { f.coordinator.reloadHosts(); fail("Cancellation must propagate") }
        catch (_: CancellationException) { /* Expected. */ }
        assertNull(f.coordinator.hostLoadProblem.value)
    }

    @Test fun maintenanceWaitsForShutdownAndReconnectsWithFreshGeneration() = runTest {
        val a = host("A"); val f = Fixture(this, a.id, listOf(a))
        f.coordinator.setForeground(true); runCurrent()
        val previous = (f.connection.state.value as ConnectionState.Connected).generation
        f.events.clear()
        f.coordinator.maintain {
            assertEquals(ConnectionState.Disconnected, f.connection.state.value)
            assertTrue(f.events.indexOf("sync:false") in 0 until f.events.indexOf("close:A"))
            f.coordinator.setForeground(true)
            runCurrent()
            assertEquals(ConnectionState.Disconnected, f.connection.state.value)
        }
        runCurrent()
        assertNotSame(previous, (f.connection.state.value as ConnectionState.Connected).generation)
    }

    @Test fun consecutiveMaintenanceCannotMissTheResumeTransition() = runTest {
        val a = host("A"); val f = Fixture(this, a.id, listOf(a))
        f.coordinator.setForeground(true); runCurrent()
        var finished = 0
        val job = f.scope.launch {
            repeat(2) { f.coordinator.maintain { finished++ } }
        }
        runCurrent()
        assertTrue("Both maintenance operations must finish", job.isCompleted)
        assertEquals(2, finished)
    }
    @Test fun cancellationOfAppOwnerCannotLeaveMaintenanceWaitingForACancelledCollector() = runTest {
        val a = host("A"); val f = Fixture(this, a.id, listOf(a)); runCurrent()
        val entered = CompletableDeferred<Unit>()
        val job = f.scope.launch { f.coordinator.maintain { entered.complete(Unit); awaitCancellation() } }
        runCurrent(); assertTrue(entered.isCompleted)
        f.scope.cancel(); runCurrent()
        assertTrue("Maintenance must finish when the app owner is cleared", job.isCompleted)
    }
}
