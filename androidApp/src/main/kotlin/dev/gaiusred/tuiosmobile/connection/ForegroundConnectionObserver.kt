package dev.gaiusred.tuiosmobile.connection

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Attach to the activity/app lifecycle when app composition is added. No background service. */
class ForegroundConnectionObserver(private val connection: ForegroundHostConnection,
    private val scope: CoroutineScope) : LifecycleEventObserver {
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> scope.launch { connection.setForeground(true) }
            Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> scope.launch { connection.setForeground(false) }
            else -> Unit
        }
    }
}
