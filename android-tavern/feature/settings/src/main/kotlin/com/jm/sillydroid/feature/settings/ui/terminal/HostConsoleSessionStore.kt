package com.jm.sillydroid.feature.settings.ui.terminal

import com.jm.sillydroid.core.common.DispatcherProvider
import com.jm.sillydroid.domain.bootstrap.ConsoleRuntimeRepository
import com.jm.sillydroid.domain.bootstrap.ConsoleShellLaunchSpec
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class HostConsolePhase {
    IDLE,
    STARTING,
    READY,
    FAILED,
    EXITED
}

data class HostConsoleSessionState(
    val phase: HostConsolePhase = HostConsolePhase.IDLE,
    val sessionId: String? = null,
    val title: String = "",
    val statusMessage: String = "终端尚未初始化。",
    val details: String = "首次切到终端页签时只会启动 shell，会话在同一次 app 进程内复用。",
    val progressPercent: Int? = null,
    val isRunning: Boolean = false
)

interface HostConsoleSessionCallbacks {
    fun onSessionTitleChanged(sessionId: String, title: String)
    fun onSessionFinished(sessionId: String, exitStatus: Int)
}

interface HostConsoleSessionHandle {
    val sessionId: String
    val title: String
    val isRunning: Boolean

    /**
     * TerminalSessionClient 必须允许页面层在每次 attach 时重新绑定，
     * 这样全局 shell 复用时才能切换到新的 TerminalView / Activity 生命周期，而不是把旧 view client 永久锁死。
     */
    fun attach(terminalView: TerminalView, terminalSessionClient: TerminalSessionClient)
    fun detach()
    fun sendControlCharacter(character: Char): Boolean
    fun sendKeyCode(keyCode: Int, keyMod: Int = 0): Boolean
    fun pasteFromClipboard(): Boolean
    fun clearScreen(): Boolean
    fun finishIfRunning()
}

fun interface HostConsoleSessionFactory {
    fun create(
        launchSpec: ConsoleShellLaunchSpec,
        callbacks: HostConsoleSessionCallbacks
    ): HostConsoleSessionHandle
}

/**
 * 进程内全局只允许一个 console shell，会话切页/退后台后继续存活，但 app 进程死亡后不恢复。
 * 因此 store 只负责单进程生命周期，不引入 service/通知等跨进程保活语义。
 */
class HostConsoleSessionStore(
    private val consoleRuntimeRepository: ConsoleRuntimeRepository,
    private val sessionFactory: HostConsoleSessionFactory,
    private val dispatchers: DispatcherProvider
) {
    private val initializationMutex = Mutex()
    private val _state = MutableStateFlow(HostConsoleSessionState())
    private var currentSession: HostConsoleSessionHandle? = null

    val state: StateFlow<HostConsoleSessionState> = _state.asStateFlow()

    suspend fun ensureSession(): HostConsoleSessionHandle {
        currentSession?.takeIf { session -> session.isRunning }?.let { session ->
            _state.value = readyState(session, "终端已连接。")
            return session
        }

        return initializationMutex.withLock {
            currentSession?.takeIf { session -> session.isRunning }?.let { session ->
                _state.value = readyState(session, "终端已连接。")
                return@withLock session
            }

            createNewSession()
        }
    }

    suspend fun recreateSession(): HostConsoleSessionHandle {
        return initializationMutex.withLock {
            currentSession?.finishIfRunning()
            currentSession = null
            createNewSession()
        }
    }

    fun currentSessionOrNull(): HostConsoleSessionHandle? {
        return currentSession
    }

    private suspend fun createNewSession(): HostConsoleSessionHandle {
        _state.value = HostConsoleSessionState(
            phase = HostConsolePhase.STARTING,
            statusMessage = "正在启动终端 shell。",
            details = "只创建终端会话，不解包、不刷新 rootfs/server 资产。",
            progressPercent = null,
            isRunning = false
        )

        try {
            val launchSpec = withContext(dispatchers.io) {
                consoleRuntimeRepository.createShellLaunchSpec()
            }

            // Termux 的 TerminalSession 构造阶段会创建 MainThreadHandler，并立即接入 emulator/client 更新链。
            // 这里必须回到主线程创建 session；如果退回 IO 线程，首屏 transcript、prompt 和 IME 输入都可能直接失效。
            val session = sessionFactory.create(
                launchSpec = launchSpec,
                callbacks = object : HostConsoleSessionCallbacks {
                    override fun onSessionTitleChanged(sessionId: String, title: String) {
                        if (currentSession?.sessionId != sessionId) {
                            return
                        }
                        _state.value = readyState(currentSession ?: return, "终端已连接。", title)
                    }

                    override fun onSessionFinished(sessionId: String, exitStatus: Int) {
                        if (currentSession?.sessionId != sessionId) {
                            return
                        }
                        _state.value = HostConsoleSessionState(
                            phase = HostConsolePhase.EXITED,
                            sessionId = sessionId,
                            title = currentSession?.title.orEmpty(),
                            statusMessage = "终端进程已退出。",
                            details = "exitCode=$exitStatus。点击“重置会话”或重新进入终端页签会创建新 shell。",
                            progressPercent = null,
                            isRunning = false
                        )
                    }
                }
            )
            currentSession = session
            _state.value = readyState(session, "终端已连接。")
            return session
        } catch (exception: Exception) {
            currentSession = null
            _state.value = HostConsoleSessionState(
                phase = HostConsolePhase.FAILED,
                statusMessage = "终端初始化失败。",
                details = exception.message ?: "未拿到可用的终端 shell 启动参数。",
                progressPercent = null,
                isRunning = false
            )
            throw exception
        }
    }

    private fun readyState(
        session: HostConsoleSessionHandle,
        statusMessage: String,
        titleOverride: String = session.title
    ): HostConsoleSessionState {
        return HostConsoleSessionState(
            phase = HostConsolePhase.READY,
            sessionId = session.sessionId,
            title = titleOverride,
            statusMessage = statusMessage,
            details = "当前 shell 会在同一次 app 进程里复用；只有手动重置或进程死亡才会重建。",
            progressPercent = null,
            isRunning = session.isRunning
        )
    }
}

object HostConsoleSessionStoreRegistry {
    @Volatile
    private var sharedStore: HostConsoleSessionStore? = null

    @Synchronized
    fun getOrCreate(
        consoleRuntimeRepository: ConsoleRuntimeRepository,
        sessionFactory: HostConsoleSessionFactory,
        dispatchers: DispatcherProvider
    ): HostConsoleSessionStore {
        return sharedStore ?: HostConsoleSessionStore(
            consoleRuntimeRepository = consoleRuntimeRepository,
            sessionFactory = sessionFactory,
            dispatchers = dispatchers
        ).also { store ->
            sharedStore = store
        }
    }

    internal fun resetForTests() {
        sharedStore = null
    }
}
