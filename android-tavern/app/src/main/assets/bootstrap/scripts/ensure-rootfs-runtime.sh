#!/system/bin/sh
set -eu

ROOTFS_DIR="${ROOTFS_DIR:?ROOTFS_DIR is required}"
BOOTSTRAP_ROOT="${BOOTSTRAP_ROOT:?BOOTSTRAP_ROOT is required}"
LOGS_DIR="${LOGS_DIR:?LOGS_DIR is required}"
HOST_PREFIX_DIR="${HOST_PREFIX_DIR:?HOST_PREFIX_DIR is required}"
HOST_RUNTIME_PREFIX="${HOST_RUNTIME_PREFIX:-/data/data/com.termux/files/usr}"
TERMUX_NODE_BIN="${TERMUX_NODE_BIN:?TERMUX_NODE_BIN is required}"
TERMUX_GIT_BIN="${TERMUX_GIT_BIN:?TERMUX_GIT_BIN is required}"
TERMUX_GIT_REMOTE_HTTP_BIN="${TERMUX_GIT_REMOTE_HTTP_BIN:?TERMUX_GIT_REMOTE_HTTP_BIN is required}"
TERMUX_CURL_BIN="${TERMUX_CURL_BIN:?TERMUX_CURL_BIN is required}"
TERMUX_SH_BIN="${TERMUX_SH_BIN:?TERMUX_SH_BIN is required}"
HOST_NATIVE_LIB_DIR="${HOST_NATIVE_LIB_DIR:?HOST_NATIVE_LIB_DIR is required}"
HOST_TMP_DIR="${HOST_TMP_DIR:?HOST_TMP_DIR is required}"

. "$BOOTSTRAP_ROOT/scripts/termux-host-runtime.sh"

LINUX_FS_DIR="$ROOTFS_DIR/fs"
MANIFEST_PATH="$ROOTFS_DIR/rootfs-manifest.json"

assert_dir "$LINUX_FS_DIR" "缺少 Linux rootfs：$LINUX_FS_DIR"
assert_dir "$HOST_PREFIX_DIR" "缺少 host prefix 目录：$HOST_PREFIX_DIR"
assert_file "$MANIFEST_PATH" "缺少 rootfs manifest：$MANIFEST_PATH"

mkdir -p "$LOGS_DIR" "$HOST_TMP_DIR"
prepare_termux_host_runtime
export HOME="$HOST_PREFIX_DIR/tmp"
mkdir -p "$HOME" "$TMPDIR"
"$TERMUX_NODE_BIN" --version >/dev/null
"$TERMUX_GIT_BIN" --version >/dev/null
"$TERMUX_CURL_BIN" --version >/dev/null
# git-remote-http 是 Git remote helper，不支持 --version；用 capabilities 握手校验 helper 可启动且不触网。
printf 'capabilities\n\n' | "$TERMUX_GIT_REMOTE_HTTP_BIN" origin https://example.invalid >/dev/null
"$TERMUX_SH_BIN" -c 'echo runtime-exec-ok' >/dev/null
echo "Termux host runtime already preloaded."
