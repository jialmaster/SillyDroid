#!/usr/bin/env bash
set -euo pipefail

# Stage Contract: 2/4 Dependency Packs
# Responsibilities:
# - Produce only reusable dependency pack archives and manifests.
# - Write stage-2 outputs to artifacts/releases/dependency-packs/<rid>/...
# Must not:
# - Build the runtime image/rootfs.
# - Sync Tavern server source.
# - Compose final server-payload or assemble the APK.

runtime_rid='linux-arm64'
include_packs='node,git'
include_explicit='0'

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workspace_root="$(cd "$script_dir/.." && pwd)"
target_root="$workspace_root/artifacts/releases/dependency-packs/$runtime_rid"
working_root="${SILLYDROID_TAVERN_ANDROID_DEPENDENCY_PACKS_WORK_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/sillydroid-tavern-dependency-packs}"
build_config_path="$workspace_root/sillydroid-build-config.json"
ubuntu_ports_base_url='https://ports.ubuntu.com/ubuntu-ports'

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

read_build_config_value() {
    local key_path="$1"
    local default_value="$2"

    if ! command -v python3 >/dev/null 2>&1; then
        printf '%s\n' "$default_value"
        return
    fi

    python3 "$workspace_root/scripts/read-sillydroid-build-config.py" "$build_config_path" "$key_path" "$default_value"
}

source_android_build_common() {
    local common_script="$workspace_root/scripts/android-build-common.sh"

    # Windows 工作树里的公共 bash 脚本可能是 CRLF；这里用临时 LF 副本加载，避免回写旧脚本。
    # shellcheck disable=SC1090
    source <(tr -d '\r' < "$common_script")
}

source_android_build_common

usage() {
    cat <<'EOF'
Usage: build-tavern-dependency-packs.sh [--runtime-rid linux-arm64] [--target-root <path>] [--working-root <path>] [--include <comma-separated>]

说明：
- 依赖包输出目录默认为 artifacts/releases/dependency-packs/<rid>。
- 未传 --include 时，优先读取 sillydroid-build-config.json 的 build.includeDependencyPacks。
- 若配置项缺失或格式非法，则回退默认 node,git；若显式配置为空数组，则禁用 dependency packs。
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --runtime-rid)
            runtime_rid="$2"
            shift 2
            ;;
        --target-root)
            target_root="$2"
            shift 2
            ;;
        --working-root)
            working_root="$2"
            shift 2
            ;;
        --include)
            include_packs="$2"
            include_explicit='1'
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

case "$runtime_rid" in
    linux-arm64)
        ;;
    *)
        echo "Unsupported runtime RID: $runtime_rid" >&2
        exit 1
        ;;
esac

if [[ "$include_explicit" != '1' ]]; then
    if ! command -v python3 >/dev/null 2>&1; then
        include_packs='node,git'
    else
        include_packs="$(python3 - "$build_config_path" <<'PY'
import json
import pathlib
import sys

config_path = pathlib.Path(sys.argv[1])
if not config_path.exists():
    print('node,git')
    raise SystemExit(0)

try:
    payload = json.loads(config_path.read_text(encoding='utf-8'))
except Exception:
    print('node,git')
    raise SystemExit(0)

items = (((payload or {}).get('build') or {}).get('includeDependencyPacks'))
if items is None:
    print('node,git')
    raise SystemExit(0)

if not isinstance(items, list):
    print('node,git')
    raise SystemExit(0)

normalized = [str(item).strip() for item in items if str(item).strip()]
print(','.join(normalized))
PY
)"
    fi
fi

json_escape() {
    local value="$1"
    value=${value//\\/\\\\}
    value=${value//"/\\"}
    value=${value//$'\n'/\\n}
    value=${value//$'\r'/\\r}
    value=${value//$'\t'/\\t}
    printf '%s' "$value"
}

sillydroid_ensure_java_home
sillydroid_require_command tar
sillydroid_require_command find
sillydroid_require_command unzip

resolved_target_root="$(realpath -m "$target_root")"
resolved_working_root="$(realpath -m "$working_root")"
downloads_root="$resolved_working_root/downloads"
extract_root="$resolved_working_root/extract"

rm -rf "$resolved_target_root" "$extract_root"
mkdir -p "$resolved_target_root" "$downloads_root" "$extract_root"

manifest_files=()
archive_files=()

declare -A apt_filename_by_package=()
declare -A apt_depends_by_package=()
declare -A apt_predepends_by_package=()
declare -A apt_repo_by_package=()

pack_is_selected() {
    local pack_name="$1"
    local token

    IFS=',' read -r -a tokens <<< "$include_packs"
    for token in "${tokens[@]}"; do
        token="$(printf '%s' "$token" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
        if [[ "$token" == "$pack_name" ]]; then
            return 0
        fi
    done

    return 1
}

expand_gzip_file() {
    local archive_path="$1"
    local destination_path="$2"

    mkdir -p "$(dirname "$destination_path")"
    gzip -dc "$archive_path" > "$destination_path"
}

expand_deb_archive() {
    local package_path="$1"
    local destination_root="$2"

    rm -rf "$destination_root"
    mkdir -p "$destination_root"
    (
        cd "$destination_root"
        ar x "$package_path"
    )
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

deduplicate_pack_files() {
    local pack_root="$1"
    local link_map_relative_path="$2"
    local link_map_path="$pack_root/$link_map_relative_path"

    if ! command -v python3 >/dev/null 2>&1; then
        return 0
    fi

    python3 - "$pack_root" "$link_map_path" <<'PY'
import hashlib
import os
import pathlib
import sys

pack_root = pathlib.Path(sys.argv[1])
link_map_path = pathlib.Path(sys.argv[2])


def choose_canonical(paths: list[str]) -> str:
    if "git/bin/git" in paths:
        return "git/bin/git"
    return sorted(
        paths,
        key=lambda path: (-len(pathlib.PurePosixPath(path).name), -len(path), path),
    )[0]


groups: dict[tuple[int, str], list[str]] = {}
for path in sorted(pack_root.rglob("*")):
    if not path.is_file() or path == link_map_path:
        continue

    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)

    key = (path.stat().st_size, digest.hexdigest())
    groups.setdefault(key, []).append(path.relative_to(pack_root).as_posix())

saved_bytes = 0
deduped_files = 0
link_rows: list[tuple[str, str, str]] = []

for (size_bytes, _digest), paths in groups.items():
    if len(paths) < 2:
        continue

    canonical = choose_canonical(paths)
    canonical_path = pathlib.PurePosixPath(canonical)

    for path in paths:
        if path == canonical:
            continue

        duplicate_path = pathlib.PurePosixPath(path)
        link_target = os.path.relpath(canonical_path, start=duplicate_path.parent or pathlib.PurePosixPath("."))
        link_rows.append((canonical, path, link_target))
        (pack_root / duplicate_path).unlink()
        saved_bytes += size_bytes
        deduped_files += 1

if link_rows:
    link_map_path.parent.mkdir(parents=True, exist_ok=True)
    with link_map_path.open("w", encoding="utf-8", newline="\n") as handle:
        for canonical, duplicate, link_target in sorted(link_rows, key=lambda row: row[1]):
            handle.write(f"{canonical}\t{duplicate}\t{link_target}\n")
else:
    link_map_path.unlink(missing_ok=True)

print(f"files={deduped_files} saved_bytes={saved_bytes}")
PY
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

if pack_is_selected 'node'; then
    node_pack_root="$extract_root/node"
    node_archive_path="$downloads_root/node-v${SILLYDROID_NODE_VERSION}-linux-arm64.tar.xz"
    node_archive_url="https://nodejs.org/dist/v${SILLYDROID_NODE_VERSION}/node-v${SILLYDROID_NODE_VERSION}-linux-arm64.tar.xz"
    node_manifest_path="$resolved_target_root/node.manifest.json"
    node_pack_archive_name="dependency-node-v${SILLYDROID_NODE_VERSION}-${runtime_rid}.zip"
    node_pack_archive_path="$resolved_target_root/$node_pack_archive_name"

    sillydroid_progress_stage 1 3 "准备 node dependency pack 下载"
    sillydroid_download_file_if_missing "$node_archive_url" "$node_archive_path"

    rm -rf "$node_pack_root"
    mkdir -p "$node_pack_root/node"
    sillydroid_progress_stage 2 3 "开始解压 node runtime"
    sillydroid_extract_archive_with_progress "$node_archive_path" "$node_pack_root/node" 'node-runtime' 1
    sillydroid_assert_path_exists "$node_pack_root/node/bin/node" "Node runtime 解压失败：$node_archive_path"
    cat > "$node_pack_root/node/sillydroid-post-extract.sh" <<'EOF'
#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"

if [ -d "$SCRIPT_DIR/bin" ]; then
    find "$SCRIPT_DIR/bin" -maxdepth 1 -type f -exec chmod 0755 {} +
fi
EOF

    mapfile -t node_packaged_files < <(find "$node_pack_root" -type f -printf '%P\n' | LC_ALL=C sort)
    sillydroid_progress_stage 3 3 "开始归档 node dependency pack"
    "$JAVA_HOME/bin/jar" --create --file "$node_pack_archive_path" --no-manifest -C "$node_pack_root" .

    {
        printf '{\n'
        printf '  "name": "node",\n'
        printf '  "version": "%s",\n' "$(json_escape "$SILLYDROID_NODE_VERSION")"
        printf '  "runtimeRid": "%s",\n' "$(json_escape "$runtime_rid")"
        printf '  "archiveFile": "%s",\n' "$(json_escape "$node_pack_archive_name")"
        printf '  "checksum": "",\n'
        printf '  "requires": [],\n'
        printf '  "conflicts": [],\n'
        printf '  "env": {\n'
        printf '    "pathPrepend": ["/tavern/server/node/bin"],\n'
        printf '    "variables": {"NODE_HOME": "/tavern/server/node", "TAVERN_NODE_BIN": "/tavern/server/node/bin/node"}\n'
        printf '  },\n'
        printf '  "postExtractScripts": ["node/sillydroid-post-extract.sh"],\n'
        printf '  "files": [\n'
        node_file_count="${#node_packaged_files[@]}"
        for index in "${!node_packaged_files[@]}"; do
            suffix=','
            if [[ "$index" -eq $((node_file_count - 1)) ]]; then
                suffix=''
            fi
            printf '    "%s"%s\n' "$(json_escape "${node_packaged_files[$index]}")" "$suffix"
        done
        printf '  ]\n'
        printf '}\n'
    } > "$node_manifest_path"

    manifest_files+=("$node_manifest_path")
    archive_files+=("$node_pack_archive_path")
fi

if pack_is_selected 'git'; then
    git_pack_root="$extract_root/git"
    git_extract_root="$extract_root/git-apt"
    git_manifest_path="$resolved_target_root/git.manifest.json"
    git_pack_archive_name=''
    git_pack_archive_path="$resolved_target_root/$git_pack_archive_name"
    apt_indexes_root="$resolved_working_root/apt-indexes"
    apt_packages_root="$downloads_root/apt"
    git_version=''

    mkdir -p "$apt_indexes_root" "$apt_packages_root"

    sillydroid_log "开始准备 git dependency pack 索引"
    sillydroid_download_queue_reset
    for ((index = 0; index < ${#apt_repo_names[@]}; index++)); do
        repo_name="${apt_repo_names[$index]}"
        repo_url="${apt_repo_urls[$index]}"
        repo_archive_path="$apt_indexes_root/$repo_name.Packages.gz"
        sillydroid_queue_download_if_missing "$repo_url" "$repo_archive_path" "apt-index:$repo_name"
    done
    sillydroid_run_download_queue

    apt_filename_by_package=()
    apt_depends_by_package=()
    apt_predepends_by_package=()
    apt_repo_by_package=()
    for ((index = 0; index < ${#apt_repo_names[@]}; index++)); do
        repo_name="${apt_repo_names[$index]}"
        repo_archive_path="$apt_indexes_root/$repo_name.Packages.gz"
        repo_index_path="$apt_indexes_root/$repo_name.Packages"
        expand_gzip_file "$repo_archive_path" "$repo_index_path"
        add_apt_package_entries_from_index "$repo_index_path" "${apt_repo_bases[$index]}"
    done

    mapfile -t resolved_git_package_names < <(resolve_apt_package_dependencies git)
    if [[ "${#resolved_git_package_names[@]}" -eq 0 ]]; then
        sillydroid_fail '未能解析 git dependency pack 的 apt 依赖。'
    fi

    sillydroid_log "开始下载 ${#resolved_git_package_names[@]} 个 git apt 包"
    sillydroid_download_queue_reset
    for package_name in "${resolved_git_package_names[@]}"; do
        package_path="$apt_packages_root/$(basename "${apt_filename_by_package[$package_name]}")"
        package_url="${apt_repo_by_package[$package_name]}/${apt_filename_by_package[$package_name]}"
        sillydroid_queue_download_if_missing "$package_url" "$package_path" "apt:$package_name"
    done
    sillydroid_run_download_queue

    rm -rf "$git_pack_root" "$git_extract_root"
    mkdir -p "$git_pack_root/git/bin" "$git_pack_root/git/lib" "$git_pack_root/git/libexec/git-core" "$git_pack_root/git/share/git-core" "$git_extract_root"

    declare -A git_data_root_by_package=()
    sillydroid_log "开始展开 ${#resolved_git_package_names[@]} 个 git apt 包"
    for package_name in "${resolved_git_package_names[@]}"; do
        package_path="$apt_packages_root/$(basename "${apt_filename_by_package[$package_name]}")"
        package_extract_root="$git_extract_root/$package_name"
        package_deb_root="$package_extract_root/deb"
        package_data_root="$package_extract_root/data"

        expand_deb_archive "$package_path" "$package_deb_root"
        package_data_archive_path="$(find "$package_deb_root" -maxdepth 1 -type f -name 'data.tar*' | head -n 1)"
        sillydroid_assert_path_exists "$package_data_archive_path" "data.tar archive was not found in $package_path"
        sillydroid_extract_archive_with_progress "$package_data_archive_path" "$package_data_root" "git:$package_name-data"
        git_data_root_by_package["$package_name"]="$package_data_root"
    done

    git_root_data_root="${git_data_root_by_package[git]:-}"
    sillydroid_assert_path_exists "$git_root_data_root/usr/bin/git" '缺少 git 主二进制：usr/bin/git'
    cp -a "$git_root_data_root/usr/bin/git" "$git_pack_root/git/bin/git"
    chmod 0755 "$git_pack_root/git/bin/git"

    if [[ -d "$git_root_data_root/usr/lib/git-core" ]]; then
        copy_resolved_directory_contents "$git_root_data_root/usr/lib/git-core" "$git_pack_root/git/libexec/git-core"
    fi
    if [[ -d "$git_root_data_root/usr/share/git-core" ]]; then
        copy_resolved_directory_contents "$git_root_data_root/usr/share/git-core" "$git_pack_root/git/share/git-core"
    fi

    for package_name in "${resolved_git_package_names[@]}"; do
        package_data_root="${git_data_root_by_package[$package_name]}"
        while IFS= read -r lib_dir; do
            copy_resolved_directory_contents "$lib_dir" "$git_pack_root/git/lib"
        done < <(find "$package_data_root" -type d \( -path '*/lib/aarch64-linux-gnu' -o -path '*/usr/lib/aarch64-linux-gnu' \) | LC_ALL=C sort)
    done

    cat > "$git_pack_root/git/sillydroid-post-extract.sh" <<'EOF'
#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
LINK_MAP_PATH="$SCRIPT_DIR/.sillydroid-dedup-links.tsv"

if [ -f "$LINK_MAP_PATH" ]; then
    TAB_CHAR="$(printf '\t')"
    while IFS="$TAB_CHAR" read -r CANONICAL_REL DUPLICATE_REL LINK_TARGET; do
        [ -n "$CANONICAL_REL" ] || continue

        CANONICAL_PATH="$SCRIPT_DIR/$CANONICAL_REL"
        DUPLICATE_PATH="$SCRIPT_DIR/$DUPLICATE_REL"
        mkdir -p "$(dirname "$DUPLICATE_PATH")"
        rm -f "$DUPLICATE_PATH"

        if ln -sfn "$LINK_TARGET" "$DUPLICATE_PATH" 2>/dev/null; then
            :
        elif ln -f "$CANONICAL_PATH" "$DUPLICATE_PATH" 2>/dev/null; then
            :
        else
            cp -f "$CANONICAL_PATH" "$DUPLICATE_PATH"
            chmod --reference="$CANONICAL_PATH" "$DUPLICATE_PATH" 2>/dev/null || true
        fi
    done < "$LINK_MAP_PATH"
    rm -f "$LINK_MAP_PATH"
fi

if [ -d "$SCRIPT_DIR/bin" ]; then
    find "$SCRIPT_DIR/bin" -maxdepth 1 -type f -exec chmod 0755 {} +
fi

if [ -d "$SCRIPT_DIR/libexec/git-core" ]; then
    find "$SCRIPT_DIR/libexec/git-core" -type f -exec chmod 0755 {} +
fi
EOF
    git_version="$(printf '%s' "$(basename "${apt_filename_by_package[git]}")" | sed -E 's/^git_([^_]+)_.*$/\1/')"
    safe_git_version="$(printf '%s' "$git_version" | sed -E 's/[^A-Za-z0-9._-]+/-/g')"
    git_pack_archive_name="dependency-git-v${safe_git_version}-${runtime_rid}.zip"
    git_pack_archive_path="$resolved_target_root/$git_pack_archive_name"

    mapfile -t git_packaged_files < <(find "$git_pack_root" -type f -printf '%P\n' | LC_ALL=C sort)
    dedup_report="$(deduplicate_pack_files "$git_pack_root/git" '.sillydroid-dedup-links.tsv')"
    if [[ -n "$dedup_report" ]]; then
        sillydroid_log "git dependency pack 去重结果：$dedup_report"
    fi
    sillydroid_log "开始归档 git dependency pack：$git_pack_archive_path"
    "$JAVA_HOME/bin/jar" --create --file "$git_pack_archive_path" --no-manifest -C "$git_pack_root" .

    {
        printf '{\n'
        printf '  "name": "git",\n'
        printf '  "version": "%s",\n' "$(json_escape "$git_version")"
        printf '  "runtimeRid": "%s",\n' "$(json_escape "$runtime_rid")"
        printf '  "archiveFile": "%s",\n' "$(json_escape "$git_pack_archive_name")"
        printf '  "checksum": "",\n'
        printf '  "requires": [],\n'
        printf '  "conflicts": [],\n'
        printf '  "env": {\n'
        printf '    "pathPrepend": ["/tavern/server/git/bin"],\n'
        printf '    "variables": {"GIT_EXEC_PATH": "/tavern/server/git/libexec/git-core", "GIT_TEMPLATE_DIR": "/tavern/server/git/share/git-core/templates", "LD_LIBRARY_PATH": "/tavern/server/git/lib${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}", "TAVERN_GIT_BIN": "/tavern/server/git/bin/git"}\n'
        printf '  },\n'
        printf '  "postExtractScripts": ["git/sillydroid-post-extract.sh"],\n'
        printf '  "files": [\n'
        git_file_count="${#git_packaged_files[@]}"
        for index in "${!git_packaged_files[@]}"; do
            suffix=','
            if [[ "$index" -eq $((git_file_count - 1)) ]]; then
                suffix=''
            fi
            printf '    "%s"%s\n' "$(json_escape "${git_packaged_files[$index]}")" "$suffix"
        done
        printf '  ]\n'
        printf '}\n'
    } > "$git_manifest_path"

    manifest_files+=("$git_manifest_path")
    archive_files+=("$git_pack_archive_path")
fi

if [[ "${#archive_files[@]}" -eq 0 ]]; then
    sillydroid_log '当前未选择任何 dependency pack；将只生成空 component-index.json。'
fi

component_index_path="$resolved_target_root/component-index.json"
{
    printf '{\n'
    printf '  "runtimeRid": "%s",\n' "$(json_escape "$runtime_rid")"
    printf '  "generatedAtUtc": "%s",\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    printf '  "dependencyPacks": [\n'
    for index in "${!manifest_files[@]}"; do
        manifest_file="${manifest_files[$index]}"
        manifest_name="$(basename "$manifest_file")"
        archive_name="$(basename "${archive_files[$index]}")"
        suffix=','
        if [[ "$index" -eq $((${#manifest_files[@]} - 1)) ]]; then
            suffix=''
        fi
        printf '    {"archiveFile": "%s", "manifestFile": "%s"}%s\n' \
            "$(json_escape "$archive_name")" \
            "$(json_escape "$manifest_name")" \
            "$suffix"
    done
    printf '  ]\n'
    printf '}\n'
} > "$component_index_path"

printf 'Built dependency packs (%s):\n' "$runtime_rid"
for archive_path in "${archive_files[@]}"; do
    printf '  - %s\n' "$archive_path"
done
