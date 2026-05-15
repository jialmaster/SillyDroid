package com.jm.sillydroid.feature.main.ui.home.webview

import android.content.SharedPreferences
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

class WebSessionPersistenceController(
    private val webView: WebView,
    private val preferences: SharedPreferences,
    private val storageKey: String,
    private val bridgeName: String,
    private val systemNotificationBridgeName: String,
    private val allowedOrigin: () -> String
) {
    private var scriptHandler: ScriptHandler? = null

    fun install() {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "当前设备的 Android System WebView 不支持 WebMessageListener，无法固化 WebView sessionStorage。"
        }
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            "当前设备的 Android System WebView 不支持 document-start script，无法在页面初始化前恢复 sessionStorage。"
        }

        val originRules = setOf(allowedOrigin())
        WebViewCompat.addWebMessageListener(
            webView,
            bridgeName,
            originRules,
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy
                ) {
                    if (!isMainFrame || sourceOrigin.toString() != allowedOrigin()) {
                        return
                    }

                    persistWebStateChange(message.data)
                }
            }
        )
        refreshScript()
    }

    fun refreshScript() {
        scriptHandler?.remove()
        scriptHandler = WebViewCompat.addDocumentStartJavaScript(
            webView,
            buildPersistenceScript(),
            setOf(allowedOrigin())
        )
    }

    fun close() {
        scriptHandler?.remove()
        scriptHandler = null
    }

    private fun buildPersistenceScript(): String {
        val persistedSnapshot = JSONObject.quote(readPersistedSnapshot())

        return """
            (function() {
                const persistedPayload = $persistedSnapshot;
                const nativeBridge = globalThis.$bridgeName;

                const restoredEntries = persistedPayload ? JSON.parse(persistedPayload) : {};
                sessionStorage.clear();
                for (const [key, value] of Object.entries(restoredEntries)) {
                    if (typeof value === 'string') {
                        sessionStorage.setItem(key, value);
                    }
                }

                if (globalThis.__staiAndroidSessionPersistenceInstalled) {
                    return;
                }

                globalThis.__staiAndroidSessionPersistenceInstalled = true;

                const collectSessionStorage = function() {
                    const snapshot = {};
                    for (let index = 0; index < sessionStorage.length; index += 1) {
                        const key = sessionStorage.key(index);
                        if (typeof key === 'string') {
                            snapshot[key] = sessionStorage.getItem(key) ?? '';
                        }
                    }
                    return snapshot;
                };

                const publishStateChange = function(type) {
                    nativeBridge.postMessage(JSON.stringify({
                        type,
                        sessionStorage: collectSessionStorage()
                    }));
                };

                const originalSetItem = sessionStorage.setItem.bind(sessionStorage);
                sessionStorage.setItem = function(key, value) {
                    const result = originalSetItem(key, value);
                    publishStateChange('sessionStorage');
                    return result;
                };

                const originalRemoveItem = sessionStorage.removeItem.bind(sessionStorage);
                sessionStorage.removeItem = function(key) {
                    const result = originalRemoveItem(key);
                    publishStateChange('sessionStorage');
                    return result;
                };

                const originalClear = sessionStorage.clear.bind(sessionStorage);
                sessionStorage.clear = function() {
                    const result = originalClear();
                    publishStateChange('sessionStorage');
                    return result;
                };

                addEventListener('pagehide', function() {
                    publishStateChange('pagehide');
                }, true);
                document.addEventListener('visibilitychange', function() {
                    if (document.visibilityState === 'hidden') {
                        publishStateChange('visibilitychange');
                    }
                }, true);

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

    private fun readPersistedSnapshot(): String {
        return preferences.getString(storageKey, "{}") ?: "{}"
    }

    private fun persistWebStateChange(payload: String?) {
        val changeEnvelope = if (payload.isNullOrBlank()) JSONObject() else JSONObject(payload)
        val sessionStorageSnapshot = changeEnvelope.optJSONObject("sessionStorage") ?: JSONObject()

        persistSessionSnapshot(sessionStorageSnapshot)
        CookieManager.getInstance().flush()
    }

    private fun persistSessionSnapshot(snapshot: JSONObject) {
        val normalizedSnapshot = JSONObject()
        val keys = snapshot.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            normalizedSnapshot.put(key, snapshot.optString(key))
        }

        preferences.edit()
            .putString(storageKey, normalizedSnapshot.toString())
            .commit()
    }
}
