#!/system/bin/sh
set -eu

ROOTFS_DIR="${ROOTFS_DIR:?ROOTFS_DIR is required}"
LOGS_DIR="${LOGS_DIR:?LOGS_DIR is required}"
HOST_PREFIX_DIR="${HOST_PREFIX_DIR:?HOST_PREFIX_DIR is required}"
HOST_RUNTIME_PREFIX="${HOST_RUNTIME_PREFIX:-/data/data/com.termux/files/usr}"

PROOT_BIN="${HOST_PROOT_BIN:?HOST_PROOT_BIN is required}"
PROOT_LIB_DIR="${HOST_PROOT_LIB_DIR:?HOST_PROOT_LIB_DIR is required}"
PROOT_LOADER_PATH="${HOST_PROOT_LOADER:?HOST_PROOT_LOADER is required}"
PROOT_LOADER_32_PATH="${HOST_PROOT_LOADER_32:-}"
LINUX_FS_DIR="$ROOTFS_DIR/fs"
PROOT_TMP_DIR="${HOST_TMP_DIR:?HOST_TMP_DIR is required}"
MANIFEST_PATH="$ROOTFS_DIR/rootfs-manifest.json"
GUEST_BASE_PATH="/usr/sbin:/usr/bin:/sbin:/bin"
GUEST_PATH="$HOST_RUNTIME_PREFIX/bin:$GUEST_BASE_PATH"
HAS_LINKERCONFIG_BIND=''

if [ -f /linkerconfig/ld.config.txt ]; then
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

assert_guest_file() {
	if [ ! -e "$LINUX_FS_DIR$1" ]; then
		echo "$2" >&2
		exit 1
	fi
}

read_manifest_string() {
	key="$1"
	if [ ! -f "$MANIFEST_PATH" ]; then
		return 0
	fi
	sed -n "s/^[[:space:]]*\"$key\":[[:space:]]*\"\([^\"]*\)\"[,]\{0,1\}[[:space:]]*$/\1/p" "$MANIFEST_PATH" | head -n 1
}

run_in_rootfs() {
	HOME=/tmp PATH="$GUEST_PATH" "$PROOT_BIN" -r "$LINUX_FS_DIR" \
		-b /dev \
		-b /proc \
		-b /sys \
		-b /system \
		-b /apex \
		-b /vendor \
		${HAS_LINKERCONFIG_BIND:+-b /linkerconfig/ld.config.txt:/linkerconfig/ld.config.txt} \
		-b "$PROOT_TMP_DIR:/tmp" \
		-b "$HOST_PREFIX_DIR:$HOST_RUNTIME_PREFIX" \
		-w / \
		"$@"
}

assert_file "$PROOT_BIN" "缺少 proot：$PROOT_BIN"
assert_dir "$PROOT_LIB_DIR" "缺少 host proot 依赖目录：$PROOT_LIB_DIR"
assert_file "$PROOT_LOADER_PATH" "缺少 host proot loader：$PROOT_LOADER_PATH"
assert_dir "$LINUX_FS_DIR" "缺少 Linux rootfs：$LINUX_FS_DIR"
assert_dir "$HOST_PREFIX_DIR" "缺少 host prefix 目录：$HOST_PREFIX_DIR"
assert_file "$MANIFEST_PATH" "缺少 rootfs manifest：$MANIFEST_PATH"

mkdir -p "$LOGS_DIR" "$PROOT_TMP_DIR"
mkdir -p "$LINUX_FS_DIR$HOST_RUNTIME_PREFIX" "$LINUX_FS_DIR/linkerconfig"
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
export PREFIX="$HOST_RUNTIME_PREFIX"

guest_shell_path="$(read_manifest_string guestShellPath)"
if [ -z "$guest_shell_path" ]; then
	guest_shell_path="/bin/sh"
fi

guest_ca_bundle_path="$(read_manifest_string guestCaBundlePath)"
if [ -z "$guest_ca_bundle_path" ]; then
	guest_ca_bundle_path="/etc/ssl/certs/ca-certificates.crt"
fi

assert_guest_file "$guest_shell_path" "缺少 guest shell：$guest_shell_path"
assert_guest_file "$guest_ca_bundle_path" "缺少 CA 证书 bundle：$guest_ca_bundle_path。"

run_in_rootfs "$guest_shell_path" -c 'echo runtime-exec-ok' >/dev/null
echo "Linux runtime already preloaded."