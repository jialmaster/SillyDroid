'use strict';

const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { pathToFileURL } = require('url');

const TARGET_FILE = 'src/endpoints/characters.js';
const TARGET_HASH = '255751D3BE5FE42FAA993882514A3D3DA9A19CB5E9C1B7E9883311D84F870B70';
const FALLBACK_CONCURRENCY = 4;
const MIN_CONCURRENCY = 1;
const MAX_CONCURRENCY = 12;
const PROGRESS_EVERY_ITEMS = 25;
const PROGRESS_EVERY_MS = 30000;
const PROGRESS_LOG_PREFIX = '[character-all-limited-concurrency]';
const CHARACTER_ALL_ROUTE_START = "router.post('/all', async function (request, response) {";
const CHARACTER_GET_ROUTE_START = "router.post('/get', validateAvatarUrlMiddleware, async function (request, response) {";
const ORIGINAL_SOURCE = "const processingPromises = pngFiles.map(file => processCharacter(file, request.user.directories, { shallow: useShallowCharacters }));";
const PATCHED_SOURCE = "const processingPromises = await globalThis.__sillydroidCharacterAllLimitedMap(pngFiles, file => processCharacter(file, request.user.directories, { shallow: useShallowCharacters }), request.user.directories.characters);";

function apply(context) {
  const targetPath = path.join(process.cwd(), TARGET_FILE);
  const targetHash = sha256File(targetPath);
  if (!targetHash) {
    context.logger({
      action: 'skip',
      reason: 'target_file_missing',
      target: TARGET_FILE
    });
    return { status: 'skipped', reason: 'target_file_missing' };
  }
  if (targetHash.toUpperCase() !== TARGET_HASH) {
    context.logger({
      action: 'skip',
      reason: 'target_hash_mismatch',
      target: TARGET_FILE,
      expectedSha256: TARGET_HASH,
      actualSha256: targetHash
    });
    return { status: 'skipped', reason: 'target_hash_mismatch' };
  }

  const { registerHooks } = require('node:module');
  if (typeof registerHooks !== 'function') {
    context.logger({
      action: 'skip',
      reason: 'node_register_hooks_unavailable',
      target: TARGET_FILE
    });
    return { status: 'skipped', reason: 'node_register_hooks_unavailable' };
  }

  const concurrencyConfig = resolveConcurrency(context.settings || {});
  installLimitedMapHelper(context, concurrencyConfig);
  registerSourceTransformHook({
    context,
    concurrency: concurrencyConfig.value,
    registerHooks,
    targetUrl: pathToFileURL(targetPath).href
  });

  context.logger({
    action: 'install_source_transform_hook',
    route: '/api/characters/all',
    concurrency: concurrencyConfig.value,
    concurrencySource: concurrencyConfig.source,
    cpuThreads: concurrencyConfig.cpuThreads,
    target: TARGET_FILE,
    targetSha256: TARGET_HASH
  });
  return { status: 'loaded' };
}

function sha256File(filePath) {
  try {
    return crypto.createHash('sha256')
      .update(fs.readFileSync(filePath))
      .digest('hex')
      .toUpperCase();
  } catch (_error) {
    return '';
  }
}

function resolveConcurrency(settings) {
  const raw = Number.parseInt(process.env.SILLYDROID_CHARACTER_ALL_CONCURRENCY || '', 10);
  if (Number.isFinite(raw)) {
    return {
      value: clampConcurrency(raw),
      source: 'env',
      cpuThreads: detectCpuThreads()
    };
  }
  const cpuThreads = detectCpuThreads();
  const configured = String(settings.concurrency || '').trim();
  if (configured && configured.toLowerCase() !== 'auto') {
    const configuredValue = Number.parseInt(configured, 10);
    if (Number.isFinite(configuredValue)) {
      return {
        value: clampConcurrency(configuredValue),
        source: 'settings',
        cpuThreads
      };
    }
  }
  if (Number.isFinite(cpuThreads) && cpuThreads > 0) {
    return {
      value: clampConcurrency(cpuThreads),
      source: 'cpu',
      cpuThreads
    };
  }
  return {
    value: FALLBACK_CONCURRENCY,
    source: 'fallback',
    cpuThreads: 0
  };
}

function detectCpuThreads() {
  if (typeof os.availableParallelism === 'function') {
    const available = os.availableParallelism();
    if (Number.isFinite(available) && available > 0) {
      return available;
    }
  }
  const cpus = os.cpus();
  return Array.isArray(cpus) ? cpus.length : 0;
}

function clampConcurrency(value) {
  return Math.min(MAX_CONCURRENCY, Math.max(MIN_CONCURRENCY, value));
}

function installLimitedMapHelper(context, concurrencyConfig) {
  const concurrency = concurrencyConfig.value;
  if (globalThis.__sillydroidCharacterAllLimitedMap) {
    context.logger({
      action: 'reuse_limited_map_helper',
      concurrency,
      concurrencySource: concurrencyConfig.source,
      cpuThreads: concurrencyConfig.cpuThreads
    });
    return;
  }

  Object.defineProperty(globalThis, '__sillydroidCharacterAllLimitedMap', {
    configurable: false,
    enumerable: false,
    value: function sillydroidCharacterAllLimitedMap(items, callback, requestKey) {
      return singleFlightLimitedMap(items, callback, concurrency, requestKey);
    }
  });
}

function registerSourceTransformHook({ context, concurrency, registerHooks, targetUrl }) {
  if (globalThis.__sillydroidCharacterAllSourceTransformInstalled) {
    context.logger({
      action: 'skip',
      reason: 'source_transform_already_installed',
      target: TARGET_FILE
    });
    return;
  }
  globalThis.__sillydroidCharacterAllSourceTransformInstalled = true;

  registerHooks({
    load(url, loadContext, nextLoad) {
      const loaded = nextLoad(url, loadContext);
      if (url !== targetUrl) {
        return loaded;
      }

      const source = normalizeSource(loaded.source);
      const transform = transformCharacterAllRoute(source);
      if (!transform.transformed) {
        context.logger({
          action: 'source_transform_skipped',
          reason: transform.reason,
          target: TARGET_FILE
        });
        return loaded;
      }

      context.logger({
        action: 'source_transformed',
        route: '/api/characters/all',
        concurrency,
        target: TARGET_FILE
      });

      return {
        ...loaded,
        source: transform.source
      };
    }
  });
}

function transformCharacterAllRoute(source) {
  const routeStart = source.indexOf(CHARACTER_ALL_ROUTE_START);
  if (routeStart < 0) {
    return { transformed: false, reason: 'character_all_route_missing', source };
  }

  const routeEnd = source.indexOf(CHARACTER_GET_ROUTE_START, routeStart);
  if (routeEnd < 0) {
    return { transformed: false, reason: 'character_all_route_end_missing', source };
  }

  // Keep the source patch scoped to /api/characters/all so another endpoint in
  // the same file cannot be changed if upstream later reuses the same snippet.
  const beforeRoute = source.slice(0, routeStart);
  const routeSource = source.slice(routeStart, routeEnd);
  const afterRoute = source.slice(routeEnd);
  const snippetCount = countOccurrences(routeSource, ORIGINAL_SOURCE);
  if (snippetCount !== 1) {
    return {
      transformed: false,
      reason: snippetCount === 0 ? 'source_snippet_missing' : 'source_snippet_ambiguous',
      source
    };
  }

  return {
    transformed: true,
    reason: '',
    source: beforeRoute + routeSource.replace(ORIGINAL_SOURCE, PATCHED_SOURCE) + afterRoute
  };
}

function countOccurrences(value, pattern) {
  if (!value || !pattern) {
    return 0;
  }
  let count = 0;
  let offset = 0;
  while (true) {
    const index = value.indexOf(pattern, offset);
    if (index < 0) {
      return count;
    }
    count += 1;
    offset = index + pattern.length;
  }
}

function normalizeSource(source) {
  if (typeof source === 'string') {
    return source;
  }
  if (source instanceof ArrayBuffer) {
    return Buffer.from(source).toString('utf8');
  }
  if (ArrayBuffer.isView(source)) {
    return Buffer.from(source.buffer, source.byteOffset, source.byteLength).toString('utf8');
  }
  return '';
}

function singleFlightLimitedMap(items, callback, concurrency, requestKey) {
  if (!Array.isArray(items) || items.length === 0) {
    return [];
  }

  const flightKey = makeFlightKey(items, requestKey);
  const inFlight = getInFlightMap();
  const current = inFlight.get(flightKey);
  if (current) {
    return current.promise;
  }

  const promise = limitedMap(items, callback, concurrency)
    .finally(() => {
      inFlight.delete(flightKey);
    });

  inFlight.set(flightKey, { promise });
  return promise;
}

function getInFlightMap() {
  if (!globalThis.__sillydroidCharacterAllInFlight) {
    Object.defineProperty(globalThis, '__sillydroidCharacterAllInFlight', {
      configurable: false,
      enumerable: false,
      value: new Map()
    });
  }
  return globalThis.__sillydroidCharacterAllInFlight;
}

function makeFlightKey(items, requestKey) {
  const base = typeof requestKey === 'string' && requestKey.trim()
    ? requestKey.trim()
    : 'unknown-character-directory';
  return `${base}|count=${items.length}|first=${items[0] || ''}|last=${items[items.length - 1] || ''}`;
}

function limitedMap(items, callback, concurrency) {
  if (!Array.isArray(items) || items.length === 0) {
    return [];
  }

  return new Promise((resolve, reject) => {
    const startedAt = Date.now();
    const results = new Array(items.length);
    let nextIndex = 0;
    let running = 0;
    let completed = 0;
    let rejected = false;
    let lastProgressAt = startedAt;

    // Keep the long-running /api/characters/all patch observable without
    // flooding the log ball with internal scheduling details.
    const logProgress = () => {
      console.log(`${PROGRESS_LOG_PREFIX} 当前进度： ${completed}/${items.length}`);
    };

    const progressTimer = setInterval(() => {
      if (!rejected && completed < items.length) {
        logProgress();
      }
    }, PROGRESS_EVERY_MS);

    const finish = (result) => {
      clearInterval(progressTimer);
      resolve(result);
    };

    logProgress();

    const schedule = () => {
      if (rejected) {
        return;
      }
      while (running < concurrency && nextIndex < items.length) {
        const index = nextIndex;
        nextIndex += 1;
        running += 1;
        setImmediate(() => {
          Promise.resolve()
            .then(() => callback(items[index], index, items))
            .then((result) => {
              results[index] = result;
            })
            .then(() => {
              running -= 1;
              completed += 1;
              const now = Date.now();
              if (
                completed === items.length ||
                completed % PROGRESS_EVERY_ITEMS === 0 ||
                now - lastProgressAt >= PROGRESS_EVERY_MS
              ) {
                lastProgressAt = now;
                logProgress();
              }
              if (completed >= items.length) {
                finish(results);
                return;
              }
              schedule();
            })
            .catch((error) => {
              rejected = true;
              clearInterval(progressTimer);
              reject(error);
            });
        });
      }
    };

    schedule();
  });
}

module.exports = { apply, transformCharacterAllRoute };
