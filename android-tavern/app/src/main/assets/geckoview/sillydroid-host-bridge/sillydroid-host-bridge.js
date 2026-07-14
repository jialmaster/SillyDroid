(function() {
  const nativeApp = 'sillydroid_host';
  const requestMessageType = 'sillydroid-gecko-host-bridge-request';
  const responseMessageType = 'sillydroid-gecko-host-bridge-response';
  const hostInfoMessageType = 'sillydroid-gecko-host-bridge-host-info';
  const hostBridgeName = 'SillyDroidAndroidHostBridge';
  const notificationBridgeName = 'AndroidSystemNotificationBridge';
  const downloadBridgeName = 'AndroidDownloadBridge';
  const defaultHostVersionInfo = {
    browserEngine: 'GECKOVIEW',
    webViewPullRefreshEnabled: false,
    floatingLogBubbleEnabled: false,
    launchWebViewOnReady: true,
    unrestrictedFileImportSelectionEnabled: false,
    serverReady: true
  };

  function mergeHostVersionInfo(value) {
    if (!value || typeof value !== 'object') {
      return defaultHostVersionInfo;
    }
    return Object.assign({}, defaultHostVersionInfo, value, { browserEngine: 'GECKOVIEW' });
  }

  function sendNative(action, payload) {
    return browser.runtime.sendNativeMessage(nativeApp, { action, payload })
      .then(function(response) {
        if (!response || response.ok !== true) {
          throw new Error(response && response.error ? response.error : 'native_message_failed');
        }
        return response.result;
      });
  }

  function sendPageResponse(requestId, ok, result, error) {
    window.postMessage({
      type: responseMessageType,
      requestId,
      ok,
      result,
      error: error || ''
    }, '*');
  }

  window.addEventListener('message', function(event) {
    if (event.source !== window) {
      return;
    }
    const detail = event && event.data && event.data.type === requestMessageType ? event.data : {};
    const requestId = String(detail.requestId || '');
    const action = String(detail.action || '');
    if (!requestId || !action) {
      return;
    }
    sendNative(action, detail.payload)
      .then(function(result) {
        if (action === 'getHostVersionInfo' && typeof result === 'string') {
          try {
            result = JSON.stringify(mergeHostVersionInfo(JSON.parse(result)));
          } catch (_) {
            result = JSON.stringify(defaultHostVersionInfo);
          }
        }
        sendPageResponse(requestId, true, result, '');
      })
      .catch(function(error) {
        console.warn('SillyDroid Gecko native bridge failed:', action, error && error.message ? error.message : error);
        sendPageResponse(requestId, false, null, error && error.message ? error.message : String(error || 'unknown'));
      });
  }, false);

  const initialHostVersionInfoPromise = sendNative('getHostVersionInfo', null)
    .then(function(result) {
      if (typeof result !== 'string') {
        return JSON.stringify(defaultHostVersionInfo);
      }
      try {
        return JSON.stringify(mergeHostVersionInfo(JSON.parse(result)));
      } catch (_) {
        return JSON.stringify(defaultHostVersionInfo);
      }
    })
    .catch(function() {
      return JSON.stringify(defaultHostVersionInfo);
    });
  initialHostVersionInfoPromise.then(function(value) {
    window.postMessage({
      type: hostInfoMessageType,
      value
    }, '*');
  });

  function recordBridgeDiagnostic(payload) {
    sendNative('recordWebPerformanceDiagnostic', String(payload || '')).catch(function() {});
  }

  // GeckoView 没有 System WebView 的 setSupportZoom 开关；用标准 viewport 约束锁定当前密度，
  // 只禁止用户手势缩放，不改变宿主提供的 50%-100% 界面密度能力。
  function lockViewportScale(content, lockedScale, fallbackContent) {
    const normalizedScale = String(Number(Number(lockedScale || 1).toFixed(4)));
    const source = String(content || fallbackContent || 'width=device-width, initial-scale=1, viewport-fit=cover');
    const preservedTokens = source
      .split(',')
      .map(function(token) { return token.trim(); })
      .filter(function(token) {
        const key = String(token.split('=')[0] || '').trim().toLowerCase();
        return key !== 'minimum-scale' && key !== 'maximum-scale' && key !== 'user-scalable';
      });
    preservedTokens.push('minimum-scale=' + normalizedScale);
    preservedTokens.push('maximum-scale=' + normalizedScale);
    preservedTokens.push('user-scalable=no');
    return preservedTokens.join(', ');
  }

  function applyViewportDensity(percent, baseViewportWidthCssPx, reason) {
    const sanitizedPercent = Math.max(50, Math.min(100, Math.round(Number(percent || 100) / 5) * 5));
    const baseWidth = Math.max(240, Math.round(Number(baseViewportWidthCssPx || window.innerWidth || 360)));
    const densityFactor = sanitizedPercent / 100;
    const initialScale = densityFactor === 1 ? '1' : String(Number(densityFactor.toFixed(4)));
    const viewportWidth = Math.max(240, Math.round(baseWidth / densityFactor));
    const root = document.documentElement;
    const body = document.body;
    if (body) {
      body.style.transform = '';
      body.style.transformOrigin = '';
      body.style.width = '';
      body.style.minWidth = '';
      body.style.minHeight = '';
      body.style.overflowX = '';
      body.style.overflowY = '';
    }
    const head = document.head || document.getElementsByTagName('head')[0];
    if (!head) {
      window.setTimeout(function() {
        applyViewportDensity(sanitizedPercent, baseWidth, reason);
      }, 0);
      return false;
    }
    let viewport = document.querySelector('meta[name="viewport"]');
    if (!viewport) {
      viewport = document.createElement('meta');
      viewport.setAttribute('name', 'viewport');
      head.appendChild(viewport);
    }
    if (viewport.dataset.sillydroidOriginalViewport === undefined) {
      viewport.dataset.sillydroidOriginalViewport = viewport.getAttribute('content') || '';
    }
    const originalViewport = viewport.dataset.sillydroidOriginalViewport || '';
    if (sanitizedPercent === 100) {
      viewport.setAttribute(
        'content',
        lockViewportScale(originalViewport, 1, 'width=device-width, initial-scale=1, viewport-fit=cover')
      );
      delete viewport.dataset.sillydroidViewportDensityPercent;
      delete viewport.dataset.sillydroidViewportDensityReason;
      delete viewport.dataset.sillydroidViewportDensityWidth;
      delete viewport.dataset.sillydroidHtmlPageZoomPercent;
      delete viewport.dataset.sillydroidHtmlPageZoomReason;
      delete viewport.dataset.sillydroidHtmlPageZoomWidth;
      if (root) {
        delete root.dataset.sillydroidViewportDensityPercent;
        delete root.dataset.sillydroidViewportDensityReason;
        delete root.dataset.sillydroidHtmlPageZoomPercent;
        delete root.dataset.sillydroidHtmlPageZoomReason;
      }
      recordBridgeDiagnostic('event=gecko_viewport_density_reset reason=' + String(reason || 'host'));
      return true;
    }
    // 界面密度只做 50%-100%：加宽 layout viewport 并用同等 initial-scale 压回屏幕内，
    // 让同屏 CSS 内容更多。这不是 Gecko tabs/page zoom，也不是 transform 位图缩放。
    viewport.setAttribute(
      'content',
      lockViewportScale(
        'width=' + viewportWidth + ', initial-scale=' + initialScale + ', viewport-fit=cover',
        initialScale
      )
    );
    viewport.dataset.sillydroidViewportDensityPercent = String(sanitizedPercent);
    viewport.dataset.sillydroidViewportDensityReason = String(reason || 'host');
    viewport.dataset.sillydroidViewportDensityWidth = String(viewportWidth);
    if (root) {
      root.dataset.sillydroidViewportDensityPercent = String(sanitizedPercent);
      root.dataset.sillydroidViewportDensityReason = String(reason || 'host');
    }
    recordBridgeDiagnostic(
      'event=gecko_viewport_density_applied percent=' + sanitizedPercent +
        ' baseViewportWidthCssPx=' + baseWidth +
        ' viewportWidth=' + viewportWidth +
        ' reason=' + String(reason || 'host')
    );
    return true;
  }

  function installViewportDensityPort() {
    let port = null;
    function connect() {
      try {
        port = browser.runtime.connectNative(nativeApp);
        port.onMessage.addListener(function(message) {
          if (message && message.action === 'recordDocumentVisibility') {
            recordDocumentVisibility(message.reason || 'host_request');
            return;
          }
          if (!message || message.action !== 'setViewportDensity') {
            return;
          }
          const applied = applyViewportDensity(
            message.percent,
            message.baseViewportWidthCssPx,
            message.reason
          );
          try {
            port.postMessage({
              action: 'viewportDensityApplied',
              applied,
              percent: message.percent,
              baseViewportWidthCssPx: message.baseViewportWidthCssPx
            });
          } catch (_) {}
        });
        port.onDisconnect.addListener(function() {
          port = null;
          window.setTimeout(connect, 1000);
        });
        recordBridgeDiagnostic('event=gecko_viewport_density_port_opened');
      } catch (error) {
        recordBridgeDiagnostic(
          'event=gecko_viewport_density_port_failed reason=' +
            String(error && error.message || error || 'unknown').replace(/\s+/g, '_').slice(0, 180)
        );
        window.setTimeout(connect, 1000);
      }
    }
    connect();
  }

  // 页面可见性诊断只记录枚举状态和时间，不读取页面内容。
  function recordDocumentVisibility(reason) {
    recordBridgeDiagnostic(
      'event=document_visibility engine=GECKOVIEW reason=' + String(reason || 'unknown') +
        ' state=' + String(document.visibilityState || 'unknown') +
        ' atEpochMs=' + Date.now()
    );
  }

  // 页面可见性是悬浮迁移是否真正维持 renderer 的关键证据。
  function installVisibilityDiagnostic() {
    if (globalThis.__sillyDroidGeckoVisibilityDiagnosticInstalled) {
      return;
    }
    globalThis.__sillyDroidGeckoVisibilityDiagnosticInstalled = true;
    document.addEventListener('visibilitychange', function() {
      recordDocumentVisibility('visibilitychange');
    }, false);
    recordDocumentVisibility('document_start');
  }

  function injectPageScript() {
    const parent = document.documentElement || document.head || document.body;
    if (!parent) {
      window.setTimeout(injectPageScript, 0);
      return;
    }

    const script = document.createElement('script');
    script.textContent = '(' + function(config) {
      if (window.__sillyDroidGeckoPageBridgeInstalled) {
        return;
      }
      window.__sillyDroidGeckoPageBridgeInstalled = true;

      const requestMessageType = config.requestMessageType;
      const responseMessageType = config.responseMessageType;
      const hostInfoMessageType = config.hostInfoMessageType;
      const hostBridgeName = config.hostBridgeName;
      const notificationBridgeName = config.notificationBridgeName;
      const downloadBridgeName = config.downloadBridgeName;
      const defaultHostVersionInfo = config.defaultHostVersionInfo;
      let hostVersionInfoCache = mergeHostVersionInfo(defaultHostVersionInfo);
      let hostVersionInfoCacheSerialized = JSON.stringify(hostVersionInfoCache);
      let notificationPermissionState = 'default';
      let requestId = 1;
      const pendingRequests = new Map();

      function mergeHostVersionInfo(value) {
        if (!value || typeof value !== 'object') {
          return defaultHostVersionInfo;
        }
        return Object.assign({}, defaultHostVersionInfo, value, { browserEngine: 'GECKOVIEW' });
      }

      function sendAsync(action, payload, timeoutMs) {
        return new Promise(function(resolve, reject) {
          const id = String(requestId++);
          const timeout = setTimeout(function() {
            pendingRequests.delete(id);
            reject(new Error('bridge_timeout:' + action));
          }, timeoutMs || 5000);
          pendingRequests.set(id, { resolve, reject, timeout });
          window.postMessage({
            type: requestMessageType,
            requestId: id,
            action,
            payload
          }, '*');
        });
      }

      window.addEventListener('message', function(event) {
        if (event.source !== window) {
          return;
        }
        const detail = event && event.data && event.data.type === responseMessageType ? event.data : {};
        const id = String(detail.requestId || '');
        const pending = pendingRequests.get(id);
        if (!pending) {
          return;
        }
        pendingRequests.delete(id);
        clearTimeout(pending.timeout);
        if (detail.ok === true) {
          pending.resolve(detail.result);
        } else {
          pending.reject(new Error(detail.error || 'bridge_failed'));
        }
      }, false);

      function defineGlobalBridge(name, value) {
        Object.defineProperty(window, name, {
          configurable: true,
          enumerable: false,
          value
        });
        Object.defineProperty(globalThis, name, {
          configurable: true,
          enumerable: false,
          value
        });
      }

      function postBoolean(action, payload) {
        sendAsync(action, payload).catch(function(error) {
          console.warn('SillyDroid Gecko host bridge failed:', action, error && error.message ? error.message : error);
        });
        return true;
      }

      function refreshHostVersionInfo() {
        sendAsync('getHostVersionInfo', null, 3000)
          .then(function(value) {
            if (typeof value !== 'string') {
              return;
            }
            try {
              const nextHostVersionInfo = mergeHostVersionInfo(JSON.parse(value));
              const nextSerialized = JSON.stringify(nextHostVersionInfo);
              if (nextSerialized === hostVersionInfoCacheSerialized) {
                return;
              }
              hostVersionInfoCache = nextHostVersionInfo;
              hostVersionInfoCacheSerialized = nextSerialized;
              window.__sillyDroidGeckoHostVersionInfo = hostVersionInfoCache;
              window.dispatchEvent(new CustomEvent('sillydroidHostVersionInfoChanged', { detail: hostVersionInfoCache }));
            } catch (_) {}
          })
          .catch(function() {});
      }

      function applyHostVersionInfoJson(value) {
        if (typeof value !== 'string') {
          return;
        }
        try {
          const nextHostVersionInfo = mergeHostVersionInfo(JSON.parse(value));
          hostVersionInfoCache = nextHostVersionInfo;
          hostVersionInfoCacheSerialized = JSON.stringify(nextHostVersionInfo);
          window.__sillyDroidGeckoHostVersionInfo = hostVersionInfoCache;
          window.dispatchEvent(new CustomEvent('sillydroidHostVersionInfoChanged', { detail: hostVersionInfoCache }));
        } catch (_) {}
      }

      window.addEventListener('message', function(event) {
        if (event.source !== window) {
          return;
        }
        const detail = event && event.data && event.data.type === hostInfoMessageType ? event.data : {};
        applyHostVersionInfoJson(detail.value);
      }, false);

      function refreshNotificationPermissionState() {
        sendAsync('notification.permissionState', null, 3000)
          .then(function(value) {
            if (typeof value === 'string' && value.trim()) {
              notificationPermissionState = value.trim();
            }
          })
          .catch(function() {});
      }

      const hostBridge = {
        openSettings: function() { return postBoolean('openSettings', null); },
        showFloatingLogsBubble: function() { return postBoolean('showFloatingLogsBubble', null); },
        openCurrentPageInBrowser: function() { return postBoolean('openCurrentPageInBrowser', null); },
        setFloatingLogsBubbleEnabled: function(enabled) { return postBoolean('setFloatingLogsBubbleEnabled', Boolean(enabled)); },
        setWebViewPullRefreshEnabled: function(enabled) { return postBoolean('setWebViewPullRefreshEnabled', Boolean(enabled)); },
        setSystemBarsBackgroundColor: function(hexColor) { return postBoolean('setSystemBarsBackgroundColor', String(hexColor || '')); },
        setSystemBarsBackgroundColors: function(statusBarColor, navigationBarColor) {
          return postBoolean('setSystemBarsBackgroundColors', {
            statusBarColor: String(statusBarColor || ''),
            navigationBarColor: String(navigationBarColor || '')
          });
        },
        reloadTavern: function() { return postBoolean('reloadTavern', null); },
        getHostVersionInfo: function() {
          refreshHostVersionInfo();
          return JSON.stringify(hostVersionInfoCache || defaultHostVersionInfo);
        },
        recordWebPerformanceDiagnostic: function(payload) {
          return postBoolean('recordWebPerformanceDiagnostic', String(payload || ''));
        }
      };
      defineGlobalBridge(hostBridgeName, hostBridge);

      const notificationBridge = {
        showNotification: function(payload) { return postBoolean('notification.show', String(payload || '')); },
        playAlertSound: function() { return postBoolean('notification.playAlertSound', null); },
        permissionState: function() {
          refreshNotificationPermissionState();
          return notificationPermissionState;
        },
        requestPermission: function() {
          postBoolean('notification.requestPermission', null);
          refreshNotificationPermissionState();
          return notificationPermissionState;
        }
      };
      defineGlobalBridge(notificationBridgeName, notificationBridge);

      const downloadBridge = {
        onBlobDownloadPreparing: function(fileName) {
          return postBoolean('download.preparing', String(fileName || ''));
        },
        saveBase64File: function(payload) {
          return postBoolean('download.saveBase64File', String(payload || ''));
        },
        beginBase64File: function(payload) {
          return sendAsync('download.beginBase64File', String(payload || ''), 10000);
        },
        appendBase64FileChunk: function(payload) {
          return sendAsync('download.appendBase64FileChunk', String(payload || ''), 10000);
        },
        completeBase64File: function(payload) {
          return sendAsync('download.completeBase64File', String(payload || ''), 10000);
        },
        cancelBase64File: function(payload) {
          return sendAsync('download.cancelBase64File', String(payload || ''), 10000);
        },
        reportDownloadFailure: function(payload) {
          return postBoolean('download.reportFailure', String(payload || ''));
        },
        recordDiagnostic: function(payload) {
          return postBoolean('download.recordDiagnostic', String(payload || ''));
        }
      };
      defineGlobalBridge(downloadBridgeName, downloadBridge);

      function installStartupLoaderAndTheme() {
        const root = document.documentElement;
        if (!root) {
          return;
        }
        root.dataset.sillydroidStartupFullscreenLoader = 'true';
        if (!document.getElementById('sillydroid-startup-loader-style')) {
          const startupLoaderStyle = document.createElement('style');
          startupLoaderStyle.id = 'sillydroid-startup-loader-style';
          startupLoaderStyle.textContent = `
            html[data-sillydroid-startup-fullscreen-loader="true"] :is(dialog.popup, #dialogue_popup, .popup):has(#loader.splash-screen) {
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
            html[data-sillydroid-startup-fullscreen-loader="true"] :is(dialog.popup, #dialogue_popup, .popup):has(#loader.splash-screen) :is(.popup-body, .popup-content, #loader.splash-screen),
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
            }`;
          root.appendChild(startupLoaderStyle);
        }
        installStartupTheme(root);
      }

      function installStartupTheme(root) {
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
          const statusBarColor = sillydroidValidColor(startupSystemBars.statusBarColor, resolvedMode === 'light' ? '#e9eef6' : '#141a23');
          const navigationBarColor = sillydroidValidColor(startupSystemBars.navigationBarColor, resolvedMode === 'light' ? '#f0f6f5' : '#111616');

          root.dataset.sillydroidStartupTheme = 'glass';
          root.dataset.sillydroidStartupThemeResolvedMode = resolvedMode;
          root.style.setProperty('--sillydroid-startup-primary', primary);
          root.style.setProperty('--sillydroid-startup-secondary', secondary);

          if (hostBridge && typeof hostBridge.setSystemBarsBackgroundColors === 'function') {
            hostBridge.setSystemBarsBackgroundColors(statusBarColor, navigationBarColor);
          } else if (hostBridge && typeof hostBridge.setSystemBarsBackgroundColor === 'function') {
            hostBridge.setSystemBarsBackgroundColor(statusBarColor);
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
              html[data-sillydroid-startup-theme="glass"] #loader.splash-screen :is(.splash-message, .loader-text, .loader-message, p, span) {
                color: var(--sillydroid-startup-muted) !important;
                text-shadow: none !important;
              }`;
            root.appendChild(startupStyle);
          }
        } else {
          root.removeAttribute('data-sillydroid-startup-theme');
          root.removeAttribute('data-sillydroid-startup-theme-resolved-mode');
          root.style.removeProperty('--sillydroid-startup-primary');
          root.style.removeProperty('--sillydroid-startup-secondary');
        }
      }

      function installNotificationShim() {
        if (globalThis.__staiAndroidHostNotificationInstalled) {
          return;
        }
        globalThis.__staiAndroidHostNotificationInstalled = true;
        const nativeNotificationBridge = globalThis[notificationBridgeName];
        if (!nativeNotificationBridge) {
          return;
        }
        const createNotificationEvent = function(type) {
          return typeof Event === 'function' ? new Event(type) : { type };
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
        window.Notification = AndroidHostNotification;
      }

      function installBlobDownloadBridge() {
        const nativeBridge = globalThis[downloadBridgeName];
        if (!nativeBridge || typeof nativeBridge.saveBase64File !== 'function') {
          return;
        }
        if (window.__staiAndroidDownloadBridgeInstalled) {
          return;
        }
        window.__staiAndroidDownloadBridgeInstalled = true;
        const blobStore = new Map();
        const blobCleanupTimers = new Map();
        window.__staiAndroidDownloadBlobStore = blobStore;
        const originalCreateObjectUrl = URL.createObjectURL.bind(URL);
        const originalRevokeObjectUrl = URL.revokeObjectURL.bind(URL);
        const originalElementClick = HTMLElement.prototype.click;
        const originalResponseBlob = Response.prototype.blob;
        const originalWindowOpen = window.open ? window.open.bind(window) : null;
        function recordDownloadDiagnostic(payload) {
          try {
            if (typeof nativeBridge.recordDiagnostic === 'function') {
              nativeBridge.recordDiagnostic(String(payload || ''));
            }
          } catch (_) {}
        }
        Response.prototype.blob = function() {
          return originalResponseBlob.call(this).then(function(blob) {
            window.__staiAndroidRecentResponseBlob = { blob, capturedAt: Date.now() };
            return blob;
          });
        };
        URL.createObjectURL = function(object) {
          const objectUrl = originalCreateObjectUrl(object);
          const previousCleanupTimer = blobCleanupTimers.get(objectUrl);
          if (previousCleanupTimer) {
            clearTimeout(previousCleanupTimer);
            blobCleanupTimers.delete(objectUrl);
          }
          blobStore.set(objectUrl, object);
          return objectUrl;
        };
        URL.revokeObjectURL = function(objectUrl) {
          const cleanupTimer = setTimeout(function() {
            blobStore.delete(objectUrl);
            blobCleanupTimers.delete(objectUrl);
          }, 15000);
          const previousCleanupTimer = blobCleanupTimers.get(objectUrl);
          if (previousCleanupTimer) {
            clearTimeout(previousCleanupTimer);
          }
          blobCleanupTimers.set(objectUrl, cleanupTimer);
          return originalRevokeObjectUrl(objectUrl);
        };
        function readBlobAsBase64(blob) {
          return new Promise(function(resolve, reject) {
            const reader = new FileReader();
            reader.onloadend = function() {
              const result = typeof reader.result === 'string' ? reader.result : '';
              const commaIndex = result.indexOf(',');
              if (commaIndex < 0) {
                reject(new Error('无法解析导出数据'));
                return;
              }
              resolve(result.slice(commaIndex + 1));
            };
            reader.onerror = function() {
              reject(reader.error || new Error('无法读取导出数据'));
            };
            reader.readAsDataURL(blob);
          });
        }
        function resolveDownloadId() {
          return 'gecko-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
        }
        function compactDownloadValue(value, limit) {
          const text = String(value || '').replace(/\s+/g, ' ').trim();
          if (!text) {
            return '-';
          }
          return text.length <= limit ? text : text.slice(0, limit) + '...';
        }
        function sanitizeFileName(value, fallback) {
          const text = String(value || '').trim()
            .replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_')
            .replace(/\s+/g, ' ')
            .slice(0, 180);
          return text || fallback || 'download';
        }
        function fileNameFromUrl(downloadUrl) {
          try {
            const url = new URL(downloadUrl, location.href);
            const lastSegment = decodeURIComponent(url.pathname.split('/').filter(Boolean).pop() || '');
            return sanitizeFileName(lastSegment, 'download');
          } catch (_) {
            return 'download';
          }
        }
        function fileNameFromContentDisposition(contentDisposition) {
          const header = String(contentDisposition || '');
          if (!header) {
            return '';
          }
          const utf8Match = header.match(/filename\*\s*=\s*UTF-8''([^;]+)/i);
          if (utf8Match) {
            try {
              return sanitizeFileName(decodeURIComponent(utf8Match[1].replace(/^"|"$/g, '')), '');
            } catch (_) {}
          }
          const plainMatch = header.match(/filename\s*=\s*("?)([^";]+)\1/i);
          return plainMatch ? sanitizeFileName(plainMatch[2], '') : '';
        }
        function resolveAnchorDownloadFileName(anchor, downloadUrl, response) {
          const downloadAttribute = anchor && typeof anchor.getAttribute === 'function'
            ? anchor.getAttribute('download') || ''
            : '';
          const responseFileName = response && response.headers
            ? fileNameFromContentDisposition(response.headers.get('content-disposition'))
            : '';
          return sanitizeFileName(downloadAttribute || responseFileName || fileNameFromUrl(downloadUrl), 'download');
        }
        function shouldCaptureHttpDownload(downloadUrl, anchor) {
          if (!anchor || !anchor.hasAttribute('download')) {
            return false;
          }
          try {
            const url = new URL(downloadUrl, location.href);
            return (url.protocol === 'http:' || url.protocol === 'https:') && url.origin === location.origin;
          } catch (_) {
            return false;
          }
        }
        async function saveBase64FileChunked(fileName, mimeType, base64) {
          const chunkSize = 192 * 1024;
          const totalLength = base64.length;
          const chunkCount = Math.ceil(totalLength / chunkSize);
          const downloadId = resolveDownloadId();
          const basePayload = {
            downloadId,
            fileName,
            mimeType: mimeType || 'application/octet-stream'
          };
          if (
            typeof nativeBridge.beginBase64File !== 'function' ||
            typeof nativeBridge.appendBase64FileChunk !== 'function' ||
            typeof nativeBridge.completeBase64File !== 'function' ||
            typeof nativeBridge.cancelBase64File !== 'function'
          ) {
            nativeBridge.saveBase64File(JSON.stringify({
              fileName,
              mimeType: mimeType || '',
              base64
            }));
            return;
          }
          try {
            await nativeBridge.beginBase64File(JSON.stringify({
              ...basePayload,
              totalBase64Length: totalLength,
              chunkCount
            }));
            for (let index = 0; index < chunkCount; index += 1) {
              await nativeBridge.appendBase64FileChunk(JSON.stringify({
                downloadId,
                index,
                base64: base64.slice(index * chunkSize, Math.min(totalLength, (index + 1) * chunkSize))
              }));
            }
            await nativeBridge.completeBase64File(JSON.stringify(basePayload));
          } catch (error) {
            try {
              await nativeBridge.cancelBase64File(JSON.stringify({
                ...basePayload,
                message: error && error.message ? error.message : '导出失败'
              }));
            } catch (_) {}
            throw error;
          }
        }
        function resolveBlob(href) {
          if (href.startsWith('blob:')) {
            const cachedBlob = blobStore.get(href);
            if (cachedBlob) {
              return Promise.resolve(cachedBlob);
            }
            const recentResponseBlob = window.__staiAndroidRecentResponseBlob;
            if (recentResponseBlob && recentResponseBlob.blob && (Date.now() - recentResponseBlob.capturedAt) <= 15000) {
              return Promise.resolve(recentResponseBlob.blob);
            }
            return fetch(href).then(function(response) { return response.blob(); });
          }
          if (href.startsWith('data:')) {
            return fetch(href).then(function(response) { return response.blob(); });
          }
          return Promise.resolve(null);
        }
        function saveBlobUrl(downloadUrl, fileName) {
          nativeBridge.onBlobDownloadPreparing(fileName);
          recordDownloadDiagnostic(
            'event=gecko_download_bridge_capture_started scheme=' +
              compactDownloadValue(downloadUrl.split(':')[0], 24) +
              ' fileName=' + compactDownloadValue(fileName, 120)
          );
          resolveBlob(downloadUrl)
            .then(function(blob) {
              if (!blob) {
                throw new Error('未找到导出数据');
              }
              return readBlobAsBase64(blob).then(function(base64) {
                return saveBase64FileChunked(fileName, blob.type || '', base64);
              });
            })
            .then(function() {
              recordDownloadDiagnostic(
                'event=gecko_download_bridge_capture_saved scheme=' +
                  compactDownloadValue(downloadUrl.split(':')[0], 24) +
                  ' fileName=' + compactDownloadValue(fileName, 120)
              );
            })
            .catch(function(error) {
              recordDownloadDiagnostic(
                'event=gecko_download_bridge_capture_failed scheme=' +
                  compactDownloadValue(downloadUrl.split(':')[0], 24) +
                  ' fileName=' + compactDownloadValue(fileName, 120) +
                  ' error=' + compactDownloadValue(error && error.message ? error.message : error, 160)
              );
              nativeBridge.reportDownloadFailure(JSON.stringify({
                fileName,
                message: error && error.message ? error.message : '导出失败'
              }));
            });
        }
        window.__staiAndroidDownloadBridgeCaptureUrl = function(downloadUrl, fileNameHint) {
          if (!downloadUrl || (!downloadUrl.startsWith('blob:') && !downloadUrl.startsWith('data:'))) {
            return false;
          }
          const fileName = sanitizeFileName(fileNameHint, 'download');
          saveBlobUrl(downloadUrl, fileName);
          return true;
        };
        function saveHttpDownload(anchor) {
          const href = anchor.href || '';
          if (!shouldCaptureHttpDownload(href, anchor)) {
            return false;
          }
          const provisionalFileName = resolveAnchorDownloadFileName(anchor, href, null);
          nativeBridge.onBlobDownloadPreparing(provisionalFileName);
          recordDownloadDiagnostic(
            'event=gecko_download_bridge_http_capture_started fileName=' +
              compactDownloadValue(provisionalFileName, 120) +
              ' url=' + compactDownloadValue(href, 180)
          );
          fetch(href, { credentials: 'include' })
            .then(function(response) {
              if (!response.ok) {
                throw new Error('HTTP ' + response.status);
              }
              const fileName = resolveAnchorDownloadFileName(anchor, href, response);
              return response.blob().then(function(blob) {
                return readBlobAsBase64(blob).then(function(base64) {
                  return saveBase64FileChunked(fileName, blob.type || response.headers.get('content-type') || '', base64)
                    .then(function() {
                      recordDownloadDiagnostic(
                        'event=gecko_download_bridge_http_capture_saved fileName=' +
                          compactDownloadValue(fileName, 120)
                      );
                    });
                });
              });
            })
            .catch(function(error) {
              recordDownloadDiagnostic(
                'event=gecko_download_bridge_http_capture_failed fileName=' +
                  compactDownloadValue(provisionalFileName, 120) +
                  ' error=' + compactDownloadValue(error && error.message ? error.message : error, 160)
              );
              nativeBridge.reportDownloadFailure(JSON.stringify({
                fileName: provisionalFileName,
                message: error && error.message ? error.message : '下载失败'
              }));
            });
          return true;
        }
        function interceptAnchorDownload(anchor) {
          if (!anchor) {
            return false;
          }
          const href = anchor.href || '';
          if (!href.startsWith('blob:') && !href.startsWith('data:')) {
            return saveHttpDownload(anchor);
          }
          const fileName = sanitizeFileName(anchor.getAttribute('download'), fileNameFromUrl(href));
          window.__staiAndroidDownloadBridgeCaptureUrl(href, fileName);
          return true;
        }
        HTMLElement.prototype.click = function() {
          if (this instanceof HTMLAnchorElement && interceptAnchorDownload(this)) {
            return;
          }
          return originalElementClick.call(this);
        };
        if (originalWindowOpen) {
          window.open = function(url, target, features) {
            const downloadUrl = String(url || '');
            if (downloadUrl.startsWith('blob:') || downloadUrl.startsWith('data:')) {
              window.__staiAndroidDownloadBridgeCaptureUrl(downloadUrl, 'download');
              return null;
            }
            return originalWindowOpen(url, target, features);
          };
        }
        document.addEventListener('click', function(event) {
          const target = event.target;
          const anchor = target && typeof target.closest === 'function' ? target.closest('a[href]') : null;
          if (!anchor || !interceptAnchorDownload(anchor)) {
            return;
          }
          event.preventDefault();
          event.stopPropagation();
        }, true);
      }

      function installSystemBarThemeSync() {
        const bridge = window[hostBridgeName];
        if (!bridge || typeof bridge.setSystemBarsBackgroundColor !== 'function') {
          return;
        }
        function normalizeHexColor(input) {
          const value = String(input || '').trim().toLowerCase();
          if (!value || value === 'transparent') {
            return '';
          }
          const hexMatch = value.match(/^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$/i);
          if (hexMatch) {
            const hex = hexMatch[1];
            if (hex.length === 3) {
              return '#' + hex.split('').map(function(char) { return char + char; }).join('');
            }
            if (hex.length === 8) {
              const alpha = parseInt(hex.slice(6, 8), 16);
              if (alpha <= 3) {
                return '';
              }
              return '#' + hex.slice(0, 6);
            }
            return '#' + hex.slice(0, 6);
          }
          const rgbaMatch = value.match(/^rgba?\(\s*(\d{1,3})(?:\s*,\s*|\s+)(\d{1,3})(?:\s*,\s*|\s+)(\d{1,3})(?:\s*(?:\/|,)\s*([0-9.]+%?))?\s*\)$/);
          if (rgbaMatch) {
            const alphaText = rgbaMatch[4];
            const alpha = alphaText && alphaText.endsWith('%') ? Number(alphaText.slice(0, -1)) / 100 : Number(alphaText == null ? 1 : alphaText);
            if (!Number.isFinite(alpha) || alpha <= 0.01) {
              return '';
            }
            const rgb = rgbaMatch.slice(1, 4).map(function(channel) {
              const clamped = Math.max(0, Math.min(255, Number(channel)));
              return clamped.toString(16).padStart(2, '0');
            });
            return '#' + rgb.join('');
          }
          return '';
        }
        function firstSolidBackgroundHex() {
          const themeMeta = document.querySelector('meta[name="theme-color"]');
          const metaColor = normalizeHexColor(themeMeta && themeMeta.content);
          if (metaColor) {
            return metaColor;
          }
          const candidates = [document.getElementById('bg1'), document.body, document.documentElement];
          for (const node of candidates) {
            if (!node) {
              continue;
            }
            const color = normalizeHexColor(window.getComputedStyle(node).backgroundColor);
            if (color) {
              return color;
            }
          }
          return '';
        }
        function notifyBridge() {
          if (document.documentElement && document.documentElement.dataset.sillydroidTheme === 'glass') {
            return;
          }
          const nextColor = firstSolidBackgroundHex();
          if (!nextColor || nextColor === window.__sillyDroidLastSystemBarColor) {
            return;
          }
          window.__sillyDroidLastSystemBarColor = nextColor;
          bridge.setSystemBarsBackgroundColor(nextColor);
        }
        if (window.__sillyDroidSystemBarThemeSyncInstalled) {
          notifyBridge();
          return;
        }
        let frameScheduled = false;
        function scheduleNotify() {
          if (frameScheduled) {
            return;
          }
          frameScheduled = true;
          window.requestAnimationFrame(function() {
            frameScheduled = false;
            notifyBridge();
          });
        }
        const observer = new MutationObserver(scheduleNotify);
        if (document.documentElement) {
          observer.observe(document.documentElement, {
            attributes: true,
            childList: true,
            subtree: true,
            attributeFilter: ['class', 'style', 'data-theme', 'theme', 'content']
          });
        }
        window.addEventListener('load', scheduleNotify);
        window.addEventListener('hashchange', scheduleNotify);
        window.addEventListener('popstate', scheduleNotify);
        document.addEventListener('readystatechange', scheduleNotify);
        window.__sillyDroidSystemBarThemeSyncInstalled = true;
        scheduleNotify();
      }

      function installWebPerformanceDiagnostic() {
        if (window.__sillyDroidWebPerformanceDiagnosticInstalled) {
          return;
        }
        window.__sillyDroidWebPerformanceDiagnosticInstalled = true;
        const bridge = window[hostBridgeName];
        if (!bridge || typeof bridge.recordWebPerformanceDiagnostic !== 'function') {
          return;
        }
        const longTasks = [];
        let observer = null;
        if ('PerformanceObserver' in window) {
          try {
            observer = new PerformanceObserver(function(list) {
              for (const entry of list.getEntries()) {
                longTasks.push(Math.round(entry.duration || 0));
              }
            });
            observer.observe({ type: 'longtask', buffered: true });
          } catch (_) {
            observer = null;
          }
        }
        function round(value) {
          return Number.isFinite(value) ? Math.max(0, Math.round(value)) : 0;
        }
        function hostKind(urlText) {
          try {
            const url = new URL(urlText, location.href);
            if (url.hostname === '127.0.0.1' || url.hostname === 'localhost' || url.hostname === '::1') {
              return 'local';
            }
            return url.protocol === 'data:' || url.protocol === 'blob:' ? url.protocol.slice(0, -1) : 'remote';
          } catch (_) {
            return 'unknown';
          }
        }
        function isIndexedDbAvailable() {
          try {
            return typeof indexedDB !== 'undefined' && !!indexedDB;
          } catch (_) {
            return false;
          }
        }
        function isStorageAvailable(name) {
          try {
            const storage = window[name];
            if (!storage) {
              return false;
            }
            const key = '__sillydroid_storage_probe__';
            storage.setItem(key, '1');
            storage.removeItem(key);
            return true;
          } catch (_) {
            return false;
          }
        }
        function isCacheStorageAvailable() {
          try {
            return typeof caches !== 'undefined' && !!caches;
          } catch (_) {
            return false;
          }
        }
        function isWebGlAvailable() {
          try {
            const canvas = document.createElement('canvas');
            return !!(canvas.getContext('webgl2') || canvas.getContext('webgl') || canvas.getContext('experimental-webgl'));
          } catch (_) {
            return false;
          }
        }
        function webGlContextInfo() {
          const emptyInfo = {
            available: false,
            contextName: '',
            version: '',
            shadingLanguageVersion: '',
            vendor: '',
            renderer: '',
            unmaskedVendor: '',
            unmaskedRenderer: ''
          };
          try {
            const canvas = document.createElement('canvas');
            const contextNames = ['webgl2', 'webgl', 'experimental-webgl'];
            let gl = null;
            let contextName = '';
            for (let index = 0; index < contextNames.length; index += 1) {
              contextName = contextNames[index];
              gl = canvas.getContext(contextName);
              if (gl) {
                break;
              }
            }
            if (!gl) {
              return emptyInfo;
            }
            const debugInfo = gl.getExtension('WEBGL_debug_renderer_info');
            return {
              available: true,
              contextName,
              version: compactText(gl.getParameter(gl.VERSION), 120),
              shadingLanguageVersion: compactText(gl.getParameter(gl.SHADING_LANGUAGE_VERSION), 120),
              vendor: compactText(gl.getParameter(gl.VENDOR), 120),
              renderer: compactText(gl.getParameter(gl.RENDERER), 160),
              unmaskedVendor: compactText(debugInfo ? gl.getParameter(debugInfo.UNMASKED_VENDOR_WEBGL) : '', 120),
              unmaskedRenderer: compactText(debugInfo ? gl.getParameter(debugInfo.UNMASKED_RENDERER_WEBGL) : '', 180)
            };
          } catch (_) {
            return emptyInfo;
          }
        }
        function isWebGpuAvailable() {
          try {
            return typeof navigator !== 'undefined' && !!navigator.gpu;
          } catch (_) {
            return false;
          }
        }
        function isWorkerAvailable() {
          try {
            return typeof Worker !== 'undefined';
          } catch (_) {
            return false;
          }
        }
        function isSharedWorkerAvailable() {
          try {
            return typeof SharedWorker !== 'undefined';
          } catch (_) {
            return false;
          }
        }
        function isServiceWorkerAvailable() {
          try {
            return typeof navigator !== 'undefined' && 'serviceWorker' in navigator;
          } catch (_) {
            return false;
          }
        }
        function isWasmAvailable() {
          try {
            return typeof WebAssembly !== 'undefined' && typeof WebAssembly.instantiate === 'function';
          } catch (_) {
            return false;
          }
        }
        function isOffscreenCanvasAvailable() {
          try {
            return typeof OffscreenCanvas !== 'undefined';
          } catch (_) {
            return false;
          }
        }
        function isCanvas2dAvailable() {
          try {
            const canvas = document.createElement('canvas');
            return !!canvas.getContext('2d');
          } catch (_) {
            return false;
          }
        }
        function cssSupports(value, expected) {
          try {
            return typeof CSS !== 'undefined' && typeof CSS.supports === 'function' && CSS.supports(value, expected);
          } catch (_) {
            return false;
          }
        }
        function cssSupportsSelector(selector) {
          try {
            return typeof CSS !== 'undefined' && typeof CSS.supports === 'function' && CSS.supports('selector(' + selector + ')');
          } catch (_) {
            return false;
          }
        }
        function filePickerCapabilitySummary() {
          return {
            showOpenFilePickerAvailable: typeof window.showOpenFilePicker === 'function',
            showSaveFilePickerAvailable: typeof window.showSaveFilePicker === 'function',
            showDirectoryPickerAvailable: typeof window.showDirectoryPicker === 'function'
          };
        }
        function clipboardCapabilitySummary() {
          const clipboard = typeof navigator !== 'undefined' ? navigator.clipboard : null;
          return {
            clipboardApiAvailable: !!clipboard,
            clipboardReadAvailable: !!clipboard && typeof clipboard.read === 'function',
            clipboardReadTextAvailable: !!clipboard && typeof clipboard.readText === 'function',
            clipboardWriteAvailable: !!clipboard && typeof clipboard.write === 'function',
            clipboardWriteTextAvailable: !!clipboard && typeof clipboard.writeText === 'function'
          };
        }
        function inputCapabilitySummary() {
          return {
            pointerEventAvailable: typeof PointerEvent !== 'undefined',
            touchEventAvailable: typeof TouchEvent !== 'undefined',
            maxTouchPoints: typeof navigator !== 'undefined' ? navigator.maxTouchPoints || 0 : 0
          };
        }
        function isFunction(value, key) {
          return !!value && typeof value[key] === 'function';
        }
        function bridgeCapabilitySummary() {
          const hostBridge = window[hostBridgeName] || globalThis[hostBridgeName];
          const downloadBridge = window[downloadBridgeName] || globalThis[downloadBridgeName];
          const notificationBridge = window[notificationBridgeName] || globalThis[notificationBridgeName];
          const notificationApi = typeof Notification !== 'undefined' ? Notification : null;
          return {
            geckoPageBridgeInstalled: window.__sillyDroidGeckoPageBridgeInstalled === true,
            hostBridgeAvailable: !!hostBridge,
            hostBridgeVersionInfoAvailable: isFunction(hostBridge, 'getHostVersionInfo'),
            hostBridgePerformanceDiagnosticAvailable: isFunction(hostBridge, 'recordWebPerformanceDiagnostic'),
            hostBridgeSystemBarsAvailable: isFunction(hostBridge, 'setSystemBarsBackgroundColor') || isFunction(hostBridge, 'setSystemBarsBackgroundColors'),
            hostBridgeOpenSettingsAvailable: isFunction(hostBridge, 'openSettings'),
            hostBridgeReloadAvailable: isFunction(hostBridge, 'reloadTavern'),
            downloadBridgeAvailable: !!downloadBridge,
            downloadBridgeSingleShotAvailable: isFunction(downloadBridge, 'saveBase64File'),
            downloadBridgeChunkedAvailable: isFunction(downloadBridge, 'beginBase64File') &&
              isFunction(downloadBridge, 'appendBase64FileChunk') &&
              isFunction(downloadBridge, 'completeBase64File') &&
              isFunction(downloadBridge, 'cancelBase64File'),
            blobDownloadHookInstalled: window.__staiAndroidDownloadBridgeInstalled === true,
            blobCaptureFunctionAvailable: typeof window.__staiAndroidDownloadBridgeCaptureUrl === 'function',
            notificationBridgeAvailable: !!notificationBridge,
            notificationShimInstalled: window.__staiAndroidHostNotificationInstalled === true,
            notificationApiAvailable: !!notificationApi,
            notificationPermission: notificationApi && typeof notificationApi.permission === 'string' ? notificationApi.permission : '',
            hostInfoBrowserEngine: hostVersionInfoCache && hostVersionInfoCache.browserEngine || '',
            hostInfoRuntimeName: hostVersionInfoCache && hostVersionInfoCache.browserRuntimeName || '',
            hostInfoCoreVersion: hostVersionInfoCache && hostVersionInfoCache.browserCoreVersion || ''
          };
        }
        function compactText(value, limit) {
          const text = String(value || '').replace(/\s+/g, ' ').trim();
          if (!text) {
            return '';
          }
          return text.length <= limit ? text : text.slice(0, limit) + '...';
        }
        function viewportMetaContent() {
          const viewportMeta = document.querySelector('meta[name="viewport"]');
          return viewportMeta ? viewportMeta.getAttribute('content') || '' : '';
        }
        function computedFontSizePx(node) {
          try {
            const fontSize = node ? window.getComputedStyle(node).fontSize : '';
            return round(Number.parseFloat(fontSize) || 0);
          } catch (_) {
            return 0;
          }
        }
        function computedCssZoom(node) {
          try {
            const zoom = node ? window.getComputedStyle(node).zoom : '';
            return Number.parseFloat(zoom) || 0;
          } catch (_) {
            return 0;
          }
        }
        function computedLineHeightPx(node) {
          try {
            const style = node ? window.getComputedStyle(node) : null;
            if (!style) {
              return 0;
            }
            const lineHeight = Number.parseFloat(style.lineHeight || '');
            if (Number.isFinite(lineHeight)) {
              return round(lineHeight);
            }
            return computedFontSizePx(node);
          } catch (_) {
            return 0;
          }
        }
        function computedTextSizeAdjust(node) {
          try {
            const style = node ? window.getComputedStyle(node) : null;
            if (!style) {
              return '';
            }
            return compactText(
              style.getPropertyValue('-webkit-text-size-adjust') ||
                style.getPropertyValue('text-size-adjust') ||
                style.webkitTextSizeAdjust ||
                '',
              40
            );
          } catch (_) {
            return '';
          }
        }
        function computedFontFamily(node) {
          try {
            return compactText(node ? window.getComputedStyle(node).fontFamily : '', 80);
          } catch (_) {
            return '';
          }
        }
        function elementMetric(selector) {
          try {
            const node = document.querySelector(selector);
            if (!node) {
              return null;
            }
            const rect = node.getBoundingClientRect();
            const style = window.getComputedStyle(node);
            return {
              width: round(rect.width),
              height: round(rect.height),
              top: round(rect.top),
              bottom: round(rect.bottom),
              display: compactText(style.display, 24),
              fontSizePx: round(Number.parseFloat(style.fontSize || '') || 0),
              lineHeightPx: round(Number.parseFloat(style.lineHeight || '') || 0)
            };
          } catch (_) {
            return null;
          }
        }
        function mediaMatches(query) {
          try {
            return typeof window.matchMedia === 'function' && window.matchMedia(query).matches;
          } catch (_) {
            return false;
          }
        }
        function buildSummary() {
          const nav = performance.getEntriesByType('navigation')[0];
          const resources = performance.getEntriesByType('resource');
          const webGlInfo = webGlContextInfo();
          const filePickerCapabilities = filePickerCapabilitySummary();
          const clipboardCapabilities = clipboardCapabilitySummary();
          const inputCapabilities = inputCapabilitySummary();
          const slowResources = resources
            .filter(function(entry) { return (entry.duration || 0) >= 250; })
            .sort(function(left, right) { return (right.duration || 0) - (left.duration || 0); })
            .slice(0, 5)
            .map(function(entry) {
              return {
                kind: entry.initiatorType || 'unknown',
                host: hostKind(entry.name),
                durationMs: round(entry.duration),
                transferSize: round(entry.transferSize || 0),
                encodedBodySize: round(entry.encodedBodySize || 0)
              };
            });
          const sortedLongTasks = longTasks.slice().sort(function(left, right) { return right - left; });
          return {
            event: 'page_load_summary',
            browserEngine: 'GECKOVIEW',
            hrefHost: hostKind(location.href),
            navType: nav ? nav.type : 'unknown',
            domContentLoadedMs: nav ? round(nav.domContentLoadedEventEnd - nav.startTime) : 0,
            loadEventMs: nav ? round(nav.loadEventEnd - nav.startTime) : 0,
            responseEndMs: nav ? round(nav.responseEnd - nav.startTime) : 0,
            transferSize: nav ? round(nav.transferSize || 0) : 0,
            encodedBodySize: nav ? round(nav.encodedBodySize || 0) : 0,
            resourceCount: resources.length,
            slowResourceCount: resources.filter(function(entry) { return (entry.duration || 0) >= 250; }).length,
            slowResources: slowResources,
            longTaskCount: longTasks.length,
            maxLongTaskMs: sortedLongTasks[0] || 0,
            topLongTasksMs: sortedLongTasks.slice(0, 5),
            viewportInnerWidth: round(window.innerWidth || 0),
            viewportInnerHeight: round(window.innerHeight || 0),
            documentClientWidth: round(document.documentElement ? document.documentElement.clientWidth : 0),
            documentClientHeight: round(document.documentElement ? document.documentElement.clientHeight : 0),
            visualViewportWidth: round(window.visualViewport ? window.visualViewport.width : 0),
            visualViewportHeight: round(window.visualViewport ? window.visualViewport.height : 0),
            visualViewportScale: window.visualViewport && Number.isFinite(window.visualViewport.scale) ? window.visualViewport.scale : 0,
            devicePixelRatio: window.devicePixelRatio || 0,
            outerWidth: round(window.outerWidth || 0),
            outerHeight: round(window.outerHeight || 0),
            screenWidth: round(window.screen ? window.screen.width : 0),
            screenHeight: round(window.screen ? window.screen.height : 0),
            screenAvailWidth: round(window.screen ? window.screen.availWidth : 0),
            screenAvailHeight: round(window.screen ? window.screen.availHeight : 0),
            userAgentFamily: navigator.userAgent && navigator.userAgent.includes('Firefox') ? 'firefox' : 'other',
            userAgent: compactText(navigator.userAgent, 180),
            viewportMetaContent: compactText(viewportMetaContent(), 180),
            rootFontSizePx: computedFontSizePx(document.documentElement),
            bodyFontSizePx: computedFontSizePx(document.body),
            rootLineHeightPx: computedLineHeightPx(document.documentElement),
            bodyLineHeightPx: computedLineHeightPx(document.body),
            rootTextSizeAdjust: computedTextSizeAdjust(document.documentElement),
            bodyTextSizeAdjust: computedTextSizeAdjust(document.body),
            rootFontFamily: computedFontFamily(document.documentElement),
            bodyFontFamily: computedFontFamily(document.body),
            rootCssZoom: computedCssZoom(document.documentElement),
            bodyCssZoom: computedCssZoom(document.body),
            mediaWidthMax360: mediaMatches('(max-width: 360px)'),
            mediaWidthMin390: mediaMatches('(min-width: 390px)'),
            mediaWidthMin600: mediaMatches('(min-width: 600px)'),
            mediaPointerCoarse: mediaMatches('(pointer: coarse)'),
            mediaPointerFine: mediaMatches('(pointer: fine)'),
            mediaHoverNone: mediaMatches('(hover: none)'),
            mediaHoverHover: mediaMatches('(hover: hover)'),
            mediaAnyPointerFine: mediaMatches('(any-pointer: fine)'),
            layoutMetrics: {
              topBar: elementMetric('#top-bar'),
              chat: elementMetric('#chat'),
              sendForm: elementMetric('#send_form'),
              leftNav: elementMetric('#left-nav-panel'),
              rightNav: elementMetric('#right-nav-panel')
            },
            hardwareConcurrency: navigator.hardwareConcurrency || 0,
            deviceMemoryGb: navigator.deviceMemory || 0,
            indexedDbAvailable: isIndexedDbAvailable(),
            localStorageAvailable: isStorageAvailable('localStorage'),
            sessionStorageAvailable: isStorageAvailable('sessionStorage'),
            cacheStorageAvailable: isCacheStorageAvailable(),
            webGlAvailable: webGlInfo.available || isWebGlAvailable(),
            webGlContextName: webGlInfo.contextName,
            webGlVersion: webGlInfo.version,
            webGlShadingLanguageVersion: webGlInfo.shadingLanguageVersion,
            webGlVendor: webGlInfo.vendor,
            webGlRenderer: webGlInfo.renderer,
            webGlUnmaskedVendor: webGlInfo.unmaskedVendor,
            webGlUnmaskedRenderer: webGlInfo.unmaskedRenderer,
            webGpuAvailable: isWebGpuAvailable(),
            canvas2dAvailable: isCanvas2dAvailable(),
            webWorkerAvailable: isWorkerAvailable(),
            sharedWorkerAvailable: isSharedWorkerAvailable(),
            serviceWorkerAvailable: isServiceWorkerAvailable(),
            wasmAvailable: isWasmAvailable(),
            offscreenCanvasAvailable: isOffscreenCanvasAvailable(),
            requestAnimationFrameAvailable: typeof requestAnimationFrame === 'function',
            mutationObserverAvailable: typeof MutationObserver !== 'undefined',
            intersectionObserverAvailable: typeof IntersectionObserver !== 'undefined',
            resizeObserverAvailable: typeof ResizeObserver !== 'undefined',
            visualViewportAvailable: typeof visualViewport !== 'undefined',
            cryptoRandomUuidAvailable: typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function',
            cssHasSelectorAvailable: cssSupportsSelector(':has(*)'),
            cssDvhAvailable: cssSupports('height', '100dvh'),
            cssColorMixAvailable: cssSupports('color', 'color-mix(in srgb, red 50%, blue)'),
            secureContext: window.isSecureContext === true,
            filePickerCapabilities,
            clipboardCapabilities,
            inputCapabilities,
            bridgeCapabilities: bridgeCapabilitySummary()
          };
        }
        function compactCapabilityValue(value) {
          if (value == null) {
            return '-';
          }
          if (typeof value === 'string') {
            return value.replace(/\s+/g, '_').slice(0, 80) || '-';
          }
          if (typeof value === 'object') {
            return JSON.stringify(value).replace(/\s+/g, '_').slice(0, 120);
          }
          return String(value);
        }
        function buildCompactCapabilitySummary(summary) {
          const bridgeCapabilities = summary.bridgeCapabilities || {};
          const topBar = summary.layoutMetrics && summary.layoutMetrics.topBar || {};
          const sendForm = summary.layoutMetrics && summary.layoutMetrics.sendForm || {};
          // 完整 JSON 会被宿主按安全长度裁剪；这条短摘要专门保留排查缩放、能力开关和桥接状态的关键字段。
          return [
            'event=page_capability_summary',
            'browserEngine=GECKOVIEW',
            'viewportInnerWidth=' + compactCapabilityValue(summary.viewportInnerWidth),
            'visualViewportWidth=' + compactCapabilityValue(summary.visualViewportWidth),
            'visualViewportScale=' + compactCapabilityValue(summary.visualViewportScale),
            'devicePixelRatio=' + compactCapabilityValue(summary.devicePixelRatio),
            'screenWidth=' + compactCapabilityValue(summary.screenWidth),
            'rootFontSizePx=' + compactCapabilityValue(summary.rootFontSizePx),
            'bodyFontSizePx=' + compactCapabilityValue(summary.bodyFontSizePx),
            'rootLineHeightPx=' + compactCapabilityValue(summary.rootLineHeightPx),
            'rootCssZoom=' + compactCapabilityValue(summary.rootCssZoom),
            'bodyCssZoom=' + compactCapabilityValue(summary.bodyCssZoom),
            'rootTextSizeAdjust=' + compactCapabilityValue(summary.rootTextSizeAdjust),
            'viewportMeta=' + compactCapabilityValue(summary.viewportMetaContent),
            'mediaWidthMax360=' + compactCapabilityValue(summary.mediaWidthMax360),
            'mediaWidthMin390=' + compactCapabilityValue(summary.mediaWidthMin390),
            'mediaWidthMin600=' + compactCapabilityValue(summary.mediaWidthMin600),
            'topBarHeight=' + compactCapabilityValue(topBar.height),
            'sendFormHeight=' + compactCapabilityValue(sendForm.height),
            'hostBridge=' + compactCapabilityValue(bridgeCapabilities.hostBridgeAvailable),
            'downloadBridge=' + compactCapabilityValue(bridgeCapabilities.downloadBridgeAvailable),
            'downloadChunked=' + compactCapabilityValue(bridgeCapabilities.downloadBridgeChunkedAvailable),
            'blobHook=' + compactCapabilityValue(bridgeCapabilities.blobDownloadHookInstalled),
            'notificationShim=' + compactCapabilityValue(bridgeCapabilities.notificationShimInstalled),
            'indexedDb=' + compactCapabilityValue(summary.indexedDbAvailable),
            'localStorage=' + compactCapabilityValue(summary.localStorageAvailable),
            'cacheStorage=' + compactCapabilityValue(summary.cacheStorageAvailable),
            'webGl=' + compactCapabilityValue(summary.webGlAvailable),
            'webGlContext=' + compactCapabilityValue(summary.webGlContextName),
            'webGlRenderer=' + compactCapabilityValue(summary.webGlUnmaskedRenderer || summary.webGlRenderer),
            'webGpu=' + compactCapabilityValue(summary.webGpuAvailable),
            'canvas2d=' + compactCapabilityValue(summary.canvas2dAvailable),
            'worker=' + compactCapabilityValue(summary.webWorkerAvailable),
            'wasm=' + compactCapabilityValue(summary.wasmAvailable),
            'offscreenCanvas=' + compactCapabilityValue(summary.offscreenCanvasAvailable),
            'secureContext=' + compactCapabilityValue(summary.secureContext),
            'clipboardWriteText=' + compactCapabilityValue(summary.clipboardCapabilities && summary.clipboardCapabilities.clipboardWriteTextAvailable),
            'showOpenFilePicker=' + compactCapabilityValue(summary.filePickerCapabilities && summary.filePickerCapabilities.showOpenFilePickerAvailable),
            'pointerEvent=' + compactCapabilityValue(summary.inputCapabilities && summary.inputCapabilities.pointerEventAvailable),
            'maxTouchPoints=' + compactCapabilityValue(summary.inputCapabilities && summary.inputCapabilities.maxTouchPoints),
            'hostInfoRuntime=' + compactCapabilityValue(bridgeCapabilities.hostInfoRuntimeName),
            'hostInfoCore=' + compactCapabilityValue(bridgeCapabilities.hostInfoCoreVersion)
          ].join(' ');
        }
        function sendSummary() {
          if (window.__sillyDroidWebPerformanceDiagnosticSent) {
            return;
          }
          window.__sillyDroidWebPerformanceDiagnosticSent = true;
          try {
            if (observer) {
              observer.disconnect();
            }
            const summary = buildSummary();
            bridge.recordWebPerformanceDiagnostic(buildCompactCapabilitySummary(summary));
            bridge.recordWebPerformanceDiagnostic(JSON.stringify(summary));
          } catch (error) {
            bridge.recordWebPerformanceDiagnostic('event=page_load_summary_failed reason=' + String(error && error.message || error));
          }
        }
        if (document.readyState === 'complete') {
          setTimeout(sendSummary, 800);
        } else {
          window.addEventListener('load', function() {
            setTimeout(sendSummary, 800);
          }, { once: true });
        }
      }

      function installPageHooks() {
        installStartupLoaderAndTheme();
        installNotificationShim();
        installBlobDownloadBridge();
        installSystemBarThemeSync();
        installWebPerformanceDiagnostic();
        refreshHostVersionInfo();
        refreshNotificationPermissionState();
      }

      installPageHooks();
      if (document.readyState === 'loading') {
        document.addEventListener('readystatechange', installPageHooks);
      }
    } + ')(' + JSON.stringify({
      requestMessageType,
      responseMessageType,
      hostInfoMessageType,
      hostBridgeName,
      notificationBridgeName,
      downloadBridgeName,
      defaultHostVersionInfo
    }) + ');';
    parent.appendChild(script);
    script.remove();
    recordBridgeDiagnostic(
      'event=gecko_page_bridge_installed hostBridgeName=' + hostBridgeName +
        ' downloadBridgeName=' + downloadBridgeName +
        ' notificationBridgeName=' + notificationBridgeName
    );
    initialHostVersionInfoPromise.then(function(value) {
      window.postMessage({
        type: hostInfoMessageType,
        value
      }, '*');
    });
  }

  installViewportDensityPort();
  installVisibilityDiagnostic();
  injectPageScript();
})();
