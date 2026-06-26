// Android no-proot child process compatibility layer.
// Third-party server plugins often pass a partial env to child_process helpers
// such as simple-git. Preserve the host runtime PATH/Git/OpenSSL variables so
// those child processes still see APK-native commands exposed by SillyDroid.
(function installNpmLifecycleAndroidShim() {
  const mainScript = process.argv[1] || '';
  const isNpmCliProcess = mainScript.endsWith('/lib/node_modules/npm/bin/npm-cli.js');
  const isNpmLifecycleProcess = Boolean(process.env.npm_lifecycle_event);

  const childProcess = require('node:child_process');
  const fs = require('node:fs');
  const path = require('node:path');

  const nodeBin = process.env.TERMUX_NODE_BIN;
  if (!nodeBin) {
    return;
  }
  const shellBin = process.env.TERMUX_SH_BIN || process.env.npm_config_script_shell || process.env.SHELL || '/system/bin/sh';
  const prefixDir = process.env.HOST_PREFIX_DIR || process.env.PREFIX || '';
  const npmCli = prefixDir ? path.join(prefixDir, 'lib/node_modules/npm/bin/npm-cli.js') : '';
  const npxCli = prefixDir ? path.join(prefixDir, 'lib/node_modules/npm/bin/npx-cli.js') : '';
  const runtimeEnvKeys = [
    'PATH',
    'LD_LIBRARY_PATH',
    'PREFIX',
    'TMPDIR',
    'TMP',
    'TEMP',
    'SHELL',
    'npm_config_script_shell',
    'NPM_CONFIG_SCRIPT_SHELL',
    'GIT_EXEC_PATH',
    'GIT_TEMPLATE_DIR',
    'GIT_CONFIG_NOSYSTEM',
    'GIT_ATTR_NOSYSTEM',
    'SSL_CERT_FILE',
    'NODE_EXTRA_CA_CERTS',
    'GIT_SSL_CAINFO',
    'OPENSSL_CONF',
    'SILLYDROID_HOST_COMMAND_PATH',
    'BOOTSTRAP_ROOT',
    'HOST_PREFIX_DIR',
    'HOST_NATIVE_LIB_DIR',
    'HOST_TMP_DIR',
    'TERMUX_NODE_BIN',
    'TERMUX_GIT_BIN',
    'TERMUX_GIT_REMOTE_HTTP_BIN',
    'TERMUX_CURL_BIN',
    'TERMUX_SH_BIN',
    'TERMUX_BASH_BIN',
  ];

  function mergeAndroidRuntimeEnv(env) {
    if (!env || typeof env !== 'object') {
      return env;
    }
    const nextEnv = { ...env };
    for (const key of runtimeEnvKeys) {
      if (nextEnv[key] === undefined && process.env[key] !== undefined) {
        nextEnv[key] = process.env[key];
      }
    }
    return nextEnv;
  }

  function withAndroidRuntimeEnv(options) {
    if (!options || typeof options !== 'object' || !options.env || typeof options.env !== 'object') {
      return options;
    }
    return { ...options, env: mergeAndroidRuntimeEnv(options.env) };
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
    if (!isNpmCliProcess && !isNpmLifecycleProcess) {
      return null;
    }
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
    if (!isNpmCliProcess && !isNpmLifecycleProcess) {
      return command;
    }
    const cwd = options && typeof options.cwd === 'string' ? options.cwd : process.cwd();
    const prebuildInstall = findNodeModulesBin(cwd, 'prebuild-install');
    const nodeGypMessage = 'echo "node-gyp is not supported in SillyDroid Android runtime" >&2; exit 127';
    const commandBoundary = '(^|[\\s;&|()])';
    const commandEnd = '(?=$|[\\s;&|()])';
    return command
      .replace(
        new RegExp(`${commandBoundary}(npm)${commandEnd}`, 'g'),
        (_match, prefix) => npmCli ? `${prefix}${shellQuote(nodeBin)} ${shellQuote(npmCli)}` : _match
      )
      .replace(
        new RegExp(`${commandBoundary}(npx)${commandEnd}`, 'g'),
        (_match, prefix) => npxCli ? `${prefix}${shellQuote(nodeBin)} ${shellQuote(npxCli)}` : _match
      )
      .replace(
        new RegExp(`${commandBoundary}((?:[^\\s'";&|()]+/node_modules/\\.bin/)?prebuild-install)${commandEnd}`, 'g'),
        (_match, prefix, token) => `${prefix}${shellQuote(nodeBin)} ${shellQuote(token.includes('/node_modules/.bin/') ? token : prebuildInstall)}`
      )
      .replace(
        new RegExp(`${commandBoundary}((?:[^\\s'";&|()]+/node_modules/\\.bin/)?node-gyp)${commandEnd}`, 'g'),
        (_match, prefix) => `${prefix}/system/bin/sh -c ${shellQuote(nodeGypMessage)}`
      )
      .replace(
        new RegExp(`${commandBoundary}([A-Za-z0-9_.@-]+)${commandEnd}`, 'g'),
        (match, prefix, token) => {
          const binPath = findNodeModulesBin(cwd, token);
          return isJavascriptBin(binPath) ? `${prefix}${shellQuote(nodeBin)} ${shellQuote(binPath)}` : match;
        }
      );
  }

  function isShellCommand(command) {
    if (typeof command !== 'string' || command.length === 0) {
      return false;
    }
    return command === 'sh'
      || command === '/system/bin/sh'
      || command === shellBin
      || commandName(command) === 'sh'
      || commandName(command) === 'libtermux-sh.so';
  }

  function rewriteShellArgs(command, args, options) {
    if (!isShellCommand(command) || !Array.isArray(args)) {
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

  function withAndroidShell(options) {
    options = withAndroidRuntimeEnv(options);
    if (options && typeof options === 'object') {
      if (options.shell) {
        return options;
      }
      return { ...options, shell: shellBin };
    }
    return { shell: shellBin };
  }

  const originalSpawn = childProcess.spawn;
  childProcess.spawn = function patchedSpawn(command, args, options) {
    options = withAndroidRuntimeEnv(options);
    const rewritten = rewriteDirectCommand(command, args);
    if (rewritten) {
      return originalSpawn.call(this, rewritten.command, rewritten.args, options);
    }
    args = rewriteShellArgs(command, args, options);
    return originalSpawn.call(this, command, args, options);
  };

  const originalSpawnSync = childProcess.spawnSync;
  childProcess.spawnSync = function patchedSpawnSync(command, args, options) {
    options = withAndroidRuntimeEnv(options);
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
    options = withAndroidRuntimeEnv(options);
    const rewritten = rewriteDirectCommand(file, args);
    if (rewritten) {
      return originalExecFile.call(this, rewritten.command, rewritten.args, options, callback);
    }
    args = rewriteShellArgs(file, args, options);
    return originalExecFile.call(this, file, args, options, callback);
  };

  const originalExecFileSync = childProcess.execFileSync;
  childProcess.execFileSync = function patchedExecFileSync(file, args, options) {
    options = withAndroidRuntimeEnv(options);
    const rewritten = rewriteDirectCommand(file, args);
    if (rewritten) {
      return originalExecFileSync.call(this, rewritten.command, rewritten.args, options);
    }
    args = rewriteShellArgs(file, args, options);
    return originalExecFileSync.call(this, file, args, options);
  };

  const originalExec = childProcess.exec;
  childProcess.exec = function patchedExec(command, options, callback) {
    if (typeof options === 'function') {
      callback = options;
      options = undefined;
    }
    options = withAndroidRuntimeEnv(options);
    const nextCommand = rewriteShellCommand(command, options);
    return originalExec.call(this, nextCommand, withAndroidShell(options), callback);
  };

  const originalExecSync = childProcess.execSync;
  childProcess.execSync = function patchedExecSync(command, options) {
    options = withAndroidRuntimeEnv(options);
    const nextCommand = rewriteShellCommand(command, options);
    return originalExecSync.call(this, nextCommand, withAndroidShell(options));
  };
})();
