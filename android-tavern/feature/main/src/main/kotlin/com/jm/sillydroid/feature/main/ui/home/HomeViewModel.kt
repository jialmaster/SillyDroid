package com.jm.sillydroid.feature.main.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
 * - 其余字段是 WebView 恢复 / 重试 / 设置入口防抖等需要在生命周期事件之间保留的轻量状态。
 */
class HomeViewModel(
    private val bootstrapController: BootstrapController
) : ViewModel() {

    private val mutableBootstrapSnapshot = MutableStateFlow(bootstrapController.currentSnapshot())
    val bootstrapSnapshot: StateFlow<BootstrapSessionSnapshot> = mutableBootstrapSnapshot.asStateFlow()

    var loadedUrl: String = ""
    var hasRestoredWebViewState: Boolean = false
    var pendingLocalRetryAttempts: Int = 0
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
        private val bootstrapController: BootstrapController
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return HomeViewModel(bootstrapController) as T
        }
    }
}
