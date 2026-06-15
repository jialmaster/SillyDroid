// Android no-proot npm compatibility layer.
// npm lifecycle scripts may execute JS bin shims from node_modules/.bin. App-private
// writable paths cannot be executed directly on newer Android versions, so this
// shim lets npm run those JS bins through the APK native Node entry instead.
(function installNpmLifecycleAndroidShim() {
  const mainScript = process.argv[1] || '';
  if (!mainScript.endsWith('/lib/node_modules/npm/bin/npm-cli.js')) {
    return;
  }

  const childProcess = require('node:child_process');
  const fs = require('node:fs');
  const path = require('node:path');

  const nodeBin = process.env.TERMUX_NODE_BIN;
  if (!nodeBin) {
    return;
  }

  function commandName(command) {
    if (typeof command !== 'string' || command.length === 0) {
      return '';
    }
    return path.basename(command);
  }

  function isJavascriptBin(command) {
    if (typeof command !== 'string' || command.length === 0) {
      return false;
    }
    if (!command.includes('/node_modules/.bin/')) {
      return false;
    }
    try {
      const header = fs.readFileSync(command, 'utf8').slice(0, 160);
      return header.startsWith('#!') && header.includes('node');
    } catch (_) {
      return false;
    }
  }

  function findNodeModulesBin(cwd, name) {
    let dir = cwd || process.cwd();
    for (;;) {
      const candidate = path.join(dir, 'node_modules/.bin', name);
      if (fs.existsSync(candidate)) {
        return candidate;
      }
      const parent = path.dirname(dir);
      if (parent === dir) {
        return candidate;
      }
      dir = parent;
    }
  }

  function rewriteDirectCommand(command, args) {
    if (commandName(command) === 'node-gyp') {
      return {
        command: '/system/bin/sh',
        args: ['-c', 'echo "node-gyp is not supported in SillyDroid Android runtime" >&2; exit 127'],
      };
    }
    if (isJavascriptBin(command)) {
      return {
        command: nodeBin,
        args: [command, ...(Array.isArray(args) ? args : [])],
      };
    }
    return null;
  }

  function shellQuote(value) {
    return `'${String(value).replace(/'/g, `'\\''`)}'`;
  }

  function rewriteShellCommand(command, options) {
    if (typeof command !== 'string') {
      return command;
    }
    if (!/\b(prebuild-install|node-gyp)\b/.test(command)) {
      return command;
    }
    const cwd = options && typeof options.cwd === 'string' ? options.cwd : process.cwd();
    const prebuildInstall = findNodeModulesBin(cwd, 'prebuild-install');
    const nodeGypMessage = 'echo "node-gyp is not supported in SillyDroid Android runtime" >&2; exit 127';
    const commandBoundary = '(^|[\\s;&|()])';
    const commandEnd = '(?=$|[\\s;&|()])';
    return command
      .replace(
        new RegExp(`${commandBoundary}((?:[^\\s'";&|()]+/node_modules/\\.bin/)?prebuild-install)${commandEnd}`, 'g'),
        (_match, prefix, token) => `${prefix}${shellQuote(nodeBin)} ${shellQuote(token.includes('/node_modules/.bin/') ? token : prebuildInstall)}`
      )
      .replace(
        new RegExp(`${commandBoundary}((?:[^\\s'";&|()]+/node_modules/\\.bin/)?node-gyp)${commandEnd}`, 'g'),
        (_match, prefix) => `${prefix}/system/bin/sh -c ${shellQuote(nodeGypMessage)}`
      );
  }

  function rewriteShellArgs(command, args, options) {
    if ((command !== 'sh' && command !== '/system/bin/sh') || !Array.isArray(args)) {
      return args;
    }
    const cIndex = args.indexOf('-c');
    if (cIndex < 0) {
      return args;
    }

    // npm run-script invokes POSIX shells as: sh -c -- "<lifecycle script>".
    // The script is therefore after the optional "--", not always directly after -c.
    const scriptIndex = args[cIndex + 1] === '--' ? cIndex + 2 : cIndex + 1;
    if (typeof args[scriptIndex] !== 'string') {
      return args;
    }

    const rewrittenCommand = rewriteShellCommand(args[scriptIndex], options);
    if (rewrittenCommand === args[scriptIndex]) {
      return args;
    }

    const nextArgs = args.slice();
    nextArgs[scriptIndex] = rewrittenCommand;
    return nextArgs;
  }

  const originalSpawn = childProcess.spawn;
  childProcess.spawn = function patchedSpawn(command, args, options) {
    const rewritten = rewriteDirectCommand(command, args);
    if (rewritten) {
      return originalSpawn.call(this, rewritten.command, rewritten.args, options);
    }
    args = rewriteShellArgs(command, args, options);
    return originalSpawn.call(this, command, args, options);
  };

  const originalSpawnSync = childProcess.spawnSync;
  childProcess.spawnSync = function patchedSpawnSync(command, args, options) {
    const rewritten = rewriteDirectCommand(command, args);
    if (rewritten) {
      return originalSpawnSync.call(this, rewritten.command, rewritten.args, options);
    }
    args = rewriteShellArgs(command, args, options);
    return originalSpawnSync.call(this, command, args, options);
  };

  const originalExecFile = childProcess.execFile;
  childProcess.execFile = function patchedExecFile(file, args, options, callback) {
    if (typeof args === 'function') {
      callback = args;
      args = [];
      options = undefined;
    } else if (typeof options === 'function') {
      callback = options;
      options = undefined;
    }
    const rewritten = rewriteDirectCommand(file, args);
    if (rewritten) {
      return originalExecFile.call(this, rewritten.command, rewritten.args, options, callback);
    }
    args = rewriteShellArgs(file, args, options);
    return originalExecFile.call(this, file, args, options, callback);
  };

  const originalExecFileSync = childProcess.execFileSync;
  childProcess.execFileSync = function patchedExecFileSync(file, args, options) {
    const rewritten = rewriteDirectCommand(file, args);
    if (rewritten) {
      return originalExecFileSync.call(this, rewritten.command, rewritten.args, options);
    }
    args = rewriteShellArgs(file, args, options);
    return originalExecFileSync.call(this, file, args, options);
  };
})();
