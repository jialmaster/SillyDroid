package com.jm.sillydroid.data.runtime
import com.jm.sillydroid.core.model.bootstrap.BootstrapEvent
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.bootstrap.withDerivedUiFlags
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

internal object BootstrapSessionRuntimeStore {
    private val mutableSnapshot = MutableStateFlow(BootstrapSessionSnapshot())
    private val mutableEvents = MutableSharedFlow<BootstrapEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )

    val snapshot = mutableSnapshot.asStateFlow()
    val events = mutableEvents.asSharedFlow()

    fun update(snapshot: BootstrapSessionSnapshot) {
        mutableSnapshot.value = snapshot.withDerivedUiFlags()
    }

    fun tryEmit(event: BootstrapEvent) {
        mutableEvents.tryEmit(event)
    }

    fun reset() {
        mutableSnapshot.value = BootstrapSessionSnapshot()
    }
}
