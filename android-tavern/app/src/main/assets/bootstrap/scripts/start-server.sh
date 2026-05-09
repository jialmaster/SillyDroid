#!/system/bin/sh
set -eu

ROOTFS_DIR="${ROOTFS_DIR:?ROOTFS_DIR is required}"
SERVER_DIR="${SERVER_DIR:?SERVER_DIR is required}"
APP_DATA_ROOT="${APP_DATA_ROOT:?APP_DATA_ROOT is required}"
LOGS_DIR="${LOGS_DIR:?LOGS_DIR is required}"
TAVERN_PORT="${TAVERN_PORT:?TAVERN_PORT is required}"
HOST_PREFIX_DIR="${HOST_PREFIX_DIR:?HOST_PREFIX_DIR is required}"
HOST_RUNTIME_PREFIX="${HOST_RUNTIME_PREFIX:-/data/data/com.termux/files/usr}"

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

if [ -d /linkerconfig ] || [ -f /linkerconfig/ld.config.txt ]; then
	HAS_LINKERCONFIG_BIND='1'
fi

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
assert_file "$SERVER_DIR/tavern-entrypoint.sh" "缺少 Tavern 服务入口：$SERVER_DIR/tavern-entrypoint.sh"

mkdir -p "$APP_DATA_ROOT" "$LOGS_DIR" "$PROOT_TMP_DIR"
mkdir -p "$LINUX_FS_DIR$HOST_RUNTIME_PREFIX"
chmod 1777 "$PROOT_TMP_DIR"

if [ -d "$PROOT_LIB_DIR" ]; then
	export LD_LIBRARY_PATH="$PROOT_LIB_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi

export PROOT_LOADER="$PROOT_LOADER_PATH"
if [ -n "$PROOT_LOADER_32_PATH" ]; then
	assert_file "$PROOT_LOADER_32_PATH" "缺少 host proot loader32：$PROOT_LOADER_32_PATH"
	export PROOT_LOADER_32="$PROOT_LOADER_32_PATH"
fi

export PROOT_TMP_DIR
export TAVERN_PORT
export TAVERN_DATA_ROOT="$DATA_MOUNT"
export TMPDIR=/tmp
export TMP=/tmp
export TEMP=/tmp
export HOME=/tmp
export PATH="$GUEST_PATH"
export PREFIX="$HOST_RUNTIME_PREFIX"

exec "$PROOT_BIN" -r "$LINUX_FS_DIR" \
	-b /dev \
	-b /proc \
	-b /sys \
	-b /system \
	-b /apex \
	-b /vendor \
	${HAS_LINKERCONFIG_BIND:+-b /linkerconfig} \
	-b "$PROOT_TMP_DIR:/tmp" \
	-b "$HOST_PREFIX_DIR:$HOST_RUNTIME_PREFIX" \
	-b "$ANDROID_RESOLV_CONF:/etc/resolv.conf" \
	-b "$SERVER_DIR:$SERVER_MOUNT" \
	-b "$APP_DATA_ROOT:$DATA_MOUNT" \
	-b "$LOGS_DIR:$LOGS_MOUNT" \
	-w "$SERVER_MOUNT" \
	/tavern/server/tavern-entrypoint.sh