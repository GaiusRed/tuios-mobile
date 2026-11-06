package dev.gaiusred.tuiosmobile.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.gaiusred.tuiosmobile.attention.AttentionRepository
import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.identity.*
import dev.gaiusred.tuiosmobile.sessions.*
import dev.gaiusred.tuiosmobile.storage.*
import dev.gaiusred.tuiosmobile.ui.design.MessagesTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.*
import kotlin.time.Instant

class AttentionTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var history: RoomHistoryStore
    private val fixture = AttentionFixture()
    private lateinit var view: android.view.View
    private val mounted = mutableStateOf(true)
    @Before fun setup() { context.deleteDatabase("attention-test.db"); history = RoomHistoryStore(context, "attention-test.db") }
    @After fun cleanup() {
        if (::view.isInitialized) {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
        }
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        history.close()
        context.deleteDatabase("attention-test.db")
    }
    @Suppress("DEPRECATION")
    private fun open(fontScale: Float = 1f) {
        val repo = AttentionRepository(fixture.connection, history, fixture, MutableStateFlow(fixture.host.id), scope) { Instant.parse("2026-11-05T01:00:00Z") }
        compose.setContent {
            if (!mounted.value) return@setContent
            view = LocalView.current
            val connection by fixture.connection.state.collectAsState()
            SideEffect { (view.context as android.app.Activity).window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MessagesTheme { AppRoot(fixture, listOf(fixture.host), connection, {}, attentionRepository = repo) }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("attention-item").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("agent-Builder").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("attention-item").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun keyboardDoesNotCoverResponseActions() {
        open()
        compose.onNodeWithTag("response-input").performScrollTo().performClick().performTextInput("A deliberate answer")
        compose.waitUntil(8_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        compose.onNodeWithTag("response-text").performScrollTo().assertIsDisplayed().assertIsEnabled()
        listOf("Sessions", "Mail", "Terminal", "Settings").forEach { compose.onNodeWithTag("tab-$it").assertIsDisplayed() }
        compose.onNodeWithTag("tab-Terminal").assertIsNotEnabled()
        capture("keyboard")
    }
    @Test fun offlineActionsAreDisabledAndLabeled() {
        open()
        compose.onNodeWithTag("response-input").performScrollTo().assertIsEnabled()
        compose.runOnIdle { fixture.offline() }
        compose.onNodeWithTag("response-input").assertIsNotEnabled()
        compose.onNodeWithTag("response-text").performScrollTo().assertIsNotEnabled()
        Assert.assertEquals(0, fixture.writes)
    }
    @Test fun typingSurvivesReadinessChangeForUnchangedQuestion() {
        open()
        compose.onNodeWithTag("response-input").performScrollTo().performTextInput("Keep this deliberate answer")
        compose.runOnIdle { fixture.offline() }
        compose.onNodeWithTag("response-input").assertTextContains("Keep this deliberate answer").assertIsNotEnabled()
    }
    @Test fun askAnswerTextIsDisplayedConfirmedAndSentVerbatim() {
        fixture.itemKind = "ask"
        open()
        compose.onNodeWithTag("response-text").performScrollTo().assertTextEquals("text").performClick()
        compose.onNodeWithText("Confirm text").assertIsDisplayed()
        Assert.assertEquals(0, fixture.writes)
        compose.onNodeWithTag("response-confirm").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Response applied.").fetchSemanticsNodes().isNotEmpty() }
        Assert.assertEquals("text", fixture.lastAnswer)
    }
    @Test fun askAnswerMixedCaseAndUnderscoreStayVerbatim() {
        fixture.itemKind = "ask"
        open()
        compose.onNodeWithTag("response-Keep_JSON").performScrollTo().assertTextEquals("Keep_JSON").performClick()
        compose.onNodeWithText("Confirm Keep_JSON").assertIsDisplayed()
        Assert.assertEquals(0, fixture.writes)
        compose.onNodeWithTag("response-confirm").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Response applied.").fetchSemanticsNodes().isNotEmpty() }
        Assert.assertEquals("Keep_JSON", fixture.lastAnswer)
    }
    @Test fun fontScalingKeepsOfferedActionsReachable() {
        open(2f)
        compose.onNodeWithTag("response-choose-2").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("response-choose-2").performClick()
        compose.onNodeWithText("Confirm choose: Continue").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        Assert.assertEquals(0, fixture.writes)
    }
    @Test fun staleItemReplacementRequiresFreshReview() {
        open()
        compose.onNodeWithTag("response-choose-2").performScrollTo().performClick()
        compose.runOnIdle { fixture.message = "Replacement question"; fixture.promptId = "screen-2" }
        compose.onNodeWithTag("response-confirm").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Replacement question").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("The request changed. Review the current request.").assertExists()
        Assert.assertEquals(0, fixture.writes)
    }
    @Test fun planDetailsAndRisksRequireExplicitAcknowledgment() {
        fixture.itemKind = "plan"
        open()
        compose.onNodeWithText(fixture.plan).assertExists()
        compose.onNodeWithTag("response-always").assertDoesNotExist()
        compose.onNodeWithTag("response-deny").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("response-once").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Acknowledge risk delete: Deletes retained files").performScrollTo().performClick()
        compose.onNodeWithTag("response-once").performScrollTo().assertIsEnabled()
        capture("plan")
        compose.onNodeWithTag("response-once").performClick()
        compose.onNodeWithTag("response-confirm").assertIsEnabled()
        compose.onNodeWithText("Risks acknowledged: delete").assertExists()
        capture("plan-confirmation")
        compose.onNodeWithText("Cancel").performClick()
        Assert.assertEquals(0, fixture.writes)
    }
    @Test fun uncertainAttemptSurvivesRoomReopenRetentionAndHostClear() = runBlocking {
        val target = fixture.target
        val key = RecordKey(target.hostId, target.sessionId, target.windowId, target.daemonBootId, null)
        val time = Instant.parse("2026-11-05T01:00:00Z")
        val attempt = CachedContent.AttentionAttempt(target.agentSessionId, "item", "question", "Current session",
            "Builder", "A saved question", null, "screen-1", "text", "uncertain")
        history.apply(HistoryBatch(key, listOf(CachedEntry("attempt", attempt, time))))
        history.close()
        history = RoomHistoryStore(context, "attention-test.db")
        history.prune(RetentionPolicy.SevenDays, Instant.parse("2027-11-05T01:00:00Z"))
        Assert.assertEquals(attempt, history.observe(HistoryQuery(target.hostId)).first().records.single().entry.content)
        val repo = AttentionRepository(fixture.connection, history, fixture, MutableStateFlow(target.hostId), scope) { time }
        Assert.assertTrue(repo.observe(target).first().single().uncertain)
        history.clearHost(target.hostId)
        Assert.assertTrue(repo.observe(target).first().isEmpty())
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureAttentionScreenshots") != "true") return
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p /sdcard/Pictures/task8-attention-$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}

/** All reads and writes terminate in this fabricated peer. Never connects to SSH. */
private class AttentionFixture : SessionRepository {
    val host = HostProfile(HostId("attention-ui-fixture"), "Fabricated host", "user", "phone", emptyList(), "public", "alias")
    val target = AgentTarget(host.id, "stable", "full-window", "boot", "agent-life")
    private val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connected(host))
    override val state = MutableStateFlow(SessionSnapshot(host.id, listOf(SessionSummary("stable", "Current session", 1, false,
        listOf(AgentSummary(target, "Builder", "needs_input", "unknown", true, true)))), false,
        verification = SessionVerification(host.id, (connectionState.value as ConnectionState.Connected).generation, "boot")))
    var message = "Which change should be applied?"; var promptId = "screen-1"; var writes = 0; var itemKind = "question"
    var lastAnswer: String? = null
    val plan = "Review the selected directory.\nRemove only the generated files.\nEnd of the complete plan."
    fun offline() { connectionState.value = ConnectionState.Disconnected; state.value = state.value.copy(offline = true, verification = null) }
    val connection = object : HostConnection {
        override val state = connectionState
        override suspend fun connect(host: HostProfile) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun openChannel(kind: ChannelKind): ProxyChannel = object : ProxyChannel {
            override val isOpen = MutableStateFlow(true)
            val incoming = Channel<ByteArray>(Channel.UNLIMITED)
            val nonce = "own-${Any().hashCode()}"; var attached = false
            override suspend fun send(bytes: ByteArray) {
                val req = Json.parseToJsonElement(bytes.decodeToString()).jsonObject; val params = req.getValue("params").jsonObject
                val verb = req.getValue("verb").jsonPrimitive.content
                if (verb != "answer-ask") check(params.getValue("session").jsonPrimitive.content == "Current session")
                val result = when (verb) {
                    "attach-presence" -> { attached = true; buildJsonObject { put("type", "presence"); put("session", "Current session"); put("client_id", "phone"); put("human_nonce", nonce) } }
                    "list-attention" -> buildJsonObject { put("type", "attention_list"); put("seq", 1); put("boot_id", "boot")
                        put("items", buildJsonArray { add(buildJsonObject { put("id", "item"); put("kind", itemKind); put("session", "Current session"); put("window", "full-window"); put("summary", message); put("since", 1); put("seq", 1)
                            if (itemKind == "plan") put("request_id", "plan-request")
                            if (itemKind == "ask") {
                                put("request_id", "ask-request")
                                put("options", buildJsonArray { add("text"); add("Keep_JSON") })
                            }
                        }) }) }
                    "get-approval" -> buildJsonObject { put("type", "approval"); put("session", "Current session"); put("window", "full-window"); put("kind", "plan"); put("request_id", "plan-request")
                        put("summary", message); put("plan", plan); put("plan_sha", "plan-sha"); put("tool", "shell"); put("target", "generated-files"); put("deny_message", true); put("untrusted", true)
                        put("options", buildJsonArray { add("once"); add("deny") }); put("always_scope", buildJsonArray {})
                        put("risk", buildJsonArray { add(buildJsonObject { put("rule", "delete"); put("why", "Deletes retained files") }) }) }
                    "peek-prompt" -> buildJsonObject { put("type", "prompt_peek"); put("session", "Current session"); put("window", "full-window"); put("prompt_id", promptId); put("found", true); put("answerable", true); put("message", message)
                        put("actions", buildJsonArray { add("choose"); add("text") }); put("lines", buildJsonArray { add("Review the change before responding.") })
                        put("options", buildJsonArray { add(buildJsonObject { put("n", 2); put("label", "Continue") }) }) }
                    "respond" -> { check(attached && params.getValue("human_nonce").jsonPrimitive.content == nonce); writes++
                        buildJsonObject { put("type", "prompt_response"); put("prompt_id", promptId); put("settled_by", "timeout") } }
                    "answer-ask" -> {
                        check(attached && params.getValue("human_nonce").jsonPrimitive.content == nonce)
                        check(params.getValue("request_id").jsonPrimitive.content == "ask-request")
                        check(params.getValue("question").jsonPrimitive.content == message)
                        val answer = params.getValue("answer").jsonPrimitive.content
                        check(answer in listOf("text", "Keep_JSON"))
                        lastAnswer = answer; writes++
                        buildJsonObject { put("type", "ask_answered"); put("applied", true) }
                    }
                    else -> error("Unexpected operation")
                }
                incoming.send((buildJsonObject { put("id", req.getValue("id")); put("result", result) }.toString() + "\n").encodeToByteArray())
            }
            override suspend fun receive() = incoming.receiveCatching().getOrNull()
            override fun close() { isOpen.value = false; incoming.close() }
        }
    }
    override suspend fun refresh() = Unit
    override suspend fun loadOlder(target: AgentTarget) = Unit
    override fun conversation(target: AgentTarget) = state.map { ConversationSnapshot(target, offline = it.offline) }
    override fun retainedConversations(hostId: HostId): Flow<List<RetainedConversation>> = flowOf(emptyList())
    override fun retainedConversation(target: RetainedConversationTarget): Flow<RetainedConversationSnapshot> = emptyFlow()
}
