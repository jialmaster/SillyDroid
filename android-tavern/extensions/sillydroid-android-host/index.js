import { Popup, POPUP_TYPE } from '../../../popup.js';
import { extension_settings } from '../../../extensions.js';
import { eventSource, event_types, saveSettingsDebounced } from '../../../../script.js';

const extensionName = 'sillydroid-android-host';
const extensionFolderPath = `scripts/extensions/third-party/${extensionName}`;
const settingsPanelId = 'sillydroid_android_host_settings_panel';
const themeStylesheetId = 'sillydroid_android_host_theme_stylesheet';
const themeNativeObserverId = 'sillydroidThemeBound';
const startupThemeStateKey = 'sillydroidAndroidHostStartupThemeState';
// 主题 CSS URL 版本用于破坏 WebView/浏览器缓存；修改内置主题样式时必须同步 bump，避免继续使用旧 glass.css。
const themeStylesheetVersion = '1.0.30';
const worldInfoSelect2ObserverId = 'sillydroidWorldInfoSelect2Observed';
const popupTitle = '安卓宿主';
const webViewSafeAutoMajorVersion = 121;
const huaweiWebViewSafeAutoMajorVersion = 120;
const defaultTavernSystemBarColor = '#111827';
const oldDefaultThemeAccentColor = '#2f7dff';
const oldDefaultThemeSecondaryColor = '#24d6b5';

const themeChoices = [
    { value: 'default', label: '默认' },
    { value: 'glass', label: '毛玻璃' },
];

const themeModeChoices = [
    { value: 'auto', label: '自动' },
    { value: 'light', label: '浅色' },
    { value: 'dark', label: '深色' },
];

const themePerformanceChoices = [
    { value: 'auto', label: '自动' },
    { value: 'simple', label: '简约' },
    { value: 'quality', label: '高质量' },
];

const defaultSettings = {
    enableNotification: false,
    enableSoundNotification: false,
    compactChatLayout: false,
    theme: 'default',
    themeMode: 'auto',
    themePerformanceMode: 'auto',
    themeAccentColor: '#6f8fbf',
    themeSecondaryColor: '#8fb8a7',
};

const themeAccentColorPresets = [
    { label: '雾蓝', color: '#6f8fbf' },
    { label: '霁青', color: '#5f92a3' },
    { label: '暮蓝', color: '#6687b8' },
    { label: '松绿', color: '#6f9a86' },
    { label: '月影', color: '#8587a5' },
    { label: '岩茶', color: '#8b8174' },
];

const themeSecondaryColorPresets = [
    { label: '薄荷', color: '#8fb8a7' },
    { label: '鼠尾草', color: '#a8a075' },
    { label: '雾玫', color: '#b08f8b' },
    { label: '茶绿', color: '#799985' },
    { label: '沙金', color: '#b4a16f' },
    { label: '灰蓝', color: '#879cac' },
];

const themeStylesheets = {
    glass: `${extensionFolderPath}/themes/glass.css?v=${themeStylesheetVersion}`,
};

const themeOptions = new Set(themeChoices.map(choice => choice.value));
const themeModeOptions = new Set(themeModeChoices.map(choice => choice.value));
const themePerformanceOptions = new Set(themePerformanceChoices.map(choice => choice.value));
const themeColorPattern = /^#[\da-f]{6}$/i;

let messageAlertHandler = null;
let notificationAudioContext = null;

function normalizeTheme(value) {
    return themeOptions.has(value) ? value : defaultSettings.theme;
}

function normalizeThemeMode(value) {
    return themeModeOptions.has(value) ? value : defaultSettings.themeMode;
}

function normalizeThemePerformanceMode(value) {
    return themePerformanceOptions.has(value) ? value : defaultSettings.themePerformanceMode;
}

function normalizeThemeColor(value, defaultColor) {
    const color = String(value || '').trim();
    if (themeColorPattern.test(color)) {
        return color.toLowerCase();
    }

    return defaultColor;
}

function getExtensionSettings() {
    extension_settings[extensionName] = extension_settings[extensionName] || {};
    const settings = extension_settings[extensionName];

    for (const [key, value] of Object.entries(defaultSettings)) {
        if (settings[key] === undefined) {
            settings[key] = value;
        }
    }

    if (settings.themeAccentColor === '#e18a24' || settings.themeAccentColor === oldDefaultThemeAccentColor) {
        settings.themeAccentColor = defaultSettings.themeAccentColor;
    }

    if (settings.themeSecondaryColor === oldDefaultThemeSecondaryColor) {
        settings.themeSecondaryColor = defaultSettings.themeSecondaryColor;
    }

    settings.theme = normalizeTheme(settings.theme);
    settings.themeMode = normalizeThemeMode(settings.themeMode);
    settings.themePerformanceMode = normalizeThemePerformanceMode(settings.themePerformanceMode);
    settings.themeAccentColor = normalizeThemeColor(settings.themeAccentColor, defaultSettings.themeAccentColor);
    settings.themeSecondaryColor = normalizeThemeColor(settings.themeSecondaryColor, defaultSettings.themeSecondaryColor);
    settings.compactChatLayout = settings.compactChatLayout === true;
    return settings;
}

function saveExtensionSetting(key, value) {
    const settings = getExtensionSettings();
    settings[key] = value;
    saveSettingsDebounced();
}

function getBridge() {
    const bridge = globalThis.SillyDroidAndroidHostBridge;
    if (!bridge || typeof bridge.getHostVersionInfo !== 'function') {
        return null;
    }

    return bridge;
}

function resolveHostManagedSwitchState(hostInfo = getHostVersionInfo()) {
    return {
        // 悬浮球和下拉刷新都由 Android 宿主偏好持久化；扩展面板首屏必须读宿主真实值，不能退回本地默认值。
        floatingBubbleEnabled: hostInfo?.floatingLogBubbleEnabled === true,
        pullRefreshEnabled: hostInfo?.webViewPullRefreshEnabled === true,
    };
}

function resolveHostPanelCapabilities(hostInfo = getHostVersionInfo()) {
    const bridge = getBridge();
    const notificationBridge = getNativeNotificationBridge();
    return {
        showVersionSummary: Boolean(hostInfo),
        canOpenSettings: Boolean(bridge && typeof bridge.openSettings === 'function'),
        canOpenCurrentPageInBrowser: Boolean(bridge && typeof bridge.openCurrentPageInBrowser === 'function'),
        canReloadTavern: Boolean(bridge && typeof bridge.reloadTavern === 'function'),
        canToggleFloatingBubble: Boolean(bridge && typeof bridge.setFloatingLogsBubbleEnabled === 'function'),
        canTogglePullRefresh: Boolean(bridge && typeof bridge.setWebViewPullRefreshEnabled === 'function'),
        showNotificationSection: Boolean(notificationBridge),
    };
}

function setElementHidden(element, hidden) {
    if (element instanceof HTMLElement) {
        element.toggleAttribute('hidden', hidden);
    }
}

function isAndroidTouchEnvironment() {
    return Boolean(getBridge()) || (/Android/i.test(navigator.userAgent || '') && navigator.maxTouchPoints > 0);
}

function hexToRgb(color) {
    const normalizedColor = normalizeThemeColor(color, '#000000').slice(1);
    return {
        r: parseInt(normalizedColor.slice(0, 2), 16),
        g: parseInt(normalizedColor.slice(2, 4), 16),
        b: parseInt(normalizedColor.slice(4, 6), 16),
    };
}

function rgbToHex({ r, g, b }) {
    const toHex = value => Math.max(0, Math.min(255, Math.round(value))).toString(16).padStart(2, '0');
    return `#${toHex(r)}${toHex(g)}${toHex(b)}`;
}

function hexToHsl(color) {
    const { r, g, b } = hexToRgb(color);
    const red = r / 255;
    const green = g / 255;
    const blue = b / 255;
    const max = Math.max(red, green, blue);
    const min = Math.min(red, green, blue);
    const lightness = (max + min) / 2;

    if (max === min) {
        return { h: 0, s: 0, l: Math.round(lightness * 100) };
    }

    const delta = max - min;
    const saturation = lightness > 0.5 ? delta / (2 - max - min) : delta / (max + min);
    let hue = 0;

    if (max === red) {
        hue = (green - blue) / delta + (green < blue ? 6 : 0);
    } else if (max === green) {
        hue = (blue - red) / delta + 2;
    } else {
        hue = (red - green) / delta + 4;
    }

    return {
        h: Math.round(hue * 60),
        s: Math.round(saturation * 100),
        l: Math.round(lightness * 100),
    };
}

function hslToHex({ h, s, l }) {
    const hue = (((Number(h) || 0) % 360) + 360) % 360;
    const saturation = Math.max(0, Math.min(100, Number(s) || 0)) / 100;
    const lightness = Math.max(0, Math.min(100, Number(l) || 0)) / 100;
    const chroma = (1 - Math.abs(2 * lightness - 1)) * saturation;
    const hueSection = hue / 60;
    const x = chroma * (1 - Math.abs((hueSection % 2) - 1));
    const match = lightness - chroma / 2;
    let red = 0;
    let green = 0;
    let blue = 0;

    if (hueSection < 1) {
        red = chroma;
        green = x;
    } else if (hueSection < 2) {
        red = x;
        green = chroma;
    } else if (hueSection < 3) {
        green = chroma;
        blue = x;
    } else if (hueSection < 4) {
        green = x;
        blue = chroma;
    } else if (hueSection < 5) {
        red = x;
        blue = chroma;
    } else {
        red = chroma;
        blue = x;
    }

    return rgbToHex({
        r: (red + match) * 255,
        g: (green + match) * 255,
        b: (blue + match) * 255,
    });
}

function formatHslChannelValue(channel, value) {
    const normalizedValue = Number.parseInt(value, 10) || 0;
    return channel === 'h' ? `${normalizedValue}°` : `${normalizedValue}%`;
}

function setImportantColorBackground(element, color) {
    if (!(element instanceof HTMLElement)) {
        return;
    }

    // 色块展示的是用户可选颜色本身，必须高于全局 button 主题规则，避免被玻璃按钮底色覆盖成空心圆。
    element.style.setProperty('background', color, 'important');
    element.style.setProperty('background-color', color, 'important');
}

function stopThemeColorPanelEvent(event) {
    event.stopPropagation();
}

function bindThemeColorPanelIsolation(panel) {
    if (!(panel instanceof HTMLElement) || panel.dataset.sillydroidEventIsolated) {
        return;
    }

    panel.dataset.sillydroidEventIsolated = 'true';
    ['pointerdown', 'mousedown', 'touchstart', 'click'].forEach(eventName => {
        // 色板浮层会提升到扩展设置容器顶层；必须隔离点击事件，避免酒馆把它当作菜单外点击而收起扩展管理。
        panel.addEventListener(eventName, stopThemeColorPanelEvent);
    });
}

function mixHexColors(baseColor, overlayColor, overlayWeight) {
    const base = hexToRgb(baseColor);
    const overlay = hexToRgb(overlayColor);
    const weight = Math.max(0, Math.min(1, overlayWeight));
    return rgbToHex({
        r: base.r * (1 - weight) + overlay.r * weight,
        g: base.g * (1 - weight) + overlay.g * weight,
        b: base.b * (1 - weight) + overlay.b * weight,
    });
}

function resolveNativeSystemBarColors(settings, resolvedMode) {
    if (settings.theme === 'default') {
        // 默认主题也只同步系统栏纯色，避免从毛玻璃切回默认后状态栏/手势条残留上一套主题色。
        return {
            statusBarColor: defaultTavernSystemBarColor,
            navigationBarColor: defaultTavernSystemBarColor,
        };
    }

    const topBaseColor = resolvedMode === 'light' ? '#f6fcff' : '#050911';
    const bottomBaseColor = resolvedMode === 'light' ? '#f0faff' : '#060a12';
    return {
        // 毛玻璃背景是左上 primary、右下 secondary 的连续渐变；原生系统栏取同一套端点色，避免状态栏/手势条和 WebView 背景断层。
        statusBarColor: mixHexColors(topBaseColor, settings.themeAccentColor, resolvedMode === 'light' ? 0.58 : 0.56),
        navigationBarColor: mixHexColors(bottomBaseColor, settings.themeSecondaryColor, resolvedMode === 'light' ? 0.56 : 0.52),
    };
}

function buildThemeRuntimeState(settings = getExtensionSettings(), resolvedMode = resolveThemeMode(settings.themeMode), hostInfo = getHostVersionInfo()) {
    const performanceProfile = resolveThemePerformanceProfile(settings, hostInfo);
    const systemBarColors = resolveNativeSystemBarColors(settings, resolvedMode);
    return {
        theme: settings.theme,
        mode: settings.themeMode,
        performanceMode: settings.themePerformanceMode,
        effectivePerformanceMode: performanceProfile.effectiveMode,
        performanceReason: performanceProfile.reason,
        resolvedMode,
        primary: settings.themeAccentColor,
        secondary: settings.themeSecondaryColor,
        systemBarColors,
        webViewMajor: performanceProfile.webViewMajor || 0,
    };
}

function persistStartupThemeState(themeState) {
    try {
        if (!themeState || themeState.theme === 'default') {
            localStorage.removeItem(startupThemeStateKey);
            sessionStorage.removeItem(startupThemeStateKey);
            return;
        }

        // 开屏 mini CSS、正式主题 CSS 和原生系统栏共用同一份运行态快照，避免改主题色时漏同步扩展加载前首屏。
        const serializedState = JSON.stringify(themeState);
        localStorage.setItem(startupThemeStateKey, serializedState);
        sessionStorage.setItem(startupThemeStateKey, serializedState);
    } catch (error) {
        console.warn('安卓宿主：保存开屏主题快照失败', error);
    }
}

function applyThemeCustomProperties(themeState) {
    const html = document.documentElement;
    html.style.setProperty('--sillydroid-theme-primary', themeState.primary);
    html.style.setProperty('--sillydroid-theme-accent', themeState.primary);
    html.style.setProperty('--sillydroid-theme-secondary', themeState.secondary);
}

function applyThemeDataset(themeState) {
    const html = document.documentElement;
    html.dataset.sillydroidTheme = themeState.theme;
    html.dataset.sillydroidThemeMode = themeState.mode;
    html.dataset.sillydroidThemeResolvedMode = themeState.resolvedMode;
    html.dataset.sillydroidThemePerformance = themeState.performanceMode;
    html.dataset.sillydroidThemeEffectivePerformance = themeState.effectivePerformanceMode;
    html.dataset.sillydroidThemePerformanceReason = themeState.performanceReason;
    if (themeState.webViewMajor) {
        html.dataset.sillydroidWebviewMajor = String(themeState.webViewMajor);
    } else {
        delete html.dataset.sillydroidWebviewMajor;
    }
}

function syncNativeSystemBarsFromThemeState(themeState) {
    const bridge = getBridge();
    if (!bridge || (
        typeof bridge.setSystemBarsBackgroundColors !== 'function'
        && typeof bridge.setSystemBarsBackgroundColor !== 'function'
    )) {
        return;
    }

    const systemBarColors = themeState?.systemBarColors;
    if (!systemBarColors?.statusBarColor || !systemBarColors?.navigationBarColor) {
        return;
    }

    if (typeof bridge.setSystemBarsBackgroundColors === 'function') {
        bridge.setSystemBarsBackgroundColors(systemBarColors.statusBarColor, systemBarColors.navigationBarColor);
        return;
    }

    if (typeof bridge.setSystemBarsBackgroundColor === 'function') {
        bridge.setSystemBarsBackgroundColor(systemBarColors.statusBarColor);
    }
}

function syncHostPlatformState(html) {
    if (isAndroidTouchEnvironment()) {
        html.dataset.sillydroidHostPlatform = 'android';
        return;
    }

    delete html.dataset.sillydroidHostPlatform;
}

function parsePositiveInteger(value) {
    const parsed = Number.parseInt(String(value ?? '').trim(), 10);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
}

function parseMajorVersion(value) {
    const match = String(value ?? '').match(/\d+/);
    return match ? parsePositiveInteger(match[0]) : 0;
}

function parseChromeMajorFromUserAgent() {
    const match = String(navigator.userAgent || '').match(/(?:Chrome|CriOS)\/(\d+)/i);
    return match ? parsePositiveInteger(match[1]) : 0;
}

function resolveThemePerformanceProfile(settings, hostInfo = getHostVersionInfo()) {
    const requestedMode = normalizeThemePerformanceMode(settings.themePerformanceMode);
    if (requestedMode === 'simple') {
        return {
            effectiveMode: 'simple',
            reason: '已手动使用简约模式，仅保留大面板毛玻璃，滚动项和小组件禁用实时模糊。',
        };
    }

    if (requestedMode === 'quality') {
        return {
            effectiveMode: 'quality',
            reason: '已手动使用高质量模式，保留完整毛玻璃效果。',
        };
    }

    const webViewPackageName = String(hostInfo?.webViewPackageName || '').toLowerCase();
    const webViewVersionName = String(hostInfo?.webViewVersionName || '');
    const webViewMajor = parseMajorVersion(webViewVersionName) || parseChromeMajorFromUserAgent();
    const androidSdkInt = parsePositiveInteger(hostInfo?.androidSdkInt);
    const memoryClassMb = parsePositiveInteger(hostInfo?.appMemoryClassMb);
    const hardwareConcurrency = parsePositiveInteger(navigator.hardwareConcurrency);
    const hasAndroidHostBridge = Boolean(getBridge());
    const isAndroidHost = isAndroidTouchEnvironment();
    const isHuaweiWebView = /huawei|honor/.test(webViewPackageName);
    const reasons = [];

    if (isHuaweiWebView && webViewMajor > 0 && webViewMajor < huaweiWebViewSafeAutoMajorVersion) {
        reasons.push(`Huawei WebView ${webViewMajor} 命中旧合成层保守规则`);
    }

    if (webViewMajor > 0 && webViewMajor < webViewSafeAutoMajorVersion) {
        reasons.push(`WebView/Chrome ${webViewMajor} 低于自动高质量阈值 ${webViewSafeAutoMajorVersion}`);
    }

    if (hostInfo?.isLowRamDevice === true) {
        reasons.push('系统标记为低内存设备');
    }

    if (androidSdkInt > 0 && androidSdkInt <= 30) {
        reasons.push(`Android ${androidSdkInt} 的 WebView 合成层较保守`);
    }

    if (memoryClassMb > 0 && memoryClassMb <= 192) {
        reasons.push(`应用内存档 ${memoryClassMb}MB 偏低`);
    }

    if (hardwareConcurrency > 0 && hardwareConcurrency <= 4) {
        reasons.push(`CPU 线程数 ${hardwareConcurrency} 偏低`);
    }

    if (!hasAndroidHostBridge && isAndroidHost && webViewMajor === 0) {
        reasons.push('外部 Android 触屏浏览器无法读取 WebView 版本');
    }

    if (reasons.length > 0) {
        return {
            effectiveMode: 'simple',
            reason: `自动判定为简约：${reasons.join('，')}。`,
            webViewMajor,
        };
    }

    const providerText = webViewMajor > 0 ? `WebView/Chrome ${webViewMajor}` : '当前浏览器';
    return {
        effectiveMode: 'quality',
        reason: `自动判定为高质量：${providerText} 未命中旧 WebView / 低资源规则。`,
        webViewMajor,
    };
}

function syncNativeSystemBars(settings, resolvedMode) {
    syncNativeSystemBarsFromThemeState(buildThemeRuntimeState(settings, resolvedMode));
}

function getNativeNotificationBridge() {
    const bridge = globalThis.AndroidSystemNotificationBridge;
    if (!bridge || typeof bridge.showNotification !== 'function') {
        return null;
    }

    return bridge;
}

function getMessageEventBinding() {
    if (!eventSource || !event_types || !event_types.MESSAGE_RECEIVED) {
        return null;
    }

    return {
        source: eventSource,
        messageReceived: event_types.MESSAGE_RECEIVED,
    };
}

function formatVersionSummary(info) {
    if (!info) {
        return '安卓宿主桥不可用';
    }

    const hostVersion = String(info.hostVersion || 'unknown');
    const apkVersionName = String(info.apkVersionName || 'unknown');
    const apkVersionCode = String(info.apkVersionCode || 'unknown');
    const webViewVersion = String(info.webViewVersionName || '').trim();
    const webViewPackage = String(info.webViewPackageName || '').trim();
    const webViewSummary = webViewVersion ? ` | WebView ${webViewVersion}${webViewPackage ? ` (${webViewPackage})` : ''}` : '';
    return `宿主 ${hostVersion} | APK ${apkVersionName} (${apkVersionCode})${webViewSummary}`;
}

function getHostVersionInfo() {
    const bridge = getBridge();
    if (!bridge) {
        return null;
    }

    try {
        return JSON.parse(String(bridge.getHostVersionInfo() || '{}'));
    } catch (error) {
        console.error('安卓宿主：解析版本信息失败', error);
        return null;
    }
}

function openVersionPopup() {
    const info = getHostVersionInfo();
    const content = document.createElement('div');
    content.classList.add('sillydroid-host-version-popup');
    content.style.display = 'flex';
    content.style.flexDirection = 'column';
    content.style.gap = '10px';

    const description = document.createElement('p');
    description.style.margin = '0';
    description.textContent = info
        ? '这里展示安卓宿主版本和 APK 版本信息。'
        : '当前页面无法连接安卓宿主桥。';
    content.appendChild(description);

    if (info) {
        const versionLine = document.createElement('div');
        versionLine.textContent = formatVersionSummary(info);
        content.appendChild(versionLine);

        const serviceLine = document.createElement('div');
        serviceLine.textContent = info.serverReady ? '本地服务：运行中' : '本地服务：启动中或已暂停';
        content.appendChild(serviceLine);
    }

    const popup = new Popup(content, POPUP_TYPE.TEXT, '', {
        okButton: '关闭',
        cancelButton: false,
    });

    void popup.show();
}

async function openSettingsCommand() {
    const bridge = getBridge();
    if (!bridge || typeof bridge.openSettings !== 'function') {
        toastr.warning('当前页面无法连接安卓宿主桥。', popupTitle);
        return;
    }

    const opened = bridge.openSettings() === true;
    if (opened) {
        toastr.success('正在打开宿主设置。', popupTitle);
    } else {
        toastr.warning('宿主设置已在打开中。', popupTitle);
    }
}

async function openCurrentPageInBrowserCommand() {
    const bridge = getBridge();
    if (!bridge || typeof bridge.openCurrentPageInBrowser !== 'function') {
        toastr.warning('当前页面无法连接安卓宿主浏览器桥。', popupTitle);
        return;
    }

    const opened = bridge.openCurrentPageInBrowser() === true;
    if (opened) {
        toastr.success('正在通过系统浏览器打开当前页面。', popupTitle);
    } else {
        toastr.warning('当前无法在系统浏览器中打开酒馆页面。', popupTitle);
    }
}

async function reloadTavernCommand() {
    const bridge = getBridge();
    if (!bridge || typeof bridge.reloadTavern !== 'function') {
        toastr.warning('当前页面无法连接安卓宿主刷新桥。', popupTitle);
        return;
    }

    const reloaded = bridge.reloadTavern() === true;
    if (reloaded) {
        toastr.success('正在刷新酒馆页面。', popupTitle);
    } else {
        toastr.warning('当前无法刷新酒馆页面。', popupTitle);
    }
}

function showSystemMessageNotification() {
    const bridge = getNativeNotificationBridge();
    if (!bridge) {
        return;
    }

    bridge.showNotification(JSON.stringify({
        notificationId: `st-message-${Date.now()}`,
        title: 'SillyTavern',
        body: '您收到了新消息',
    }));
}

function getNotificationAudioContext() {
    const AudioContextConstructor = globalThis.AudioContext || globalThis.webkitAudioContext;
    if (!AudioContextConstructor) {
        return null;
    }

    if (!notificationAudioContext) {
        notificationAudioContext = new AudioContextConstructor();
    }

    return notificationAudioContext;
}

async function playNotificationSound() {
    const audioContext = getNotificationAudioContext();
    if (!audioContext) {
        return false;
    }

    if (audioContext.state === 'suspended') {
        await audioContext.resume();
    }

    if (audioContext.state !== 'running') {
        return false;
    }

    const startTime = audioContext.currentTime;
    const masterGain = audioContext.createGain();
    masterGain.gain.setValueAtTime(0.0001, startTime);
    masterGain.gain.exponentialRampToValueAtTime(0.18, startTime + 0.025);
    masterGain.gain.exponentialRampToValueAtTime(0.0001, startTime + 0.42);
    masterGain.connect(audioContext.destination);

    // 声音通知不依赖 Android 通知通道；用 WebAudio 合成短促双音，方便在酒馆页面内直接热更新验证。
    [660, 880].forEach((frequency, index) => {
        const oscillator = audioContext.createOscillator();
        const toneGain = audioContext.createGain();
        const toneStart = startTime + index * 0.09;
        const toneEnd = toneStart + 0.2;

        oscillator.type = 'sine';
        oscillator.frequency.setValueAtTime(frequency, toneStart);
        toneGain.gain.setValueAtTime(0.0001, toneStart);
        toneGain.gain.exponentialRampToValueAtTime(1, toneStart + 0.02);
        toneGain.gain.exponentialRampToValueAtTime(0.0001, toneEnd);

        oscillator.connect(toneGain);
        toneGain.connect(masterGain);
        oscillator.start(toneStart);
        oscillator.stop(toneEnd + 0.02);
    });

    window.setTimeout(() => {
        masterGain.disconnect();
    }, 520);

    return true;
}

function shouldListenForMessages() {
    const settings = getExtensionSettings();
    return settings.enableNotification === true || settings.enableSoundNotification === true;
}

function handleMessageReceivedForAlerts() {
    const settings = getExtensionSettings();

    if (settings.enableNotification === true) {
        showSystemMessageNotification();
    }

    if (settings.enableSoundNotification === true) {
        void playNotificationSound().catch(error => {
            console.warn('安卓宿主：播放提示音失败', error);
        });
    }
}

function attachMessageAlertListener() {
    if (messageAlertHandler) {
        return;
    }

    const binding = getMessageEventBinding();
    if (!binding) {
        return;
    }

    messageAlertHandler = handleMessageReceivedForAlerts;
    binding.source.on(binding.messageReceived, messageAlertHandler);
}

function detachMessageAlertListener() {
    if (!messageAlertHandler) {
        return;
    }

    const binding = getMessageEventBinding();
    if (binding) {
        binding.source.removeListener(binding.messageReceived, messageAlertHandler);
    }

    messageAlertHandler = null;
}

function syncMessageAlertListener() {
    if (shouldListenForMessages()) {
        attachMessageAlertListener();
    } else {
        detachMessageAlertListener();
    }
}

async function setNotificationPushEnabled(enabled) {
    if (enabled) {
        const bridge = getNativeNotificationBridge();
        if (!bridge) {
            toastr.warning('安卓通知桥不可用，请确认应用版本。', popupTitle);
            return { updated: false };
        }

        if (typeof bridge.permissionState === 'function' && bridge.permissionState() !== 'granted') {
            if (typeof bridge.requestPermission === 'function') {
                bridge.requestPermission();
            }

            await new Promise(resolve => setTimeout(resolve, 600));

            if (typeof bridge.permissionState === 'function' && bridge.permissionState() !== 'granted') {
                toastr.warning('尚未获得通知权限，请在系统设置中允许通知后再试。', popupTitle);
                return { updated: false };
            }
        }
    }

    saveExtensionSetting('enableNotification', enabled);
    syncMessageAlertListener();
    toastr[enabled ? 'success' : 'info'](enabled ? '已开启消息通知。' : '已关闭消息通知。', popupTitle);
    return { updated: true };
}

async function setSoundNotificationEnabled(enabled) {
    if (enabled) {
        const canPlaySound = await playNotificationSound().catch(error => {
            console.warn('安卓宿主：初始化提示音失败', error);
            return false;
        });

        if (!canPlaySound) {
            toastr.warning('当前 WebView 不支持提示音。', popupTitle);
            return { updated: false };
        }
    }

    saveExtensionSetting('enableSoundNotification', enabled);
    syncMessageAlertListener();
    toastr[enabled ? 'success' : 'info'](enabled ? '已开启声音通知。' : '已关闭声音通知。', popupTitle);
    return { updated: true };
}

async function setFloatingBubbleEnabled(enabled) {
    const bridge = getBridge();
    if (!bridge || typeof bridge.setFloatingLogsBubbleEnabled !== 'function') {
        toastr.warning('安卓悬浮球桥不可用，请确认应用版本。', popupTitle);
        return { updated: false };
    }

    const updated = bridge.setFloatingLogsBubbleEnabled(enabled) === true;
    if (!updated) {
        toastr.warning(enabled ? '暂时无法启用悬浮球。' : '暂时无法关闭悬浮球。', popupTitle);
        return { updated: false };
    }

    // 悬浮球状态以宿主 SharedPreferences 为唯一事实源，避免扩展本地缓存把首次打开面板的状态带偏。
    toastr[enabled ? 'success' : 'info'](enabled ? '已启用悬浮球。' : '已关闭悬浮球。', popupTitle);
    return { updated: true };
}

async function setWebViewPullRefreshEnabled(enabled) {
    const bridge = getBridge();
    if (!bridge || typeof bridge.setWebViewPullRefreshEnabled !== 'function') {
        toastr.warning('安卓下拉刷新桥不可用，请确认应用版本。', popupTitle);
        return { updated: false };
    }

    const updated = bridge.setWebViewPullRefreshEnabled(enabled) === true;
    if (!updated) {
        toastr.warning(enabled ? '暂时无法启用下拉刷新。' : '暂时无法关闭下拉刷新。', popupTitle);
        return { updated: false };
    }

    toastr[enabled ? 'success' : 'info'](enabled ? '已启用下拉刷新。' : '已关闭下拉刷新。', popupTitle);
    return { updated: true };
}

function removeThemeStylesheet() {
    document.getElementById(themeStylesheetId)?.remove();
}

function removeThemeCustomProperties() {
    document.documentElement.style.removeProperty('--sillydroid-theme-primary');
    document.documentElement.style.removeProperty('--sillydroid-theme-accent');
    document.documentElement.style.removeProperty('--sillydroid-theme-secondary');
}

function ensureThemeStylesheet(theme) {
    const stylesheetPath = themeStylesheets[theme];
    if (!stylesheetPath) {
        removeThemeStylesheet();
        return;
    }

    const existingLink = document.getElementById(themeStylesheetId);
    if (existingLink instanceof HTMLLinkElement) {
        if (existingLink.href.endsWith(stylesheetPath)) {
            return;
        }

        existingLink.remove();
    }

    const link = document.createElement('link');
    link.id = themeStylesheetId;
    link.rel = 'stylesheet';
    // WebView 会缓存扩展 CSS；主题文件带版本参数，确保热更新和测试包能立刻读取最新毛玻璃覆盖。
    link.href = stylesheetPath;
    document.head.appendChild(link);
}

function resolveThemeMode(mode) {
    if (mode !== 'auto') {
        return mode;
    }

    const nativeThemeSelect = document.getElementById('themes');
    const selectedText = nativeThemeSelect instanceof HTMLSelectElement
        ? `${nativeThemeSelect.value} ${nativeThemeSelect.selectedOptions?.[0]?.textContent || ''}`.toLowerCase()
        : '';

    if (/light|day|white|浅|亮|白/.test(selectedText)) {
        return 'light';
    }

    if (/dark|night|black|深|暗|黑|夜/.test(selectedText)) {
        return 'dark';
    }

    return globalThis.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

function bindNativeThemeRefresh() {
    const nativeThemeSelect = document.getElementById('themes');
    if (!(nativeThemeSelect instanceof HTMLSelectElement) || nativeThemeSelect.dataset[themeNativeObserverId]) {
        return;
    }

    nativeThemeSelect.dataset[themeNativeObserverId] = 'true';
    nativeThemeSelect.addEventListener('change', () => {
        window.setTimeout(applyThemeState, 80);
    });
}

function applyCompactChatLayoutState(settings = getExtensionSettings()) {
    const enabled = settings.compactChatLayout === true;
    const html = document.documentElement;
    if (enabled) {
        // 紧凑模式只暴露状态标记，布局由 CSS 处理；禁止移动聊天 DOM，避免破坏上游 swipe/按钮逻辑。
        html.dataset.sillydroidChatCompact = 'true';
        return;
    }

    delete html.dataset.sillydroidChatCompact;
}

function applyThemeState() {
    const settings = getExtensionSettings();
    const html = document.documentElement;
    const enabled = settings.theme !== 'default';
    const resolvedMode = resolveThemeMode(settings.themeMode);

    bindNativeThemeRefresh();
    syncHostPlatformState(html);
    applyCompactChatLayoutState(settings);

    if (!enabled) {
        const themeState = buildThemeRuntimeState(settings, resolvedMode);
        removeThemeStylesheet();
        removeThemeCustomProperties();
        delete html.dataset.sillydroidTheme;
        delete html.dataset.sillydroidThemeMode;
        delete html.dataset.sillydroidThemeResolvedMode;
        delete html.dataset.sillydroidThemePerformance;
        delete html.dataset.sillydroidThemeEffectivePerformance;
        delete html.dataset.sillydroidThemePerformanceReason;
        delete html.dataset.sillydroidWebviewMajor;
        persistStartupThemeState(themeState);
        syncNativeSystemBarsFromThemeState(themeState);
        return;
    }

    // JS 只接入酒馆主题链路、注入主题色变量并暴露全局状态；具体视觉覆盖和过渡动画都收敛在主题 CSS 文件内。
    const themeState = buildThemeRuntimeState(settings, resolvedMode);
    ensureThemeStylesheet(settings.theme);
    applyThemeCustomProperties(themeState);
    applyThemeDataset(themeState);
    persistStartupThemeState(themeState);
    syncNativeSystemBarsFromThemeState(themeState);
}

function setTheme(theme) {
    const normalizedTheme = normalizeTheme(theme);
    saveExtensionSetting('theme', normalizedTheme);
    syncSettingsPanel();
    toastr.success(`已切换到${normalizedTheme === 'glass' ? '毛玻璃' : '默认'}主题。`, popupTitle);
}

function setThemeMode(mode) {
    const normalizedMode = normalizeThemeMode(mode);
    saveExtensionSetting('themeMode', normalizedMode);
    syncSettingsPanel();
    toastr.success(`主题模式已切换为${themeModeChoices.find(choice => choice.value === normalizedMode)?.label || '自动'}。`, popupTitle);
}

function setThemePerformanceMode(mode) {
    const normalizedMode = normalizeThemePerformanceMode(mode);
    saveExtensionSetting('themePerformanceMode', normalizedMode);
    syncSettingsPanel();
    toastr.success(`毛玻璃性能模式已切换为${themePerformanceChoices.find(choice => choice.value === normalizedMode)?.label || '自动'}。`, popupTitle);
}

function setCompactChatLayoutEnabled(enabled) {
    const normalizedEnabled = enabled === true;
    saveExtensionSetting('compactChatLayout', normalizedEnabled);
    syncSettingsPanel();
    toastr[normalizedEnabled ? 'success' : 'info'](normalizedEnabled ? '已启用聊天紧凑模式。' : '已关闭聊天紧凑模式。', popupTitle);
}

function setThemeAccentColor(color) {
    const normalizedColor = normalizeThemeColor(color, defaultSettings.themeAccentColor);
    if (getExtensionSettings().themeAccentColor === normalizedColor) {
        syncThemeColorControls();
        return;
    }

    saveExtensionSetting('themeAccentColor', normalizedColor);
    syncThemeColorControls();
    applyThemeState();
}

function setThemeSecondaryColor(color) {
    const normalizedColor = normalizeThemeColor(color, defaultSettings.themeSecondaryColor);
    if (getExtensionSettings().themeSecondaryColor === normalizedColor) {
        syncThemeColorControls();
        return;
    }

    saveExtensionSetting('themeSecondaryColor', normalizedColor);
    syncThemeColorControls();
    applyThemeState();
}

function setThemeColorByRole(colorRole, color) {
    if (colorRole === 'secondary') {
        setThemeSecondaryColor(color);
        return;
    }

    setThemeAccentColor(color);
}

function normalizeThemeColorRole(colorRole) {
    return colorRole === 'secondary' ? 'secondary' : 'accent';
}

function getThemeColorDefaultByRole(colorRole) {
    return normalizeThemeColorRole(colorRole) === 'secondary'
        ? defaultSettings.themeSecondaryColor
        : defaultSettings.themeAccentColor;
}

function syncLiveThemeColorProperties(colorRole, color) {
    const settings = getExtensionSettings();
    if (settings.theme === 'default') {
        return;
    }

    const normalizedRole = normalizeThemeColorRole(colorRole);
    const normalizedColor = normalizeThemeColor(color, getThemeColorDefaultByRole(normalizedRole));
    const nextSettings = {
        ...settings,
        themeAccentColor: normalizedRole === 'accent' ? normalizedColor : settings.themeAccentColor,
        themeSecondaryColor: normalizedRole === 'secondary' ? normalizedColor : settings.themeSecondaryColor,
    };
    const html = document.documentElement;

    // 颜色面板可能被提升到扩展菜单顶层；选色后必须同步全局主题变量，避免外层预览变了但实际主题仍停在旧主色。
    if (normalizedRole === 'secondary') {
        html.style.setProperty('--sillydroid-theme-secondary', normalizedColor);
    } else {
        html.style.setProperty('--sillydroid-theme-primary', normalizedColor);
        html.style.setProperty('--sillydroid-theme-accent', normalizedColor);
    }

    const resolvedMode = resolveThemeMode(nextSettings.themeMode);
    const themeState = buildThemeRuntimeState(nextSettings, resolvedMode);
    persistStartupThemeState(themeState);
    syncNativeSystemBarsFromThemeState(themeState);
}

function createColorChannelControl(colorRole, channel, label, value) {
    const wrapper = document.createElement('label');
    wrapper.classList.add('sillydroid-host-color-channel-row');
    wrapper.dataset.sillydroidColorChannel = channel;

    const labelText = document.createElement('span');
    labelText.classList.add('sillydroid-host-color-channel-label');
    labelText.textContent = label;

    const input = document.createElement('input');
    input.classList.add('sillydroid-host-color-channel');
    input.type = 'range';
    input.min = '0';
    input.max = channel === 'h' ? '360' : '100';
    input.step = '1';
    input.value = String(value);
    input.dataset.sillydroidColorRole = colorRole;
    input.dataset.sillydroidColorChannel = channel;
    const channelName = channel === 'h' ? '色相' : channel === 's' ? '饱和度' : '明度';
    input.setAttribute('aria-label', `${channelName} 通道`);

    const valueText = document.createElement('span');
    valueText.classList.add('sillydroid-host-color-channel-value');
    valueText.textContent = formatHslChannelValue(channel, value);

    wrapper.append(labelText, input, valueText);
    return wrapper;
}

function createSection(title) {
    const section = document.createElement('fieldset');
    section.classList.add('sillydroid-host-section');

    const legend = document.createElement('legend');
    legend.classList.add('sillydroid-host-section-title');
    legend.textContent = title;
    section.appendChild(legend);

    return section;
}

function createGrid(columns = 3) {
    const grid = document.createElement('div');
    grid.classList.add('sillydroid-host-grid');
    if (columns !== 3) {
        grid.style.setProperty('--sillydroid-host-columns', String(columns));
    }
    return grid;
}

function createActionButton(id, text, iconClass) {
    const button = document.createElement('button');
    button.classList.add('menu_button', 'sillydroid-host-control', 'sillydroid-host-action-button');
    button.type = 'button';
    button.id = id;

    if (iconClass) {
        const icon = document.createElement('i');
        icon.className = iconClass;
        button.appendChild(icon);
    }

    const label = document.createElement('span');
    label.textContent = text;
    button.appendChild(label);
    return button;
}

function createSwitchControl(id, text, checked) {
    const row = document.createElement('label');
    row.classList.add('sillydroid-host-control', 'sillydroid-host-switch');
    row.htmlFor = id;

    const input = document.createElement('input');
    input.id = id;
    input.type = 'checkbox';
    input.checked = checked;

    const label = document.createElement('span');
    label.classList.add('sillydroid-host-switch-label');
    label.textContent = text;

    const track = document.createElement('span');
    track.classList.add('sillydroid-host-switch-track');
    track.setAttribute('aria-hidden', 'true');

    row.appendChild(label);
    row.appendChild(input);
    row.appendChild(track);
    return row;
}

function createSelectControl(id, text, choices, selectedValue) {
    const row = document.createElement('label');
    row.classList.add('sillydroid-host-control', 'sillydroid-host-select-control');
    row.htmlFor = id;

    const label = document.createElement('span');
    label.classList.add('sillydroid-host-select-label');
    label.textContent = text;

    const select = document.createElement('select');
    select.id = id;
    select.classList.add('text_pole', 'sillydroid-host-select');

    choices.forEach(choice => {
        const option = document.createElement('option');
        option.value = choice.value;
        option.textContent = choice.label;
        option.selected = choice.value === selectedValue;
        select.appendChild(option);
    });

    row.appendChild(label);
    row.appendChild(select);
    return row;
}

function createColorControl(id, text, value, presets, colorRole) {
    const row = document.createElement('div');
    row.classList.add('sillydroid-host-control', 'sillydroid-host-color-control');
    row.dataset.sillydroidColorRole = colorRole;
    row.style.setProperty('--sillydroid-host-color-value', value);

    const label = document.createElement('span');
    label.classList.add('sillydroid-host-select-label');
    label.textContent = text;

    const controls = document.createElement('div');
    controls.classList.add('sillydroid-host-color-controls');

    const trigger = document.createElement('button');
    trigger.type = 'button';
    trigger.classList.add('sillydroid-host-color-trigger');
    trigger.dataset.sillydroidColorRole = colorRole;
    trigger.setAttribute('aria-label', `打开${text}颜色面板`);
    trigger.setAttribute('aria-expanded', 'false');

    const preview = document.createElement('span');
    preview.classList.add('sillydroid-host-color-preview');
    preview.style.setProperty('--sillydroid-host-color-value', value);
    setImportantColorBackground(preview, value);
    preview.setAttribute('aria-hidden', 'true');

    const valueText = document.createElement('span');
    valueText.classList.add('sillydroid-host-color-value-text');
    valueText.textContent = value;

    trigger.append(preview, valueText);

    const customPreview = document.createElement('button');
    customPreview.id = id;
    customPreview.type = 'button';
    customPreview.classList.add('sillydroid-host-custom-color-preview');
    customPreview.dataset.sillydroidColorRole = colorRole;
    customPreview.style.setProperty('--sillydroid-host-color-value', value);
    setImportantColorBackground(customPreview, value);
    customPreview.setAttribute('aria-label', `编辑${text}自定义颜色`);
    customPreview.title = `${text} ${value}`;

    const codeInput = document.createElement('input');
    codeInput.id = `${id}_code`;
    codeInput.classList.add('text_pole', 'sillydroid-host-color-code');
    codeInput.type = 'text';
    codeInput.inputMode = 'text';
    codeInput.maxLength = 7;
    codeInput.value = value;
    codeInput.setAttribute('aria-label', `${text} HEX`);
    codeInput.setAttribute('spellcheck', 'false');

    const panel = document.createElement('div');
    panel.classList.add('sillydroid-host-color-panel');
    panel.hidden = true;
    panel.dataset.sillydroidColorRole = colorRole;
    bindThemeColorPanelIsolation(panel);

    const presetTitle = document.createElement('div');
    presetTitle.classList.add('sillydroid-host-color-panel-title');
    presetTitle.textContent = '推荐颜色';
    panel.appendChild(presetTitle);

    if (Array.isArray(presets) && presets.length > 0) {
        const swatches = document.createElement('div');
        swatches.classList.add('sillydroid-host-color-swatches');
        swatches.setAttribute('aria-label', `${text}推荐色`);

        presets.forEach(preset => {
            const button = document.createElement('button');
            button.type = 'button';
            button.classList.add('sillydroid-host-color-swatch');
            button.dataset.sillydroidColorRole = colorRole;
            button.dataset.sillydroidColor = preset.color;
            button.style.setProperty('--sillydroid-host-swatch-color', preset.color);
            // 推荐色是控件内容本身，直接写入内联背景，避免全局 button 主题规则覆盖成空心圆。
            setImportantColorBackground(button, preset.color);
            button.title = `${preset.label} ${preset.color}`;
            button.setAttribute('aria-label', `使用${preset.label}${preset.color}`);
            swatches.appendChild(button);
        });

        panel.appendChild(swatches);
    }

    const customTitle = document.createElement('div');
    customTitle.classList.add('sillydroid-host-color-panel-title');
    customTitle.textContent = '自定义';

    const customGroup = document.createElement('div');
    customGroup.classList.add('sillydroid-host-color-input-group');
    customGroup.append(customPreview, codeInput);

    const channels = document.createElement('div');
    channels.classList.add('sillydroid-host-color-channel-grid');
    channels.setAttribute('aria-label', `${text}自定义颜色`);
    const { h, s, l } = hexToHsl(value);
    channels.append(
        createColorChannelControl(colorRole, 'h', '色相', h),
        createColorChannelControl(colorRole, 's', '饱和度', s),
        createColorChannelControl(colorRole, 'l', '明度', l)
    );

    // 自定义颜色只走 Web 内 HEX + HSL 滑杆，不调用 Android / WebView 原生颜色选择器。
    panel.append(customTitle, customGroup, channels);
    controls.append(trigger, panel);
    row.appendChild(label);
    row.appendChild(controls);
    return row;
}

function createThemePerformanceNotice() {
    const notice = document.createElement('div');
    notice.id = 'sillydroid_android_host_theme_notice';
    notice.classList.add('sillydroid-host-theme-notice');
    // 毛玻璃会触发更多透明层、模糊和阴影合成；提示固定跟随设置项，避免用户误以为是宿主卡顿。
    const warning = document.createElement('span');
    warning.textContent = '提示：毛玻璃会增加 WebView / GPU 渲染负担，低端机、长聊天或多弹窗场景可能出现卡顿、闪烁。';

    const status = document.createElement('span');
    status.id = 'sillydroid_android_host_theme_performance_status';
    status.classList.add('sillydroid-host-theme-performance-status');

    notice.append(warning, status);
    return notice;
}

function buildSettingsPanel() {
    const settings = getExtensionSettings();
    const hostInfo = getHostVersionInfo();
    const hostManagedState = resolveHostManagedSwitchState(hostInfo);
    const hostCapabilities = resolveHostPanelCapabilities(hostInfo);
    const existingPanel = document.getElementById(settingsPanelId);
    if (existingPanel) {
        return existingPanel;
    }

    const wrapper = document.createElement('div');
    wrapper.classList.add('inline-drawer', 'sillydroid-host-panel');
    wrapper.id = settingsPanelId;

    const header = document.createElement('div');
    header.classList.add('inline-drawer-toggle', 'inline-drawer-header');

    const title = document.createElement('b');
    title.textContent = popupTitle;
    header.appendChild(title);

    const icon = document.createElement('div');
    icon.classList.add('inline-drawer-icon', 'fa-solid', 'fa-circle-chevron-down', 'down');
    header.appendChild(icon);

    const content = document.createElement('div');
    content.classList.add('inline-drawer-content', 'sillydroid-host-content');

    const hostSection = createSection('宿主设置');
    const versionSummary = document.createElement('div');
    versionSummary.id = 'sillydroid_android_host_version_summary';
    versionSummary.classList.add('sillydroid-host-version-line');
    versionSummary.textContent = formatVersionSummary(hostInfo);
    versionSummary.toggleAttribute('hidden', !hostCapabilities.showVersionSummary);

    const hostGrid = createGrid(3);
    const openSettingsButton = createActionButton('sillydroid_android_host_open_settings', '打开设置', 'fa-solid fa-sliders');
    openSettingsButton.toggleAttribute('hidden', !hostCapabilities.canOpenSettings);
    hostGrid.appendChild(openSettingsButton);

    const openCurrentPageInBrowserButton = createActionButton('sillydroid_android_host_open_current_page_in_browser', '在浏览器中打开', 'fa-solid fa-arrow-up-right-from-square');
    openCurrentPageInBrowserButton.toggleAttribute('hidden', !hostCapabilities.canOpenCurrentPageInBrowser);
    hostGrid.appendChild(openCurrentPageInBrowserButton);

    const reloadButton = createActionButton('sillydroid_android_host_reload_tavern', '刷新', 'fa-solid fa-rotate-right');
    reloadButton.toggleAttribute('hidden', !hostCapabilities.canReloadTavern);
    hostGrid.appendChild(reloadButton);

    const versionButton = createActionButton('sillydroid_android_host_version_info', '版本说明', 'fa-solid fa-circle-info');
    versionButton.toggleAttribute('hidden', !hostCapabilities.showVersionSummary);
    hostGrid.appendChild(versionButton);

    const floatingBubbleSwitch = createSwitchControl(
        'sillydroid_android_host_floating_bubble',
        '悬浮球',
        hostManagedState.floatingBubbleEnabled
    );
    floatingBubbleSwitch.toggleAttribute('hidden', !hostCapabilities.canToggleFloatingBubble);
    hostGrid.appendChild(floatingBubbleSwitch);

    const pullRefreshSwitch = createSwitchControl(
        'sillydroid_android_host_pull_refresh',
        '下拉刷新',
        hostManagedState.pullRefreshEnabled
    );
    pullRefreshSwitch.toggleAttribute('hidden', !hostCapabilities.canTogglePullRefresh);
    hostGrid.appendChild(pullRefreshSwitch);

    hostGrid.appendChild(createSwitchControl(
        'sillydroid_android_host_compact_chat',
        '聊天紧凑模式',
        settings.compactChatLayout === true
    ));
    hostSection.appendChild(versionSummary);
    hostSection.appendChild(hostGrid);

    const notificationSection = createSection('通知');
    const notificationGrid = createGrid(3);
    notificationGrid.appendChild(createSwitchControl(
        'sillydroid_android_host_notification',
        '消息通知',
        settings.enableNotification === true
    ));
    notificationGrid.appendChild(createSwitchControl(
        'sillydroid_android_host_sound_notification',
        '提示音',
        settings.enableSoundNotification === true
    ));
    notificationSection.appendChild(notificationGrid);
    notificationSection.toggleAttribute('hidden', !hostCapabilities.showNotificationSection);

    const themeSection = createSection('主题切换');
    const themeGrid = createGrid(3);
    themeGrid.appendChild(createSelectControl(
        'sillydroid_android_host_theme',
        '主题风格',
        themeChoices,
        settings.theme
    ));
    themeGrid.appendChild(createSelectControl(
        'sillydroid_android_host_theme_mode',
        '明暗模式',
        themeModeChoices,
        settings.themeMode
    ));
    themeGrid.appendChild(createSelectControl(
        'sillydroid_android_host_theme_performance',
        '性能模式',
        themePerformanceChoices,
        settings.themePerformanceMode
    ));
    themeGrid.appendChild(createColorControl(
        'sillydroid_android_host_theme_accent',
        '主题主色',
        settings.themeAccentColor,
        themeAccentColorPresets,
        'accent'
    ));
    themeGrid.appendChild(createColorControl(
        'sillydroid_android_host_theme_secondary',
        '主题辅色',
        settings.themeSecondaryColor,
        themeSecondaryColorPresets,
        'secondary'
    ));
    themeSection.appendChild(themeGrid);
    themeSection.appendChild(createThemePerformanceNotice());

    content.appendChild(hostSection);
    content.appendChild(notificationSection);
    content.appendChild(themeSection);

    wrapper.appendChild(header);
    wrapper.appendChild(content);
    return wrapper;
}

function syncSettingsPanel() {
    const settings = getExtensionSettings();
    const hostInfo = getHostVersionInfo();
    const hostManagedState = resolveHostManagedSwitchState(hostInfo);
    const hostCapabilities = resolveHostPanelCapabilities(hostInfo);
    const panel = document.getElementById(settingsPanelId);
    const openSettingsButton = document.getElementById('sillydroid_android_host_open_settings');
    const openCurrentPageInBrowserButton = document.getElementById('sillydroid_android_host_open_current_page_in_browser');
    const reloadButton = document.getElementById('sillydroid_android_host_reload_tavern');
    const versionButton = document.getElementById('sillydroid_android_host_version_info');
    const bubbleToggle = document.getElementById('sillydroid_android_host_floating_bubble');
    const pullRefreshToggle = document.getElementById('sillydroid_android_host_pull_refresh');
    const compactChatToggle = document.getElementById('sillydroid_android_host_compact_chat');
    const notificationToggle = document.getElementById('sillydroid_android_host_notification');
    const soundNotificationToggle = document.getElementById('sillydroid_android_host_sound_notification');
    const themeSelect = document.getElementById('sillydroid_android_host_theme');
    const themeModeSelect = document.getElementById('sillydroid_android_host_theme_mode');
    const themePerformanceSelect = document.getElementById('sillydroid_android_host_theme_performance');
    const themeAccentInput = document.getElementById('sillydroid_android_host_theme_accent');
    const themeAccentCodeInput = document.getElementById('sillydroid_android_host_theme_accent_code');
    const themeSecondaryInput = document.getElementById('sillydroid_android_host_theme_secondary');
    const themeSecondaryCodeInput = document.getElementById('sillydroid_android_host_theme_secondary_code');
    const themeNotice = document.getElementById('sillydroid_android_host_theme_notice');
    const themePerformanceStatus = document.getElementById('sillydroid_android_host_theme_performance_status');
    const versionSummary = document.getElementById('sillydroid_android_host_version_summary');

    if (panel instanceof HTMLElement) {
        panel.classList.add('sillydroid-host-panel');
        panel.dataset.sillydroidTheme = settings.theme;
        panel.dataset.sillydroidThemePerformance = settings.themePerformanceMode;
    }

    setElementHidden(openSettingsButton, !hostCapabilities.canOpenSettings);
    setElementHidden(openCurrentPageInBrowserButton, !hostCapabilities.canOpenCurrentPageInBrowser);
    setElementHidden(reloadButton, !hostCapabilities.canReloadTavern);
    setElementHidden(versionButton, !hostCapabilities.showVersionSummary);

    if (bubbleToggle instanceof HTMLInputElement) {
        setElementHidden(bubbleToggle.closest('.sillydroid-host-control'), !hostCapabilities.canToggleFloatingBubble);
        bubbleToggle.checked = hostManagedState.floatingBubbleEnabled;
    }

    if (pullRefreshToggle instanceof HTMLInputElement) {
        setElementHidden(pullRefreshToggle.closest('.sillydroid-host-control'), !hostCapabilities.canTogglePullRefresh);
        pullRefreshToggle.checked = hostManagedState.pullRefreshEnabled;
    }

    if (compactChatToggle instanceof HTMLInputElement) {
        compactChatToggle.checked = settings.compactChatLayout === true;
    }

    if (notificationToggle instanceof HTMLInputElement) {
        notificationToggle.checked = settings.enableNotification === true;
    }

    if (soundNotificationToggle instanceof HTMLInputElement) {
        soundNotificationToggle.checked = settings.enableSoundNotification === true;
    }

    setElementHidden(notificationToggle instanceof HTMLElement ? notificationToggle.closest('.sillydroid-host-section') : null, !hostCapabilities.showNotificationSection);

    if (themeSelect instanceof HTMLSelectElement) {
        themeSelect.value = settings.theme;
    }

    if (themeModeSelect instanceof HTMLSelectElement) {
        themeModeSelect.value = settings.themeMode;
        themeModeSelect.closest('.sillydroid-host-select-control')?.toggleAttribute('hidden', settings.theme === 'default');
    }

    if (themePerformanceSelect instanceof HTMLSelectElement) {
        themePerformanceSelect.value = settings.themePerformanceMode;
        themePerformanceSelect.closest('.sillydroid-host-select-control')?.toggleAttribute('hidden', settings.theme === 'default');
    }

    if (themeAccentInput instanceof HTMLElement) {
        syncThemeColorPicker(themeAccentInput, settings.themeAccentColor);
        themeAccentInput.closest('.sillydroid-host-color-control')?.toggleAttribute('hidden', settings.theme === 'default');
    }

    if (themeAccentCodeInput instanceof HTMLInputElement) {
        themeAccentCodeInput.value = settings.themeAccentColor;
        themeAccentCodeInput.toggleAttribute('aria-invalid', false);
    }

    if (themeSecondaryInput instanceof HTMLElement) {
        syncThemeColorPicker(themeSecondaryInput, settings.themeSecondaryColor);
        themeSecondaryInput.closest('.sillydroid-host-color-control')?.toggleAttribute('hidden', settings.theme === 'default');
    }

    if (themeSecondaryCodeInput instanceof HTMLInputElement) {
        themeSecondaryCodeInput.value = settings.themeSecondaryColor;
        themeSecondaryCodeInput.toggleAttribute('aria-invalid', false);
    }

    if (themeNotice instanceof HTMLElement) {
        themeNotice.toggleAttribute('hidden', settings.theme === 'default');
    }

    if (themePerformanceStatus instanceof HTMLElement) {
        const performanceProfile = resolveThemePerformanceProfile(settings, hostInfo);
        themePerformanceStatus.textContent = performanceProfile.reason;
    }

    if (versionSummary instanceof HTMLElement) {
        versionSummary.toggleAttribute('hidden', !hostCapabilities.showVersionSummary);
        versionSummary.textContent = formatVersionSummary(hostInfo);
    }

    applyThemeState();
}

function syncThemeColorControls() {
    const settings = getExtensionSettings();
    const themeAccentCodeInput = document.getElementById('sillydroid_android_host_theme_accent_code');
    const themeSecondaryCodeInput = document.getElementById('sillydroid_android_host_theme_secondary_code');

    syncThemeColorControlByRole('accent', settings.themeAccentColor);

    if (themeAccentCodeInput instanceof HTMLInputElement) {
        themeAccentCodeInput.value = settings.themeAccentColor;
        themeAccentCodeInput.toggleAttribute('aria-invalid', false);
    }

    syncThemeColorControlByRole('secondary', settings.themeSecondaryColor);

    if (themeSecondaryCodeInput instanceof HTMLInputElement) {
        themeSecondaryCodeInput.value = settings.themeSecondaryColor;
        themeSecondaryCodeInput.toggleAttribute('aria-invalid', false);
    }

    syncThemeColorSwatches();
}

function syncThemeColorControlByRole(colorRole, color) {
    const normalizedRole = normalizeThemeColorRole(colorRole);
    const normalizedColor = normalizeThemeColor(color, getThemeColorDefaultByRole(normalizedRole));
    syncLiveThemeColorProperties(normalizedRole, normalizedColor);

    const colorControls = document.querySelectorAll(`.sillydroid-host-color-control[data-sillydroid-color-role="${normalizedRole}"]`);
    const colorPanels = document.querySelectorAll(`.sillydroid-host-color-panel[data-sillydroid-color-role="${normalizedRole}"]`);

    colorControls.forEach(colorControl => {
        if (!(colorControl instanceof HTMLElement)) {
            return;
        }

        colorControl.style.setProperty('--sillydroid-host-color-value', normalizedColor);

        const colorText = colorControl.querySelector('.sillydroid-host-color-value-text');
        if (colorText instanceof HTMLElement) {
            colorText.textContent = normalizedColor;
        }

        const colorTrigger = colorControl.querySelector('.sillydroid-host-color-trigger');
        if (colorTrigger instanceof HTMLElement) {
            colorTrigger.style.setProperty('--sillydroid-host-color-value', normalizedColor);
        }

        const triggerPreview = colorControl.querySelector('.sillydroid-host-color-preview');
        if (triggerPreview instanceof HTMLElement) {
            // 外层小圆球是当前颜色摘要；色板提升到菜单顶层后，不能再依赖父子 DOM 关系同步。
            triggerPreview.style.setProperty('--sillydroid-host-color-value', normalizedColor);
            setImportantColorBackground(triggerPreview, normalizedColor);
        }
    });

    colorPanels.forEach(colorPanel => {
        if (!(colorPanel instanceof HTMLElement)) {
            return;
        }

        colorPanel.style.setProperty('--sillydroid-host-color-value', normalizedColor);

        const codeInput = colorPanel.querySelector('.sillydroid-host-color-code');
        if (codeInput instanceof HTMLInputElement) {
            codeInput.value = normalizedColor;
            codeInput.toggleAttribute('aria-invalid', false);
        }

        const customPreview = colorPanel.querySelector('.sillydroid-host-custom-color-preview');
        if (customPreview instanceof HTMLElement) {
            customPreview.style.setProperty('--sillydroid-host-color-value', normalizedColor);
            setImportantColorBackground(customPreview, normalizedColor);
            customPreview.title = normalizedColor;
        }
    });

    syncThemeColorChannelControls(normalizedRole, normalizedColor);
}

function syncThemeColorPicker(picker, color) {
    if (!(picker instanceof HTMLElement)) {
        return;
    }

    const colorRole = normalizeThemeColorRole(picker.dataset.sillydroidColorRole);
    const normalizedColor = normalizeThemeColor(color, getThemeColorDefaultByRole(colorRole));
    picker.style.setProperty('--sillydroid-host-color-value', normalizedColor);
    setImportantColorBackground(picker, normalizedColor);
    picker.title = normalizedColor;
    syncThemeColorControlByRole(colorRole, normalizedColor);

    if (picker instanceof HTMLInputElement && picker.value.toLowerCase() !== normalizedColor) {
        picker.value = normalizedColor;
    }
}

function syncThemeColorSwatches() {
    const settings = getExtensionSettings();
    document.querySelectorAll('.sillydroid-host-color-swatch').forEach(button => {
        if (!(button instanceof HTMLButtonElement)) {
            return;
        }

        const colorRole = button.dataset.sillydroidColorRole;
        const currentColor = colorRole === 'secondary' ? settings.themeSecondaryColor : settings.themeAccentColor;
        const active = normalizeThemeColor(button.dataset.sillydroidColor, currentColor) === currentColor;
        button.classList.toggle('sillydroid-host-color-swatch-active', active);
        button.setAttribute('aria-pressed', String(active));
    });
}

function syncThemeColorChannelControls(colorRole, color) {
    const panel = getThemeColorPanel(colorRole) || getThemeColorControl(colorRole)?.querySelector('.sillydroid-host-color-panel');
    if (!(panel instanceof HTMLElement)) {
        return;
    }

    const hsl = hexToHsl(color);
    panel.querySelectorAll('.sillydroid-host-color-channel').forEach(input => {
        if (!(input instanceof HTMLInputElement)) {
            return;
        }

        const channel = input.dataset.sillydroidColorChannel;
        const value = channel === 's' ? hsl.s : channel === 'l' ? hsl.l : hsl.h;
        if (input.value !== String(value)) {
            input.value = String(value);
        }

        const valueText = input.closest('.sillydroid-host-color-channel-row')?.querySelector('.sillydroid-host-color-channel-value');
        if (valueText instanceof HTMLElement) {
            valueText.textContent = formatHslChannelValue(channel, value);
        }
    });
}

function readThemeColorFromChannels(colorRole) {
    const panel = getThemeColorPanel(colorRole) || getThemeColorControl(colorRole)?.querySelector('.sillydroid-host-color-panel');
    if (!(panel instanceof HTMLElement)) {
        return null;
    }

    const currentColor = colorRole === 'secondary'
        ? getExtensionSettings().themeSecondaryColor
        : getExtensionSettings().themeAccentColor;
    const values = hexToHsl(currentColor);
    panel.querySelectorAll('.sillydroid-host-color-channel').forEach(input => {
        if (!(input instanceof HTMLInputElement)) {
            return;
        }

        const channel = input.dataset.sillydroidColorChannel;
        if (channel === 'h' || channel === 's' || channel === 'l') {
            values[channel] = Number.parseInt(input.value, 10) || 0;
        }
    });

    return hslToHex(values);
}

function closeThemeColorPanels(exceptPanel = null) {
    document.querySelectorAll('.sillydroid-host-color-panel').forEach(panel => {
        if (!(panel instanceof HTMLElement) || panel === exceptPanel) {
            return;
        }

        panel.hidden = true;
        panel.style.removeProperty('--sillydroid-host-color-panel-top');
        panel.style.removeProperty('--sillydroid-host-color-panel-left');
        const originalParentId = panel.dataset.sillydroidOriginalParentId;
        const originalParent = originalParentId ? document.getElementById(originalParentId) : null;
        if (originalParent instanceof HTMLElement && panel.parentElement !== originalParent) {
            // 色板打开时会提升到 body 顶层，关闭必须归还原位，避免宿主设置面板重建时丢事件和样式状态。
            originalParent.appendChild(panel);
        }

        const control = getThemeColorControl(panel.dataset.sillydroidColorRole);
        const trigger = control?.querySelector('.sillydroid-host-color-trigger');
        if (trigger instanceof HTMLButtonElement) {
            trigger.setAttribute('aria-expanded', 'false');
        }
    });
}

function getThemeColorControl(colorRole) {
    return document.querySelector(`.sillydroid-host-color-control[data-sillydroid-color-role="${colorRole}"]`);
}

function getThemeColorPanel(colorRole) {
    return document.querySelector(`.sillydroid-host-color-panel[data-sillydroid-color-role="${colorRole}"]`);
}

function promoteThemeColorPanel(panel) {
    if (!(panel.parentElement instanceof HTMLElement)) {
        return;
    }

    const originalParent = panel.parentElement;
    if (!originalParent.id) {
        originalParent.id = `sillydroid_host_color_panel_parent_${panel.dataset.sillydroidColorRole || 'unknown'}`;
    }

    panel.dataset.sillydroidOriginalParentId = originalParent.id;
    const control = getThemeColorControl(panel.dataset.sillydroidColorRole);
    const layer = control?.closest('#extensionsMenu, #extensions_settings, #extensions_settings2') || document.body;
    if (layer instanceof HTMLElement && !layer.id) {
        layer.id = `sillydroid_host_color_panel_layer_${panel.dataset.sillydroidColorRole || 'unknown'}`;
    }

    if (layer instanceof HTMLElement && panel.parentElement !== layer) {
        // 浮层放到扩展菜单容器顶层而不是 body，既保留 viewport 定位，也不会被酒馆判定为点到扩展管理外部。
        layer.appendChild(panel);
    }
}

function positionThemeColorPanel(panel, trigger) {
    const gap = 6;
    const viewportWidth = document.documentElement.clientWidth || window.innerWidth;
    const viewportHeight = document.documentElement.clientHeight || window.innerHeight;
    const triggerRect = trigger.getBoundingClientRect();
    const panelRect = panel.getBoundingClientRect();
    const panelWidth = Math.min(panelRect.width || 260, Math.max(160, viewportWidth - gap * 2));
    const panelHeight = Math.min(panelRect.height || 180, Math.max(120, viewportHeight - gap * 2));
    const preferredTop = triggerRect.bottom + gap;
    const fallbackTop = triggerRect.top - panelHeight - gap;
    const top = preferredTop + panelHeight <= viewportHeight - gap ? preferredTop : Math.max(gap, fallbackTop);
    const left = Math.min(Math.max(gap, triggerRect.right - panelWidth), viewportWidth - panelWidth - gap);

    // 颜色面板是 viewport 浮层，不参与扩展页 flow；避免展开后被后续扩展分组挤到下面。
    panel.style.setProperty('--sillydroid-host-color-panel-top', `${Math.round(top)}px`);
    panel.style.setProperty('--sillydroid-host-color-panel-left', `${Math.round(left)}px`);
}

function toggleThemeColorPanel(colorRole) {
    const control = getThemeColorControl(colorRole);
    const panel = control?.querySelector('.sillydroid-host-color-panel') || getThemeColorPanel(colorRole);
    const trigger = control?.querySelector('.sillydroid-host-color-trigger');
    if (!(panel instanceof HTMLElement) || !(trigger instanceof HTMLButtonElement)) {
        return;
    }

    const shouldOpen = panel.hidden;
    closeThemeColorPanels(shouldOpen ? panel : null);
    panel.hidden = !shouldOpen;
    if (shouldOpen) {
        promoteThemeColorPanel(panel);
        bindThemeColorPanelIsolation(panel);
        positionThemeColorPanel(panel, trigger);
        requestAnimationFrame(() => {
            positionThemeColorPanel(panel, trigger);
        });
    }
    trigger.setAttribute('aria-expanded', String(shouldOpen));
}

function getAndroidMultipleSelect2Placeholder(select) {
    if (select.id === 'world_info') {
        return '未启用世界书，点这里选择。';
    }

    const labelText = select.closest('label, .range-block-range, .world_entry_form_control, .completion_prompt_manager_popup_entry_form_control')
        ?.textContent
        ?.trim()
        ?.replace(/\s+/g, ' ');

    if (select.name === 'characterFilter' || /绑定到角色或标签/.test(labelText || '')) {
        return '点这里选择角色或标签。';
    }

    if (select.name === 'triggers' || /触发器/.test(labelText || '')) {
        return '点这里选择触发器。';
    }

    return select.getAttribute('placeholder') || select.getAttribute('data-placeholder') || '点这里选择。';
}

function scheduleAndroidMultipleSelect2Restore(delay = 0) {
    if (!isAndroidTouchEnvironment()) {
        return;
    }

    window.setTimeout(ensureAndroidMultipleSelect2, delay);
}

function ensureAndroidMultipleSelect2() {
    if (!isAndroidTouchEnvironment()) {
        return;
    }

    const $ = globalThis.jQuery || globalThis.$;
    if (!$?.fn?.select2) {
        return;
    }

    document.querySelectorAll('select[multiple]').forEach(select => {
        if (!(select instanceof HTMLSelectElement)) {
            return;
        }

        const $select = $(select);
        if ($select.data('select2')) {
            return;
        }

        // 上游移动端会跳过部分 multiple select 的 Select2 初始化；Android WebView 原生多选会变成黑白列表。
        // 只恢复已经存在的数据控件，不改 option/value 链路，让世界书、绑定角色/标签等同类多选保持同一操作模式。
        $select.select2({
            width: '100%',
            placeholder: getAndroidMultipleSelect2Placeholder(select),
            allowClear: true,
            closeOnSelect: false,
        });
        select.dataset.sillydroidMultipleSelect2 = 'true';
    });
}

function observeAndroidMultipleSelect2() {
    if (!isAndroidTouchEnvironment() || document.documentElement.dataset[worldInfoSelect2ObserverId]) {
        return;
    }

    if (!document.body) {
        window.setTimeout(observeAndroidMultipleSelect2, 100);
        return;
    }

    document.documentElement.dataset[worldInfoSelect2ObserverId] = 'true';
    const observer = new MutationObserver(() => {
        scheduleAndroidMultipleSelect2Restore();
    });

    // 多选控件会随抽屉/弹窗重建；一个观察器集中恢复 Android 上被上游移动端跳过的 Select2。
    observer.observe(document.body, {
        childList: true,
        subtree: true,
    });

    document.addEventListener('pointerup', () => {
        scheduleAndroidMultipleSelect2Restore(80);
        scheduleAndroidMultipleSelect2Restore(260);
    }, true);

    document.addEventListener('focusin', event => {
        if (event.target instanceof HTMLSelectElement || event.target instanceof HTMLInputElement) {
            scheduleAndroidMultipleSelect2Restore(80);
        }
    }, true);

    ensureAndroidMultipleSelect2();
}

function bindSettingsPanelEvents() {
    const bubbleToggle = document.getElementById('sillydroid_android_host_floating_bubble');
    const pullRefreshToggle = document.getElementById('sillydroid_android_host_pull_refresh');
    const compactChatToggle = document.getElementById('sillydroid_android_host_compact_chat');
    const notificationToggle = document.getElementById('sillydroid_android_host_notification');
    const soundNotificationToggle = document.getElementById('sillydroid_android_host_sound_notification');
    const themeSelect = document.getElementById('sillydroid_android_host_theme');
    const themeModeSelect = document.getElementById('sillydroid_android_host_theme_mode');
    const themePerformanceSelect = document.getElementById('sillydroid_android_host_theme_performance');
    const themeAccentInput = document.getElementById('sillydroid_android_host_theme_accent');
    const themeAccentCodeInput = document.getElementById('sillydroid_android_host_theme_accent_code');
    const themeSecondaryInput = document.getElementById('sillydroid_android_host_theme_secondary');
    const themeSecondaryCodeInput = document.getElementById('sillydroid_android_host_theme_secondary_code');
    const colorTriggerButtons = document.querySelectorAll('.sillydroid-host-color-trigger');
    const colorSwatchButtons = document.querySelectorAll('.sillydroid-host-color-swatch');
    const colorChannelInputs = document.querySelectorAll('.sillydroid-host-color-channel');
    const openSettingsButton = document.getElementById('sillydroid_android_host_open_settings');
    const openCurrentPageInBrowserButton = document.getElementById('sillydroid_android_host_open_current_page_in_browser');
    const reloadButton = document.getElementById('sillydroid_android_host_reload_tavern');
    const versionButton = document.getElementById('sillydroid_android_host_version_info');

    if (bubbleToggle instanceof HTMLInputElement && !bubbleToggle.dataset.sillydroidBound) {
        bubbleToggle.dataset.sillydroidBound = 'true';
        bubbleToggle.addEventListener('change', async () => {
            const result = await setFloatingBubbleEnabled(bubbleToggle.checked);
            if (!result.updated) {
                bubbleToggle.checked = resolveHostManagedSwitchState(getHostVersionInfo()).floatingBubbleEnabled;
            }
        });
    }

    if (pullRefreshToggle instanceof HTMLInputElement && !pullRefreshToggle.dataset.sillydroidBound) {
        pullRefreshToggle.dataset.sillydroidBound = 'true';
        pullRefreshToggle.addEventListener('change', async () => {
            const result = await setWebViewPullRefreshEnabled(pullRefreshToggle.checked);
            if (!result.updated) {
                pullRefreshToggle.checked = resolveHostManagedSwitchState(getHostVersionInfo()).pullRefreshEnabled;
            }
        });
    }

    if (notificationToggle instanceof HTMLInputElement && !notificationToggle.dataset.sillydroidBound) {
        notificationToggle.dataset.sillydroidBound = 'true';
        notificationToggle.addEventListener('change', async () => {
            const result = await setNotificationPushEnabled(notificationToggle.checked);
            if (!result.updated) {
                notificationToggle.checked = getExtensionSettings().enableNotification === true;
            }
        });
    }

    if (soundNotificationToggle instanceof HTMLInputElement && !soundNotificationToggle.dataset.sillydroidBound) {
        soundNotificationToggle.dataset.sillydroidBound = 'true';
        soundNotificationToggle.addEventListener('change', async () => {
            const result = await setSoundNotificationEnabled(soundNotificationToggle.checked);
            if (!result.updated) {
                soundNotificationToggle.checked = getExtensionSettings().enableSoundNotification === true;
            }
        });
    }

    if (compactChatToggle instanceof HTMLInputElement && !compactChatToggle.dataset.sillydroidBound) {
        compactChatToggle.dataset.sillydroidBound = 'true';
        compactChatToggle.addEventListener('change', () => {
            setCompactChatLayoutEnabled(compactChatToggle.checked);
        });
    }

    if (themeSelect instanceof HTMLSelectElement && !themeSelect.dataset.sillydroidBound) {
        themeSelect.dataset.sillydroidBound = 'true';
        themeSelect.addEventListener('change', () => {
            setTheme(themeSelect.value);
        });
    }

    document.querySelectorAll('.sillydroid-host-color-panel').forEach(bindThemeColorPanelIsolation);

    if (themeModeSelect instanceof HTMLSelectElement && !themeModeSelect.dataset.sillydroidBound) {
        themeModeSelect.dataset.sillydroidBound = 'true';
        themeModeSelect.addEventListener('change', () => {
            setThemeMode(themeModeSelect.value);
        });
    }

    if (themePerformanceSelect instanceof HTMLSelectElement && !themePerformanceSelect.dataset.sillydroidBound) {
        themePerformanceSelect.dataset.sillydroidBound = 'true';
        themePerformanceSelect.addEventListener('change', () => {
            setThemePerformanceMode(themePerformanceSelect.value);
        });
    }

    if (themeAccentInput instanceof HTMLButtonElement && !themeAccentInput.dataset.sillydroidBound) {
        themeAccentInput.dataset.sillydroidBound = 'true';
        themeAccentInput.addEventListener('click', () => {
            themeAccentInput.closest('.sillydroid-host-color-panel')?.querySelector('.sillydroid-host-color-channel')?.focus();
        });
    }

    if (themeAccentCodeInput instanceof HTMLInputElement && !themeAccentCodeInput.dataset.sillydroidBound) {
        themeAccentCodeInput.dataset.sillydroidBound = 'true';
        themeAccentCodeInput.addEventListener('input', () => {
            const color = themeAccentCodeInput.value.trim();
            if (!themeColorPattern.test(color)) {
                themeAccentCodeInput.toggleAttribute('aria-invalid', true);
                return;
            }

            setThemeAccentColor(color);
        });
        themeAccentCodeInput.addEventListener('change', () => {
            const color = themeAccentCodeInput.value.trim();
            if (!themeColorPattern.test(color)) {
                syncThemeColorControls();
                toastr.warning('颜色请输入 #RRGGBB 格式。', popupTitle);
            }
        });
    }

    if (themeSecondaryInput instanceof HTMLButtonElement && !themeSecondaryInput.dataset.sillydroidBound) {
        themeSecondaryInput.dataset.sillydroidBound = 'true';
        themeSecondaryInput.addEventListener('click', () => {
            themeSecondaryInput.closest('.sillydroid-host-color-panel')?.querySelector('.sillydroid-host-color-channel')?.focus();
        });
    }

    if (themeSecondaryCodeInput instanceof HTMLInputElement && !themeSecondaryCodeInput.dataset.sillydroidBound) {
        themeSecondaryCodeInput.dataset.sillydroidBound = 'true';
        themeSecondaryCodeInput.addEventListener('input', () => {
            const color = themeSecondaryCodeInput.value.trim();
            if (!themeColorPattern.test(color)) {
                themeSecondaryCodeInput.toggleAttribute('aria-invalid', true);
                return;
            }

            setThemeSecondaryColor(color);
        });
        themeSecondaryCodeInput.addEventListener('change', () => {
            const color = themeSecondaryCodeInput.value.trim();
            if (!themeColorPattern.test(color)) {
                syncThemeColorControls();
                toastr.warning('颜色请输入 #RRGGBB 格式。', popupTitle);
            }
        });
    }

    colorTriggerButtons.forEach(button => {
        if (!(button instanceof HTMLButtonElement) || button.dataset.sillydroidBound) {
            return;
        }

        button.dataset.sillydroidBound = 'true';
        button.addEventListener('click', event => {
            event.stopPropagation();
            toggleThemeColorPanel(button.dataset.sillydroidColorRole);
        });
    });

    colorSwatchButtons.forEach(button => {
        if (!(button instanceof HTMLButtonElement) || button.dataset.sillydroidBound) {
            return;
        }

        button.dataset.sillydroidBound = 'true';
        button.addEventListener('click', () => {
            const color = button.dataset.sillydroidColor;
            if (button.dataset.sillydroidColorRole === 'secondary') {
                setThemeSecondaryColor(color);
                closeThemeColorPanels();
                return;
            }

            setThemeAccentColor(color);
            closeThemeColorPanels();
        });
    });

    colorChannelInputs.forEach(input => {
        if (!(input instanceof HTMLInputElement) || input.dataset.sillydroidBound) {
            return;
        }

        input.dataset.sillydroidBound = 'true';
        input.addEventListener('input', () => {
            const colorRole = input.dataset.sillydroidColorRole;
            const color = readThemeColorFromChannels(colorRole);
            if (color) {
                setThemeColorByRole(colorRole, color);
            }
        });
    });

    if (!document.documentElement.dataset.sillydroidThemeColorPanelBound) {
        document.documentElement.dataset.sillydroidThemeColorPanelBound = 'true';
        document.addEventListener('pointerdown', event => {
            if (event.target instanceof Element && event.target.closest('.sillydroid-host-color-control, .sillydroid-host-color-panel')) {
                return;
            }

            closeThemeColorPanels();
        });
        document.addEventListener('keydown', event => {
            if (event.key === 'Escape') {
                closeThemeColorPanels();
            }
        });
    }

    if (openSettingsButton instanceof HTMLButtonElement && !openSettingsButton.dataset.sillydroidBound) {
        openSettingsButton.dataset.sillydroidBound = 'true';
        openSettingsButton.addEventListener('click', () => {
            void openSettingsCommand();
        });
    }

    if (openCurrentPageInBrowserButton instanceof HTMLButtonElement && !openCurrentPageInBrowserButton.dataset.sillydroidBound) {
        openCurrentPageInBrowserButton.dataset.sillydroidBound = 'true';
        // “在浏览器中打开”必须走宿主桥复用当前 WebView URL，避免 Web 侧自己拼首页地址后把当前路由丢掉。
        openCurrentPageInBrowserButton.addEventListener('click', () => {
            void openCurrentPageInBrowserCommand();
        });
    }

    if (reloadButton instanceof HTMLButtonElement && !reloadButton.dataset.sillydroidBound) {
        reloadButton.dataset.sillydroidBound = 'true';
        reloadButton.addEventListener('click', () => {
            void reloadTavernCommand();
        });
    }

    if (versionButton instanceof HTMLButtonElement && !versionButton.dataset.sillydroidBound) {
        versionButton.dataset.sillydroidBound = 'true';
        versionButton.addEventListener('click', openVersionPopup);
    }
}

async function ensureSettingsPanel() {
    const root = document.getElementById('extensions_settings') || document.getElementById('extensions_settings2');
    if (!(root instanceof HTMLElement)) {
        window.setTimeout(ensureSettingsPanel, 500);
        return;
    }

    const existingPanel = document.getElementById(settingsPanelId);
    if (existingPanel) {
        // 如果 panel 已存在但不在正确容器内，移回来，避免酒馆扩展页重渲染后丢失宿主入口。
        if (!root.contains(existingPanel)) {
            root.appendChild(existingPanel);
        }
        syncSettingsPanel();
        bindSettingsPanelEvents();
        return;
    }

    try {
        const panel = buildSettingsPanel();
        root.appendChild(panel);
        syncSettingsPanel();
        bindSettingsPanelEvents();
    } catch (error) {
        console.error('安卓宿主：加载设置面板失败', error);
    }
}

async function init() {
    getExtensionSettings();
    syncMessageAlertListener();
    applyThemeState();
    observeAndroidMultipleSelect2();
    await ensureSettingsPanel();
}

export async function activate() {
    await init();
}

jQuery(() => {
    void init();
});
