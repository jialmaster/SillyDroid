#!/usr/bin/env bash
set -euo pipefail

ubuntu_base_url='https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz'
ubuntu_ports_base_url='https://ports.ubuntu.com/ubuntu-ports'
proot_package_url='https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107-70_aarch64.deb'
proot_source_url='https://github.com/termux/proot/archive/4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f.zip'
termux_packages_index_url='https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages'
android_ndk_linux_url='https://dl.google.com/android/repository/android-ndk-r27-linux.zip'

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workspace_root="$(cd "$script_dir/.." && pwd)"
build_config_path="$workspace_root/sillydroid-build-config.json"
proot_patch_signature="$(sha256sum "$script_dir/sync-android-rootfs.sh" | awk '{print $1}')"
target_root="$workspace_root/android-tavern/app/src/main/assets/bootstrap/rootfs"
jni_libs_root="$workspace_root/android-tavern/app/src/main/jniLibs/arm64-v8a"
runtime_prefix='/data/data/com.jm.sillydroid/files/usr'
runtime_loader_dir="$runtime_prefix/libexec/proot"
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

offline_runtime_packages=(
    ca-certificates
    libfontconfig1
    libgomp1
    libgssapi-krb5-2
)

apt_repo_names=(
    ubuntu-security-main
    ubuntu-updates-main
    ubuntu-main
    ubuntu-security-universe
    ubuntu-updates-universe
    ubuntu-universe
)

apt_repo_urls=(
    "$ubuntu_ports_base_url/dists/noble-security/main/binary-arm64/Packages.gz"
    "$ubuntu_ports_base_url/dists/noble-updates/main/binary-arm64/Packages.gz"
    "$ubuntu_ports_base_url/dists/noble/main/binary-arm64/Packages.gz"
    "$ubuntu_ports_base_url/dists/noble-security/universe/binary-arm64/Packages.gz"
    "$ubuntu_ports_base_url/dists/noble-updates/universe/binary-arm64/Packages.gz"
    "$ubuntu_ports_base_url/dists/noble/universe/binary-arm64/Packages.gz"
)

apt_repo_bases=(
    "$ubuntu_ports_base_url"
    "$ubuntu_ports_base_url"
    "$ubuntu_ports_base_url"
    "$ubuntu_ports_base_url"
    "$ubuntu_ports_base_url"
    "$ubuntu_ports_base_url"
)

declare -A termux_filename_by_package=()
declare -A termux_depends_by_package=()
declare -A termux_repo_by_package=()

declare -A apt_filename_by_package=()
declare -A apt_depends_by_package=()
declare -A apt_predepends_by_package=()
declare -A apt_repo_by_package=()

usage() {
    cat <<'EOF'
Usage: sync-android-rootfs.sh [--target-root <path>]
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
    local npm_cli_relative_path='lib/node_modules/npm/bin/npm-cli.js'
    local npx_cli_relative_path='lib/node_modules/npm/bin/npx-cli.js'
    local corepack_npm_cli_relative_path='lib/node_modules/corepack/dist/npm.js'

    mkdir -p "$prefix_root/bin"

    if [[ -f "$prefix_root/$npm_cli_relative_path" ]]; then
        cat > "$prefix_root/bin/npm" <<EOF
#!/bin/sh
set -eu
prefix_root="\${PREFIX:-$termux_guest_runtime_prefix}"
exec "\$prefix_root/bin/node" "\$prefix_root/$npm_cli_relative_path" "\$@"
EOF
        chmod 0755 "$prefix_root/bin/npm"
    elif [[ -f "$prefix_root/$corepack_npm_cli_relative_path" ]]; then
    cat > "$prefix_root/bin/npm" <<EOF
#!/bin/sh
set -eu
prefix_root="\${PREFIX:-$termux_guest_runtime_prefix}"
exec "\$prefix_root/bin/node" "\$prefix_root/$corepack_npm_cli_relative_path" "\$@"
EOF
    chmod 0755 "$prefix_root/bin/npm"
    fi

    if [[ -f "$prefix_root/$npx_cli_relative_path" ]]; then
        cat > "$prefix_root/bin/npx" <<EOF
#!/bin/sh
set -eu
prefix_root="\${PREFIX:-$termux_guest_runtime_prefix}"
exec "\$prefix_root/bin/node" "\$prefix_root/$npx_cli_relative_path" "\$@"
EOF
        chmod 0755 "$prefix_root/bin/npx"
    elif [[ -f "$prefix_root/$corepack_npm_cli_relative_path" ]]; then
        cat > "$prefix_root/bin/npx" <<EOF
#!/bin/sh
set -eu
prefix_root="\${PREFIX:-$termux_guest_runtime_prefix}"
exec "\$prefix_root/bin/node" "\$prefix_root/$corepack_npm_cli_relative_path" exec --yes -- "\$@"
EOF
        chmod 0755 "$prefix_root/bin/npx"
    fi
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

resolve_android_ndk_prebuilt_root() {
    local ndk_root="$1"
    local candidate="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64"

    if [[ -d "$candidate" ]]; then
        realpath "$candidate"
        return
    fi

    echo "Unable to resolve Linux Android NDK prebuilt directory under $ndk_root" >&2
    exit 1
}

ensure_linux_ndk_root() {
    local cached_sdk_root="$working_root/android-sdk"
    local cached_ndk_zip_path="$downloads_root/$(basename "$android_ndk_linux_url")"
    local cached_ndk_root="$cached_sdk_root/ndk/android-ndk-r27"
    local candidate

    for candidate in \
        "${ANDROID_NDK_ROOT:-}" \
        "${ANDROID_NDK_HOME:-}" \
        "${ANDROID_SDK_ROOT:-}/ndk/android-ndk-r27" \
        "${ANDROID_HOME:-}/ndk/android-ndk-r27" \
        "$HOME/Android/Sdk/ndk/android-ndk-r27" \
        "$cached_ndk_root"
    do
        if [[ -n "$candidate" && -d "$candidate" && -d "$candidate/toolchains/llvm/prebuilt/linux-x86_64" ]]; then
            realpath "$candidate"
            return
        fi
    done

    mkdir -p "$cached_sdk_root/ndk"
    download_file_if_missing "$android_ndk_linux_url" "$cached_ndk_zip_path"
    rm -rf "$cached_ndk_root"
    sillydroid_extract_archive_with_progress "$cached_ndk_zip_path" "$cached_sdk_root/ndk" 'android-ndk'
    assert_path_exists "$cached_ndk_root/toolchains/llvm/prebuilt/linux-x86_64" "Unable to provision Linux Android NDK from $android_ndk_linux_url"
    realpath "$cached_ndk_root"
}

resolve_tool_path() {
    local root="$1"
    local relative_path="$2"
    local candidate

    for candidate in "$root/$relative_path" "$root/$relative_path.exe"; do
        if [[ -f "$candidate" ]]; then
            realpath "$candidate"
            return
        fi
    done

    echo "Unable to resolve required tool: $relative_path" >&2
    exit 1
}

create_tool_wrapper() {
    local wrapper_path="$1"
    shift

    {
        printf '#!/usr/bin/env bash\n'
        printf 'exec'
        local arg
        for arg in "$@"; do
            printf ' %q' "$arg"
        done
        printf ' "$@"\n'
    } > "$wrapper_path"
    chmod +x "$wrapper_path"
}

prepare_ndk_wrappers() {
    local ndk_prebuilt_root="$1"
    local wrapper_root="$2"

    local clang_path
    local strip_path
    local objcopy_path
    local objdump_path
    local readelf_path

    clang_path="$(resolve_tool_path "$ndk_prebuilt_root/bin" clang)"
    strip_path="$(resolve_tool_path "$ndk_prebuilt_root/bin" llvm-strip)"
    objcopy_path="$(resolve_tool_path "$ndk_prebuilt_root/bin" llvm-objcopy)"
    objdump_path="$(resolve_tool_path "$ndk_prebuilt_root/bin" llvm-objdump)"
    readelf_path="$(resolve_tool_path "$ndk_prebuilt_root/bin" llvm-readelf)"

    rm -rf "$wrapper_root"
    mkdir -p "$wrapper_root"

    create_tool_wrapper "$wrapper_root/clang" "$clang_path" --target=aarch64-linux-android29 --sysroot "$ndk_prebuilt_root/sysroot"
    create_tool_wrapper "$wrapper_root/strip" "$strip_path"
    create_tool_wrapper "$wrapper_root/objcopy" "$objcopy_path"
    create_tool_wrapper "$wrapper_root/objdump" "$objdump_path"
    create_tool_wrapper "$wrapper_root/readelf" "$readelf_path"
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

add_apt_package_entries_from_index() {
    local packages_index_path="$1"
    local repository_base_url="$2"
    local pkg
    local filename
    local depends
    local predepends
    local repo

    while IFS=$'\x1f' read -r pkg filename depends predepends repo; do
        [[ -z "$pkg" ]] && continue
        if [[ -n "${apt_filename_by_package[$pkg]:-}" ]]; then
            continue
        fi

        apt_filename_by_package["$pkg"]="$filename"
        apt_depends_by_package["$pkg"]="$depends"
        apt_predepends_by_package["$pkg"]="$predepends"
        apt_repo_by_package["$pkg"]="$repo"
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

resolve_apt_package_dependencies() {
    local queue=("$@")
    local -A seen=()
    local resolved=()
    local package_name
    local dependency_name

    while (( ${#queue[@]} > 0 )); do
        package_name="${queue[0]}"
        queue=("${queue[@]:1}")

        [[ -z "$package_name" || -n "${seen[$package_name]:-}" ]] && continue
        if [[ -z "${apt_filename_by_package[$package_name]:-}" ]]; then
            echo "Unable to locate apt package entry for $package_name" >&2
            exit 1
        fi

        seen["$package_name"]=1
        resolved+=("$package_name")

        while IFS= read -r dependency_name; do
            [[ -n "$dependency_name" && -z "${seen[$dependency_name]:-}" ]] && queue+=("$dependency_name")
        done < <(normalize_dependency_names "${apt_predepends_by_package[$package_name]:-}" "${apt_depends_by_package[$package_name]:-}")
    done

    printf '%s\n' "${resolved[@]}"
}

patch_proot_source() {
    local source_root="$1"

    # Android NDK 头文件不会像 glibc 一样间接暴露 bzero/string API；这里在已定位的最小文件集上补齐声明。
    if ! grep -q '#include <strings.h>' "$source_root/src/tracee/tracee.c"; then
        sed -i '/#include <string.h>/a #include <strings.h>    /* bzero(3), */' "$source_root/src/tracee/tracee.c"
    fi

    if ! grep -q '#include <strings.h>' "$source_root/src/tracee/event.c"; then
        sed -i '/#include <string.h>/a #include <strings.h>    /* bzero(3), */' "$source_root/src/tracee/event.c"
    fi

    if ! grep -q '#include <strings.h>' "$source_root/src/ptrace/ptrace.c"; then
        sed -i '/#include <string.h>/a #include <strings.h>    /* bzero(3), */' "$source_root/src/ptrace/ptrace.c"
    fi

    if ! grep -q '#include <string.h>' "$source_root/src/extension/ashmem_memfd/ashmem_memfd.c"; then
        sed -i '/#include <unistd.h>/a #include <string.h>' "$source_root/src/extension/ashmem_memfd/ashmem_memfd.c"
    fi

    if ! grep -q '#include <sys/syscall.h>' "$source_root/src/extension/sysvipc/sysvipc_shm.c"; then
        sed -i '/#include <syscall.h>/a #include <sys\/syscall.h> /* __NR_memfd_create */' "$source_root/src/extension/sysvipc/sysvipc_shm.c"
    fi

    if ! grep -q 'syscall(__NR_memfd_create, name_buffer, 0)' "$source_root/src/extension/sysvipc/sysvipc_shm.c"; then
        # Android 12 的 app 进程里，ashmem ioctl 路径会让 PostgreSQL 的 shmget 落到 ENOSPC；这里优先切到 memfd，失败后再保留 ashmem 回退。
        perl -0pi -e 's@static int sysvipc_shm_do_allocate\(size_t size, int shmid\) \{.*?\n\}\n\nvoid sysvipc_shm_helper_main\(\) \{@static int sysvipc_shm_do_allocate(size_t size, int shmid) {\n#ifdef __ANDROID__\n        char name_buffer[ASHMEM_NAME_LEN] = {0};\n        int fd;\n        snprintf(name_buffer, ASHMEM_NAME_LEN - 1, "sysvshm_0x%X", shmid);\n\n#ifdef __NR_memfd_create\n        fd = syscall(__NR_memfd_create, name_buffer, 0);\n        if (fd >= 0) {\n                if (ftruncate(fd, size) == 0) {\n                        return fd;\n                }\n                close(fd);\n        }\n#endif\n\n        fd = open("/dev/ashmem", O_RDWR, 0);\n        if (fd < 0) return -ENOSPC;\n\n        ioctl(fd, ASHMEM_SET_NAME, name_buffer);\n\n        int ret = ioctl(fd, ASHMEM_SET_SIZE, size);\n        if (ret < 0) {\n                close(fd);\n                return -ENOSPC;\n        }\n\n        return fd;\n#else\n        (void) shmid;\n        FILE *fdesc = tmpfile();\n        if (!fdesc) return -ENOSPC;\n        int fd = dup(fileno(fdesc));\n        fclose(fdesc);\n        if (fd < 0) return -ENOSPC;\n\n        if (ftruncate(fd, size) == -1) {\n                return -ENOSPC;\n        }\n\n        return fd;\n#endif\n}\n\nvoid sysvipc_shm_helper_main() {@s' "$source_root/src/extension/sysvipc/sysvipc_shm.c"

        if ! grep -q 'syscall(__NR_memfd_create, name_buffer, 0)' "$source_root/src/extension/sysvipc/sysvipc_shm.c"; then
            echo "Failed to patch PRoot sysvipc allocator with memfd support." >&2
            exit 1
        fi
    fi
}

build_termux_proot() {
    local source_root="$1"
    local ndk_prebuilt_root="$2"
    local include_root="$3"
    local lib_root="$4"
    local output_root="$5"
    local wrapper_root="$6"

    prepare_ndk_wrappers "$ndk_prebuilt_root" "$wrapper_root"
    patch_proot_source "$source_root"

    export PATH="$wrapper_root:$PATH"
    export CC="$wrapper_root/clang"
    export LD="$wrapper_root/clang"
    export STRIP="$wrapper_root/strip"
    export OBJCOPY="$wrapper_root/objcopy"
    export OBJDUMP="$wrapper_root/objdump"
    export CPPFLAGS="-I$include_root -DARG_MAX=131072"
    export LDFLAGS="-L$lib_root"

    # 保留 files/usr 作为编译期 fallback loader 路径；真正运行时 loader 会由 App 注入 PROOT_LOADER 指向 nativeLibraryDir。
    (
        cd "$source_root/src"
        make clean
        make PROOT_UNBUNDLE_LOADER="$runtime_loader_dir" build.h
        make PROOT_UNBUNDLE_LOADER="$runtime_loader_dir"
    )

    mkdir -p "$output_root/bin" "$output_root/libexec/proot"
    cp "$source_root/src/proot" "$output_root/bin/proot"
    cp "$source_root/src/loader/loader" "$output_root/libexec/proot/loader"
    if [[ -f "$source_root/src/loader/loader-m32" ]]; then
        cp "$source_root/src/loader/loader-m32" "$output_root/libexec/proot/loader32"
    fi
}

sync_host_runtime_jni_libs() {
    local source_root="$1"
    local destination_root="$2"

    mkdir -p "$destination_root"
    rm -f "$destination_root/libproot.so" "$destination_root/libproot-loader.so" "$destination_root/libproot-loader32.so" "$destination_root/libtalloc_2.so"
    sillydroid_require_command perl

    # Android 只会把 lib*.so 当成 native lib 收进 APK；这里把 libtalloc.so.2 映射成等长的 libtalloc_2.so，
    # 再同步改写 libproot.so 的 NEEDED，确保包管理器能把宿主依赖一起放进 lib/arm64-v8a。
    cp "$source_root/bin/proot" "$destination_root/libproot.so"
    cp "$source_root/libexec/proot/loader" "$destination_root/libproot-loader.so"
    assert_path_exists "$source_root/lib/libtalloc.so.2" "缺少 host proot 依赖：$source_root/lib/libtalloc.so.2"
    cp "$source_root/lib/libtalloc.so.2" "$destination_root/libtalloc_2.so"
    perl -0pi -e 's/libtalloc\.so\.2/libtalloc_2.so/g' "$destination_root/libproot.so"
    chmod 0755 "$destination_root/libproot.so" "$destination_root/libproot-loader.so"

    if [[ -f "$source_root/libexec/proot/loader32" ]]; then
        cp "$source_root/libexec/proot/loader32" "$destination_root/libproot-loader32.so"
        chmod 0755 "$destination_root/libproot-loader32.so"
    fi
}

# rootfs/NDK 的大文件缓存默认落在 WSL 本地文件系统，避免 DrvFs 上的大包下载与解压影响 Linux 构建稳定性。
working_root="${SILLYDROID_ANDROID_ROOTFS_WORKDIR:-${XDG_CACHE_HOME:-$HOME/.cache}/sillydroid-android-rootfs}"
downloads_root="$working_root/downloads"
apt_indexes_root="$working_root/apt-indexes"
apt_packages_root="$downloads_root/apt"
proot_build_root="$working_root/proot-build"
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

proot_package_path="$downloads_root/$(basename "$proot_package_url")"
proot_source_archive_path="$downloads_root/$(basename "$proot_source_url")"
termux_packages_index_path="$downloads_root/$(basename "$termux_packages_index_url")"
ubuntu_archive_path="$downloads_root/$(basename "$ubuntu_base_url")"

existing_manifest_path="$resolved_target_root/rootfs-manifest.json"
if [[ -f "$existing_manifest_path" ]] \
    && grep -Fq '"baseFlavor": "termux"' "$existing_manifest_path" \
    && grep -Fq "$termux_packages_index_url" "$existing_manifest_path" \
    && grep -Fq "$proot_source_url" "$existing_manifest_path" \
    && grep -Fq "$proot_patch_signature" "$existing_manifest_path" \
    && grep -Fq "$termux_guest_runtime_prefix" "$existing_manifest_path" \
    && grep -Fq '"hostRuntimeEntry": "nativeLibraryDir"' "$existing_manifest_path" \
    && [[ -f "$rootfs_fs_archive_path" ]] \
    && [[ -f "$host_prefix_archive_path" ]] \
    && [[ -f "$resolved_jni_libs_root/libproot.so" ]] \
    && [[ -f "$resolved_jni_libs_root/libproot-loader.so" ]]
then
    sillydroid_log "Android rootfs assets are up to date, skipping sync."
    exit 0
fi

mkdir -p "$downloads_root" "$apt_indexes_root" "$apt_packages_root"
sillydroid_download_queue_reset
sillydroid_queue_download_if_missing "$proot_package_url" "$proot_package_path" 'proot-termux'
sillydroid_queue_download_if_missing "$proot_source_url" "$proot_source_archive_path" 'proot-source'
sillydroid_queue_download_if_missing "$termux_packages_index_url" "$termux_packages_index_path" 'termux-packages-index'
sillydroid_run_download_queue

sillydroid_ensure_java_home
sillydroid_log "开始准备 Android SDK/NDK 工具链"
android_sdk_root="$(sillydroid_resolve_linux_android_sdk_root)"
sillydroid_ensure_linux_android_sdk "$android_sdk_root"
ndk_root="$(sillydroid_ensure_linux_android_ndk_root "$android_sdk_root")"
ndk_prebuilt_root="$(resolve_android_ndk_prebuilt_root "$ndk_root")"
jar_path="$JAVA_HOME/bin/jar"
assert_path_exists "$jar_path" "缺少 jar 命令。Android rootfs 资产归档要求当前 Linux 环境提供 JDK。"

rm -rf "$assets_stage_root" "$termux_extract_root" "$proot_build_root" "$rootfs_extract_root"
mkdir -p "$host_prefix_stage_root/lib" "$rootfs_fs_stage_root" "$termux_extract_root" "$proot_build_root" "$rootfs_extract_root"

sillydroid_log "开始解包 Termux proot 与运行时依赖"
expand_deb_archive "$proot_package_path" "$termux_extract_root/proot-deb" 'termux:proot-deb'
proot_control_archive_path="$(find "$termux_extract_root/proot-deb" -maxdepth 1 -type f -name 'control.tar*' | head -n 1)"
assert_path_exists "$proot_control_archive_path" "control.tar archive was not found in $proot_package_path"

proot_depends_value="$(tar -xJOf "$proot_control_archive_path" ./control 2>/dev/null | awk -F': ' '$1 == "Depends" { print $2 }')"
if [[ -z "$proot_depends_value" ]]; then
    proot_depends_value="$(tar -xJOf "$proot_control_archive_path" control 2>/dev/null | awk -F': ' '$1 == "Depends" { print $2 }')"
fi

load_termux_package_table "$termux_packages_index_path" 'https://packages.termux.dev/apt/termux-main'
mapfile -t proot_dependency_names < <(normalize_dependency_names "$proot_depends_value")
mapfile -t resolved_termux_dependency_names < <(resolve_termux_package_dependencies "${proot_dependency_names[@]}")
mapfile -t resolved_termux_base_package_names < <(resolve_termux_package_dependencies "${termux_base_packages[@]}")

declare -A required_termux_packages=()
resolved_termux_package_names=()
for package_name in "${resolved_termux_dependency_names[@]}" "${resolved_termux_base_package_names[@]}"; do
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

rm -rf "$proot_build_root/source"
mkdir -p "$proot_build_root/source"
sillydroid_extract_archive_with_progress "$proot_source_archive_path" "$proot_build_root/source" 'proot-source'
proot_source_root="$(find "$proot_build_root/source" -mindepth 1 -maxdepth 1 -type d -name 'proot-*' | LC_ALL=C sort | head -n 1)"
assert_path_exists "$proot_source_root" "Unable to extract Termux proot source from $proot_source_archive_path"

libtalloc_include_root="$proot_build_root/libtalloc-include"
libtalloc_lib_root="$proot_build_root/libtalloc-lib"
rm -rf "$libtalloc_include_root" "$libtalloc_lib_root"
mkdir -p "$libtalloc_include_root" "$libtalloc_lib_root"

for dependency_name in "${resolved_termux_dependency_names[@]}"; do
    dependency_data_root="$termux_extract_root/$dependency_name-data"
    while IFS= read -r include_dir; do
        copy_resolved_directory_contents "$include_dir" "$libtalloc_include_root"
    done < <(find "$dependency_data_root" -type d -path '*/usr/include' | LC_ALL=C sort)
done
copy_resolved_directory_contents "$host_prefix_stage_root/lib" "$libtalloc_lib_root"

sillydroid_log "开始编译 proot 原生库"
build_termux_proot "$proot_source_root" "$ndk_prebuilt_root" "$libtalloc_include_root" "$libtalloc_lib_root" "$host_prefix_stage_root" "$proot_build_root/toolwrap"
sync_host_runtime_jni_libs "$host_prefix_stage_root" "$resolved_jni_libs_root"

sillydroid_log '开始组装 Termux guest rootfs skeleton'
install_termux_guest_rootfs_shims "$host_prefix_stage_root" "$rootfs_fs_stage_root"

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
termux_base_version="$(extract_termux_package_version dash "${termux_filename_by_package[dash]:-}")"
if [[ -n "$termux_base_version" ]]; then
    termux_base_version="stable-dash.$termux_base_version"
else
    termux_base_version='stable'
fi
proot_package_version="$(printf '%s' "$proot_package_url" | sed -n 's#.*/proot_\([^_]*\)_aarch64\.deb#\1#p')"
runtime_version="$SILLYDROID_ROOTFS_VERSION"

manifest_path="$resolved_target_root/rootfs-manifest.json"

{
    printf '{\n'
    printf '  "staiRootfsVersion": "%s",\n' "$(json_escape "$SILLYDROID_ROOTFS_VERSION")"
    printf '  "runtimeVersion": "%s",\n' "$(json_escape "$runtime_version")"
    printf '  "baseFlavor": "termux",\n'
    printf '  "baseVersion": "%s",\n' "$(json_escape "$termux_base_version")"
    printf '  "baseSourceUrl": "%s",\n' "$(json_escape "$termux_packages_index_url")"
    printf '  "ubuntuBaseVersion": "",\n'
    printf '  "prootVersion": "%s",\n' "$(json_escape "$proot_package_version")"
    printf '  "ubuntuBaseUrl": "",\n'
    printf '  "ubuntuPortsBaseUrl": "",\n'
    printf '  "prootPackageUrl": "%s",\n' "$(json_escape "$proot_package_url")"
    printf '  "prootSourceUrl": "%s",\n' "$(json_escape "$proot_source_url")"
    printf '  "prootPatchSignature": "%s",\n' "$(json_escape "$proot_patch_signature")"
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
    printf '  }\n'
    printf '}\n'
} > "$manifest_path"

sillydroid_log "已打包 Android rootfs 资产：$rootfs_fs_archive_path 和 $host_prefix_archive_path"
printf 'Packed Android rootfs assets into %s and %s\n' "$rootfs_fs_archive_path" "$host_prefix_archive_path"
