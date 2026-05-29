#!/system/bin/sh
# 设置页终端必须进入和酒馆服务相同的 Termux host runtime，
# 避免用户在终端中看到的 PATH、Git、证书和服务运行环境分叉。
set -eu

BOOTSTRAP_ROOT="${BOOTSTRAP_ROOT:?BOOTSTRAP_ROOT is required}"
SERVER_DIR="${SERVER_DIR:?SERVER_DIR is required}"
APP_DATA_ROOT="${APP_DATA_ROOT:?APP_DATA_ROOT is required}"
LOGS_DIR="${LOGS_DIR:?LOGS_DIR is required}"
HOST_PREFIX_DIR="${HOST_PREFIX_DIR:?HOST_PREFIX_DIR is required}"
TERMUX_NODE_BIN="${TERMUX_NODE_BIN:?TERMUX_NODE_BIN is required}"
TERMUX_GIT_BIN="${TERMUX_GIT_BIN:?TERMUX_GIT_BIN is required}"
TERMUX_GIT_REMOTE_HTTP_BIN="${TERMUX_GIT_REMOTE_HTTP_BIN:?TERMUX_GIT_REMOTE_HTTP_BIN is required}"
TERMUX_SH_BIN="${TERMUX_SH_BIN:?TERMUX_SH_BIN is required}"
TERMUX_BASH_BIN="${TERMUX_BASH_BIN:-}"
HOST_NATIVE_LIB_DIR="${HOST_NATIVE_LIB_DIR:?HOST_NATIVE_LIB_DIR is required}"
HOST_TMP_DIR="${HOST_TMP_DIR:?HOST_TMP_DIR is required}"
SILLYDROID_CONSOLE_PROMPT="${SILLYDROID_CONSOLE_PROMPT:-\$PWD > }"
TERM="${TERM:-xterm-256color}"
COLORTERM="${COLORTERM:-truecolor}"

. "$BOOTSTRAP_ROOT/scripts/termux-host-runtime.sh"

if [ -n "$TERMUX_BASH_BIN" ] && [ -f "$TERMUX_BASH_BIN" ]; then
	TERMUX_CONSOLE_SHELL="$TERMUX_BASH_BIN"
else
	TERMUX_CONSOLE_SHELL="$TERMUX_SH_BIN"
fi

assert_dir "$SERVER_DIR" "缺少 Tavern 服务目录：$SERVER_DIR"
assert_dir "$HOST_PREFIX_DIR" "缺少 host prefix 目录：$HOST_PREFIX_DIR"
assert_dir "$HOST_NATIVE_LIB_DIR" "缺少 host native lib 目录：$HOST_NATIVE_LIB_DIR"
assert_file "$TERMUX_NODE_BIN" "缺少 Termux Node 入口：$TERMUX_NODE_BIN"
assert_file "$TERMUX_GIT_BIN" "缺少 Termux Git 入口：$TERMUX_GIT_BIN"
assert_file "$TERMUX_GIT_REMOTE_HTTP_BIN" "缺少 Termux Git HTTPS helper 入口：$TERMUX_GIT_REMOTE_HTTP_BIN"
assert_file "$TERMUX_CONSOLE_SHELL" "缺少 Termux shell 入口：$TERMUX_CONSOLE_SHELL"

mkdir -p "$APP_DATA_ROOT/.sillydroid-terminal-home" "$LOGS_DIR" "$HOST_TMP_DIR"
prepare_termux_host_runtime
export TERM
export COLORTERM
export HOME="$APP_DATA_ROOT/.sillydroid-terminal-home"
export SHELL="$TERMUX_CONSOLE_SHELL"
export PS1="$SILLYDROID_CONSOLE_PROMPT"
cd "$SERVER_DIR"

exec "$TERMUX_CONSOLE_SHELL" -i
