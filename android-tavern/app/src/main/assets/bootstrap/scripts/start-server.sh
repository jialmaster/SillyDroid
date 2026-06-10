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
# V8 老生代堆上限（MB）：0/未设表示自动，由 tavern-entrypoint.sh 决定是否注入 --max-old-space-size。
TAVERN_NODE_MAX_OLD_SPACE_MB="${TAVERN_NODE_MAX_OLD_SPACE_MB:-0}"
# V8 新生代 semi-space 上限（MB）：0/未设表示自动，由 tavern-entrypoint.sh 决定是否注入 --max-semi-space-size。
TAVERN_NODE_MAX_SEMI_SPACE_MB="${TAVERN_NODE_MAX_SEMI_SPACE_MB:-0}"
SILLYDROID_TAVERN_PATCH_PRESET="${SILLYDROID_TAVERN_PATCH_PRESET:-off}"
SILLYDROID_TAVERN_PATCH_SETTINGS="${SILLYDROID_TAVERN_PATCH_SETTINGS:-}"
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
# 透传 V8 堆上限给 tavern-entrypoint.sh；宿主 ProcessBuilder 注入后必须显式 export，
# 否则 exec 出去的入口脚本拿不到这个值。
export TAVERN_NODE_MAX_OLD_SPACE_MB
export TAVERN_NODE_MAX_SEMI_SPACE_MB
case "$SILLYDROID_TAVERN_PATCH_PRESET" in
    ''|off|false|0|disabled)
        echo "sillydroid_runtime_patch event=launcher patch_requested=false patch_effective=false preset=off"
        ;;
    *)
        patch_loader="$BOOTSTRAP_ROOT/runtime-patches/loader.cjs"
        if [ -f "$patch_loader" ]; then
            NODE_OPTIONS="--require $patch_loader ${NODE_OPTIONS:-}"
            export NODE_OPTIONS
            export SILLYDROID_TAVERN_PATCH_PRESET
            export SILLYDROID_TAVERN_PATCH_SETTINGS
            echo "sillydroid_runtime_patch event=launcher patch_requested=true patch_effective=pending preset=$SILLYDROID_TAVERN_PATCH_PRESET loader=$patch_loader"
        else
            echo "sillydroid_runtime_patch event=launcher patch_requested=true patch_effective=false reason=loader_missing preset=$SILLYDROID_TAVERN_PATCH_PRESET loader=$patch_loader"
        fi
        ;;
esac
export HOME="$APP_DATA_ROOT/.termux-home"
export TERMUX_NODE_BIN
export TERMUX_GIT_BIN
mkdir -p "$HOME" "$TMPDIR"
cd "$SERVER_DIR"
exec "$TERMUX_SH_BIN" "$SERVER_DIR/tavern-entrypoint.sh"
