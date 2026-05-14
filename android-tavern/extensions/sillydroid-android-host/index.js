import { Popup, POPUP_TYPE } from '../../../popup.js';
import { extension_settings } from '../../../extensions.js';
import { eventSource, event_types, saveSettingsDebounced } from '../../../../script.js';

const extensionName = 'sillydroid-android-host';
const extensionFolderPath = `scripts/extensions/third-party/${extensionName}`;
const settingsPanelId = 'sillydroid_android_host_settings_panel';
const popupTitle = '安卓宿主';

const defaultSettings = {
    enableFloatingBubble: false,
    enableNotification: false,
};

let notificationPushHandler = null;

function getExtensionSettings() {
    extension_settings[extensionName] = extension_settings[extensionName] || {};
    const settings = extension_settings[extensionName];

    for (const [key, value] of Object.entries(defaultSettings)) {
        if (settings[key] === undefined) {
            settings[key] = value;
        }
    }

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
    return `宿主 ${hostVersion} | APK ${apkVersionName} (${apkVersionCode})`;
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

function handleMessageReceivedForPush() {
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

function attachNotificationPushListener() {
    if (notificationPushHandler) {
        return;
    }

    const binding = getMessageEventBinding();
    if (!binding) {
        return;
    }

    notificationPushHandler = handleMessageReceivedForPush;
    binding.source.on(binding.messageReceived, notificationPushHandler);
}

function detachNotificationPushListener() {
    if (!notificationPushHandler) {
        return;
    }

    const binding = getMessageEventBinding();
    if (binding) {
        binding.source.removeListener(binding.messageReceived, notificationPushHandler);
    }

    notificationPushHandler = null;
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

    if (enabled) {
        attachNotificationPushListener();
        toastr.success('已开启消息推送通知。', popupTitle);
    } else {
        detachNotificationPushListener();
        toastr.info('已关闭消息推送通知。', popupTitle);
    }

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

    saveExtensionSetting('enableFloatingBubble', enabled);
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

function buildSettingsPanel() {
    const settings = getExtensionSettings();
    const hostInfo = getHostVersionInfo();
    const existingPanel = document.getElementById(settingsPanelId);
    if (existingPanel) {
        return existingPanel;
    }

    const wrapper = document.createElement('div');
    wrapper.classList.add('inline-drawer');
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
    content.classList.add('inline-drawer-content');

    const intro = document.createElement('div');
    intro.style.display = 'flex';
    intro.style.flexDirection = 'column';
    intro.style.gap = '4px';
    intro.style.marginBottom = '12px';

    const introTitle = document.createElement('strong');
    introTitle.textContent = '安卓宿主扩展设置';
    intro.appendChild(introTitle);

    const introText = document.createElement('span');
    introText.textContent = '这里提供悬浮球、下拉刷新、消息通知、打开设置和版本说明。';
    introText.style.opacity = '0.82';
    intro.appendChild(introText);

    const versionSummary = document.createElement('div');
    versionSummary.id = 'sillydroid_android_host_version_summary';
    versionSummary.style.marginBottom = '12px';
    versionSummary.style.opacity = '0.92';
    versionSummary.textContent = formatVersionSummary(hostInfo);

    const bubbleRow = document.createElement('label');
    bubbleRow.classList.add('checkbox_label');
    bubbleRow.style.display = 'flex';
    bubbleRow.style.alignItems = 'center';
    bubbleRow.style.gap = '8px';
    bubbleRow.style.marginBottom = '8px';

    const bubbleToggle = document.createElement('input');
    bubbleToggle.id = 'sillydroid_android_host_floating_bubble';
    bubbleToggle.type = 'checkbox';
    bubbleToggle.checked = settings.enableFloatingBubble === true;

    const bubbleText = document.createElement('span');
    bubbleText.textContent = '启用悬浮球';
    bubbleRow.appendChild(bubbleToggle);
    bubbleRow.appendChild(bubbleText);

    const pullRefreshRow = document.createElement('label');
    pullRefreshRow.classList.add('checkbox_label');
    pullRefreshRow.style.display = 'flex';
    pullRefreshRow.style.alignItems = 'center';
    pullRefreshRow.style.gap = '8px';
    pullRefreshRow.style.marginBottom = '8px';

    const pullRefreshToggle = document.createElement('input');
    pullRefreshToggle.id = 'sillydroid_android_host_pull_refresh';
    pullRefreshToggle.type = 'checkbox';
    pullRefreshToggle.checked = hostInfo?.webViewPullRefreshEnabled === true;

    const pullRefreshText = document.createElement('span');
    pullRefreshText.textContent = '启用下拉刷新（会和悬浮小组件的拖动功能冲突，谨慎开启）';
    pullRefreshRow.appendChild(pullRefreshToggle);
    pullRefreshRow.appendChild(pullRefreshText);

    const notificationRow = document.createElement('label');
    notificationRow.classList.add('checkbox_label');
    notificationRow.style.display = 'flex';
    notificationRow.style.alignItems = 'center';
    notificationRow.style.gap = '8px';
    notificationRow.style.marginBottom = '12px';

    const notificationToggle = document.createElement('input');
    notificationToggle.id = 'sillydroid_android_host_notification';
    notificationToggle.type = 'checkbox';
    notificationToggle.checked = settings.enableNotification === true;

    const notificationText = document.createElement('span');
    notificationText.textContent = '启用消息通知';
    notificationRow.appendChild(notificationToggle);
    notificationRow.appendChild(notificationText);

    const actionRow = document.createElement('div');
    actionRow.style.display = 'flex';
    actionRow.style.flexWrap = 'nowrap';
    actionRow.style.alignItems = 'center';
    actionRow.style.gap = '8px';

    const openSettingsButton = document.createElement('button');
    openSettingsButton.classList.add('menu_button');
    openSettingsButton.type = 'button';
    openSettingsButton.id = 'sillydroid_android_host_open_settings';
    openSettingsButton.textContent = '打开设置';
    openSettingsButton.style.whiteSpace = 'nowrap';
    openSettingsButton.style.minWidth = '96px';
    openSettingsButton.style.display = 'inline-flex';
    openSettingsButton.style.justifyContent = 'center';

    const reloadButton = document.createElement('button');
    reloadButton.classList.add('menu_button');
    reloadButton.type = 'button';
    reloadButton.id = 'sillydroid_android_host_reload_tavern';
    reloadButton.textContent = '刷新页面';
    reloadButton.style.whiteSpace = 'nowrap';
    reloadButton.style.minWidth = '96px';
    reloadButton.style.display = 'inline-flex';
    reloadButton.style.justifyContent = 'center';

    const versionButton = document.createElement('button');
    versionButton.classList.add('menu_button');
    versionButton.type = 'button';
    versionButton.id = 'sillydroid_android_host_version_info';
    versionButton.textContent = '版本说明';
    versionButton.style.whiteSpace = 'nowrap';
    versionButton.style.minWidth = '96px';
    versionButton.style.display = 'inline-flex';
    versionButton.style.justifyContent = 'center';

    actionRow.appendChild(openSettingsButton);
    actionRow.appendChild(reloadButton);
    actionRow.appendChild(versionButton);

    content.appendChild(intro);
    content.appendChild(versionSummary);
    content.appendChild(bubbleRow);
    content.appendChild(pullRefreshRow);
    content.appendChild(notificationRow);
    content.appendChild(actionRow);

    wrapper.appendChild(header);
    wrapper.appendChild(content);
    return wrapper;
}

function syncSettingsPanel() {
    const settings = getExtensionSettings();
    const hostInfo = getHostVersionInfo();
    const bubbleToggle = document.getElementById('sillydroid_android_host_floating_bubble');
    const pullRefreshToggle = document.getElementById('sillydroid_android_host_pull_refresh');
    const notificationToggle = document.getElementById('sillydroid_android_host_notification');
    const versionSummary = document.getElementById('sillydroid_android_host_version_summary');

    if (bubbleToggle instanceof HTMLInputElement) {
        bubbleToggle.checked = settings.enableFloatingBubble === true;
    }

    if (pullRefreshToggle instanceof HTMLInputElement) {
        pullRefreshToggle.checked = hostInfo?.webViewPullRefreshEnabled === true;
    }

    if (notificationToggle instanceof HTMLInputElement) {
        notificationToggle.checked = settings.enableNotification === true;
    }

    if (versionSummary instanceof HTMLElement) {
        versionSummary.textContent = formatVersionSummary(hostInfo);
    }
}

function bindSettingsPanelEvents() {
    const bubbleToggle = document.getElementById('sillydroid_android_host_floating_bubble');
    const pullRefreshToggle = document.getElementById('sillydroid_android_host_pull_refresh');
    const notificationToggle = document.getElementById('sillydroid_android_host_notification');
    const openSettingsButton = document.getElementById('sillydroid_android_host_open_settings');
    const reloadButton = document.getElementById('sillydroid_android_host_reload_tavern');
    const versionButton = document.getElementById('sillydroid_android_host_version_info');

    if (bubbleToggle instanceof HTMLInputElement && !bubbleToggle.dataset.sillydroidBound) {
        bubbleToggle.dataset.sillydroidBound = 'true';
        bubbleToggle.addEventListener('change', async () => {
            const result = await setFloatingBubbleEnabled(bubbleToggle.checked);
            if (!result.updated) {
                bubbleToggle.checked = getExtensionSettings().enableFloatingBubble === true;
            }
        });
    }

    if (pullRefreshToggle instanceof HTMLInputElement && !pullRefreshToggle.dataset.sillydroidBound) {
        pullRefreshToggle.dataset.sillydroidBound = 'true';
        pullRefreshToggle.addEventListener('change', async () => {
            const result = await setWebViewPullRefreshEnabled(pullRefreshToggle.checked);
            if (!result.updated) {
                pullRefreshToggle.checked = getHostVersionInfo()?.webViewPullRefreshEnabled === true;
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

    if (openSettingsButton instanceof HTMLButtonElement && !openSettingsButton.dataset.sillydroidBound) {
        openSettingsButton.dataset.sillydroidBound = 'true';
        openSettingsButton.addEventListener('click', () => {
            void openSettingsCommand();
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
        // 如果 panel 已存在但不在正确容器内，移回来
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
    const settings = getExtensionSettings();
    if (settings.enableNotification && !notificationPushHandler) {
        attachNotificationPushListener();
    }

    await ensureSettingsPanel();
}

export async function activate() {
    await init();
}

jQuery(() => {
    void init();
});
