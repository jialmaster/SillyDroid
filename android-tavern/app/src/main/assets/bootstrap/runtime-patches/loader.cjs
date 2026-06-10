'use strict';

const fs = require('fs');
const path = require('path');

const EVENT_PREFIX = 'sillydroid_runtime_patch';
const LOADER_DIR = __dirname;
const ROOT_MANIFEST_PATH = path.join(LOADER_DIR, 'manifest.json');
const DISABLED_PRESETS = new Set(['', 'off', 'false', '0', 'disabled']);

function log(fields) {
  const line = Object.entries(fields)
    .map(([key, value]) => `${key}=${formatValue(value)}`)
    .join(' ');
  console.log(`${EVENT_PREFIX} ${line}`);
}

function formatValue(value) {
  if (value === undefined || value === null) {
    return '';
  }
  return String(value).replace(/\s+/g, '_');
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function normalizePreset(value, fallback) {
  const normalized = String(value || fallback || '').trim();
  return DISABLED_PRESETS.has(normalized.toLowerCase()) ? 'off' : normalized;
}

function readTavernVersion() {
  try {
    const packageJson = readJson(path.join(process.cwd(), 'package.json'));
    return String(packageJson.version || '').trim();
  } catch (error) {
    return '';
  }
}

function supportsVersion(moduleManifest, tavernVersion) {
  const supported = Array.isArray(moduleManifest.supportedTavernVersions)
    ? moduleManifest.supportedTavernVersions
    : [];
  return supported.includes(tavernVersion);
}

function listVersions(value) {
  return Array.isArray(value) ? value.join(',') : '';
}

function parseDisabledModuleIds(value) {
  return new Set(
    String(value || '')
      .split(',')
      .map((id) => id.trim())
      .filter(Boolean)
  );
}

function parseSettings(rawValue) {
  if (!rawValue || !String(rawValue).trim()) {
    return {};
  }
  try {
    const parsed = JSON.parse(String(rawValue));
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      log({ event: 'settings_ignored', reason: 'settings_root_not_object' });
      return {};
    }
    return parsed;
  } catch (error) {
    log({
      event: 'settings_ignored',
      reason: 'settings_json_invalid',
      message: error && error.message ? error.message : 'unknown'
    });
    return {};
  }
}

function resolveModuleSettings(moduleManifest, moduleId, settingsByModuleId) {
  const rawOverrides = settingsByModuleId && settingsByModuleId[moduleId];
  const overrides = rawOverrides && typeof rawOverrides === 'object' && !Array.isArray(rawOverrides)
    ? rawOverrides
    : {};
  const schema = Array.isArray(moduleManifest.settings) ? moduleManifest.settings : [];
  const resolved = {};
  for (const setting of schema) {
    if (!setting || typeof setting !== 'object') {
      continue;
    }
    const key = String(setting.key || '').trim();
    if (!key) {
      continue;
    }
    const defaultValue = setting.defaultValue === undefined || setting.defaultValue === null
      ? ''
      : String(setting.defaultValue).trim();
    const rawValue = overrides[key] === undefined || overrides[key] === null
      ? defaultValue
      : String(overrides[key]).trim();
    resolved[key] = sanitizeSettingValue(setting, rawValue, defaultValue);
  }
  return resolved;
}

function sanitizeSettingValue(setting, rawValue, defaultValue) {
  const type = normalizeSettingType(setting.type);
  if (type === 'switch' || type === 'checkbox') {
    const normalized = String(rawValue || defaultValue || '').trim().toLowerCase();
    if (['true', '1', 'yes', 'on', 'enabled'].includes(normalized)) {
      return true;
    }
    if (['false', '0', 'no', 'off', 'disabled'].includes(normalized)) {
      return false;
    }
    return String(defaultValue).trim().toLowerCase() === 'true';
  }
  if (type === 'number') {
    const parsed = Number(rawValue);
    const fallback = Number(defaultValue);
    let value = Number.isFinite(parsed) ? parsed : fallback;
    if (!Number.isFinite(value)) {
      value = 0;
    }
    if (Number.isFinite(setting.min)) {
      value = Math.max(value, Number(setting.min));
    }
    if (Number.isFinite(setting.max)) {
      value = Math.min(value, Number(setting.max));
    }
    return setting.type === 'integer' || setting.type === 'int' ? Math.trunc(value) : value;
  }
  const normalizedRawValue = String(rawValue || defaultValue || '').trim();
  const options = Array.isArray(setting.options) ? setting.options : [];
  if (options.length > 0) {
    const allowed = new Set(options.map((option) => String(option && option.value !== undefined ? option.value : '').trim()));
    if (allowed.has(normalizedRawValue)) {
      return normalizedRawValue;
    }
    if (allowed.has(String(defaultValue).trim())) {
      return String(defaultValue).trim();
    }
    return Array.from(allowed).find(Boolean) || '';
  }
  return normalizedRawValue;
}

function normalizeSettingType(type) {
  const normalized = String(type || '').trim().toLowerCase();
  if (['switch', 'toggle', 'boolean'].includes(normalized)) {
    return 'switch';
  }
  if (['check', 'checkbox'].includes(normalized)) {
    return 'checkbox';
  }
  if (['number', 'integer', 'int', 'float', 'decimal'].includes(normalized)) {
    return 'number';
  }
  if (['list', 'select', 'radio', 'choice'].includes(normalized)) {
    return 'select';
  }
  return 'text';
}

function resolveModuleEntries(rootManifest, preset) {
  const presetModuleIds = Array.isArray(rootManifest.presets && rootManifest.presets[preset])
    ? rootManifest.presets[preset]
    : [];
  const entriesById = new Map(
    (Array.isArray(rootManifest.modules) ? rootManifest.modules : [])
      .map((entry) => [entry && entry.id, entry])
      .filter(([id, entry]) => Boolean(id) && Boolean(entry))
  );
  return presetModuleIds.map((id) => entriesById.get(id)).filter(Boolean);
}

function loadModule(entry, tavernVersion, disabledModuleIds, settingsByModuleId) {
  const moduleRoot = path.join(LOADER_DIR, entry.path || '');
  const moduleManifest = readJson(path.join(moduleRoot, entry.manifest || 'manifest.json'));
  const moduleId = moduleManifest.id || entry.id || path.basename(moduleRoot);
  const moduleVersion = moduleManifest.version || '0.0.0';
  const supportedTavernVersions = listVersions(moduleManifest.supportedTavernVersions);

  if (disabledModuleIds.has(moduleId)) {
    log({
      event: 'module_skipped',
      module: moduleId,
      moduleVersion,
      reason: 'disabled_by_user',
      tavernVersion,
      supportedTavernVersions
    });
    return 'skipped';
  }

  if (!supportsVersion(moduleManifest, tavernVersion)) {
    log({
      event: 'module_skipped',
      module: moduleId,
      moduleVersion,
      reason: 'unsupported_tavern_version',
      tavernVersion,
      supportedTavernVersions
    });
    return 'skipped';
  }

  const entrypoint = moduleManifest.entrypoint || 'index.cjs';
  const modulePath = path.join(moduleRoot, entrypoint);
  const moduleExports = require(modulePath);
  const moduleSettings = resolveModuleSettings(moduleManifest, moduleId, settingsByModuleId);
  let applyResult;
  if (moduleExports && typeof moduleExports.apply === 'function') {
    applyResult = moduleExports.apply({
      tavernVersion,
      moduleRoot,
      moduleVersion,
      supportedTavernVersions,
      settings: moduleSettings,
      logger: (fields) => log({ event: 'module_event', module: moduleId, moduleVersion, ...fields })
    });
  }
  if (applyResult && applyResult.status === 'skipped') {
    log({
      event: 'module_skipped',
      module: moduleId,
      moduleVersion,
      reason: applyResult.reason || 'module_requested_skip',
      tavernVersion,
      supportedTavernVersions
    });
    return 'skipped';
  }
  log({
    event: 'module_loaded',
    module: moduleId,
    moduleVersion,
    tavernVersion,
    supportedTavernVersions,
    settingCount: Object.keys(moduleSettings).length
  });
  return 'loaded';
}

function main() {
  const rootManifest = readJson(ROOT_MANIFEST_PATH);
  const frameworkVersion = rootManifest.frameworkVersion || '0.0.0';
  const compatibleTavernVersions = Array.isArray(rootManifest.compatibleTavernVersions)
    ? rootManifest.compatibleTavernVersions
    : [];
  const preset = normalizePreset(
    process.env.SILLYDROID_TAVERN_PATCH_PRESET,
    rootManifest.defaultPreset
  );
  const disabledModuleIds = parseDisabledModuleIds(process.env.SILLYDROID_TAVERN_PATCH_DISABLED_MODULES);
  const settingsByModuleId = parseSettings(process.env.SILLYDROID_TAVERN_PATCH_SETTINGS);
  const tavernVersion = readTavernVersion();

  if (preset === 'off') {
    log({ event: 'loader_disabled', patch_requested: false, patch_effective: false, frameworkVersion, preset: 'off' });
    return;
  }

  const entries = resolveModuleEntries(rootManifest, preset);
  if (!compatibleTavernVersions.includes(tavernVersion)) {
    log({
      event: 'loader_skipped',
      patch_requested: true,
      patch_effective: false,
      frameworkVersion,
      preset,
      reason: 'unsupported_tavern_version',
      tavernVersion,
      compatibleTavernVersions: listVersions(compatibleTavernVersions),
      moduleCount: entries.length,
      disabledModuleCount: disabledModuleIds.size,
      settingsModuleCount: Object.keys(settingsByModuleId).length
    });
    return;
  }

  log({
    event: 'loader_start',
    patch_requested: true,
    patch_effective: 'pending',
    frameworkVersion,
    preset,
    tavernVersion,
    compatibleTavernVersions: listVersions(compatibleTavernVersions),
    moduleCount: entries.length,
    disabledModuleCount: disabledModuleIds.size,
    settingsModuleCount: Object.keys(settingsByModuleId).length
  });

  let loaded = 0;
  let skipped = 0;
  let failed = 0;
  for (const entry of entries) {
    try {
      const result = loadModule(entry, tavernVersion, disabledModuleIds, settingsByModuleId);
      if (result === 'loaded') {
        loaded += 1;
      } else {
        skipped += 1;
      }
    } catch (error) {
      failed += 1;
      log({
        event: 'module_failed',
        module: entry && entry.id,
        frameworkVersion,
        reason: error && error.message ? error.message : 'unknown'
      });
    }
  }

  log({
    event: 'loader_complete',
    patch_requested: true,
    patch_effective: loaded > 0,
    frameworkVersion,
    preset,
    tavernVersion,
    compatibleTavernVersions: listVersions(compatibleTavernVersions),
    moduleCount: entries.length,
    disabledModuleCount: disabledModuleIds.size,
    settingsModuleCount: Object.keys(settingsByModuleId).length,
    loaded,
    skipped,
    failed
  });
}

main();
