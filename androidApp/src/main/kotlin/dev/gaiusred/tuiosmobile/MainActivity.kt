package dev.gaiusred.tuiosmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.gaiusred.tuiosmobile.ui.AppRoot
import dev.gaiusred.tuiosmobile.ui.design.MessagesTheme
import dev.gaiusred.tuiosmobile.ui.settings.SettingsScreen

class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = ViewModelProvider(this)[AppContainer::class.java]
        setContent {
            val hosts by container.hosts.collectAsState()
            val connection by container.connection.state.collectAsState()
            val cachedHostIds by container.cachedHostIds.collectAsState()
            MessagesTheme {
                AppRoot(container.sessions, hosts, connection, container::selectHost, mailRepository = container.mail,
                    attentionRepository = container.attention, cachedHostIds = cachedHostIds, onRetryConnection = container::retryConnection,
                    settingsContent = { SettingsScreen(container.settings) })
            }
        }
    }

    override fun onStart() { super.onStart(); container.setForeground(true) }
    override fun onStop() { container.setForeground(false); super.onStop() }
}
