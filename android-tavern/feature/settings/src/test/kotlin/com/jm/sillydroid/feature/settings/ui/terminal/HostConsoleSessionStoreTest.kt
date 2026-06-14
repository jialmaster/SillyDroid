package com.jm.sillydroid.feature.settings.ui.terminal

import com.jm.sillydroid.core.common.DispatcherProvider
import com.jm.sillydroid.domain.bootstrap.ConsoleRuntimeRepository
import com.jm.sillydroid.domain.bootstrap.ConsoleShellLaunchSpec
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HostConsoleSessionStoreTest {
    private val dispatchers = TestDispatcherProvider()

    @Test
    fun `store construction does not create shell launch spec`() {
        val runtimeRepository = FakeConsoleRuntimeRepository()
        HostConsoleSessionStore(runtimeRepository, FakeHostConsoleSessionFactory(), dispatchers)

        assertEquals(0, runtimeRepository.createSpecCalls)
    }

    @Test
    fun `ensureSession creates session once without preparing assets`() = runTest {
        val runtimeRepository = FakeConsoleRuntimeRepository()
        val sessionFactory = FakeHostConsoleSessionFactory()
        val store = HostConsoleSessionStore(runtimeRepository, sessionFactory, dispatchers)

        val firstSession = store.ensureSession()
        val secondSession = store.ensureSession()

        assertSame(firstSession, secondSession)
        assertEquals(1, runtimeRepository.createSpecCalls)
        assertEquals(1, sessionFactory.createCalls)
        assertEquals(firstSession.sessionId, store.state.value.sessionId)
        assertEquals(HostConsolePhase.READY, store.state.value.phase)
    }

    @Test
    fun `ensureSession recreates shell after previous session exits`() = runTest {
        val runtimeRepository = FakeConsoleRuntimeRepository()
        val sessionFactory = FakeHostConsoleSessionFactory()
        val store = HostConsoleSessionStore(runtimeRepository, sessionFactory, dispatchers)

        val firstSession = store.ensureSession() as FakeHostConsoleSessionHandle
        firstSession.isRunning = false

        val secondSession = store.ensureSession()

        assertFalse(firstSession === secondSession)
        assertEquals(2, runtimeRepository.createSpecCalls)
        assertEquals(2, sessionFactory.createCalls)
        assertEquals(secondSession.sessionId, store.state.value.sessionId)
    }

    @Test
    fun `recreateSession finishes current shell and creates a new one`() = runTest {
        val runtimeRepository = FakeConsoleRuntimeRepository()
        val sessionFactory = FakeHostConsoleSessionFactory()
        val store = HostConsoleSessionStore(runtimeRepository, sessionFactory, dispatchers)

        val firstSession = store.ensureSession() as FakeHostConsoleSessionHandle
        val secondSession = store.recreateSession() as FakeHostConsoleSessionHandle

        assertTrue(firstSession.finishCalls == 1)
        assertFalse(firstSession.isRunning)
        assertFalse(firstSession === secondSession)
        assertEquals(2, runtimeRepository.createSpecCalls)
        assertEquals(2, sessionFactory.createCalls)
        assertEquals(secondSession.sessionId, store.state.value.sessionId)
    }

    @Test
    fun `session finished callback updates state to exited`() = runTest {
        val runtimeRepository = FakeConsoleRuntimeRepository()
        val sessionFactory = FakeHostConsoleSessionFactory()
        val store = HostConsoleSessionStore(runtimeRepository, sessionFactory, dispatchers)

        val session = store.ensureSession()
        sessionFactory.lastCallbacks!!.onSessionFinished(session.sessionId, 130)

        assertEquals(HostConsolePhase.EXITED, store.state.value.phase)
        assertFalse(store.state.value.isRunning)
        assertEquals(session.sessionId, store.state.value.sessionId)
    }
}

private class TestDispatcherProvider : DispatcherProvider {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = UnconfinedTestDispatcher()

    override val main = dispatcher
    override val mainImmediate = dispatcher
    override val io = dispatcher
    override val default = dispatcher
}

private class FakeConsoleRuntimeRepository : ConsoleRuntimeRepository {
    var createSpecCalls = 0

    override fun createShellLaunchSpec(): ConsoleShellLaunchSpec {
        createSpecCalls += 1
        return ConsoleShellLaunchSpec(
            shellPath = "/system/bin/sh",
            workingDirectory = "/bootstrap",
            arguments = listOf("/bootstrap/scripts/start-console-shell.sh"),
            environment = emptyMap(),
            transcriptRows = 10_000
        )
    }
}

private class FakeHostConsoleSessionFactory : HostConsoleSessionFactory {
    var createCalls = 0
    var lastCallbacks: HostConsoleSessionCallbacks? = null

    override fun create(
        launchSpec: ConsoleShellLaunchSpec,
        callbacks: HostConsoleSessionCallbacks
    ): HostConsoleSessionHandle {
        createCalls += 1
        lastCallbacks = callbacks
        return FakeHostConsoleSessionHandle(sessionId = "session-$createCalls")
    }
}

private class FakeHostConsoleSessionHandle(
    override val sessionId: String,
    override val title: String = "Console $sessionId"
) : HostConsoleSessionHandle {
    override var isRunning: Boolean = true
    var finishCalls: Int = 0

    override fun attach(terminalView: TerminalView, terminalSessionClient: TerminalSessionClient) {
    }

    override fun detach() {
    }

    override fun sendControlCharacter(character: Char): Boolean = true

    override fun sendKeyCode(keyCode: Int, keyMod: Int): Boolean = true

    override fun pasteFromClipboard(): Boolean = true

    override fun clearScreen(): Boolean = true

    override fun finishIfRunning() {
        finishCalls += 1
        isRunning = false
    }
}
