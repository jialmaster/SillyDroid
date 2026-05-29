#!/system/bin/sh
set -eu

ROOTFS_DIR="${ROOTFS_DIR:?ROOTFS_DIR is required}"
BOOTSTRAP_ROOT="${BOOTSTRAP_ROOT:?BOOTSTRAP_ROOT is required}"
SERVER_DIR="${SERVER_DIR:?SERVER_DIR is required}"
APP_DATA_ROOT="${APP_DATA_ROOT:?APP_DATA_ROOT is required}"
LOGS_DIR="${LOGS_DIR:?LOGS_DIR is required}"
TAVERN_PORT="${TAVERN_PORT:?TAVERN_PORT is required}"
HOST_PREFIX_DIR="${HOST_PREFIX_DIR:?HOST_PREFIX_DIR is required}"
HOST_RUNTIME_PREFIX="${HOST_RUNTIME_PREFIX:-/data/data/com.termux/files/usr}"
TERMUX_NODE_BIN="${TERMUX_NODE_BIN:?TERMUX_NODE_BIN is required}"
TERMUX_GIT_BIN="${TERMUX_GIT_BIN:?TERMUX_GIT_BIN is required}"
TERMUX_GIT_REMOTE_HTTP_BIN="${TERMUX_GIT_REMOTE_HTTP_BIN:?TERMUX_GIT_REMOTE_HTTP_BIN is required}"
TERMUX_SH_BIN="${TERMUX_SH_BIN:?TERMUX_SH_BIN is required}"
TERMUX_BASH_BIN="${TERMUX_BASH_BIN:-}"
HOST_NATIVE_LIB_DIR="${HOST_NATIVE_LIB_DIR:?HOST_NATIVE_LIB_DIR is required}"
HOST_TMP_DIR="${HOST_TMP_DIR:?HOST_TMP_DIR is required}"

. "$BOOTSTRAP_ROOT/scripts/termux-host-runtime.sh"

LINUX_FS_DIR="$ROOTFS_DIR/fs"
ANDROID_RESOLV_CONF="$ROOTFS_DIR/android-resolv.conf"

assert_dir "$LINUX_FS_DIR" "缺少 Linux rootfs：$LINUX_FS_DIR"
assert_dir "$HOST_PREFIX_DIR" "缺少 host prefix 目录：$HOST_PREFIX_DIR"
assert_file "$ANDROID_RESOLV_CONF" "缺少 Android DNS 配置：$ANDROID_RESOLV_CONF"
assert_file "$SERVER_DIR/tavern-entrypoint.sh" "缺少 Tavern 服务入口：$SERVER_DIR/tavern-entrypoint.sh"

mkdir -p "$APP_DATA_ROOT" "$LOGS_DIR" "$HOST_TMP_DIR"
prepare_termux_host_runtime
export TAVERN_PORT
export TAVERN_DATA_ROOT="$APP_DATA_ROOT"
export TAVERN_NODE_BIN="$TERMUX_NODE_BIN"
export HOME="$APP_DATA_ROOT/.termux-home"
export TERMUX_NODE_BIN
export TERMUX_GIT_BIN
mkdir -p "$HOME" "$TMPDIR"
cd "$SERVER_DIR"
exec "$TERMUX_SH_BIN" "$SERVER_DIR/tavern-entrypoint.sh"
