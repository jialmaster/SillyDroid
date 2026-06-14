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
SILLYDROID_CONSOLE_WORKDIR="${SILLYDROID_CONSOLE_WORKDIR:-/tavern/server}"
SILLYDROID_CONSOLE_HOME="${SILLYDROID_CONSOLE_HOME:-/tavern/data/.sillydroid-terminal-home}"
SILLYDROID_CONSOLE_PROMPT="${SILLYDROID_CONSOLE_PROMPT:-\$(sillydroid_prompt_path) > }"
SERVER_DIR_ALIAS="${SERVER_DIR}"
APP_DATA_ROOT_ALIAS="${APP_DATA_ROOT}"
HOME_ALIAS="${APP_DATA_ROOT_ALIAS}/.sillydroid-terminal-home"
case "$SERVER_DIR_ALIAS" in
	/data/user/0/*) SERVER_DIR_ALIAS="/data/data/${SERVER_DIR_ALIAS#/data/user/0/}" ;;
esac
case "$APP_DATA_ROOT_ALIAS" in
	/data/user/0/*) APP_DATA_ROOT_ALIAS="/data/data/${APP_DATA_ROOT_ALIAS#/data/user/0/}" ;;
esac
case "$HOME_ALIAS" in
	/data/user/0/*) HOME_ALIAS="/data/data/${HOME_ALIAS#/data/user/0/}" ;;
esac
export SILLYDROID_CONSOLE_WORKDIR
export SILLYDROID_CONSOLE_HOME
export SILLYDROID_CONSOLE_PROMPT
export SERVER_DIR_ALIAS
export APP_DATA_ROOT_ALIAS
export HOME_ALIAS
export BOOTSTRAP_ROOT
export SERVER_DIR
export APP_DATA_ROOT
export LOGS_DIR
export HOST_PREFIX_DIR
export HOST_NATIVE_LIB_DIR
export HOST_TMP_DIR
export TERMUX_NODE_BIN
export TERMUX_GIT_BIN
export TERMUX_GIT_REMOTE_HTTP_BIN
export TERMUX_SH_BIN
export TERMUX_BASH_BIN
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
if [ -d "$HOST_PREFIX_DIR/share/terminfo" ]; then
	export TERMINFO="$HOST_PREFIX_DIR/share/terminfo"
	export TERMINFO_DIRS="$HOST_PREFIX_DIR/share/terminfo${TERMINFO_DIRS:+:$TERMINFO_DIRS}"
fi
if [ -f "$HOST_PREFIX_DIR/etc/inputrc" ]; then
	cat > "$HOME/.sillydroid-inputrc" <<EOF
\$include $HOST_PREFIX_DIR/etc/inputrc
set horizontal-scroll-mode off
EOF
else
	cat > "$HOME/.sillydroid-inputrc" <<'EOF'
set horizontal-scroll-mode off
EOF
fi
export INPUTRC="$HOME/.sillydroid-inputrc"
cd "$SERVER_DIR"

# 设置页终端必须显式恢复 DEC 自动换行；某些复用会话/错误 terminfo 状态下会退成横向滚动，
# 表现为长粘贴只看到末尾，逐字删除时前面的内容才重新出现。
printf '\033[?7h'

if [ "$TERMUX_CONSOLE_SHELL" = "$TERMUX_BASH_BIN" ]; then
	cat > "$HOME/.sillydroid-bashrc" <<'EOF'
sillydroid_prompt_path() {
	case "$PWD" in
		"$SERVER_DIR") printf '%s' "$SILLYDROID_CONSOLE_WORKDIR" ;;
		"$SERVER_DIR"/*) printf '%s/%s' "$SILLYDROID_CONSOLE_WORKDIR" "${PWD#"$SERVER_DIR"/}" ;;
		"$SERVER_DIR_ALIAS") printf '%s' "$SILLYDROID_CONSOLE_WORKDIR" ;;
		"$SERVER_DIR_ALIAS"/*) printf '%s/%s' "$SILLYDROID_CONSOLE_WORKDIR" "${PWD#"$SERVER_DIR_ALIAS"/}" ;;
		"$APP_DATA_ROOT") printf '%s' "/tavern/data" ;;
		"$APP_DATA_ROOT"/*) printf '%s/%s' "/tavern/data" "${PWD#"$APP_DATA_ROOT"/}" ;;
		"$APP_DATA_ROOT_ALIAS") printf '%s' "/tavern/data" ;;
		"$APP_DATA_ROOT_ALIAS"/*) printf '%s/%s' "/tavern/data" "${PWD#"$APP_DATA_ROOT_ALIAS"/}" ;;
		"$HOME") printf '%s' "$SILLYDROID_CONSOLE_HOME" ;;
		"$HOME"/*) printf '%s/%s' "$SILLYDROID_CONSOLE_HOME" "${PWD#"$HOME"/}" ;;
		"$HOME_ALIAS") printf '%s' "$SILLYDROID_CONSOLE_HOME" ;;
		"$HOME_ALIAS"/*) printf '%s/%s' "$SILLYDROID_CONSOLE_HOME" "${PWD#"$HOME_ALIAS"/}" ;;
		*) printf '%s' "$PWD" ;;
	esac
}

if [ -r "$HOST_PREFIX_DIR/etc/bash.bashrc" ]; then
	. "$HOST_PREFIX_DIR/etc/bash.bashrc"
fi

bind 'set horizontal-scroll-mode off' 2>/dev/null || true
printf '\033[?7h'
export PS1="$SILLYDROID_CONSOLE_PROMPT"
EOF
	exec "$TERMUX_CONSOLE_SHELL" --rcfile "$HOME/.sillydroid-bashrc" -i
fi

printf '\033[?7h'
export PS1="$SILLYDROID_CONSOLE_WORKDIR > "
exec "$TERMUX_CONSOLE_SHELL" -i
