package com.jm.sillydroid.feature.settings.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView

class TermuxHostConsoleSessionFactory(
    context: Context
) : HostConsoleSessionFactory {
    private val appContext = context.applicationContext

    override fun create(
        launchSpec: com.jm.sillydroid.domain.bootstrap.ConsoleShellLaunchSpec,
        callbacks: HostConsoleSessionCallbacks
    ): HostConsoleSessionHandle {
        return TermuxHostConsoleSession(
            appContext = appContext,
            launchSpec = launchSpec,
            callbacks = callbacks
        )
    }
}

private class TermuxHostConsoleSession(
    private val appContext: Context,
    launchSpec: com.jm.sillydroid.domain.bootstrap.ConsoleShellLaunchSpec,
    private val callbacks: HostConsoleSessionCallbacks
) : HostConsoleSessionHandle {
    private var cachedTitle = "SillyDroid Console"
    private val forwardingSessionClient = ForwardingTerminalSessionClient(
        appContext = appContext,
        callbacks = callbacks,
        sessionIdProvider = { sessionId },
        onTitleUpdated = { title ->
            cachedTitle = title
        }
    )

    /**
     * 全局 shell 会跨 Activity/view 生命周期复用，因此 session 自身只保留进程级状态；
     * 每次 attach 时再把当前页面的 TerminalSessionClient 绑定进去，避免旧 Activity 持有的 view 回调泄漏到下一次进入。
     */
    private val session = TerminalSession(
        launchSpec.shellPath,
        launchSpec.workingDirectory,
        launchSpec.arguments.toTypedArray(),
        launchSpec.environment.map { entry -> "${entry.key}=${entry.value}" }.toTypedArray(),
        launchSpec.transcriptRows,
        forwardingSessionClient
    ).also { terminalSession ->
        terminalSession.mSessionName = "SillyDroid Console"
    }

    override val sessionId: String
        get() = session.mHandle

    override val title: String
        get() = cachedTitle

    override val isRunning: Boolean
        get() = session.isRunning

    override fun attach(terminalView: TerminalView, terminalSessionClient: TerminalSessionClient) {
        forwardingSessionClient.bindAttachedClient(terminalSessionClient)
        session.updateTerminalSessionClient(forwardingSessionClient)
        terminalView.attachSession(session)
        terminalView.onScreenUpdated()
    }

    override fun detach() {
        forwardingSessionClient.bindAttachedClient(null)
        session.updateTerminalSessionClient(forwardingSessionClient)
    }

    override fun sendControlCharacter(character: Char): Boolean {
        val normalized = character.lowercaseChar()
        val codePoint = when (normalized) {
            in 'a'..'z' -> normalized.code - 'a'.code + 1
            ' ' -> 0
            '[' -> 27
            '\\' -> 28
            ']' -> 29
            '^' -> 30
            '_', '/' -> 31
            else -> return false
        }
        session.writeCodePoint(false, codePoint)
        return true
    }

    override fun sendKeyCode(keyCode: Int, keyMod: Int): Boolean {
        val emulator = session.emulator ?: return false
        val code = KeyHandler.getCode(
            keyCode,
            keyMod,
            emulator.isCursorKeysApplicationMode,
            emulator.isKeypadApplicationMode
        ) ?: return false
        session.write(code)
        return true
    }

    override fun pasteFromClipboard(): Boolean {
        // 终端长按粘贴必须复用和 Ctrl/IME 同一条 session 输入链路，
        // 不能退回 Activity 侧自己拼输入框逻辑，否则会重新引入“键盘出来了但字符没进 shell”的分叉。
        forwardingSessionClient.onPasteTextFromClipboard(session)
        return true
    }

    override fun clearScreen(): Boolean {
        return sendControlCharacter('l')
    }

    override fun finishIfRunning() {
        session.finishIfRunning()
    }
}

/**
 * 进程级 session client 负责两个不会随着页面销毁而消失的职责：
 * 把标题/退出状态回传给 store，并提供统一的剪贴板桥接。
 * 页面自己的 TerminalView 更新则继续通过 attach 时绑定的 session client 转发。
 */
private class ForwardingTerminalSessionClient(
    private val appContext: Context,
    private val callbacks: HostConsoleSessionCallbacks,
    private val sessionIdProvider: () -> String,
    private val onTitleUpdated: (String) -> Unit
) : TerminalSessionClient {
    @Volatile
    private var attachedClient: TerminalSessionClient? = null

    fun bindAttachedClient(client: TerminalSessionClient?) {
        attachedClient = client
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        attachedClient?.onTextChanged(changedSession)
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        val title = changedSession.title?.takeIf { value -> value.isNotBlank() } ?: "SillyDroid Console"
        onTitleUpdated(title)
        callbacks.onSessionTitleChanged(sessionIdProvider(), title)
        attachedClient?.onTitleChanged(changedSession)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        callbacks.onSessionFinished(sessionIdProvider(), finishedSession.exitStatus)
        attachedClient?.onSessionFinished(finishedSession)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboardManager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboardManager.setPrimaryClip(ClipData.newPlainText("terminal-copy", text))
        attachedClient?.onCopyTextToClipboard(session, text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboardManager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString().orEmpty()
        if (text.isNotEmpty()) {
            session.write(text)
        }
        attachedClient?.onPasteTextFromClipboard(session)
    }

    override fun onBell(session: TerminalSession) {
        attachedClient?.onBell(session)
    }

    override fun onColorsChanged(session: TerminalSession) {
        attachedClient?.onColorsChanged(session)
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        attachedClient?.onTerminalCursorStateChange(state)
    }

    override fun getTerminalCursorStyle(): Int {
        return attachedClient?.terminalCursorStyle ?: TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
    }

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, e.message, e)
    }
}
