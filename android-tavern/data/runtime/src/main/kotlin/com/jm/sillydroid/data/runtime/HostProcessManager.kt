package com.jm.sillydroid.data.runtime
import android.content.Context
import androidx.core.content.ContextCompat
import com.jm.sillydroid.core.model.bootstrap.BootstrapEvent
import com.jm.sillydroid.core.model.bootstrap.BootstrapLifecycle
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.domain.bootstrap.BootstrapController
import com.jm.sillydroid.core.common.DispatcherProvider
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

interface HostProcessManager : BootstrapController {
    override fun start(retry: Boolean)

    fun stopForSettings()

    override fun restart() {
        start(retry = true)
    }

}

class DefaultHostProcessManager(
    context: Context,
    private val dispatchers: DispatcherProvider
) : HostProcessManager {
    private val appContext = context.applicationContext

    private val stoppedLifecycles = setOf(
        BootstrapLifecycle.CONFIGURING,
        BootstrapLifecycle.IDLE,
        BootstrapLifecycle.FAILED_BLOCKED,
        BootstrapLifecycle.FAILED_ERROR,
        BootstrapLifecycle.STOPPED
    )

    override val snapshot: StateFlow<BootstrapSessionSnapshot>
        get() = BootstrapSessionRuntimeStore.snapshot

    override val events: SharedFlow<BootstrapEvent>
        get() = BootstrapSessionRuntimeStore.events

    override fun currentSnapshot(): BootstrapSessionSnapshot {
        return snapshot.value
    }

    override fun start(retry: Boolean) {
        val intent = StartupCoordinatorService.createStartIntent(appContext, retry = retry)
        ContextCompat.startForegroundService(appContext, intent)
    }

    override fun stopForSettings() {
        appContext.startService(StartupCoordinatorService.createStopForSettingsIntent(appContext))
    }

    override suspend fun stopForSettingsAndAwait(timeoutMessage: String) {
        val timeoutMillis = 15_000L
        if (currentSnapshot().lifecycle !in stoppedLifecycles) {
            withContext(dispatchers.mainImmediate) {
                stopForSettings()
            }
        }

        val stoppedState = withTimeoutOrNull(timeoutMillis) {
            snapshot.first { sessionSnapshot ->
                sessionSnapshot.lifecycle in stoppedLifecycles
            }
        }

        if (stoppedState == null) {
            throw BootstrapException(BootstrapError.RuntimeStopTimeout(timeoutMessage))
        }
    }
}
