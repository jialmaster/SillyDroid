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
TERMUX_CURL_BIN="${TERMUX_CURL_BIN:?TERMUX_CURL_BIN is required}"
TERMUX_SH_BIN="${TERMUX_SH_BIN:?TERMUX_SH_BIN is required}"
TERMUX_BASH_BIN="${TERMUX_BASH_BIN:-}"
HOST_NATIVE_LIB_DIR="${HOST_NATIVE_LIB_DIR:?HOST_NATIVE_LIB_DIR is required}"
HOST_TMP_DIR="${HOST_TMP_DIR:?HOST_TMP_DIR is required}"
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
export TERMUX_CURL_BIN
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
assert_file "$TERMUX_CURL_BIN" "缺少 Termux curl 入口：$TERMUX_CURL_BIN"
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
sillydroid_canonical_path() {
	(cd "$1" 2>/dev/null && pwd -P) || printf '%s' "$1"
}

sillydroid_normalize_android_data_path() {
	case "$1" in
		/data/user/0/*) printf '/data/data/%s' "${1#/data/user/0/}" ;;
		*) printf '%s' "$1" ;;
	esac
}

sillydroid_format_path_alias() {
	case "$1" in
		"$2") printf '%s' "$3" ;;
		"$2"/*) printf '%s/%s' "$3" "${1#"$2"/}" ;;
		*) return 1 ;;
	esac
}

sillydroid_prompt_path() {
	local current_path current_real_path current_normal_path current_normal_real_path
	local server_normal_path server_normal_real_path data_normal_path data_normal_real_path
	local home_normal_path home_normal_real_path
	current_path="$PWD"
	current_real_path="$(sillydroid_canonical_path "$PWD")"
	current_normal_path="$(sillydroid_normalize_android_data_path "$current_path")"
	current_normal_real_path="$(sillydroid_normalize_android_data_path "$current_real_path")"
	server_normal_path="$(sillydroid_normalize_android_data_path "$SERVER_DIR")"
	server_normal_real_path="$(sillydroid_normalize_android_data_path "$(sillydroid_canonical_path "$SERVER_DIR")")"
	data_normal_path="$(sillydroid_normalize_android_data_path "$APP_DATA_ROOT")"
	data_normal_real_path="$(sillydroid_normalize_android_data_path "$(sillydroid_canonical_path "$APP_DATA_ROOT")")"
	home_normal_path="$(sillydroid_normalize_android_data_path "$HOME")"
	home_normal_real_path="$(sillydroid_normalize_android_data_path "$(sillydroid_canonical_path "$HOME")")"

	sillydroid_format_path_alias "$current_path" "$SERVER_DIR" "sillyTavern" && return
	sillydroid_format_path_alias "$current_normal_path" "$server_normal_path" "sillyTavern" && return
	sillydroid_format_path_alias "$current_normal_real_path" "$server_normal_real_path" "sillyTavern" && return
	sillydroid_format_path_alias "$current_path" "$APP_DATA_ROOT" "/tavern/data" && return
	sillydroid_format_path_alias "$current_normal_path" "$data_normal_path" "/tavern/data" && return
	sillydroid_format_path_alias "$current_normal_real_path" "$data_normal_real_path" "/tavern/data" && return
	sillydroid_format_path_alias "$current_path" "$HOME" "/tavern/data/.sillydroid-terminal-home" && return
	sillydroid_format_path_alias "$current_normal_path" "$home_normal_path" "/tavern/data/.sillydroid-terminal-home" && return
	sillydroid_format_path_alias "$current_normal_real_path" "$home_normal_real_path" "/tavern/data/.sillydroid-terminal-home" && return
	printf '%s' "$current_path"
}

sillydroid_resolve_console_path() {
	case "$1" in
		sillyTavern) printf '%s' "$SERVER_DIR" ;;
		sillyTavern/*) printf '%s/%s' "$SERVER_DIR" "${1#sillyTavern/}" ;;
		*) printf '%s' "$1" ;;
	esac
}

cd() {
	case "$#" in
		0) command cd "$HOME" ;;
		1) command cd "$(sillydroid_resolve_console_path "$1")" ;;
		*) command cd "$@" ;;
	esac
}

if [ -r "$HOST_PREFIX_DIR/etc/bash.bashrc" ]; then
	. "$HOST_PREFIX_DIR/etc/bash.bashrc"
fi

sillydroid_run_npm_cli() {
	local npm_cli="$HOST_PREFIX_DIR/lib/node_modules/npm/bin/npm-cli.js"
	if [ ! -r "$npm_cli" ]; then
		printf '缺少 npm CLI：%s\n' "$npm_cli" >&2
		return 127
	fi
	# 由 native node 读取 npm JS 入口，不从 app 私有可写目录直接 exec 脚本。
	PREFIX="$HOST_PREFIX_DIR" command "$TERMUX_NODE_BIN" "$npm_cli" "$@"
}

sillydroid_run_npx_cli() {
	local npx_cli="$HOST_PREFIX_DIR/lib/node_modules/npm/bin/npx-cli.js"
	if [ ! -r "$npx_cli" ]; then
		printf '缺少 npx CLI：%s\n' "$npx_cli" >&2
		return 127
	fi
	PREFIX="$HOST_PREFIX_DIR" command "$TERMUX_NODE_BIN" "$npx_cli" "$@"
}

npm() {
	sillydroid_run_npm_cli "$@"
}

npx() {
	sillydroid_run_npx_cli "$@"
}

export -f sillydroid_run_npm_cli sillydroid_run_npx_cli npm npx

bind 'set horizontal-scroll-mode off' 2>/dev/null || true
shopt -s checkwinsize 2>/dev/null || true
printf '\033[?7h'
export PS1='$(sillydroid_prompt_path) > '
EOF
	exec "$TERMUX_CONSOLE_SHELL" --rcfile "$HOME/.sillydroid-bashrc" -i
fi

printf '\033[?7h'
export PS1="sillyTavern > "
exec "$TERMUX_CONSOLE_SHELL" -i
