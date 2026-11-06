package dev.gaiusred.tuiosmobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gaiusred.tuiosmobile.connection.*
import dev.gaiusred.tuiosmobile.identity.HostId
import dev.gaiusred.tuiosmobile.sessions.SessionRepository
import dev.gaiusred.tuiosmobile.mail.MailRepository
import dev.gaiusred.tuiosmobile.attention.AttentionRepository
import dev.gaiusred.tuiosmobile.ui.attention.AttentionPanel
import dev.gaiusred.tuiosmobile.ui.mail.*
import dev.gaiusred.tuiosmobile.ui.sessions.*
import dev.gaiusred.tuiosmobile.ui.conversation.*
import dev.gaiusred.tuiosmobile.ui.design.*

@Composable
fun AppRoot(repository: SessionRepository, hosts: List<HostProfile>, connection: ConnectionState,
    onSelectHost: (HostId) -> Unit,
    mailContent: @Composable () -> Unit = { FeaturePlaceholder("Mail", "Messages between you and your agents will appear here.") },
    settingsContent: @Composable () -> Unit = { HostSettingsPlaceholder(hosts, onSelectHost) },
    mailRepository: MailRepository? = null,
    attentionRepository: AttentionRepository? = null,
    cachedHostIds: List<HostId> = emptyList(),
    onRetryConnection: () -> Unit = {},
) {
    var destinationName by rememberSaveable { mutableStateOf(Destination.Sessions.name) }
    var conversationRoute by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val source = conversationRoute.toConversationSource()
    val destination = Destination.valueOf(destinationName)
    val scope = rememberCoroutineScope()
    val mailState = rememberSaveableStateHolder()
    var mailAgent by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val mailModel = remember(mailRepository, scope) { mailRepository?.let { MailViewModel(it, scope) } }
    val sessionsViewModel = remember(repository, scope) { SessionsViewModel(repository, scope) }
    val sessionSnapshot by repository.state.collectAsState()
    BackHandler(source != null || destination != Destination.Sessions) {
        if (destination != Destination.Sessions) destinationName = Destination.Sessions.name
        else conversationRoute = emptyList()
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (destination) {
                    Destination.Sessions -> if (source == null) SessionsScreen(sessionsViewModel, hosts, connection,
                        onOpenAgent = { agent, session ->
                            conversationRoute = ConversationSource.Live(agent.target, agent.name, session.name,
                                hosts.find { it.id == agent.target.hostId }?.machine ?: "Saved host").toRoute()
                        }, onOpenSaved = { row ->
                            conversationRoute = ConversationSource.Saved(row.conversation.target, row.conversation.agentName,
                                row.conversation.sessionName, row.hostName, row.conversation.agentSessionId).toRoute()
                        }, onSettings = { destinationName = Destination.Settings.name }, attentionContent = { target ->
                            attentionRepository?.let { AttentionPanel(it, target, sessionSnapshot) }
                        }, cachedHostIds = cachedHostIds, onRetryConnection = onRetryConnection)
                    else key(source) {
                        val conversationScope = rememberCoroutineScope()
                        val model = remember(repository, source) { ConversationViewModel(repository, source, conversationScope) }
                        ConversationScreen(model, sessionSnapshot, onBack = { conversationRoute = emptyList() },
                            onMail = {
                                mailAgent = when (source) {
                                    is ConversationSource.Live -> source.target.let { listOf(it.hostId.value, it.sessionId, it.daemonBootId, it.windowId) }
                                    is ConversationSource.Saved -> emptyList()
                                }
                                destinationName = Destination.Mail.name
                            }, attentionContent = {
                                if (source is ConversationSource.Live) attentionRepository?.let { AttentionPanel(it, source.target, sessionSnapshot) }
                            })
                    }
                    Destination.Mail -> mailState.SaveableStateProvider("mail") {
                        if (mailModel != null) MailScreen(mailModel, hosts, mailAgent, onInitialAgentConsumed = { mailAgent = emptyList() }, cachedHostIds = cachedHostIds) else mailContent()
                    }
                    Destination.Settings -> settingsContent()
                    Destination.Terminal -> error("Terminal has no route")
                }
            }
            BottomDestinations(destination) { if (it != Destination.Terminal) destinationName = it.name }
        }
    }
}

@Composable
fun FeaturePlaceholder(title: String, message: String) {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HostSettingsPlaceholder(hosts: List<HostProfile>, onSelectHost: (HostId) -> Unit) {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)
        Text("Saved hosts", style = MaterialTheme.typography.titleMedium)
        if (hosts.isEmpty()) Text("No paired hosts yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        hosts.forEach { host -> TextButton(onClick = { onSelectHost(host.id) }) { Text(host.machine) } }
    }
}
