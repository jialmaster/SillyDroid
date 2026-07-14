package com.jm.sillydroid.feature.main.floatingbrowser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.feature.main.ui.home.webview.TavernBrowserHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/** 浏览器表面当前由哪个宿主窗口持有。 */
enum class FloatingBrowserAttachmentState {
    DETACHED,
    ACTIVITY,
    OVERLAY,
    DESTROYED
}

/** coordinator 依赖的最小进程级 surface 控制契约；测试可用纯状态 fake 验证迁移。 */
internal interface FloatingBrowserSurfaceController {
    val activeSessionIdentity: String?
    val activeBrowserEngine: BrowserEngine?
    val activePageLoadCount: Long

    /** 当前唯一浏览器表面是否具备迁移条件。 */
    fun canAttachActiveSurfaceToOverlay(): Boolean
    /** 把当前唯一浏览器表面迁移到指定 overlay 容器。 */
    fun attachActiveSurfaceToOverlay(container: ViewGroup): Boolean
    /** 把当前唯一浏览器表面恢复到仍存活的 Activity 容器。 */
    fun restoreActiveSurfaceToActivity(): Boolean
    /** 无 Activity 可恢复时从 overlay 容器摘下当前表面。 */
    fun detachActiveSurfaceFromOverlay(): Boolean
    /** 当前唯一浏览器表面是否由指定容器持有。 */
    fun isActiveSurfaceAttachedTo(container: ViewGroup): Boolean
    /** 根据真实父容器返回当前挂载状态。 */
    fun currentAttachmentState(): FloatingBrowserAttachmentState
}

/**
 * 进程级浏览器表面协调器。
 *
 * 允许：登记 Activity UI host、在 Activity/overlay/无窗口之间迁移进程级真实表面、输出无内容诊断。
 * 不允许：持有已销毁 Activity、创建第二浏览器、导航页面，或恢复进程死亡后的 DOM。
 */
class FloatingBrowserSessionCoordinator internal constructor(
    private val surfaceStore: FloatingBrowserSurfaceController
) {
    private var host: TavernBrowserHost? = null
    private var diagnosticSink: (String) -> Unit = {}

    var state: FloatingBrowserAttachmentState = FloatingBrowserAttachmentState.DETACHED
        private set

    val sessionIdentity: String?
        get() = surfaceStore.activeSessionIdentity

    /** 登记当前 Activity 已配置完成的 UI host；真实浏览器身份必须与进程 store 一致。 */
    @Synchronized
    fun registerHost(browserHost: TavernBrowserHost, sink: (String) -> Unit = {}) {
        val retainedSession = surfaceStore.activeSessionIdentity
        check(retainedSession == browserHost.browserSessionIdentity) {
            "Cannot register a browser host that does not own the retained browser session."
        }
        val existingHost = host
        if (existingHost != null && existingHost !== browserHost && state == FloatingBrowserAttachmentState.OVERLAY) {
            check(existingHost.browserSessionIdentity == browserHost.browserSessionIdentity) {
                "Cannot replace an overlay-attached browser host with a different browser session."
            }
        }
        host = browserHost
        diagnosticSink = sink
        state = surfaceStore.currentAttachmentState()
        record(
            "event=host_registered engine=${browserHost.browserEngine.name} " +
                "session=${browserHost.browserSessionIdentity} state=${state.name}"
        )
    }

    /** 当前进程级浏览器表面是否满足迁移到 overlay 的前置条件。 */
    @Synchronized
    fun canAttachToOverlay(): Boolean {
        val currentHost = host
        val canAttach = if (
            currentHost != null && currentHost.browserSessionIdentity == surfaceStore.activeSessionIdentity
        ) {
            currentHost.canAttachToFloatingBrowser()
        } else {
            surfaceStore.canAttachActiveSurfaceToOverlay()
        }
        return state != FloatingBrowserAttachmentState.DESTROYED &&
            state != FloatingBrowserAttachmentState.OVERLAY &&
            canAttach
    }

    /** 将当前进程级浏览器表面移入 overlay；迁移不依赖 Activity host 是否仍存活。 */
    @Synchronized
    fun attachToOverlay(container: ViewGroup): Boolean {
        if (state == FloatingBrowserAttachmentState.DESTROYED) {
            return false
        }
        if (state == FloatingBrowserAttachmentState.OVERLAY) {
            return surfaceStore.isActiveSurfaceAttachedTo(container)
        }
        val currentHost = host
        val attached = if (
            currentHost != null && currentHost.browserSessionIdentity == surfaceStore.activeSessionIdentity
        ) {
            // 存活宿主负责迁移后的内核专属诊断；无宿主时仍可由进程级 store 独立迁移。
            currentHost.attachToFloatingBrowser(container)
        } else {
            surfaceStore.attachActiveSurfaceToOverlay(container)
        }
        if (attached) {
            state = FloatingBrowserAttachmentState.OVERLAY
            recordActiveSurfaceEvent("attached_to_overlay")
        }
        return attached
    }

    /** 将 overlay 中的同一浏览器表面挂回当前仍存活的 Activity 容器。 */
    @Synchronized
    fun restoreToActivity(): Boolean {
        if (state == FloatingBrowserAttachmentState.ACTIVITY) {
            return surfaceStore.currentAttachmentState() == FloatingBrowserAttachmentState.ACTIVITY
        }
        if (state != FloatingBrowserAttachmentState.OVERLAY) {
            return false
        }
        val currentHost = host
        val restored = if (
            currentHost != null && currentHost.browserSessionIdentity == surfaceStore.activeSessionIdentity
        ) {
            // Activity 宿主恢复浏览器可见性、焦点和诊断；宿主已销毁时由 store 返回失败并安全 detach。
            currentHost.attachToActivityBrowser()
        } else {
            surfaceStore.restoreActiveSurfaceToActivity()
        }
        if (restored) {
            state = FloatingBrowserAttachmentState.ACTIVITY
            recordActiveSurfaceEvent("restored_to_activity")
        }
        return restored
    }

    /** 无可用 Activity 时把表面从已移除的 overlay 窗口安全摘下，等待下个可见宿主。 */
    @Synchronized
    fun detachFromOverlay(): Boolean {
        if (state != FloatingBrowserAttachmentState.OVERLAY) {
            return state == FloatingBrowserAttachmentState.DETACHED
        }
        val detached = surfaceStore.detachActiveSurfaceFromOverlay()
        if (detached) {
            state = FloatingBrowserAttachmentState.DETACHED
            recordActiveSurfaceEvent("detached_from_overlay")
        }
        return detached
    }

    /** overlay Window 完成挂载后通知当前内核记录最终页面可见性，不在迁移中途提前取样。 */
    @Synchronized
    fun notifyOverlayWindowVisible(): Boolean {
        if (state != FloatingBrowserAttachmentState.OVERLAY) {
            return false
        }
        val currentHost = host?.takeIf { browserHost ->
            browserHost.browserSessionIdentity == surfaceStore.activeSessionIdentity
        } ?: return false
        return runCatching {
            currentHost.onFloatingBrowserWindowVisible()
            recordActiveSurfaceEvent("overlay_window_visible")
            true
        }.getOrDefault(false)
    }

    /** Activity 销毁时只解除 UI host；进程级 surface/session 继续由 store 持有。 */
    @Synchronized
    fun unregisterHost(browserHost: TavernBrowserHost) {
        if (host !== browserHost) {
            return
        }
        record(
            "event=host_unregistered engine=${browserHost.browserEngine.name} " +
                "session=${browserHost.browserSessionIdentity}"
        )
        host = null
        diagnosticSink = {}
        state = surfaceStore.currentAttachmentState()
    }

    /** 浏览器通知桥可据此判断进程级页面是否仍存在，不再依赖 Activity 是否存活。 */
    @Synchronized
    fun isSessionActive(): Boolean {
        return state != FloatingBrowserAttachmentState.DESTROYED && surfaceStore.activeSessionIdentity != null
    }

    /** 进程级功能明确销毁时清空协调状态；浏览器资源仍由所属宿主的销毁路径负责。 */
    @Synchronized
    fun markDestroyed() {
        host = null
        diagnosticSink = {}
        state = FloatingBrowserAttachmentState.DESTROYED
    }

    /** 记录当前 surface 的稳定身份、内核、状态和页面加载计数。 */
    private fun recordActiveSurfaceEvent(event: String) {
        record(
            "event=$event engine=${surfaceStore.activeBrowserEngine?.name.orEmpty()} " +
                "session=${surfaceStore.activeSessionIdentity.orEmpty()} state=${state.name} " +
                "pageLoadCount=${surfaceStore.activePageLoadCount}"
        )
    }

    /** 诊断写入失败不能影响浏览器迁移主流程。 */
    private fun record(body: String) {
        runCatching { diagnosticSink(body) }
    }
}

/**
 * 当前进程内唯一的悬浮浏览器运行时。
 *
 * 允许：由 Application 持有 coordinator、抑制注册表、UI delegate、真实浏览器 surface/session 和桥接协程。
 * 不允许：跨进程恢复 DOM，也不负责启动或停止 Node 后端。
 */
class FloatingBrowserRuntimeState {
    val suppressionRegistry: FloatingBrowserSuppressionRegistry = FloatingBrowserSuppressionRegistry()
    val browserSurfaceStore: FloatingBrowserSurfaceStore = FloatingBrowserSurfaceStore()
    val coordinator: FloatingBrowserSessionCoordinator = FloatingBrowserSessionCoordinator(browserSurfaceStore)
    val uiDelegateRegistry: FloatingBrowserUiDelegateRegistry = FloatingBrowserUiDelegateRegistry(coordinator::isSessionActive)
    val browserBridgeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

/** Application 实现该接口后，Activity 与 Service 都从同一处取得进程级悬浮浏览器运行时。 */
interface FloatingBrowserRuntimeProvider {
    val floatingBrowserRuntime: FloatingBrowserRuntimeState
}

/**
 * 进程级浏览器 surface/session 仓库。
 *
 * 允许：仅保留当前内核的一个真实表面、迁移父容器、恢复全屏布局、记录无内容页面加载计数。
 * 不允许：保存 Activity 弹窗、文件选择 callback、系统栏控制器，或同时运行两套浏览器内核。
 */
class FloatingBrowserSurfaceStore : FloatingBrowserSurfaceController {
    private var activeSurface: RetainedBrowserSurface? = null

    override val activeSessionIdentity: String?
        @Synchronized get() = activeSurface?.sessionIdentity

    override val activeBrowserEngine: BrowserEngine?
        @Synchronized get() = activeSurface?.browserEngine

    override val activePageLoadCount: Long
        @Synchronized get() = activeSurface?.pageLoadCount ?: 0L

    /** 获取或创建唯一的进程级 WebView；切换内核时先销毁旧 Gecko 会话。 */
    @Synchronized
    internal fun acquireWebView(activityContext: Context, attachTo: ViewGroup): RetainedWebViewSurface {
        val record = activeSurface as? RetainedWebViewSurface ?: run {
            activeSurface?.destroy()
            RetainedWebViewSurface(
                contextWrapper = MutableContextWrapper(activityContext.applicationContext)
            ).also { created -> activeSurface = created }
        }
        record.attachToActivity(activityContext = activityContext, attachTo = attachTo)
        return record
    }

    /** 获取或创建唯一的进程级 Gecko surface；切换内核时先销毁旧 WebView。 */
    @Synchronized
    internal fun acquireGecko(activityContext: Context, attachTo: ViewGroup): RetainedGeckoSurface {
        val record = activeSurface as? RetainedGeckoSurface ?: run {
            activeSurface?.destroy()
            RetainedGeckoSurface(
                contextWrapper = MutableContextWrapper(activityContext.applicationContext)
            ).also { created -> activeSurface = created }
        }
        record.attachToActivity(activityContext = activityContext, attachTo = attachTo)
        return record
    }

    /** Activity 销毁时解除对应容器并切回 Application Context，不销毁页面会话。 */
    @Synchronized
    fun releaseActivityBinding(surface: View, activityContainer: ViewGroup) {
        activeSurface
            ?.takeIf { retained -> retained.surface === surface }
            ?.releaseActivityBinding(activityContainer)
    }

    /** 兼容迁移调用：只把真实表面 Context 切回 Application，不变更父容器。 */
    @Synchronized
    fun detachSurfaceContext(surface: View) {
        activeSurface
            ?.takeIf { retained -> retained.surface === surface }
            ?.detachContextToApplication()
    }

    /** 当前真实表面是否已加载页面并可进入 overlay。 */
    @Synchronized
    override fun canAttachActiveSurfaceToOverlay(): Boolean = activeSurface?.canAttachToOverlay() == true

    /** 把当前唯一表面迁移到 overlay，并保留离开 Activity 前的完整测量尺寸。 */
    @Synchronized
    override fun attachActiveSurfaceToOverlay(container: ViewGroup): Boolean {
        return activeSurface?.attachToOverlay(container) == true
    }

    /** 把当前唯一表面挂回仍存活的 Activity；没有 Activity 时返回 false。 */
    @Synchronized
    override fun restoreActiveSurfaceToActivity(): Boolean {
        return activeSurface?.restoreToActivity() == true
    }

    /** 从已移除的 overlay 摘下当前表面，避免继续挂在不可见 Window 根节点。 */
    @Synchronized
    override fun detachActiveSurfaceFromOverlay(): Boolean {
        return activeSurface?.detachFromOverlay() == true
    }

    /** 判断当前表面是否已经由指定 overlay 容器持有。 */
    @Synchronized
    override fun isActiveSurfaceAttachedTo(container: ViewGroup): Boolean {
        return activeSurface?.surface?.parent === container
    }

    /** 返回 store 根据真实父容器计算的当前挂载状态。 */
    @Synchronized
    override fun currentAttachmentState(): FloatingBrowserAttachmentState {
        return activeSurface?.attachmentState() ?: FloatingBrowserAttachmentState.DETACHED
    }

    /** 页面主文档开始加载时递增进程级计数，迁移本身不得改变该值。 */
    @Synchronized
    fun incrementPageLoadCount(surface: View): Long {
        val retained = activeSurface?.takeIf { candidate -> candidate.surface === surface }
            ?: return activePageLoadCount
        retained.pageLoadCount += 1L
        return retained.pageLoadCount
    }

    /** 明确销毁当前 System WebView；普通 Activity 销毁不得调用。 */
    @Synchronized
    fun destroyWebViewIfSame(webView: WebView) {
        val record = activeSurface as? RetainedWebViewSurface ?: return
        if (record.webView !== webView) {
            return
        }
        record.destroy()
        activeSurface = null
    }

    /** 明确销毁当前 Gecko 会话；普通 Activity 销毁不得调用。 */
    @Synchronized
    fun destroyGeckoIfSame(geckoView: GeckoView, session: GeckoSession) {
        val record = activeSurface as? RetainedGeckoSurface ?: return
        if (record.geckoView !== geckoView || record.session !== session) {
            return
        }
        record.destroy()
        activeSurface = null
    }
}

/** 进程级浏览器表面的内部迁移契约，不允许暴露 Activity 专属能力。 */
internal interface RetainedBrowserSurface {
    val browserEngine: BrowserEngine
    val sessionIdentity: String
    val surface: View
    var pageLoadCount: Long

    /** 绑定当前 Activity Context 与全屏容器，不触发页面导航。 */
    fun attachToActivity(activityContext: Context, attachTo: ViewGroup)
    /** Activity 销毁时解除旧容器引用，但保留浏览器会话。 */
    fun releaseActivityBinding(activityContainer: ViewGroup)
    /** 把可切换 Context 的基础 Context 降为 Application。 */
    fun detachContextToApplication()
    /** 当前表面是否已经加载页面并可迁移到 overlay。 */
    fun canAttachToOverlay(): Boolean
    /** 把同一表面迁移到 overlay 容器，不触发导航或刷新。 */
    fun attachToOverlay(container: ViewGroup): Boolean
    /** 把同一表面恢复到仍存活的 Activity 容器。 */
    fun restoreToActivity(): Boolean
    /** 从失效 overlay 容器摘下表面并进入无窗口状态。 */
    fun detachFromOverlay(): Boolean
    /** 根据真实父容器返回当前挂载状态。 */
    fun attachmentState(): FloatingBrowserAttachmentState
    /** 永久释放浏览器表面和会话资源。 */
    fun destroy()
}

/** 进程级 System WebView 持有者，只保存真实 View、父容器状态和可切换基础 Context。 */
internal class RetainedWebViewSurface internal constructor(
    private val contextWrapper: MutableContextWrapper
) : RetainedBrowserSurface {
    override val browserEngine: BrowserEngine = BrowserEngine.SYSTEM_WEBVIEW
    val webView: WebView = WebView(contextWrapper).apply {
        layoutParams = matchParentLayoutParams()
    }
    override val surface: View
        get() = webView
    override val sessionIdentity: String
        get() = "${browserEngine.name}:${System.identityHashCode(webView)}"
    override var pageLoadCount: Long = 0L
    private var activityContainer: ViewGroup? = null
    private var overlayContainer: ViewGroup? = null
    private var retainedWidth = 1
    private var retainedHeight = 1

    /** 将 WebView 挂到当前 Activity 容器并恢复 MATCH_PARENT；该操作不得导航页面。 */
    override fun attachToActivity(activityContext: Context, attachTo: ViewGroup) {
        activityContainer = attachTo
        overlayContainer = null
        contextWrapper.baseContext = activityContext
        webView.layoutParams = matchParentLayoutParams()
        moveViewToParent(webView, attachTo, index = 0)
    }

    /** Activity 销毁时只清除旧容器引用；overlay 中的 WebView 保持原位。 */
    override fun releaseActivityBinding(activityContainer: ViewGroup) {
        if (this.activityContainer !== activityContainer) {
            return
        }
        this.activityContainer = null
        if (webView.parent === activityContainer) {
            activityContainer.removeView(webView)
        }
        detachContextToApplication()
    }

    /** overlay 或 Activity 销毁期间切回 Application Context。 */
    override fun detachContextToApplication() {
        contextWrapper.baseContext = contextWrapper.applicationContext
    }

    /** WebView 已有可见主文档时才允许迁移。 */
    override fun canAttachToOverlay(): Boolean {
        return webView.url?.isNotBlank() == true && webView.visibility == View.VISIBLE
    }

    /** 把同一 WebView 移入 overlay，并用离开 Activity 前的全屏尺寸参与测量。 */
    override fun attachToOverlay(container: ViewGroup): Boolean {
        captureFullSize(webView, activityContainer)
        webView.layoutParams = ViewGroup.LayoutParams(retainedWidth, retainedHeight)
        moveViewToParent(webView, container)
        overlayContainer = container
        webView.visibility = View.VISIBLE
        detachContextToApplication()
        return webView.parent === container
    }

    /** 仅在仍有存活 Activity 容器时恢复 WebView。 */
    override fun restoreToActivity(): Boolean {
        val target = activityContainer?.takeIf(::isActivityContainerAlive) ?: run {
            activityContainer = null
            return false
        }
        contextWrapper.baseContext = target.context
        webView.layoutParams = matchParentLayoutParams()
        moveViewToParent(webView, target, index = 0)
        overlayContainer = null
        webView.visibility = View.VISIBLE
        return webView.parent === target
    }

    /** 无 Activity 可恢复时从 overlay 根节点摘下 WebView。 */
    override fun detachFromOverlay(): Boolean {
        val currentOverlay = overlayContainer
        if (currentOverlay != null && webView.parent === currentOverlay) {
            currentOverlay.removeView(webView)
        }
        overlayContainer = null
        detachContextToApplication()
        return webView.parent == null
    }

    /** 根据真实父容器返回当前挂载状态。 */
    override fun attachmentState(): FloatingBrowserAttachmentState {
        return when {
            overlayContainer != null && webView.parent === overlayContainer -> FloatingBrowserAttachmentState.OVERLAY
            activityContainer != null && webView.parent === activityContainer -> FloatingBrowserAttachmentState.ACTIVITY
            else -> FloatingBrowserAttachmentState.DETACHED
        }
    }

    /** 真正释放 WebView 资源，仅用于内核切换、renderer gone 或进程级强制重建。 */
    override fun destroy() {
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        activityContainer = null
        overlayContainer = null
        runCatching { webView.destroy() }
    }

    /** 保存最后一个有效全屏尺寸，防止小尺寸 overlay 触发响应式重排。 */
    private fun captureFullSize(surface: View, activityContainer: ViewGroup?) {
        retainedWidth = sequenceOf(surface.width, activityContainer?.width ?: 0, retainedWidth)
            .firstOrNull { value -> value > 1 }
            ?: 1
        retainedHeight = sequenceOf(surface.height, activityContainer?.height ?: 0, retainedHeight)
            .firstOrNull { value -> value > 1 }
            ?: 1
    }
}

/** 进程级 GeckoView/GeckoSession 持有者，只保存真实会话、父容器状态和可切换 Context。 */
internal class RetainedGeckoSurface internal constructor(
    private val contextWrapper: MutableContextWrapper
) : RetainedBrowserSurface {
    override val browserEngine: BrowserEngine = BrowserEngine.GECKOVIEW
    val geckoView: GeckoView = GeckoView(contextWrapper).apply {
        // SurfaceView 拥有独立 Surface，无法被小尺寸圆形 overlay 的 outline 裁剪；TextureView 才能保留真实页面像素并呈现为球形。
        setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        layoutParams = matchParentLayoutParams()
    }
    val session: GeckoSession = GeckoSession(
        GeckoSessionSettings.Builder()
            .allowJavascript(true)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .displayMode(GeckoSessionSettings.DISPLAY_MODE_BROWSER)
            .useTrackingProtection(false)
            .suspendMediaWhenInactive(false)
            .build()
    )
    override val surface: View
        get() = geckoView
    override val sessionIdentity: String
        get() = "${browserEngine.name}:${System.identityHashCode(session)}"
    override var pageLoadCount: Long = 0L
    var currentUrl: String = ""
    private var activityContainer: ViewGroup? = null
    private var overlayContainer: ViewGroup? = null
    private var retainedWidth = 1
    private var retainedHeight = 1

    /** 将 GeckoView 挂到当前 Activity 容器并恢复 MATCH_PARENT；GeckoSession 身份保持不变。 */
    override fun attachToActivity(activityContext: Context, attachTo: ViewGroup) {
        activityContainer = attachTo
        overlayContainer = null
        contextWrapper.baseContext = activityContext
        geckoView.layoutParams = matchParentLayoutParams()
        moveViewToParent(geckoView, attachTo, index = 0)
    }

    /** Activity 销毁时只清除旧容器引用；overlay 中的 GeckoView 保持原位。 */
    override fun releaseActivityBinding(activityContainer: ViewGroup) {
        if (this.activityContainer !== activityContainer) {
            return
        }
        this.activityContainer = null
        if (geckoView.parent === activityContainer) {
            activityContainer.removeView(geckoView)
        }
        detachContextToApplication()
    }

    /** overlay 或 Activity 销毁期间切回 Application Context。 */
    override fun detachContextToApplication() {
        contextWrapper.baseContext = contextWrapper.applicationContext
    }

    /** GeckoSession 已打开且已有可见主文档时才允许迁移。 */
    override fun canAttachToOverlay(): Boolean {
        return session.isOpen && currentUrl.isNotBlank() && geckoView.visibility == View.VISIBLE
    }

    /** 把同一 GeckoView/Session 移入 overlay，保持 session active 且不调用 loadUri。 */
    override fun attachToOverlay(container: ViewGroup): Boolean {
        captureFullSize(geckoView, activityContainer)
        geckoView.layoutParams = ViewGroup.LayoutParams(retainedWidth, retainedHeight)
        moveViewToParent(geckoView, container)
        overlayContainer = container
        geckoView.visibility = View.VISIBLE
        detachContextToApplication()
        session.setActive(true)
        session.setFocused(false)
        return geckoView.parent === container
    }

    /** 仅在仍有存活 Activity 容器时恢复 GeckoView。 */
    override fun restoreToActivity(): Boolean {
        val target = activityContainer?.takeIf(::isActivityContainerAlive) ?: run {
            activityContainer = null
            return false
        }
        contextWrapper.baseContext = target.context
        geckoView.layoutParams = matchParentLayoutParams()
        moveViewToParent(geckoView, target, index = 0)
        overlayContainer = null
        geckoView.visibility = View.VISIBLE
        session.setActive(true)
        session.setFocused(true)
        return geckoView.parent === target
    }

    /** 无 Activity 可恢复时从 overlay 根节点摘下 GeckoView，并暂停不可见 session。 */
    override fun detachFromOverlay(): Boolean {
        val currentOverlay = overlayContainer
        if (currentOverlay != null && geckoView.parent === currentOverlay) {
            currentOverlay.removeView(geckoView)
        }
        overlayContainer = null
        detachContextToApplication()
        runCatching { session.setFocused(false) }
        runCatching { session.setActive(false) }
        return geckoView.parent == null
    }

    /** 根据真实父容器返回当前挂载状态。 */
    override fun attachmentState(): FloatingBrowserAttachmentState {
        return when {
            overlayContainer != null && geckoView.parent === overlayContainer -> FloatingBrowserAttachmentState.OVERLAY
            activityContainer != null && geckoView.parent === activityContainer -> FloatingBrowserAttachmentState.ACTIVITY
            else -> FloatingBrowserAttachmentState.DETACHED
        }
    }

    /** 真正释放 Gecko 资源，仅用于内核切换、renderer gone 或进程级强制重建。 */
    override fun destroy() {
        runCatching { session.stop() }
        runCatching { session.setActive(false) }
        runCatching { session.setFocused(false) }
        runCatching { session.close() }
        runCatching { (geckoView.parent as? ViewGroup)?.removeView(geckoView) }
        activityContainer = null
        overlayContainer = null
    }

    /** 保存最后一个有效全屏尺寸，防止小尺寸 overlay 触发响应式重排。 */
    private fun captureFullSize(surface: View, activityContainer: ViewGroup?) {
        retainedWidth = sequenceOf(surface.width, activityContainer?.width ?: 0, retainedWidth)
            .firstOrNull { value -> value > 1 }
            ?: 1
        retainedHeight = sequenceOf(surface.height, activityContainer?.height ?: 0, retainedHeight)
            .firstOrNull { value -> value > 1 }
            ?: 1
    }
}

/** 返回浏览器在 Activity 中使用的稳定全屏布局参数。 */
private fun matchParentLayoutParams(): ViewGroup.LayoutParams {
    return ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
}

/** 保持 View 单父节点约束；Activity 容器可指定插入层级，overlay 默认追加。 */
private fun moveViewToParent(view: View, parent: ViewGroup, index: Int? = null) {
    val currentParent = view.parent as? ViewGroup
    if (currentParent === parent) {
        return
    }
    currentParent?.removeView(view)
    if (index == null) {
        parent.addView(view)
    } else {
        parent.addView(view, index.coerceIn(0, parent.childCount))
    }
}

/** Activity 容器只有在其 Context 最终指向未销毁 Activity 时才允许恢复浏览器。 */
private fun isActivityContainerAlive(container: ViewGroup): Boolean {
    val activity = findActivity(container.context) ?: return false
    return !activity.isFinishing && !activity.isDestroyed
}

/** 沿 ContextWrapper 链查找 Activity，避免把 Application/overlay Context 误当成可恢复宿主。 */
private tailrec fun findActivity(context: Context): Activity? {
    return when (context) {
        is Activity -> context
        is ContextWrapper -> {
            val base = context.baseContext
            if (base === context) null else findActivity(base)
        }
        else -> null
    }
}
