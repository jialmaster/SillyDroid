import { POPUP_RESULT, POPUP_TYPE, Popup } from '../../../popup.js';
import { SlashCommand } from '../../../slash-commands/SlashCommand.js';
import { SlashCommandParser } from '../../../slash-commands/SlashCommandParser.js';

const bridgeName = 'SillyDroidAndroidHostBridge';
const menuButtonId = 'sillydroid_android_host_menu_button';
const popupTitle = '安卓宿主';

let slashCommandsRegistered = false;
let bootstrapScheduled = false;

function getBridge() {
    const bridge = globalThis[bridgeName];
    if (!bridge || typeof bridge.getHostVersionInfo !== 'function') {
        return null;
    }

    return bridge;
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

function showBridgeUnavailableToast() {
    toastr.warning('当前页面无法连接安卓宿主桥。', popupTitle);
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

async function openSettingsCommand() {
    const bridge = getBridge();
    if (!bridge || typeof bridge.openSettings !== 'function') {
        showBridgeUnavailableToast();
        return '安卓宿主桥不可用';
    }

    const opened = bridge.openSettings() === true;
    if (opened) {
        toastr.success('正在打开宿主设置。', popupTitle);
        return '正在打开宿主设置';
    }

    toastr.warning('宿主设置已在打开中。', popupTitle);
    return '宿主设置已在打开中';
}

async function setLogsBubbleEnabled(enabled) {
    const bridge = getBridge();
    if (!bridge || typeof bridge.setFloatingLogsBubbleEnabled !== 'function') {
        showBridgeUnavailableToast();
        return {
            updated: false,
            summary: '安卓宿主桥不可用',
        };
    }

    const updated = bridge.setFloatingLogsBubbleEnabled(enabled) === true;
    if (!updated) {
        const failedMessage = enabled ? '暂时无法启用调试日志球。' : '暂时无法关闭调试日志球。';
        toastr.warning(failedMessage, popupTitle);
        return {
            updated: false,
            summary: failedMessage,
        };
    }

    const info = getHostVersionInfo();
    const message = enabled ? '已启用调试日志球。' : '已关闭调试日志球。';
    if (enabled) {
        toastr.success(message, popupTitle);
    } else {
        toastr.info(message, popupTitle);
    }

    return {
        updated: true,
        summary: info ? formatVersionSummary(info) : message,
    };
}

async function showLogsBubbleCommand() {
    const result = await setLogsBubbleEnabled(true);
    return result.summary;
}

async function showVersionCommand() {
    const info = getHostVersionInfo();
    const summary = formatVersionSummary(info);
    if (info) {
        toastr.info(summary, popupTitle);
    } else {
        showBridgeUnavailableToast();
    }
    return summary;
}

function buildInfoRow(label, value) {
    const row = document.createElement('div');
    row.style.display = 'flex';
    row.style.flexDirection = 'column';
    row.style.gap = '4px';

    const title = document.createElement('strong');
    title.textContent = label;
    row.appendChild(title);

    const body = document.createElement('span');
    body.textContent = value;
    row.appendChild(body);

    return row;
}

function applySwitchVisualState(toggle, knob, enabled) {
    toggle.setAttribute('aria-checked', enabled ? 'true' : 'false');
    toggle.style.background = enabled ? '#0f766e' : 'rgba(15, 118, 110, 0.18)';
    toggle.style.borderColor = enabled ? '#0f766e' : 'rgba(15, 118, 110, 0.18)';
    knob.style.transform = enabled ? 'translateX(18px)' : 'translateX(0)';
}

function buildSwitchRow(initialChecked) {
    const row = document.createElement('div');
    row.style.display = 'flex';
    row.style.alignItems = 'center';
    row.style.justifyContent = 'space-between';
    row.style.gap = '12px';
    row.style.padding = '8px 10px';
    row.style.border = '1px solid rgba(15, 118, 110, 0.18)';
    row.style.borderRadius = '12px';
    row.style.background = 'rgba(15, 118, 110, 0.08)';

    const textGroup = document.createElement('div');
    textGroup.style.display = 'flex';
    textGroup.style.flexDirection = 'column';
    textGroup.style.gap = '4px';
    textGroup.style.flex = '1';

    const title = document.createElement('strong');
    title.textContent = '调试日志球';
    textGroup.appendChild(title);

    const summary = document.createElement('span');
    summary.textContent = '打开后会在主界面右下角显示调试日志球，点击即可展开实时日志。';
    summary.style.fontSize = '0.92em';
    summary.style.opacity = '0.82';
    textGroup.appendChild(summary);

    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.setAttribute('role', 'switch');
    toggle.style.position = 'relative';
    toggle.style.width = '44px';
    toggle.style.height = '26px';
    toggle.style.flexShrink = '0';
    toggle.style.border = '1px solid rgba(15, 118, 110, 0.18)';
    toggle.style.borderRadius = '999px';
    toggle.style.padding = '0';
    toggle.style.cursor = 'pointer';
    toggle.style.transition = 'background 160ms ease, border-color 160ms ease, opacity 160ms ease';

    const knob = document.createElement('span');
    knob.style.position = 'absolute';
    knob.style.top = '3px';
    knob.style.left = '3px';
    knob.style.width = '18px';
    knob.style.height = '18px';
    knob.style.borderRadius = '999px';
    knob.style.background = '#ffffff';
    knob.style.boxShadow = '0 1px 3px rgba(15, 23, 42, 0.24)';
    knob.style.transition = 'transform 160ms ease';
    toggle.appendChild(knob);

    let checked = initialChecked;
    applySwitchVisualState(toggle, knob, checked);

    toggle.addEventListener('click', async () => {
        const requestedEnabled = !checked;
        toggle.disabled = true;
        toggle.style.opacity = '0.72';
        applySwitchVisualState(toggle, knob, requestedEnabled);
        try {
            const result = await setLogsBubbleEnabled(requestedEnabled);
            if (!result.updated) {
                applySwitchVisualState(toggle, knob, checked);
                return;
            }
            checked = requestedEnabled;
        } catch (error) {
            console.error('安卓宿主：切换调试日志球失败', error);
            applySwitchVisualState(toggle, knob, checked);
            toastr.error('切换调试日志球失败。', popupTitle);
        } finally {
            toggle.disabled = false;
            toggle.style.opacity = '1';
        }
    });

    row.appendChild(textGroup);
    row.appendChild(toggle);
    return row;
}

function buildPopupContent(info) {
    const wrapper = document.createElement('div');
    wrapper.style.display = 'flex';
    wrapper.style.flexDirection = 'column';
    wrapper.style.gap = '12px';

    const description = document.createElement('p');
    description.style.margin = '0';
    description.textContent = info
        ? '这里可以直接查看宿主版本、打开宿主设置，并即时切换调试日志球。'
        : '当前页面无法连接安卓宿主桥。';
    wrapper.appendChild(description);

    if (!info) {
        return wrapper;
    }

    wrapper.appendChild(buildInfoRow('宿主版本', String(info.hostVersion || 'unknown')));
    wrapper.appendChild(
        buildInfoRow(
            'APK 版本',
            `${String(info.apkVersionName || 'unknown')} (${String(info.apkVersionCode || 'unknown')})`
        )
    );
    wrapper.appendChild(buildSwitchRow(info.floatingLogBubbleEnabled === true));
    wrapper.appendChild(
        buildInfoRow(
            '本地服务',
            info.serverReady ? '运行中' : '启动中或已暂停'
        )
    );

    return wrapper;
}

async function openAndroidHostPopup() {
    const info = getHostVersionInfo();
    const popup = new Popup(buildPopupContent(info), POPUP_TYPE.TEXT, '', {
        okButton: '关闭',
        cancelButton: false,
        customButtons: info ? [
            {
                text: '打开宿主设置',
                result: POPUP_RESULT.CUSTOM1,
                icon: 'fa-sliders',
            },
        ] : null,
    });

    const result = await popup.show();
    if (result === POPUP_RESULT.CUSTOM1) {
        await openSettingsCommand();
    }
}

function addMenuButton() {
    if (document.getElementById(menuButtonId)) {
        return;
    }

    const menu = document.getElementById('extensionsMenu');
    if (!(menu instanceof HTMLElement)) {
        window.setTimeout(addMenuButton, 500);
        return;
    }

    const button = document.createElement('div');
    button.id = menuButtonId;
    button.classList.add('list-group-item', 'flex-container', 'flexGap5');

    const icon = document.createElement('div');
    icon.classList.add('fa-solid', 'fa-mobile-screen-button', 'extensionsMenuExtensionButton');
    button.appendChild(icon);

    const text = document.createElement('span');
    text.textContent = popupTitle;
    button.appendChild(text);

    button.addEventListener('click', () => {
        openAndroidHostPopup();
    });

    menu.appendChild(button);
}

function registerSlashCommands() {
    if (slashCommandsRegistered) {
        return;
    }

    slashCommandsRegistered = true;

    SlashCommandParser.addCommandObject(SlashCommand.fromProps({
        name: 'android-host',
        callback: async () => {
            await openAndroidHostPopup();
            return formatVersionSummary(getHostVersionInfo());
        },
        returns: '安卓宿主版本摘要',
        helpString: `
            <div>
                打开安卓宿主弹窗，查看版本信息并执行快捷操作。
            </div>
        `,
    }));

    SlashCommandParser.addCommandObject(SlashCommand.fromProps({
        name: 'android-host-settings',
        callback: openSettingsCommand,
        returns: '状态消息',
        helpString: `
            <div>
                不停止 Tavern，直接打开安卓宿主设置页。
            </div>
        `,
    }));

    SlashCommandParser.addCommandObject(SlashCommand.fromProps({
        name: 'android-host-logs',
        callback: showLogsBubbleCommand,
        returns: '状态消息',
        helpString: `
            <div>
                启用并显示安卓调试日志球。
            </div>
        `,
    }));

    SlashCommandParser.addCommandObject(SlashCommand.fromProps({
        name: 'android-host-version',
        callback: showVersionCommand,
        returns: '安卓宿主版本摘要',
        helpString: `
            <div>
                显示当前安卓宿主和 APK 版本信息。
            </div>
        `,
    }));
}

function bootstrapHostExtensionUi() {
    registerSlashCommands();
    addMenuButton();
}

function scheduleHostExtensionBootstrap() {
    if (bootstrapScheduled) {
        return;
    }

    bootstrapScheduled = true;
    const run = () => {
        bootstrapHostExtensionUi();
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', run, { once: true });
        window.setTimeout(run, 500);
        return;
    }

    window.setTimeout(run, 0);
}

scheduleHostExtensionBootstrap();

export async function activate() {
    bootstrapHostExtensionUi();
}