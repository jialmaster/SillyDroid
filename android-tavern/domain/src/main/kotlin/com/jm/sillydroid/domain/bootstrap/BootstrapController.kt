package com.jm.sillydroid.domain.bootstrap

import com.jm.sillydroid.core.model.bootstrap.BootstrapEvent
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface BootstrapSessionRepository {
    val snapshot: StateFlow<BootstrapSessionSnapshot>
    val events: SharedFlow<BootstrapEvent>
    fun currentSnapshot(): BootstrapSessionSnapshot
}

interface BootstrapController : BootstrapSessionRepository {
    fun start(retry: Boolean = false)
    suspend fun stopForSettingsAndAwait(timeoutMessage: String)
    fun retry() {
        start(retry = true)
    }

    fun restart() {
        start(retry = true)
    }
}
