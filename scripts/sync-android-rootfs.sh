#!/usr/bin/env bash
set -euo pipefail

termux_packages_index_url='https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages'

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workspace_root="$(cd "$script_dir/.." && pwd)"
build_config_path="$workspace_root/sillydroid-build-config.json"
termux_host_runtime_signature="$(sha256sum "$script_dir/sync-android-rootfs.sh" | awk '{print $1}')"
# 默认把 rootfs/jni 临时资产写入本地缓存目录，避免独立执行脚本时污染 android-tavern 工程目录。
target_root="${SILLYDROID_ANDROID_ROOTFS_TARGET_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/sillydroid-android-rootfs-assets/rootfs}"
jni_libs_root="${SILLYDROID_ANDROID_ROOTFS_JNI_LIBS_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/sillydroid-android-rootfs-assets/jniLibs/arm64-v8a}"
runtime_prefix='/data/data/com.jm.sillydroid/files/usr'
termux_guest_runtime_prefix='/data/data/com.termux/files/usr'
termux_prefix_shell_relative_path='bin/sh'
termux_prefix_bash_relative_path='bin/bash'
termux_prefix_dash_relative_path='bin/dash'
termux_prefix_env_relative_path='bin/env'
termux_prefix_ca_bundle_relative_path='etc/tls/cert.pem'
guest_shell_path='/bin/sh'
guest_ca_bundle_path='/etc/ssl/certs/ca-certificates.crt'

termux_bootstrap_packages=(
    bash
    coreutils
    findutils
    grep
    sed
    gawk
    tar
    gzip
    xz-utils
    which
    ca-certificates
    termux-tools
)

termux_base_packages=(
    git
    nodejs-lts
    curl
    npm
)

source "$workspace_root/scripts/android-build-common.sh"

read_termux_packages_from_config() {
    if ! command -v python3 >/dev/null 2>&1; then
        printf '%s\n' "${termux_base_packages[@]}"
        return
    fi

    python3 - "$build_config_path" <<'PY'
import json
import pathlib
import sys

config_path = pathlib.Path(sys.argv[1])
default = [
    "git",
    "nodejs-lts",
    "curl",
    "npm",
]

if not config_path.exists():
    print("\n".join(default))
    raise SystemExit(0)

try:
    data = json.loads(config_path.read_text(encoding="utf-8"))
except Exception:
    print("\n".join(default))
    raise SystemExit(0)

value = (((data or {}).get("build") or {}).get("termuxPackages"))
if not isinstance(value, list) or not value:
    print("\n".join(default))
    raise SystemExit(0)

items = []
seen = set()
for entry in value:
    if not isinstance(entry, str):
        continue
    name = entry.strip()
    if not name or name in seen:
        continue
    seen.add(name)
    items.append(name)

if not items:
    items = default

print("\n".join(items))
PY
}

declare -A termux_filename_by_package=()
declare -A termux_depends_by_package=()
declare -A termux_repo_by_package=()

usage() {
    cat <<'EOF'
Usage: sync-android-rootfs.sh [--target-root <path>]

说明：
- 默认把 rootfs/jni 临时资产写入本地缓存目录，不再直接写回 android-tavern 工程目录。
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --target-root)
            target_root="$2"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "Unsupported argument: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

mapfile -t configured_termux_base_packages < <(read_termux_packages_from_config)
merged_termux_packages=()
declare -A seen_termux_packages=()

for package_name in "${termux_bootstrap_packages[@]}" "${configured_termux_base_packages[@]}"; do
    [[ -n "$package_name" ]] || continue
    if [[ -n "${seen_termux_packages[$package_name]:-}" ]]; then
        continue
    fi
    seen_termux_packages["$package_name"]=1
    merged_termux_packages+=("$package_name")
done

termux_base_packages=("${merged_termux_packages[@]}")

assert_path_exists() {
    local path="$1"
    local message="$2"

    if [[ ! -e "$path" ]]; then
        echo "$message" >&2
        exit 1
    fi
}

json_escape() {
    local value="$1"
    value=${value//\\/\\\\}
    value=${value//"/\\"}
    value=${value//$'\n'/\\n}
    value=${value//$'\r'/\\r}
    value=${value//$'\t'/\\t}
    printf '%s' "$value"
}

download_file_if_missing() {
    local uri="$1"
    local destination_path="$2"

    sillydroid_download_file_if_missing "$uri" "$destination_path"
}

expand_deb_archive() {
    local package_path="$1"
    local destination_root="$2"
    local label="${3:-$(basename "$package_path") }"
    local member_count='0'
    local current_member='0'
    local member_name
    local tty='0'

    rm -rf "$destination_root"
    mkdir -p "$destination_root"

    if [[ -t 2 ]]; then
        tty='1'
    fi

    mapfile -t deb_members < <(ar t "$package_path")
    member_count="${#deb_members[@]}"
    for member_name in "${deb_members[@]}"; do
        current_member=$((current_member + 1))
        if [[ "$tty" == '1' ]]; then
            printf '\r\x1b[2K%s 解压中 %5.1f%% %s/%s' "$label" "$(python3 - <<'PY' "$current_member" "$member_count"
import sys
current = int(sys.argv[1])
total = int(sys.argv[2])
print(f"{(current * 100.0 / total) if total else 100.0:.1f}")
PY
)" "$current_member" "$member_count" >&2
        else
            printf '%s 解压中 %5.1f%% %s/%s\n' "$label" "$(python3 - <<'PY' "$current_member" "$member_count"
import sys
current = int(sys.argv[1])
total = int(sys.argv[2])
print(f"{(current * 100.0 / total) if total else 100.0:.1f}")
PY
)" "$current_member" "$member_count" >&2
        fi
        ar p "$package_path" "$member_name" > "$destination_root/$member_name"
    done

    if [[ "$tty" == '1' && "$member_count" -gt 0 ]]; then
        printf '\n' >&2
    fi
}

expand_tar_archive() {
    local archive_path="$1"
    local destination_root="$2"
    local label="${3:-$(basename "$archive_path") }"

    mkdir -p "$destination_root"
    sillydroid_extract_archive_with_progress "$archive_path" "$destination_root" "$label"
}

expand_gzip_file() {
    local archive_path="$1"
    local destination_path="$2"

    mkdir -p "$(dirname "$destination_path")"
    gzip -dc "$archive_path" > "$destination_path"
}

test_tar_contains_path() {
    local archive_path="$1"
    local expected_path="$2"

    tar -tf "$archive_path" | grep -Fx -- "$expected_path" >/dev/null || tar -tf "$archive_path" | grep -Fx -- "./$expected_path" >/dev/null
}

resolve_link_target() {
    local source_path="$1"
    local source_root="$2"
    local link_target

    link_target="$(readlink "$source_path")"
    if [[ -z "$link_target" ]]; then
        return
    fi

    if [[ "$link_target" = /* ]]; then
        realpath -m "$source_root/${link_target#/}"
        return
    fi

    realpath -m "$(dirname "$source_path")/$link_target"
}

copy_resolved_item() {
    local source_path="$1"
    local destination_path="$2"
    local source_root="$3"

    if [[ -L "$source_path" ]]; then
        local resolved_target
        resolved_target="$(resolve_link_target "$source_path" "$source_root" || true)"
        if [[ -z "$resolved_target" || ! -e "$resolved_target" ]]; then
            # Ubuntu base 和 apt payload 里会带少量悬空链接；这里和 PowerShell 版保持一致，直接跳过不可达目标。
            return
        fi

        copy_resolved_item "$resolved_target" "$destination_path" "$source_root"
        return
    fi

    if [[ -d "$source_path" ]]; then
        mkdir -p "$destination_path"

        shopt -s dotglob nullglob
        local child
        for child in "$source_path"/*; do
            copy_resolved_item "$child" "$destination_path/$(basename "$child")" "$source_root"
        done
        shopt -u dotglob nullglob
        return
    fi

    mkdir -p "$(dirname "$destination_path")"
    cp -a "$source_path" "$destination_path"
}

copy_resolved_directory_contents() {
    local source_root="$1"
    local destination_root="$2"

    mkdir -p "$destination_root"
    shopt -s dotglob nullglob
    local child
    for child in "$source_root"/*; do
        copy_resolved_item "$child" "$destination_root/$(basename "$child")" "$source_root"
    done
    shopt -u dotglob nullglob
}

resolve_termux_package_prefix_root() {
    local package_data_root="$1"
    local prefix_root=''

    prefix_root="$(find "$package_data_root" -type d -path '*/data/data/com.termux/files/usr' | LC_ALL=C sort | head -n 1)"
    if [[ -z "$prefix_root" ]]; then
        prefix_root="$(find "$package_data_root" -type d -path '*/usr' | awk '{ print length($0) " " $0 }' | LC_ALL=C sort -n | head -n 1 | cut -d' ' -f2-)"
    fi

    assert_path_exists "$prefix_root" "Resolved Termux package is missing a prefix root under $package_data_root"
    realpath "$prefix_root"
}

copy_termux_package_prefix_contents() {
    local package_data_root="$1"
    local destination_root="$2"
    local prefix_root=''

    prefix_root="$(resolve_termux_package_prefix_root "$package_data_root")"
    mkdir -p "$destination_root"
    cp -a "$prefix_root/." "$destination_root/"
}

prepare_archive_stage_with_symlink_manifest() {
    local source_root="$1"
    local archive_stage_root="$2"
    local symlink_manifest_path="$archive_stage_root/SYMLINKS.txt"
    local directory_path=''
    local file_path=''
    local link_path=''
    local relative_path=''
    local link_target=''
    local symlink_count='0'

    rm -rf "$archive_stage_root"
    mkdir -p "$archive_stage_root"

    while IFS= read -r directory_path; do
        relative_path="${directory_path#"$source_root"/}"
        mkdir -p "$archive_stage_root/$relative_path"
    done < <(find "$source_root" -mindepth 1 -type d | LC_ALL=C sort)

    while IFS= read -r file_path; do
        relative_path="${file_path#"$source_root"/}"
        mkdir -p "$(dirname "$archive_stage_root/$relative_path")"
        cp -a "$file_path" "$archive_stage_root/$relative_path"
    done < <(find "$source_root" -mindepth 1 -type f | LC_ALL=C sort)

    : > "$symlink_manifest_path"
    while IFS= read -r link_path; do
        relative_path="${link_path#"$source_root"/}"
        link_target="$(readlink "$link_path")"
        printf '%s←%s\n' "$link_target" "$relative_path" >> "$symlink_manifest_path"
        symlink_count=$((symlink_count + 1))
    done < <(find "$source_root" -mindepth 1 -type l | LC_ALL=C sort)

    if [[ "$symlink_count" -eq 0 ]]; then
        rm -f "$symlink_manifest_path"
    fi
}

install_termux_guest_rootfs_shims() {
    local prefix_root="$1"
    local guest_root="$2"

    mkdir -p "$guest_root/bin" "$guest_root/usr/bin" "$guest_root/etc/ssl/certs" "$guest_root/etc/tls" "$guest_root/tmp"
    chmod 1777 "$guest_root/tmp"

    assert_path_exists "$prefix_root/$termux_prefix_shell_relative_path" "Resolved Termux host prefix is incomplete: missing shell at $prefix_root/$termux_prefix_shell_relative_path"
    cp -L "$prefix_root/$termux_prefix_shell_relative_path" "$guest_root/bin/sh"
    chmod 0755 "$guest_root/bin/sh"

    if [[ -f "$prefix_root/$termux_prefix_dash_relative_path" ]]; then
        cp -L "$prefix_root/$termux_prefix_dash_relative_path" "$guest_root/bin/dash"
        cp -L "$prefix_root/$termux_prefix_dash_relative_path" "$guest_root/usr/bin/dash"
        chmod 0755 "$guest_root/bin/dash" "$guest_root/usr/bin/dash"
    fi

    if [[ -f "$prefix_root/$termux_prefix_bash_relative_path" ]]; then
        cp -L "$prefix_root/$termux_prefix_bash_relative_path" "$guest_root/bin/bash"
        cp -L "$prefix_root/$termux_prefix_bash_relative_path" "$guest_root/usr/bin/bash"
        chmod 0755 "$guest_root/bin/bash" "$guest_root/usr/bin/bash"
    fi

    if [[ -f "$prefix_root/$termux_prefix_env_relative_path" ]]; then
        cp -L "$prefix_root/$termux_prefix_env_relative_path" "$guest_root/bin/env"
        cp -L "$prefix_root/$termux_prefix_env_relative_path" "$guest_root/usr/bin/env"
        chmod 0755 "$guest_root/bin/env" "$guest_root/usr/bin/env"
    fi

    assert_path_exists "$prefix_root/$termux_prefix_ca_bundle_relative_path" "Resolved Termux host prefix is incomplete: missing CA bundle at $prefix_root/$termux_prefix_ca_bundle_relative_path"
    cp -L "$prefix_root/$termux_prefix_ca_bundle_relative_path" "$guest_root/etc/ssl/certs/ca-certificates.crt"
    cp -L "$prefix_root/$termux_prefix_ca_bundle_relative_path" "$guest_root/etc/tls/cert.pem"
}

install_termux_host_prefix_wrappers() {
    local prefix_root="$1"
    local npm_cli_relative_path='lib/node_modules/npm/lib/cli.js'
    local npm_bin_relative_path='lib/node_modules/npm/bin/npm-cli.js'
    local npx_bin_relative_path='lib/node_modules/npm/bin/npx-cli.js'

    mkdir -p "$prefix_root/bin"

    assert_path_exists "$prefix_root/$npm_cli_relative_path" "缺少预置 npm CLI：$prefix_root/$npm_cli_relative_path"
    assert_path_exists "$prefix_root/$npm_bin_relative_path" "缺少预置 npm wrapper：$prefix_root/$npm_bin_relative_path"
    assert_path_exists "$prefix_root/$npx_bin_relative_path" "缺少预置 npx wrapper：$prefix_root/$npx_bin_relative_path"

    if grep -F 'SILLYDROID_NPM_CLI=' "$prefix_root/$npm_bin_relative_path" >/dev/null 2>&1; then
        sillydroid_fail "预置 npm wrapper 已被构建包装脚本污染：$prefix_root/$npm_bin_relative_path"
    fi
    if grep -F 'SILLYDROID_NPM_CLI=' "$prefix_root/$npx_bin_relative_path" >/dev/null 2>&1; then
        sillydroid_fail "预置 npx wrapper 已被构建包装脚本污染：$prefix_root/$npx_bin_relative_path"
    fi

    # Termux 的 bin/npm 与 bin/npx 可能是 symlink；先删除再写，避免重定向跟随 symlink 覆盖 npm 包本体。
    rm -f "$prefix_root/bin/npm" "$prefix_root/bin/npx"

    cat > "$prefix_root/bin/npm" <<EOF
#!/bin/sh
set -eu
prefix_root="\${PREFIX:-$termux_guest_runtime_prefix}"
exec "\$prefix_root/bin/node" "\$prefix_root/$npm_bin_relative_path" "\$@"
EOF
    chmod 0755 "$prefix_root/bin/npm"

    cat > "$prefix_root/bin/npx" <<EOF
#!/bin/sh
set -eu
prefix_root="\${PREFIX:-$termux_guest_runtime_prefix}"
exec "\$prefix_root/bin/node" "\$prefix_root/$npx_bin_relative_path" "\$@"
EOF
    chmod 0755 "$prefix_root/bin/npx"

    if grep -F 'SILLYDROID_NPM_CLI=' "$prefix_root/$npm_bin_relative_path" >/dev/null 2>&1; then
        sillydroid_fail "写入 bin/npm 时误覆盖了 npm 包本体：$prefix_root/$npm_bin_relative_path"
    fi
    if grep -F 'SILLYDROID_NPM_CLI=' "$prefix_root/$npx_bin_relative_path" >/dev/null 2>&1; then
        sillydroid_fail "写入 bin/npx 时误覆盖了 npx 包本体：$prefix_root/$npx_bin_relative_path"
    fi
}

prune_termux_host_prefix_for_native_entrypoints() {
    local prefix_root="$1"

    # no-proot 运行时不能从 files/usr 直接 exec ELF；这些入口已经复制到 APK nativeLibraryDir。
    # 这里保留 npm 本体、Git helper、证书、模板等资源，避免酒馆 simple-git/npm 相关能力退化。
    rm -f \
        "$prefix_root/bin/node" \
        "$prefix_root/bin/bash" \
        "$prefix_root/bin/dash" \
        "$prefix_root/bin/curl" \
        "$prefix_root/bin/git" \
        "$prefix_root/bin/git-shell"

    find "$prefix_root/lib" -maxdepth 1 \( -type f -o -type l \) -name 'lib*.so*' -delete
}

copy_termux_host_executable() {
    local source_path="$1"
    local destination_path="$2"
    local label="$3"

    assert_path_exists "$source_path" "缺少 Termux host runtime 入口：$label ($source_path)"
    cp -L "$source_path" "$destination_path"
    chmod 0755 "$destination_path"
}

copy_termux_host_library_for_jni() {
    local source_root="$1"
    local destination_root="$2"
    local needed_name="$3"
    local source_path=''
    local destination_name=''

    source_path="$source_root/lib/$needed_name"
    assert_path_exists "$source_path" "缺少 Termux host runtime 依赖库：$needed_name"
    destination_name="$needed_name"

    if [[ "$needed_name" =~ ^lib.+\.so\.[0-9] ]]; then
        destination_name="$(printf '%s' "$needed_name" | sed -E 's/\.so\..*$/.so/')"
    fi

    if [[ -f "$destination_root/$destination_name" ]]; then
        return
    fi

    cp -L "$source_path" "$destination_root/$destination_name"
    chmod 0644 "$destination_root/$destination_name"
}

patch_termux_host_runtime_jni_dependencies() {
    local destination_root="$1"

    # Android 只会稳定打包 lib*.so 形态；这里把 Termux ELF 的版本号 NEEDED 改成同目录无版本库名。
    python3 - "$destination_root" <<'PY'
import pathlib
import re
import sys

destination_root = pathlib.Path(sys.argv[1])
if not destination_root.is_dir():
    raise SystemExit(0)

needed_name_pattern = re.compile(rb"lib[0-9A-Za-z_+.-]+\.so\.[0-9][0-9A-Za-z_.-]*")

def patch_bytes(payload: bytes) -> bytes:
    patched = payload
    for old in sorted(set(needed_name_pattern.findall(payload)), key=len, reverse=True):
        new = re.sub(rb"\.so\..*$", b".so", old)
        if (destination_root / new.decode("utf-8", errors="strict")).is_file():
            patched = patched.replace(old, new + (b"\0" * (len(old) - len(new))))
    return patched

for path in destination_root.iterdir():
    if not path.is_file():
        continue
    payload = path.read_bytes()
    patched = patch_bytes(payload)
    if patched != payload:
        path.write_bytes(patched)
PY
}

sync_termux_host_runtime_jni_libs() {
    local source_root="$1"
    local destination_root="$2"

    mkdir -p "$destination_root"
    find "$destination_root" -maxdepth 1 -type f \
        -name 'lib*.so*' \
        -delete

    # targetSdk 29+ 不能从可写 files/usr 目录直接 exec ELF；这些入口由 APK nativeLibraryDir 承载。
    copy_termux_host_executable "$source_root/bin/node" "$destination_root/libtermux-node.so" 'node'
    copy_termux_host_executable "$source_root/libexec/git-core/git" "$destination_root/libtermux-git.so" 'git'
    copy_termux_host_executable "$source_root/libexec/git-core/git-remote-http" "$destination_root/libtermux-git-remote-http.so" 'git-remote-http'
    copy_termux_host_executable "$source_root/bin/curl" "$destination_root/libtermux-curl.so" 'curl'
    copy_termux_host_executable "$source_root/bin/dash" "$destination_root/libtermux-sh.so" 'shell'
    if [[ -f "$source_root/bin/bash" ]]; then
        copy_termux_host_executable "$source_root/bin/bash" "$destination_root/libtermux-bash.so" 'bash'
    fi

    sillydroid_require_command readelf
    while IFS= read -r shared_library_name; do
        [[ -n "$shared_library_name" ]] || continue
        copy_termux_host_library_for_jni "$source_root" "$destination_root" "$shared_library_name"
    done < <(python3 - "$source_root" <<'PY'
import pathlib
import re
import subprocess
import sys

source_root = pathlib.Path(sys.argv[1])
seed_relative_paths = [
    "bin/node",
    "libexec/git-core/git",
    "libexec/git-core/git-remote-http",
    "bin/curl",
    "bin/dash",
    "bin/bash",
]
system_libraries = {
    "libandroid.so",
    "libc.so",
    "libdl.so",
    "liblog.so",
    "libm.so",
}
needed_pattern = re.compile(r"\(NEEDED\)\s+Shared library: \[([^\]]+)\]")

def elf_needed(path: pathlib.Path) -> list[str]:
    result = subprocess.run(
        ["readelf", "-d", str(path)],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if result.returncode != 0:
        return []
    return [match.group(1) for match in needed_pattern.finditer(result.stdout)]

def resolve_library(name: str) -> pathlib.Path | None:
    direct = source_root / "lib" / name
    if direct.exists():
        return direct.resolve()
    if ".so." in name:
        unversioned_name = name.split(".so.", 1)[0] + ".so"
        unversioned = source_root / "lib" / unversioned_name
        if unversioned.exists():
            return unversioned.resolve()
    return None

queue: list[pathlib.Path] = []
for relative_path in seed_relative_paths:
    candidate = source_root / relative_path
    if candidate.exists():
        queue.append(candidate.resolve())

seen_elf: set[pathlib.Path] = set()
needed_libraries: set[str] = set()
while queue:
    current = queue.pop(0)
    if current in seen_elf:
        continue
    seen_elf.add(current)
    for library_name in elf_needed(current):
        if library_name in system_libraries:
            continue
        library_path = resolve_library(library_name)
        if library_path is None:
            raise SystemExit(f"Unable to resolve Termux host runtime dependency: {library_name} from {current}")
        needed_libraries.add(library_name)
        queue.append(library_path)

for library_name in sorted(needed_libraries):
    print(library_name)
PY
)

    patch_termux_host_runtime_jni_dependencies "$destination_root"
}

extract_termux_package_version() {
    local package_name="$1"
    local package_filename="$2"

    printf '%s' "$package_filename" | sed -n "s#.*${package_name}_\([^_]*\)_[^/]*\.deb#\1#p"
}

generate_rootfs_ca_store() {
    local rootfs_root="$1"
    local ca_source_root="$rootfs_root/usr/share/ca-certificates"
    local ca_conf_path="$rootfs_root/etc/ca-certificates.conf"
    local ca_cert_dir="$rootfs_root/etc/ssl/certs"
    local ca_bundle_path="$ca_cert_dir/ca-certificates.crt"

    assert_path_exists "$ca_source_root" "Resolved Android rootfs is incomplete: missing CA certificate sources at $ca_source_root"

    mkdir -p "$ca_cert_dir"

    # ca-certificates.deb 只提供源证书与目录结构；Android 离线 rootfs 不跑 postinst，这里直接生成运行时需要的配置和证书 bundle。
    find "$ca_source_root" -type f -name '*.crt' \
        | sed "s#^$ca_source_root/##" \
        | LC_ALL=C sort > "$ca_conf_path"

    : > "$ca_bundle_path"
    while IFS= read -r relative_cert_path; do
        [[ -n "$relative_cert_path" ]] || continue
        cat "$ca_source_root/$relative_cert_path" >> "$ca_bundle_path"
        printf '\n' >> "$ca_bundle_path"
    done < "$ca_conf_path"

    if [[ ! -s "$ca_bundle_path" ]]; then
        echo "Resolved Android rootfs CA bundle is empty: $ca_bundle_path" >&2
        exit 1
    fi
}

parse_packages_index_records() {
    local packages_index_path="$1"
    local repository_base_url="$2"

    awk -v repo="$repository_base_url" -v sep='\037' '
        function flush_entry() {
            if (pkg != "" && filename != "") {
                gsub(/\037/, " ", depends)
                gsub(/\037/, " ", predepends)
                printf "%s%s%s%s%s%s%s%s%s\n", pkg, sep, filename, sep, depends, sep, predepends, sep, repo
            }
        }
        /^[[:space:]]*$/ {
            flush_entry()
            pkg = ""
            filename = ""
            depends = ""
            predepends = ""
            current = ""
            next
        }
        /^[^[:space:]][^:]*:/ {
            current = substr($0, 1, index($0, ":") - 1)
            value = substr($0, index($0, ":") + 1)
            sub(/^ /, "", value)
            if (current == "Package") {
                pkg = value
            } else if (current == "Filename") {
                filename = value
            } else if (current == "Depends") {
                depends = value
            } else if (current == "Pre-Depends") {
                predepends = value
            }
            next
        }
        /^[[:space:]]/ {
            continuation = substr($0, 2)
            if (current == "Depends") {
                depends = depends " " continuation
            } else if (current == "Pre-Depends") {
                predepends = predepends " " continuation
            }
            next
        }
        END {
            flush_entry()
        }
    ' "$packages_index_path"
}

load_termux_package_table() {
    local packages_index_path="$1"
    local repository_base_url="$2"
    local pkg
    local filename
    local depends
    local predepends
    local repo

    while IFS=$'\x1f' read -r pkg filename depends predepends repo; do
        [[ -z "$pkg" ]] && continue
        termux_filename_by_package["$pkg"]="$filename"
        termux_depends_by_package["$pkg"]="$depends"
        termux_repo_by_package["$pkg"]="$repo"
    done < <(parse_packages_index_records "$packages_index_path" "$repository_base_url")
}

normalize_dependency_names() {
    local depends_value
    local dependency_group
    local primary_alternative
    local normalized_dependency
    local package_token
    local package_name

    for depends_value in "$@"; do
        [[ -z "$depends_value" ]] && continue
        IFS=',' read -r -a dependency_groups <<< "$depends_value"
        for dependency_group in "${dependency_groups[@]}"; do
            primary_alternative="${dependency_group%%|*}"
            normalized_dependency="$(printf '%s' "$primary_alternative" | sed -E 's/\[[^]]+\]//g; s/<[^>]+>//g; s/\([^)]*\)//g; s/^[[:space:]]+//; s/[[:space:]]+$//')"
            package_token="${normalized_dependency%% *}"
            package_name="${package_token%%:*}"
            [[ -n "$package_name" ]] && printf '%s\n' "$package_name"
        done
    done | awk '!seen[$0]++'
}

resolve_termux_package_dependencies() {
    local queue=("$@")
    local -A seen=()
    local resolved=()
    local package_name
    local dependency_name

    while (( ${#queue[@]} > 0 )); do
        package_name="${queue[0]}"
        queue=("${queue[@]:1}")

        [[ -z "$package_name" || -n "${seen[$package_name]:-}" ]] && continue
        if [[ -z "${termux_filename_by_package[$package_name]:-}" ]]; then
            echo "Unable to locate Termux package entry for $package_name" >&2
            exit 1
        fi

        seen["$package_name"]=1
        resolved+=("$package_name")

        while IFS= read -r dependency_name; do
            [[ -n "$dependency_name" && -z "${seen[$dependency_name]:-}" ]] && queue+=("$dependency_name")
        done < <(normalize_dependency_names "${termux_depends_by_package[$package_name]:-}")
    done

    printf '%s\n' "${resolved[@]}"
}

# rootfs 的大文件缓存默认落在 WSL 本地文件系统，避免 DrvFs 上的大包下载与解压影响 Linux 构建稳定性。
working_root="${SILLYDROID_ANDROID_ROOTFS_WORKDIR:-${XDG_CACHE_HOME:-$HOME/.cache}/sillydroid-android-rootfs}"
downloads_root="$working_root/downloads"
apt_indexes_root="$working_root/apt-indexes"
apt_packages_root="$downloads_root/apt"
termux_extract_root="$working_root/termux"
rootfs_extract_root="$working_root/rootfs"
assets_stage_root="$working_root/assets-stage"
resolved_target_root="$(realpath -m "$target_root")"
resolved_jni_libs_root="$(realpath -m "$jni_libs_root")"
host_prefix_stage_root="$assets_stage_root/usr"
host_prefix_archive_stage_root="$assets_stage_root/usr-archive"
rootfs_fs_stage_root="$assets_stage_root/fs"
rootfs_fs_archive_path="$resolved_target_root/rootfs-fs.zip"
host_prefix_archive_path="$resolved_target_root/rootfs-usr.zip"

termux_packages_index_path="$apt_indexes_root/$(basename "$termux_packages_index_url")"

existing_manifest_path="$resolved_target_root/rootfs-manifest.json"
if [[ -f "$existing_manifest_path" ]] \
    && grep -Fq '"baseFlavor": "termux"' "$existing_manifest_path" \
    && grep -Fq '"runtimeMode": "termux-host"' "$existing_manifest_path" \
    && grep -Fq "$termux_packages_index_url" "$existing_manifest_path" \
    && grep -Fq "$termux_host_runtime_signature" "$existing_manifest_path" \
    && grep -Fq "$termux_guest_runtime_prefix" "$existing_manifest_path" \
    && grep -Fq '"hostRuntimeEntry": "nativeLibraryDir"' "$existing_manifest_path" \
    && [[ -f "$rootfs_fs_archive_path" ]] \
    && [[ -f "$host_prefix_archive_path" ]] \
    && [[ -f "$resolved_jni_libs_root/libtermux-node.so" ]] \
    && [[ -f "$resolved_jni_libs_root/libtermux-git.so" ]] \
    && [[ -f "$resolved_jni_libs_root/libtermux-git-remote-http.so" ]] \
    && [[ -f "$resolved_jni_libs_root/libtermux-curl.so" ]] \
    && [[ -f "$resolved_jni_libs_root/libtermux-sh.so" ]]
then
    sillydroid_log "Android rootfs assets are up to date, skipping sync."
    exit 0
fi

mkdir -p "$downloads_root" "$apt_indexes_root" "$apt_packages_root"
sillydroid_download_queue_reset
# Termux main 仓库是滚动源，旧 Packages 索引里的 deb 文件可能已经被上游清理。
# deb 包本体可以缓存，但包索引必须每次刷新，避免 GitHub Actions cache 复用旧索引后下载 404。
rm -f "$termux_packages_index_path"
sillydroid_queue_download_if_missing "$termux_packages_index_url" "$termux_packages_index_path" 'termux-packages-index'
sillydroid_run_download_queue

sillydroid_ensure_java_home
jar_path="$JAVA_HOME/bin/jar"
assert_path_exists "$jar_path" "缺少 jar 命令。Android rootfs 资产归档要求当前 Linux 环境提供 JDK。"

rm -rf "$assets_stage_root" "$termux_extract_root" "$rootfs_extract_root"
mkdir -p "$host_prefix_stage_root/lib" "$rootfs_fs_stage_root" "$termux_extract_root" "$rootfs_extract_root"

sillydroid_log "开始解析 Termux host runtime 依赖"
load_termux_package_table "$termux_packages_index_path" 'https://packages.termux.dev/apt/termux-main'
mapfile -t resolved_termux_base_package_names < <(resolve_termux_package_dependencies "${termux_base_packages[@]}")

declare -A required_termux_packages=()
resolved_termux_package_names=()
for package_name in "${resolved_termux_base_package_names[@]}"; do
    [[ -n "$package_name" ]] || continue
    if [[ -n "${required_termux_packages[$package_name]:-}" ]]; then
        continue
    fi
    required_termux_packages["$package_name"]=1
    resolved_termux_package_names+=("$package_name")
done

sillydroid_download_queue_reset
for dependency_name in "${resolved_termux_package_names[@]}"; do
    dependency_url="${termux_repo_by_package[$dependency_name]}/${termux_filename_by_package[$dependency_name]}"
    dependency_package_path="$downloads_root/$(basename "${termux_filename_by_package[$dependency_name]}")"
    sillydroid_queue_download_if_missing "$dependency_url" "$dependency_package_path" "termux:$dependency_name"
done
sillydroid_run_download_queue

sillydroid_log "开始展开 ${#resolved_termux_package_names[@]} 个 Termux 包"
for dependency_name in "${resolved_termux_package_names[@]}"; do
    dependency_url="${termux_repo_by_package[$dependency_name]}/${termux_filename_by_package[$dependency_name]}"
    dependency_package_path="$downloads_root/$(basename "${termux_filename_by_package[$dependency_name]}")"
    dependency_deb_root="$termux_extract_root/$dependency_name-deb"
    dependency_data_root="$termux_extract_root/$dependency_name-data"

    expand_deb_archive "$dependency_package_path" "$dependency_deb_root" "termux:$dependency_name-deb"
    dependency_data_archive_path="$(find "$dependency_deb_root" -maxdepth 1 -type f -name 'data.tar*' | head -n 1)"
    assert_path_exists "$dependency_data_archive_path" "data.tar archive was not found in $dependency_package_path"
    rm -rf "$dependency_data_root"
    mkdir -p "$dependency_data_root"
    expand_tar_archive "$dependency_data_archive_path" "$dependency_data_root" "termux:$dependency_name-data"
    copy_termux_package_prefix_contents "$dependency_data_root" "$host_prefix_stage_root"
done

install_termux_host_prefix_wrappers "$host_prefix_stage_root"
sync_termux_host_runtime_jni_libs "$host_prefix_stage_root" "$resolved_jni_libs_root"

sillydroid_log '开始组装 Termux guest rootfs skeleton'
install_termux_guest_rootfs_shims "$host_prefix_stage_root" "$rootfs_fs_stage_root"
prune_termux_host_prefix_for_native_entrypoints "$host_prefix_stage_root"

assert_path_exists "$rootfs_fs_stage_root/bin/sh" "Resolved Android rootfs is incomplete: missing bin/sh at $rootfs_fs_stage_root/bin/sh"
assert_path_exists "$rootfs_fs_stage_root/etc/ssl/certs/ca-certificates.crt" "Resolved Android rootfs is incomplete: missing CA bundle at $rootfs_fs_stage_root/etc/ssl/certs/ca-certificates.crt"

rm -rf "$resolved_target_root"
mkdir -p "$resolved_target_root"

# 把 rootfs fs/usr 资产收敛成两个 ZIP，直接减少 Gradle merge/compress assets 的文件数。
rm -f "$rootfs_fs_archive_path" "$host_prefix_archive_path"
sillydroid_log "开始归档 rootfs fs/usr 资产"
prepare_archive_stage_with_symlink_manifest "$host_prefix_stage_root" "$host_prefix_archive_stage_root"
"$jar_path" --create --file "$rootfs_fs_archive_path" --no-manifest -C "$rootfs_fs_stage_root" .
"$jar_path" --create --file "$host_prefix_archive_path" --no-manifest -C "$host_prefix_archive_stage_root" .

rootfs_fs_file_count="$(find "$rootfs_fs_stage_root" -type f | wc -l | tr -d '[:space:]')"
host_prefix_file_count="$(find "$host_prefix_stage_root" -type f | wc -l | tr -d '[:space:]')"
rootfs_fs_archive_size_bytes="$(stat -c '%s' "$rootfs_fs_archive_path")"
host_prefix_archive_size_bytes="$(stat -c '%s' "$host_prefix_archive_path")"
rootfs_fs_archive_sha256="$(sha256sum "$rootfs_fs_archive_path" | awk '{print $1}')"
host_prefix_archive_sha256="$(sha256sum "$host_prefix_archive_path" | awk '{print $1}')"
termux_base_version="$(extract_termux_package_version dash "${termux_filename_by_package[dash]:-}")"
if [[ -n "$termux_base_version" ]]; then
    termux_base_version="stable-dash.$termux_base_version"
else
    termux_base_version='stable'
fi
runtime_version="$SILLYDROID_ROOTFS_VERSION"

manifest_path="$resolved_target_root/rootfs-manifest.json"

{
    printf '{\n'
    printf '  "staiRootfsVersion": "%s",\n' "$(json_escape "$SILLYDROID_ROOTFS_VERSION")"
    printf '  "runtimeVersion": "%s",\n' "$(json_escape "$runtime_version")"
    printf '  "baseFlavor": "termux",\n'
    printf '  "runtimeMode": "termux-host",\n'
    printf '  "baseVersion": "%s",\n' "$(json_escape "$termux_base_version")"
    printf '  "baseSourceUrl": "%s",\n' "$(json_escape "$termux_packages_index_url")"
    printf '  "termuxHostRuntimeSignature": "%s",\n' "$(json_escape "$termux_host_runtime_signature")"
    printf '  "runtimePrefix": "%s",\n' "$(json_escape "$runtime_prefix")"
    printf '  "guestRuntimePrefix": "%s",\n' "$(json_escape "$termux_guest_runtime_prefix")"
    printf '  "guestShellPath": "%s",\n' "$(json_escape "$guest_shell_path")"
    printf '  "guestCaBundlePath": "%s",\n' "$(json_escape "$guest_ca_bundle_path")"
    printf '  "hostRuntimeEntry": "nativeLibraryDir",\n'
    printf '  "syncedAtUtc": "%s",\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    printf '  "offlineRuntimePackages": [\n'
    for ((index = 0; index < 0; index++)); do
        separator=','
        if (( index == -1 )); then
            separator=''
        fi
        printf '    "%s"%s\n' '' "$separator"
    done
    printf '  ],\n'
    printf '  "termuxBasePackages": [\n'
    for ((index = 0; index < ${#termux_base_packages[@]}; index++)); do
        separator=','
        if (( index == ${#termux_base_packages[@]} - 1 )); then
            separator=''
        fi
        printf '    "%s"%s\n' "$(json_escape "${termux_base_packages[$index]}")" "$separator"
    done
    printf '  ],\n'
    printf '  "termuxResolvedPackages": [\n'
    for ((index = 0; index < ${#resolved_termux_package_names[@]}; index++)); do
        separator=','
        if (( index == ${#resolved_termux_package_names[@]} - 1 )); then
            separator=''
        fi
        printf '    "%s"%s\n' "$(json_escape "${resolved_termux_package_names[$index]}")" "$separator"
    done
    printf '  ],\n'
    printf '  "archiveFiles": [\n'
    printf '    "%s",\n' "$(json_escape "$(basename "$rootfs_fs_archive_path")")"
    printf '    "%s"\n' "$(json_escape "$(basename "$host_prefix_archive_path")")"
    printf '  ],\n'
    printf '  "archiveEntryCounts": {\n'
    printf '    "fs": %s,\n' "$rootfs_fs_file_count"
    printf '    "usr": %s\n' "$host_prefix_file_count"
    printf '  },\n'
    printf '  "archiveSizeBytes": {\n'
    printf '    "fs": %s,\n' "$rootfs_fs_archive_size_bytes"
    printf '    "usr": %s\n' "$host_prefix_archive_size_bytes"
    printf '  },\n'
    printf '  "archiveSha256": {\n'
    printf '    "fs": "%s",\n' "$rootfs_fs_archive_sha256"
    printf '    "usr": "%s"\n' "$host_prefix_archive_sha256"
    printf '  }\n'
    printf '}\n'
} > "$manifest_path"

sillydroid_log "已打包 Android rootfs 资产：$rootfs_fs_archive_path 和 $host_prefix_archive_path"
printf 'Packed Android rootfs assets into %s and %s\n' "$rootfs_fs_archive_path" "$host_prefix_archive_path"
