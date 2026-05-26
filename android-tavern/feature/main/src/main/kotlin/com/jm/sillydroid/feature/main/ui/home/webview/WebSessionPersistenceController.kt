package com.jm.sillydroid.feature.main.ui.home.webview

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 在 local Tavern 页面 document-start 阶段注入宿主脚本：
 * 1) 修正首屏 loader 的全屏展示；
 * 2) 用扩展保存的主题运行态同步开屏 mini CSS；
 * 3) 安装宿主 Notification shim。
 *
 * 这里不再保存或还原页面 sessionStorage；WebView 会话恢复只依赖 WebView 自身 restoreState。
 */
class WebSessionPersistenceController(
    private val webView: WebView,
    private val systemNotificationBridgeName: String,
    private val androidHostBridgeName: String,
    private val allowedOrigin: () -> String
) {
    private var scriptHandler: ScriptHandler? = null

    fun install(): Boolean {
        return refreshScript()
    }

    fun refreshScript(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptHandler?.remove()
            scriptHandler = null
            return false
        }

        scriptHandler?.remove()
        scriptHandler = WebViewCompat.addDocumentStartJavaScript(
            webView,
            buildDocumentStartScript(),
            setOf(allowedOrigin())
        )
        return true
    }

    fun close() {
        scriptHandler?.remove()
        scriptHandler = null
    }

    private fun buildDocumentStartScript(): String {
        return """
            (function() {
                ${buildStartupLoaderScript()}
                ${buildStartupThemeScript()}

                if (globalThis.__staiAndroidHostDocumentStartInstalled) {
                    return;
                }

                globalThis.__staiAndroidHostDocumentStartInstalled = true;
                ${buildNotificationShimScript()}
            })();
        """.trimIndent()
    }

    private fun buildNotificationShimScript(): String {
        return """
            const nativeNotificationBridge = globalThis.$systemNotificationBridgeName;
            if (nativeNotificationBridge && !globalThis.__staiAndroidHostNotificationInstalled) {
                globalThis.__staiAndroidHostNotificationInstalled = true;

                const createNotificationEvent = function(type) {
                    return typeof Event === 'function' ? new Event(type) : { type: type };
                };

                class AndroidHostNotification {
                    constructor(title, options) {
                        const normalizedOptions = options && typeof options === 'object' ? options : {};
                        this.title = String(title ?? '');
                        this.body = typeof normalizedOptions.body === 'string' ? normalizedOptions.body : '';
                        this.tag = typeof normalizedOptions.tag === 'string' ? normalizedOptions.tag : '';
                        this.data = normalizedOptions.data;
                        this.icon = typeof normalizedOptions.icon === 'string' ? normalizedOptions.icon : '';
                        this.onclick = null;
                        this.onerror = null;
                        this.onshow = null;
                        this.onclose = null;
                        this.listeners = new Map();

                        const shown = nativeNotificationBridge.showNotification(JSON.stringify({
                            notificationId: this.tag || this.title,
                            title: this.title,
                            body: this.body,
                            tag: this.tag
                        }));

                        Promise.resolve().then(() => {
                            this.dispatchEvent(createNotificationEvent(shown ? 'show' : 'error'));
                        });
                    }

                    addEventListener(type, listener) {
                        if (typeof listener !== 'function') {
                            return;
                        }

                        const currentListeners = this.listeners.get(type) || [];
                        currentListeners.push(listener);
                        this.listeners.set(type, currentListeners);
                    }

                    removeEventListener(type, listener) {
                        const currentListeners = this.listeners.get(type) || [];
                        this.listeners.set(type, currentListeners.filter((item) => item !== listener));
                    }

                    dispatchEvent(event) {
                        const currentListeners = this.listeners.get(event.type) || [];
                        for (const listener of currentListeners) {
                            listener.call(this, event);
                        }

                        const handler = this['on' + event.type];
                        if (typeof handler === 'function') {
                            handler.call(this, event);
                        }

                        return true;
                    }

                    close() {
                        this.dispatchEvent(createNotificationEvent('close'));
                    }

                    static requestPermission(callback) {
                        const permission = nativeNotificationBridge.requestPermission();
                        if (typeof callback === 'function') {
                            callback(permission);
                        }
                        return Promise.resolve(permission);
                    }
                }

                Object.defineProperty(AndroidHostNotification, 'permission', {
                    configurable: true,
                    enumerable: true,
                    get() {
                        return nativeNotificationBridge.permissionState();
                    }
                });
                Object.defineProperty(AndroidHostNotification, 'maxActions', {
                    configurable: true,
                    enumerable: true,
                    value: 0
                });

                globalThis.Notification = AndroidHostNotification;
                if (typeof window !== 'undefined') {
                    window.Notification = AndroidHostNotification;
                }
            }
        """.trimIndent()
    }

    private fun buildStartupLoaderScript(): String {
        return """
            document.documentElement.dataset.sillydroidStartupFullscreenLoader = 'true';
            if (!document.getElementById('sillydroid-startup-loader-style')) {
                const startupLoaderStyle = document.createElement('style');
                startupLoaderStyle.id = 'sillydroid-startup-loader-style';
                startupLoaderStyle.textContent = `
                    html[data-sillydroid-startup-fullscreen-loader="true"] :is(
                        dialog.popup,
                        #dialogue_popup,
                        .popup
                    ):has(#loader.splash-screen) {
                        /* SillyTavern 1.18 初始化 loader 由 Popup 包裹；首屏始终是全屏状态页，不允许被通用 popup 尺寸包成小卡片。 */
                        position: fixed !important;
                        inset: 0 !important;
                        top: 0 !important;
                        left: 0 !important;
                        right: auto !important;
                        transform: none !important;
                        width: 100vw !important;
                        width: 100dvw !important;
                        height: 100vh !important;
                        height: 100dvh !important;
                        min-width: 100vw !important;
                        min-width: 100dvw !important;
                        min-height: 100vh !important;
                        min-height: 100dvh !important;
                        max-width: none !important;
                        max-height: none !important;
                        margin: 0 !important;
                        padding: 0 !important;
                        background: var(--sillydroid-startup-bg, var(--SmartThemeBlurTintColor, #111827)) !important;
                        box-shadow: none !important;
                        border: 0 !important;
                        border-radius: 0 !important;
                        overflow: hidden !important;
                    }

                    html[data-sillydroid-startup-fullscreen-loader="true"] :is(
                        dialog.popup,
                        #dialogue_popup,
                        .popup
                    ):has(#loader.splash-screen) :is(.popup-body, .popup-content, #loader.splash-screen),
                    html[data-sillydroid-startup-fullscreen-loader="true"] #loader.splash-screen {
                        width: 100% !important;
                        height: 100% !important;
                        min-width: 100% !important;
                        min-height: 100% !important;
                        max-width: none !important;
                        max-height: none !important;
                        margin: 0 !important;
                        padding: 0 !important;
                        border: 0 !important;
                        border-radius: 0 !important;
                        background: transparent !important;
                        color: var(--sillydroid-startup-text, var(--SmartThemeBodyColor, #eef3ff)) !important;
                        box-shadow: none !important;
                        backdrop-filter: none !important;
                        -webkit-backdrop-filter: none !important;
                        overflow: hidden !important;
                    }

                    html[data-sillydroid-startup-fullscreen-loader="true"] #loader.splash-screen :is(.splash-message, .loader-text, .loader-message, p, span) {
                        color: var(--sillydroid-startup-muted, var(--SmartThemeBodyColor, #eef3ff)) !important;
                        text-shadow: none !important;
                    }
                `;
                document.documentElement.appendChild(startupLoaderStyle);
            }
        """.trimIndent()
    }

    private fun buildStartupThemeScript(): String {
        return """
            const sillydroidStartupThemeStateKey = 'sillydroidAndroidHostStartupThemeState';
            const sillydroidThemeColorPattern = /^#[\da-f]{6}$/i;
            const sillydroidReadStartupThemeState = function() {
                const payload = localStorage.getItem(sillydroidStartupThemeStateKey)
                    || sessionStorage.getItem(sillydroidStartupThemeStateKey);
                if (!payload) {
                    return null;
                }

                try {
                    return JSON.parse(payload);
                } catch (_) {
                    localStorage.removeItem(sillydroidStartupThemeStateKey);
                    sessionStorage.removeItem(sillydroidStartupThemeStateKey);
                    return null;
                }
            };
            const sillydroidValidColor = function(value, fallback) {
                return typeof value === 'string' && sillydroidThemeColorPattern.test(value) ? value : fallback;
            };
            const sillydroidStartupTheme = sillydroidReadStartupThemeState();
            if (sillydroidStartupTheme && sillydroidStartupTheme.theme === 'glass') {
                const resolvedMode = sillydroidStartupTheme.resolvedMode === 'light' ? 'light' : 'dark';
                const primary = sillydroidValidColor(sillydroidStartupTheme.primary, '#6f8fbf');
                const secondary = sillydroidValidColor(sillydroidStartupTheme.secondary, '#8fb8a7');
                const startupSystemBars = sillydroidStartupTheme.systemBarColors || {};
                const statusBarColor = sillydroidValidColor(startupSystemBars.statusBarColor, resolvedMode === 'light' ? '#eef5ff' : '#10131c');
                const navigationBarColor = sillydroidValidColor(startupSystemBars.navigationBarColor, resolvedMode === 'light' ? '#fff8ea' : '#10131c');

                document.documentElement.dataset.sillydroidStartupTheme = 'glass';
                document.documentElement.dataset.sillydroidStartupThemeResolvedMode = resolvedMode;
                document.documentElement.style.setProperty('--sillydroid-startup-primary', primary);
                document.documentElement.style.setProperty('--sillydroid-startup-secondary', secondary);

                const androidHostBridge = globalThis.$androidHostBridgeName;
                if (androidHostBridge && typeof androidHostBridge.setSystemBarsBackgroundColors === 'function') {
                    // 开屏 mini CSS 消费扩展保存的同一份主题运行态，避免扩展未加载前状态栏/手势条使用旧颜色。
                    androidHostBridge.setSystemBarsBackgroundColors(statusBarColor, navigationBarColor);
                } else if (androidHostBridge && typeof androidHostBridge.setSystemBarsBackgroundColor === 'function') {
                    androidHostBridge.setSystemBarsBackgroundColor(statusBarColor);
                }

                if (!document.getElementById('sillydroid-startup-theme-style')) {
                    const startupStyle = document.createElement('style');
                    startupStyle.id = 'sillydroid-startup-theme-style';
                    startupStyle.textContent = `
                        html[data-sillydroid-startup-theme="glass"] {
                            --sillydroid-startup-bg:
                                radial-gradient(ellipse at 18% 12%, color-mix(in srgb, var(--sillydroid-startup-primary) 18%, transparent), transparent 56%),
                                radial-gradient(ellipse at 86% 86%, color-mix(in srgb, var(--sillydroid-startup-secondary) 16%, transparent), transparent 58%),
                                linear-gradient(135deg,
                                    color-mix(in srgb, var(--sillydroid-startup-primary) 34%, rgb(6 10 18)) 0%,
                                    color-mix(in srgb, var(--sillydroid-startup-primary) 18%, rgb(9 14 24)) 34%,
                                    color-mix(in srgb, var(--sillydroid-startup-secondary) 18%, rgb(10 15 26)) 66%,
                                    color-mix(in srgb, var(--sillydroid-startup-secondary) 32%, rgb(7 11 20)) 100%);
                            --sillydroid-startup-text: rgb(238 243 255);
                            --sillydroid-startup-muted: rgb(184 196 214);
                        }

                        html[data-sillydroid-startup-theme="glass"][data-sillydroid-startup-theme-resolved-mode="light"] {
                            --sillydroid-startup-bg:
                                radial-gradient(ellipse at 18% 12%, color-mix(in srgb, var(--sillydroid-startup-primary) 14%, transparent), transparent 58%),
                                radial-gradient(ellipse at 86% 86%, color-mix(in srgb, var(--sillydroid-startup-secondary) 13%, transparent), transparent 60%),
                                linear-gradient(135deg,
                                    color-mix(in srgb, var(--sillydroid-startup-primary) 28%, rgb(248 252 255)) 0%,
                                    color-mix(in srgb, var(--sillydroid-startup-primary) 14%, rgb(246 250 255)) 34%,
                                    color-mix(in srgb, var(--sillydroid-startup-secondary) 14%, rgb(255 250 241)) 66%,
                                    color-mix(in srgb, var(--sillydroid-startup-secondary) 26%, rgb(239 247 255)) 100%);
                            --sillydroid-startup-text: rgb(30 39 58);
                            --sillydroid-startup-muted: rgb(86 101 124);
                        }

                        html[data-sillydroid-startup-theme="glass"],
                        html[data-sillydroid-startup-theme="glass"] body {
                            background: var(--sillydroid-startup-bg) !important;
                            color: var(--sillydroid-startup-text) !important;
                        }

                        html[data-sillydroid-startup-theme="glass"] #bg1 {
                            background: var(--sillydroid-startup-bg) !important;
                        }

                        html[data-sillydroid-startup-theme="glass"] #loader.splash-screen :is(.splash-message, .loader-text, .loader-message, p, span) {
                            color: var(--sillydroid-startup-muted) !important;
                            text-shadow: none !important;
                        }
                    `;
                    document.documentElement.appendChild(startupStyle);
                }
            } else {
                document.documentElement.removeAttribute('data-sillydroid-startup-theme');
                document.documentElement.removeAttribute('data-sillydroid-startup-theme-resolved-mode');
                document.documentElement.style.removeProperty('--sillydroid-startup-primary');
                document.documentElement.style.removeProperty('--sillydroid-startup-secondary');
            }
        """.trimIndent()
    }
}
