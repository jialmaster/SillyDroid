package com.jm.sillydroid.feature.main.ui.home

import android.os.Bundle
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.savedstate.SavedStateRegistryOwner
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.domain.bootstrap.BootstrapController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 把 MainActivity 上跨重建仍要保留的会话级状态集中托管，
 * 避免 Activity 自己持有大量裸字段同时混杂 UI 操作与 BootstrapController 订阅。
 *
 * - bootstrapSnapshot: 由 ViewModel 在 viewModelScope 内持续订阅 processManager.snapshot；
 *   Activity 通过 repeatOnLifecycle 重新接收。
 * - loadedUrl / pendingLocalRetryAttempts 走 SavedStateHandle，可跨进程死亡恢复。
 * - 其余字段是进程生命期内的轻量瞬态状态，不走 SavedStateHandle。
 */
class HomeViewModel(
    private val bootstrapController: BootstrapController,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : ViewModel() {

    private val mutableBootstrapSnapshot = MutableStateFlow(bootstrapController.currentSnapshot())
    val bootstrapSnapshot: StateFlow<BootstrapSessionSnapshot> = mutableBootstrapSnapshot.asStateFlow()

    var loadedUrl: String
        get() = savedStateHandle[KEY_LOADED_URL] ?: ""
        set(value) { savedStateHandle[KEY_LOADED_URL] = value }

    var pendingLocalRetryAttempts: Int
        get() = savedStateHandle[KEY_PENDING_LOCAL_RETRY] ?: 0
        set(value) { savedStateHandle[KEY_PENDING_LOCAL_RETRY] = value }

    var hasRestoredWebViewState: Boolean = false
    var isOpeningBootstrapSettings: Boolean = false
    var isPullGestureRefreshing: Boolean = false
    var isImeVisible: Boolean = false

    init {
        viewModelScope.launch {
            bootstrapController.snapshot.collect { snapshot ->
                mutableBootstrapSnapshot.value = snapshot
            }
        }
    }

    fun resetForBootstrapRestart() {
        hasRestoredWebViewState = false
        loadedUrl = ""
        pendingLocalRetryAttempts = 0
    }

    fun resetAfterRendererRecreated() {
        hasRestoredWebViewState = false
        loadedUrl = ""
        pendingLocalRetryAttempts = 0
    }

    class Factory(
        owner: SavedStateRegistryOwner,
        private val bootstrapController: BootstrapController,
        defaultArgs: Bundle? = null
    ) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            key: String,
            modelClass: Class<T>,
            handle: SavedStateHandle
        ): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return HomeViewModel(bootstrapController, handle) as T
        }
    }

    private companion object {
        const val KEY_LOADED_URL = "home.loaded_url"
        const val KEY_PENDING_LOCAL_RETRY = "home.pending_local_retry"
    }
}
