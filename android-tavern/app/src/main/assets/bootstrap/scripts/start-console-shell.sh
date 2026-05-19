#!/system/bin/sh
# 设置页终端必须懒连接到和宿主相同的 rootfs/proot 运行时，
# 不能退回 Android 系统 shell，也不能另外定义一套挂载契约，否则用户在终端里看到的环境会和酒馆真实运行环境分叉。
set -eu

ROOTFS_DIR="${ROOTFS_DIR:?ROOTFS_DIR is required}"
SERVER_DIR="${SERVER_DIR:?SERVER_DIR is required}"
APP_DATA_ROOT="${APP_DATA_ROOT:?APP_DATA_ROOT is required}"
LOGS_DIR="${LOGS_DIR:?LOGS_DIR is required}"
HOST_PREFIX_DIR="${HOST_PREFIX_DIR:?HOST_PREFIX_DIR is required}"
HOST_RUNTIME_PREFIX="${HOST_RUNTIME_PREFIX:-/data/data/com.termux/files/usr}"
GUEST_SHELL_PATH="${SILLYDROID_GUEST_SHELL_PATH:-/bin/sh}"
SILLYDROID_CONSOLE_HOME="${SILLYDROID_CONSOLE_HOME:-/tavern/data/.sillydroid-terminal-home}"
SILLYDROID_CONSOLE_WORKDIR="${SILLYDROID_CONSOLE_WORKDIR:-/tavern/server}"
SILLYDROID_CONSOLE_PROMPT="${SILLYDROID_CONSOLE_PROMPT:-\$PWD > }"
TERM="${TERM:-xterm-256color}"
COLORTERM="${COLORTERM:-truecolor}"

PROOT_BIN="${HOST_PROOT_BIN:?HOST_PROOT_BIN is required}"
PROOT_LIB_DIR="${HOST_PROOT_LIB_DIR:?HOST_PROOT_LIB_DIR is required}"
PROOT_LOADER_PATH="${HOST_PROOT_LOADER:?HOST_PROOT_LOADER is required}"
PROOT_LOADER_32_PATH="${HOST_PROOT_LOADER_32:-}"
LINUX_FS_DIR="$ROOTFS_DIR/fs"
PROOT_TMP_DIR="${HOST_TMP_DIR:?HOST_TMP_DIR is required}"
ANDROID_RESOLV_CONF="$ROOTFS_DIR/android-resolv.conf"
SERVER_MOUNT="/tavern/server"
DATA_MOUNT="/tavern/data"
LOGS_MOUNT="/tavern/logs"
GUEST_BASE_PATH="/usr/sbin:/usr/bin:/sbin:/bin"
GUEST_PATH="$HOST_RUNTIME_PREFIX/bin:$GUEST_BASE_PATH"
HAS_LINKERCONFIG_BIND=''

assert_file() {
	if [ ! -f "$1" ]; then
		echo "$2" >&2
		exit 1
	fi
}

assert_dir() {
	if [ ! -d "$1" ]; then
		echo "$2" >&2
		exit 1
	fi
}

assert_file "$PROOT_BIN" "缺少 proot：$PROOT_BIN"
assert_dir "$PROOT_LIB_DIR" "缺少 host proot 依赖目录：$PROOT_LIB_DIR"
assert_file "$PROOT_LOADER_PATH" "缺少 host proot loader：$PROOT_LOADER_PATH"
assert_dir "$LINUX_FS_DIR" "缺少 Linux rootfs：$LINUX_FS_DIR"
assert_dir "$HOST_PREFIX_DIR" "缺少 host prefix 目录：$HOST_PREFIX_DIR"
assert_file "$ANDROID_RESOLV_CONF" "缺少 Android DNS 配置：$ANDROID_RESOLV_CONF"
assert_dir "$SERVER_DIR" "缺少 Tavern 服务目录：$SERVER_DIR"

if [ -f /linkerconfig/ld.config.txt ]; then
	HAS_LINKERCONFIG_BIND='1'
fi

mkdir -p "$APP_DATA_ROOT" "$LOGS_DIR" "$PROOT_TMP_DIR"
mkdir -p "$APP_DATA_ROOT/.sillydroid-terminal-home"
mkdir -p "$LINUX_FS_DIR$HOST_RUNTIME_PREFIX" "$LINUX_FS_DIR/linkerconfig"
chmod 1777 "$PROOT_TMP_DIR"

CONSOLE_RC_FILE_HOST="$APP_DATA_ROOT/.sillydroid-terminal-home/.sillydroid-console-rc"
CONSOLE_RC_FILE_GUEST="$SILLYDROID_CONSOLE_HOME/.sillydroid-console-rc"
TERMUX_COMPAT_TAVERN_LINK_HOST="$APP_DATA_ROOT/.sillydroid-terminal-home/SillyTavern"
TERMUX_COMPAT_TAVERN_LINK_GUEST="$SILLYDROID_CONSOLE_HOME/SillyTavern"
# Android /system/bin/sh 处理 here-doc 时会尝试在 /data/local 下创建临时文件；
# 应用 UID 对该目录没有写权限。如果这里退回 cat <<EOF，rc 文件会被先截断成 0 字节，
# 然后整个 console shell 直接 exit 1，首屏既没有 prompt，也无法进入真实交互 shell。
{
	printf '%s\n' '# 终端 prompt 不能依赖不同 sh 实现各自的默认值；'
	printf '%s\n' '# 这里显式写入交互 rc，保证进入终端时就能看到当前路径语义，而不是空白黑框。'
	printf '%s\n' 'if [ -n "${SILLYDROID_CONSOLE_WORKDIR:-}" ]; then'
	printf '%s\n' '	cd "$SILLYDROID_CONSOLE_WORKDIR"'
	printf '%s\n' 'fi'
	printf "%s\n" "PS1='$SILLYDROID_CONSOLE_PROMPT'"
	printf '%s\n' 'export PS1'
} > "$CONSOLE_RC_FILE_HOST"
chmod 600 "$CONSOLE_RC_FILE_HOST"

# 终端真实服务目录仍然固定挂载在 /tavern/server；
# 这里只额外补一个 ~/SillyTavern 软链接，兼容依赖 Termux 常见目录约定的脚本，
# 不能把它理解成真实安装路径迁移，否则宿主运行时和终端看到的目录语义会再次分叉。
if ! ln -sfn "$SERVER_MOUNT" "$TERMUX_COMPAT_TAVERN_LINK_HOST"; then
	echo "创建 Termux 兼容软链接失败：$TERMUX_COMPAT_TAVERN_LINK_GUEST -> $SERVER_MOUNT" >&2
	exit 1
fi

if [ -d "$PROOT_LIB_DIR" ]; then
	export LD_LIBRARY_PATH="$PROOT_LIB_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi

export PROOT_LOADER="$PROOT_LOADER_PATH"
if [ -n "$PROOT_LOADER_32_PATH" ]; then
	assert_file "$PROOT_LOADER_32_PATH" "缺少 host proot loader32：$PROOT_LOADER_32_PATH"
	export PROOT_LOADER_32="$PROOT_LOADER_32_PATH"
fi

export PROOT_TMP_DIR
export TMPDIR=/tmp
export TMP=/tmp
export TEMP=/tmp
export PREFIX="$HOST_RUNTIME_PREFIX"
export PATH="$GUEST_PATH"
export TERM
export COLORTERM
export HOME="$SILLYDROID_CONSOLE_HOME"
export SHELL="$GUEST_SHELL_PATH"
export PS1="$SILLYDROID_CONSOLE_PROMPT"

# 设置页终端必须以 interactive shell 进入相同 rootfs/proot 契约，
# 否则不会稳定显示 prompt/路径，也拿不到 shell 自身的历史与交互语义，用户看到的就不是真终端。
if [ "$(basename "$GUEST_SHELL_PATH")" = "bash" ]; then
	exec "$PROOT_BIN" -r "$LINUX_FS_DIR" \
		-b /dev \
		-b /proc \
		-b /sys \
		-b /system \
		-b /apex \
		-b /vendor \
		${HAS_LINKERCONFIG_BIND:+-b /linkerconfig/ld.config.txt:/linkerconfig/ld.config.txt} \
		-b "$PROOT_TMP_DIR:/tmp" \
		-b "$HOST_PREFIX_DIR:$HOST_RUNTIME_PREFIX" \
		-b "$ANDROID_RESOLV_CONF:/etc/resolv.conf" \
		-b "$SERVER_DIR:$SERVER_MOUNT" \
		-b "$APP_DATA_ROOT:$DATA_MOUNT" \
		-b "$LOGS_DIR:$LOGS_MOUNT" \
		-w "$SILLYDROID_CONSOLE_WORKDIR" \
		"$GUEST_SHELL_PATH" --rcfile "$CONSOLE_RC_FILE_GUEST" -i
fi

export ENV="$CONSOLE_RC_FILE_GUEST"
exec "$PROOT_BIN" -r "$LINUX_FS_DIR" \
	-b /dev \
	-b /proc \
	-b /sys \
	-b /system \
	-b /apex \
	-b /vendor \
	${HAS_LINKERCONFIG_BIND:+-b /linkerconfig/ld.config.txt:/linkerconfig/ld.config.txt} \
	-b "$PROOT_TMP_DIR:/tmp" \
	-b "$HOST_PREFIX_DIR:$HOST_RUNTIME_PREFIX" \
	-b "$ANDROID_RESOLV_CONF:/etc/resolv.conf" \
	-b "$SERVER_DIR:$SERVER_MOUNT" \
	-b "$APP_DATA_ROOT:$DATA_MOUNT" \
	-b "$LOGS_DIR:$LOGS_MOUNT" \
	-w "$SILLYDROID_CONSOLE_WORKDIR" \
	"$GUEST_SHELL_PATH" -i
