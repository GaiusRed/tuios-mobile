package dev.gaiusred.tuiosmobile.connection

import dev.gaiusred.tuiosmobile.identity.*
import dev.gaiusred.tuiosmobile.storage.*
import dev.gaiusred.tuiosmobile.settings.RetentionSettings
import dev.gaiusred.tuiosmobile.ui.settings.SettingsResult
import dev.gaiusred.tuiosmobile.ui.settings.SettingsActions
import dev.gaiusred.tuiosmobile.ui.settings.SettingsViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Instant
import kotlinx.serialization.json.*
import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsManagerTest {
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    private val a = HostProfile(HostId("A"), "Computer A", "user", "phone", emptyList(), "public", "key-A")
    private val b = a.copy(id = HostId("B"), machine = "Computer B", keyAlias = "key-B")
    private class Profiles(var values: List<HostProfile>) : HostProfileStore {
        override suspend fun list() = values
        override suspend fun save(profile: HostProfile) { values = values.filterNot { it.id == profile.id } + profile }
        override suspend fun remove(id: HostId) { values = values.filterNot { it.id == id } }
    }
    private class History : HistoryStore {
        val cleared = mutableListOf<HostId>()
        override fun observeHosts() = flowOf(listOf(HostId("A"), HostId("removed")))
        override fun observe(query: HistoryQuery) = flowOf(HistorySnapshot())
        override suspend fun apply(batch: HistoryBatch) = Unit
        override suspend fun saveCheckpoint(checkpoint: SyncCheckpoint) = Unit
        override suspend fun saveDraft(draft: MailDraft) = Unit
        override suspend fun adoptLegacyDraft(legacy: MailDraft, adopted: MailDraft) = false
        override suspend fun prune(policy: RetentionPolicy, now: Instant) = Unit
        override suspend fun clearHost(hostId: HostId) { cleared += hostId }
    }
    private inner class Fixture(test: TestScope, http: PairingHttp = PairingHttp { _, _ -> error("No pairing") }) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val profiles = Profiles(listOf(a, b))
        val deleted = mutableListOf<String>()
        var channelFactory: (() -> ProxyChannel)? = null
        val keys = object : PhoneKeyStore {
            override fun aliases() = setOf("key-A", "key-B", "detached-key")
            override fun create(alias: String) = PhonePublicKey("test-key", "SHA256:phone-example")
            override fun privateKey(alias: String): ByteArray = error("No SSH expected")
            override fun delete(alias: String) { deleted += alias }
        }
        val history = History()
        val connection = ForegroundHostConnection(ProxyTransport {
            checkNotNull(channelFactory)
            object : ProxyLink {
                override val isOpen = MutableStateFlow(true)
                override suspend fun open(human: Boolean) = checkNotNull(channelFactory).invoke()
                override fun close() { isOpen.value = false }
            }
        }, scope)
        val coordinator = HostSessionCoordinator(profiles, connection, {}, MutableStateFlow(a.id), scope, {})
        var settled = false
        val manager = SettingsManager(coordinator, profiles, keys, connection, history,
            RetentionSettings(history, persist = {}, now = { now }),
            PairingClient(http, keys), scope,
            { action -> settled = true; action() })
    }
    @Test fun clearHistoryKeepsSavedConnection() = runTest {
        val f = Fixture(this); runCurrent()
        try {
            assertEquals(SettingsResult.Cleared, f.manager.clearHistory(a.id))
            assertEquals(listOf(a.id), f.history.cleared)
            assertEquals(listOf(a, b), f.profiles.values)
            assertEquals(a.id, f.manager.selectedHostId.value)
            assertTrue(f.deleted.isEmpty())
            assertTrue(f.settled)
        } finally { f.scope.cancel() }
    }
    @Test fun removeHostDeletesSelectedCredentialsAndCache() = runTest {
        val f = Fixture(this); runCurrent()
        try {
            assertEquals(SettingsResult.Removed, f.manager.removeHost(a.id, true, true))
            assertEquals(listOf(b), f.profiles.values)
            assertEquals(b.id, f.manager.selectedHostId.value)
            assertEquals(listOf("key-A"), f.deleted)
            assertEquals(listOf(a.id), f.history.cleared)
            assertTrue(f.settled)
        } finally { f.scope.cancel() }
    }
    @Test fun removingProfileCanPreserveBothCredentialsAndHistory() = runTest {
        val f = Fixture(this); runCurrent()
        try {
            assertEquals(SettingsResult.Removed, f.manager.removeHost(a.id, false, false))
            assertEquals(listOf(b), f.profiles.values)
            assertTrue(f.deleted.isEmpty()); assertTrue(f.history.cleared.isEmpty())
        } finally { f.scope.cancel() }
    }
    @Test fun sharedCredentialIsKeptForRemainingProfile() = runTest {
        val f = Fixture(this); f.profiles.values = listOf(a, b.copy(keyAlias = a.keyAlias)); runCurrent()
        try {
            f.manager.removeHost(a.id, true, true)
            assertTrue(f.deleted.isEmpty())
        } finally { f.scope.cancel() }
    }

    private val hostBlob = ByteBuffer.allocate(51).putInt(11).put("ssh-ed25519".toByteArray()).putInt(32).put(ByteArray(32) { 1 }).array()
    private val hostKey = "ssh-ed25519 ${Base64.getEncoder().encodeToString(hostBlob)}"
    private val invitation get() = PairInvitation("tuios://pair?v=1&m=fabricated&u=user&s=100.100.1.1:22&p=100.100.1.1:1234&fp=${sshFingerprint(hostBlob)}&t=AAECAwQFBgcICQoLDA0ODw", "phone")
    private fun approvedResponse(): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(ByteArray(16) { it.toByte() }, "HmacSHA256"))
            Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal("tuios-pair-v1-ok\nphone\n$hostKey".toByteArray()))
        }
        return buildJsonObject {
            put("ok", true); put("device", "phone"); put("host_key", hostKey); put("mac", mac); put("check", "565911")
        }.toString().toByteArray()
    }
    @Test fun successfulPairingReloadsAndDeliberatelySelectsNewProfile() = runTest {
        val f = Fixture(this, PairingHttp { _, _ -> approvedResponse() }); runCurrent()
        try {
            assertEquals(SettingsResult.Paired, f.manager.pair(invitation, null))
            assertEquals(3, f.manager.hosts.value.size)
            assertEquals("fabricated", f.manager.hosts.value.single { it.id == f.manager.selectedHostId.value }.machine)
            assertFalse(f.manager.pairingUncertain.value)
        } finally { f.scope.cancel() }
    }
    @Test fun uncertainPairingKeepsCredentialAndNeverRetriesPost() = runTest {
        var posts = 0
        val f = Fixture(this, PairingHttp { _, _ -> posts++; throw java.io.IOException("fixture detail") }); runCurrent()
        try {
            assertEquals(SettingsResult.Uncertain, f.manager.pair(invitation, null))
            assertTrue(f.manager.pairingUncertain.value)
            assertEquals(1, posts)
            assertTrue(f.deleted.isEmpty())
            assertEquals(listOf(a, b), f.manager.hosts.value)
        } finally { f.scope.cancel() }
    }
    @Test fun newInvalidInvitationDoesNotHideEarlierUncertainPairing() = runTest {
        val f = Fixture(this, PairingHttp { _, _ -> throw java.io.IOException("fixture") }); runCurrent()
        try {
            f.manager.pair(invitation, null)
            assertEquals(SettingsResult.InvalidInvitation, f.manager.pair(PairInvitation("invalid", "phone"), null))
            assertTrue(f.manager.pairingUncertain.value)
        } finally { f.scope.cancel() }
    }
    @Test fun cancelledPossiblePairingPostKeepsVisibleUncertaintyAndLocalKey() = runTest {
        var posts = 0
        val f = Fixture(this, PairingHttp { _, _ -> posts++; throw CancellationException("fixture cancellation") }); runCurrent()
        try {
            try { f.manager.pair(invitation, null); fail("Cancellation must propagate") }
            catch (_: CancellationException) { }
            assertTrue(f.manager.pairingUncertain.value)
            assertTrue(f.deleted.isEmpty()); assertEquals(1, posts)
        } finally { f.scope.cancel() }
    }
    @Test fun leavingForegroundCancelsPairingWithoutRetry() = runTest {
        val f = Fixture(this); runCurrent()
        var entered = 0; var cancelled = false
        val actions = object : SettingsActions by f.manager {
            override suspend fun pair(invitation: PairInvitation, repair: HostId?): SettingsResult {
                entered++
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        val model = SettingsViewModel(actions, f.scope)
        try {
            model.pair(PairInvitation("fixture", "phone"), null); runCurrent()
            assertTrue(model.busy.value)
            model.pair(PairInvitation("second-tap", "phone"), null)
            model.onBackground(); runCurrent()
            assertTrue(cancelled); assertFalse(model.busy.value); assertEquals(1, entered)
        } finally { f.scope.cancel() }
    }
    @Test fun repairReplacesProfileAndLocalKeyWhileKeepingHostHistoryIdentity() = runTest {
        val f = Fixture(this, PairingHttp { _, _ -> approvedResponse() }); runCurrent()
        try {
            assertEquals(SettingsResult.Paired, f.manager.pair(invitation, a.id))
            assertEquals(a.id, f.manager.selectedHostId.value)
            assertEquals(2, f.manager.hosts.value.size)
            assertEquals(hostKey, f.manager.hosts.value.single { it.id == a.id }.hostKey)
            assertEquals(listOf("key-A"), f.deleted)
            assertTrue(f.history.cleared.isEmpty())
        } finally { f.scope.cancel() }
    }
    @Test fun replacementDuringCapabilityReadCannotPublishOldHostSupport() = runTest {
        val f = Fixture(this); runCurrent()
        val verbs = mutableListOf<String>()
        f.channelFactory = { CapabilityChannel(verbs) { f.coordinator.selectHost(b.id); runCurrent() } }
        f.coordinator.setForeground(true); runCurrent()
        try {
            assertEquals(SettingsResult.Failed, f.manager.checkCapabilities())
            assertEquals(listOf("hello"), verbs)
            assertNull(f.manager.capabilities.value)
        } finally { f.scope.cancel() }
    }
    @Test fun clearingAllHistoryIncludesRemovedHostsAndKeepsConnections() = runTest {
        val f = Fixture(this); runCurrent()
        try {
            assertEquals(SettingsResult.Cleared, f.manager.clearAllHistory())
            assertEquals(setOf(HostId("A"), HostId("removed")), f.history.cleared.toSet())
            assertEquals(listOf(a, b), f.profiles.values)
            assertTrue(f.deleted.isEmpty())
        } finally { f.scope.cancel() }
    }
    @Test fun removeAllLocalDataDeletesDetachedCredentialsAndHistory() = runTest {
        val f = Fixture(this); runCurrent()
        try {
            assertEquals(SettingsResult.Removed, f.manager.removeAllLocalData())
            assertEquals(setOf("key-A", "key-B", "detached-key"), f.deleted.toSet())
            assertEquals(setOf(HostId("A"), HostId("removed")), f.history.cleared.toSet())
            assertTrue(f.profiles.values.isEmpty())
            assertNull(f.manager.selectedHostId.value)
        } finally { f.scope.cancel() }
    }
    @Test fun resetConnectionsPreservesAllContentAndCredentials() = runTest {
        val f = Fixture(this); runCurrent()
        try {
            assertEquals(SettingsResult.Removed, f.manager.resetConnections())
            assertTrue(f.profiles.values.isEmpty()); assertNull(f.manager.selectedHostId.value)
            assertTrue(f.deleted.isEmpty()); assertTrue(f.history.cleared.isEmpty())
        } finally { f.scope.cancel() }
    }
    @Test fun capabilitiesUseOnlySafeReadsFromCurrentConnection() = runTest {
        val f = Fixture(this); runCurrent()
        val verbs = mutableListOf<String>()
        f.channelFactory = { CapabilityChannel(verbs) }
        f.coordinator.setForeground(true); runCurrent()
        try {
            assertEquals(SettingsResult.Saved, f.manager.checkCapabilities())
            assertEquals(listOf("hello", "list-verbs"), verbs)
            assertEquals(setOf("list-sessions", "read-agent-messages"), f.manager.capabilities.value?.verbs)
            assertEquals(a.id, f.manager.capabilities.value?.hostId)
            f.coordinator.selectHost(b.id); runCurrent()
            assertNull(f.manager.capabilities.value)
        } finally { f.scope.cancel() }
    }
    private class CapabilityChannel(private val requests: MutableList<String>, private val afterHello: suspend () -> Unit = {}) : ProxyChannel {
        override val isOpen = MutableStateFlow(true)
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override suspend fun send(bytes: ByteArray) {
            val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val verb = request.getValue("verb").jsonPrimitive.content
            requests += verb
            val result = when (verb) {
                "hello" -> """{"type":"hello","protocol":1,"min_protocol":1,"daemon_version":"fixture-1"}"""
                "list-verbs" -> """{"type":"verb_list","verbs":[{"verb":"list-sessions"},{"verb":"read-agent-messages"}]}"""
                else -> error("Capabilities sent an unauthorized verb")
            }
            if (verb == "hello") afterHello()
            incoming.send(("{\"id\":${request.getValue("id")},\"result\":$result}\n").toByteArray())
        }
        override suspend fun receive() = incoming.receiveCatching().getOrNull()
        override fun close() { isOpen.value = false; incoming.close() }
    }
}
