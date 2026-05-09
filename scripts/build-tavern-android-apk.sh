#!/usr/bin/env bash
set -euo pipefail

# Stage Contract: 4/4 APK Assembly
# Responsibilities:
# - Consume stage-1 runtime image, stage-2 dependency packs, and stage-3 server source.
# - Compose the final server-payload only inside stage-4 temporary output, inject assets, and assemble the APK.
# Must not:
# - Implicitly rebuild or refresh stage-1/stage-2/stage-3 prerequisites.
# - Move stage-3 responsibilities back into this script's callers or into earlier stages.

runtime_rid='linux-arm64'
build_type=''
runtime_image_path=''
server_package_path=''
tavern_tag=''
dependency_packs_override=''
default_android_build_root="${SILLYDROID_TAVERN_ANDROID_BUILD_ROOT:-${SILLYDROID_ANDROID_BUILD_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/sillydroid-tavern-android-build}}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workspace_root="$(cd "$script_dir/.." && pwd)"
workspace_android_root="$workspace_root/android-tavern"
android_root="$(realpath -m "$default_android_build_root/android-project/android-tavern")"
sync_server_script="$workspace_root/scripts/sync-tavern-android-bootstrap.sh"
dependency_pack_script="$workspace_root/scripts/build-tavern-dependency-packs.sh"
build_config_path="$workspace_root/sillydroid-build-config.json"
rootfs_manifest_path="$workspace_android_root/app/src/main/assets/bootstrap/rootfs/rootfs-manifest.json"

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
Usage: build-tavern-android-apk.sh [--runtime-image <path>] [--server-package <path> | --tag <sillytavern-tag>] [--runtime-rid linux-arm64] [--build-type debug|release] [--dependency-packs <comma-separated>]

说明：
- runtime image / dependency packs 只负责 Termux rootfs、环境依赖、环境修复脚本；server source 只负责 Tavern 源码与 server overlay。
- Android Host 扩展与默认扩展列表在 APK build 阶段分别写入独立 assets 目录，不再混入 server source。
- 这是纯 stage 4 脚本，只消费现有 runtime image、server source 与 dependency packs，然后把它们合并进 android-tavern 工程。
- 若不传 --runtime-image，默认读取 artifacts/releases/rootfs/<rid>/tavern-rootfs-<rid>.zip；缺失时会直接报错。
- 若不传 --server-package，则默认读取 artifacts/releases/server-source/<rid>/<tag>/server-source.zip，并从 artifacts/releases/dependency-packs/<rid> 组合出最终 server payload。
- build.includeDependencyPacks 若显式配置为空数组，则跳过 dependency packs，直接依赖 rootfs 提供运行时。
- 若显式传 --server-package，则直接把该归档写入 Android 工程，不再读取 dependency packs 目录。
- 若不传 --build-type，则优先读取仓库根目录 sillydroid-build-config.json 的 build.buildType。
- 缺少前置物料时会直接报错，并提示对应上一阶段命令；不会隐式触发下载或构建。
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --runtime-rid)
            runtime_rid="$2"
            shift 2
            ;;
        --build-type)
            build_type="$2"
            shift 2
            ;;
        --runtime-image)
            runtime_image_path="$2"
            shift 2
            ;;
        --server-package)
            server_package_path="$2"
            shift 2
            ;;
        --tag)
            tavern_tag="$2"
            shift 2
            ;;
        --dependency-packs)
            dependency_packs_override="$2"
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

configured_build_type="$(read_build_config_value 'build.buildType' 'release')"
configured_tavern_tag="$(read_build_config_value 'build.tavernVersion' 'latest')"

read_termux_packages_from_config() {
    if ! command -v python3 >/dev/null 2>&1; then
        printf 'git\nnodejs-lts\nnano\nbash\ndash\ncoreutils\nfindutils\ngrep\nsed\ngawk\ntar\ngzip\nxz-utils\nwhich\nca-certificates\n'
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

read_rootfs_base_flavor() {
    if command -v python3 >/dev/null 2>&1; then
        python3 - "$runtime_image_path" "$rootfs_manifest_path" <<'PY'
import json
import pathlib
import sys
import zipfile

runtime_image = pathlib.Path(sys.argv[1])
workspace_manifest_path = pathlib.Path(sys.argv[2])
payload = None

if runtime_image.is_file():
    try:
        with zipfile.ZipFile(runtime_image) as archive:
            payload = json.loads(archive.read("assets/bootstrap/rootfs/rootfs-manifest.json").decode("utf-8"))
    except Exception:
        payload = None

if payload is None and workspace_manifest_path.is_file():
    try:
        payload = json.loads(workspace_manifest_path.read_text(encoding="utf-8"))
    except Exception:
        payload = None

if not isinstance(payload, dict):
    raise SystemExit(0)

value = str(payload.get("baseFlavor") or "").strip()
if value:
    print(value)
PY
        return
    fi

    if [[ -f "$rootfs_manifest_path" ]]; then
        sed -n 's/^[[:space:]]*"baseFlavor":[[:space:]]*"\([^"]*\)".*$/\1/p' "$rootfs_manifest_path" | head -n 1
    fi
}

read_rootfs_termux_packages() {
    if command -v python3 >/dev/null 2>&1; then
        python3 - "$runtime_image_path" "$rootfs_manifest_path" <<'PY'
import json
import pathlib
import sys
import zipfile

runtime_image = pathlib.Path(sys.argv[1])
workspace_manifest_path = pathlib.Path(sys.argv[2])
payload = None

if runtime_image.is_file():
    try:
        with zipfile.ZipFile(runtime_image) as archive:
            payload = json.loads(archive.read("assets/bootstrap/rootfs/rootfs-manifest.json").decode("utf-8"))
    except Exception:
        payload = None

if payload is None and workspace_manifest_path.is_file():
    try:
        payload = json.loads(workspace_manifest_path.read_text(encoding="utf-8"))
    except Exception:
        payload = None

items = (payload or {}).get("termuxBasePackages") if isinstance(payload, dict) else None
if not isinstance(items, list):
    raise SystemExit(0)

seen = set()
for item in items:
    name = str(item).strip()
    if not name or name in seen:
        continue
    seen.add(name)
    print(name)
PY
        return
    fi

    read_termux_packages_from_config
}

rootfs_base_flavor=''
rootfs_provides_node_pack='0'
rootfs_provides_git_pack='0'

read_dependency_packs_from_config() {
    if ! command -v python3 >/dev/null 2>&1; then
        printf 'node\ngit\n'
        return
    fi

    python3 - "$build_config_path" <<'PY'
import json
import pathlib
import sys

config_path = pathlib.Path(sys.argv[1])
default = ["node", "git"]

if not config_path.exists():
    print("\n".join(default))
    raise SystemExit(0)

try:
    data = json.loads(config_path.read_text(encoding="utf-8"))
except Exception:
    print("\n".join(default))
    raise SystemExit(0)

value = (((data or {}).get("build") or {}).get("includeDependencyPacks"))
if value is None:
    print("\n".join(default))
    raise SystemExit(0)

if not isinstance(value, list):
    print("\n".join(default))
    raise SystemExit(0)

items = []
for entry in value:
    if isinstance(entry, str):
        name = entry.strip()
        if name:
            items.append(name)

print("\n".join(items))
PY
}

resolve_dependency_pack_list() {
    local raw_packs=''

    if [[ -n "$dependency_packs_override" ]]; then
        raw_packs="$(printf '%s\n' "$dependency_packs_override" | tr ',' '\n')"
    else
        raw_packs="$(read_dependency_packs_from_config)"
    fi

    printf '%s\n' "$raw_packs" \
        | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//' \
        | awk 'NF > 0' \
        | awk '!seen[$0]++' \
        | while IFS= read -r pack_name; do
            if [[ "$pack_name" == 'node' && "$rootfs_provides_node_pack" == '1' ]]; then
                continue
            fi
            if [[ "$pack_name" == 'git' && "$rootfs_provides_git_pack" == '1' ]]; then
                continue
            fi
            printf '%s\n' "$pack_name"
        done
}

server_source_prepare_hint() {
    printf 'bash ./scripts/sync-tavern-android-bootstrap.sh --runtime-rid %s --tag %s --target-root ./artifacts/releases/server-source/%s/%s' "$runtime_rid" "$tavern_tag" "$runtime_rid" "$tavern_tag"
}

dependency_packs_prepare_hint() {
    if [[ -n "$dependency_packs_override" ]]; then
        printf 'bash ./scripts/build-tavern-dependency-packs.sh --runtime-rid %s --include %s' "$runtime_rid" "$dependency_packs_override"
        return
    fi

    printf 'bash ./scripts/build-tavern-dependency-packs.sh --runtime-rid %s' "$runtime_rid"
}

runtime_image_prepare_hint() {
    printf 'bash ./scripts/build-tavern-android-runtime-image.sh --runtime-rid %s --output ./artifacts/releases/rootfs/%s/tavern-rootfs-%s.zip' "$runtime_rid" "$runtime_rid" "$runtime_rid"
}

input_path_newer_than() {
    local input_path="$1"
    local reference_path="$2"

    [[ -e "$input_path" ]] || return 1

    if [[ -d "$input_path" ]]; then
        find "$input_path" -type f -newer "$reference_path" -print -quit | grep -q .
        return
    fi

    [[ "$input_path" -nt "$reference_path" ]]
}

prepare_staged_android_project() {
    local source_root="$1"
    local staged_root="$2"

    rm -rf "$staged_root"
    mkdir -p "$(dirname "$staged_root")"
    cp -R "$source_root" "$(dirname "$staged_root")/"

    rm -rf \
        "$staged_root/.gradle" \
        "$staged_root/app/build" \
        "$staged_root/app/src/main/assets/bootstrap/bundled-extensions" \
        "$staged_root/app/src/main/assets/bootstrap/default-extensions" \
        "$staged_root/app/src/main/assets/bootstrap/rootfs" \
        "$staged_root/app/src/main/assets/bootstrap/server" \
        "$staged_root/app/src/main/jniLibs/arm64-v8a"

    if [[ -f "$build_config_path" ]]; then
        cp -f "$build_config_path" "$staged_root/sillydroid-build-config.json"
    else
        rm -f "$staged_root/sillydroid-build-config.json"
    fi
}

generated_server_source_satisfy_request() {
    local generated_root="$1"
    local server_source_path="$generated_root/server-source.zip"
    local server_source_manifest_path="$generated_root/server-source-manifest.json"
    local server_overlay_root="$workspace_root/android-tavern/server-overlay"

    [[ -f "$server_source_path" && -f "$server_source_manifest_path" ]] || return 1
    command -v python3 >/dev/null 2>&1 || return 1

    if input_path_newer_than "$sync_server_script" "$server_source_manifest_path" \
        || input_path_newer_than "$server_overlay_root" "$server_source_manifest_path"; then
        return 1
    fi

    python3 - "$server_source_manifest_path" "$runtime_rid" "$tavern_tag" <<'PY'
import json
import sys

payload = json.loads(open(sys.argv[1], 'r', encoding='utf-8').read())
runtime_rid = sys.argv[2]
expected_tag = sys.argv[3]

if str(payload.get('runtimeRid') or '').strip() != runtime_rid:
    raise SystemExit(1)

archive_file = str(payload.get('archiveFile') or '').strip()
if archive_file != 'server-source.zip':
    raise SystemExit(1)

manifest_tag = str(payload.get('tag') or payload.get('payloadVersion') or '').strip()
if expected_tag and manifest_tag and manifest_tag != expected_tag:
    raise SystemExit(1)
PY
}

dependency_packs_satisfy_request() {
    local dependency_root="$1"
    local manifest_path=''
    local archive_file=''
    local pack_name=''
    local -a requested_pack_names=()

    command -v python3 >/dev/null 2>&1 || return 1

    mapfile -t requested_pack_names < <(resolve_dependency_pack_list)
    if [[ "${#requested_pack_names[@]}" -eq 0 ]]; then
        return 0
    fi

    [[ -d "$dependency_root" ]] || return 1

    while IFS= read -r pack_name; do
        [[ -z "$pack_name" ]] && continue
        manifest_path="$dependency_root/$pack_name.manifest.json"
        [[ -f "$manifest_path" ]] || return 1

        if [[ "$dependency_pack_script" -nt "$manifest_path" || "$build_config_path" -nt "$manifest_path" ]]; then
            return 1
        fi

        archive_file="$(python3 - "$manifest_path" <<'PY'
import json
import sys

payload = json.loads(open(sys.argv[1], 'r', encoding='utf-8').read())
archive_file = str(payload.get('archiveFile') or '').strip()
if not archive_file:
    raise SystemExit(1)
print(archive_file)
PY
)" || return 1
        [[ -f "$dependency_root/$archive_file" ]] || return 1
    done < <(printf '%s\n' "${requested_pack_names[@]}")

    return 0
}

compose_server_payload_from_components() {
    local generated_root="$1"
    local dependency_root="$2"
    local output_package_path="$3"
    local compose_root="$(realpath -m "$default_android_build_root/server-compose/$runtime_rid")"
    local server_source_path="$generated_root/server-source.zip"
    local selected_list_path="$compose_root/selected-packs.txt"
    local env_file_path="$compose_root/dependency-env.sh"
    local post_extract_hook_path="$compose_root/dependency-post-extract.sh"
    local selection_manifest_path="$compose_root/dependency-selection.json"
    local composed_manifest_path="$generated_root/bootstrap-manifest.json"
    local output_archive_size_bytes='0'
    local archive_file
    local -a dependency_pack_names=()

    sillydroid_assert_path_exists "$server_source_path" "缺少 server source 产物：$server_source_path"
    sillydroid_require_command unzip
    sillydroid_ensure_java_home

    rm -rf "$compose_root"
    mkdir -p "$compose_root"

    mapfile -t dependency_pack_names < <(resolve_dependency_pack_list)
    if [[ "${#dependency_pack_names[@]}" -gt 0 ]]; then
        sillydroid_assert_path_exists "$dependency_root" "缺少 dependency packs 目录：$dependency_root"
    fi

    if [[ "${#dependency_pack_names[@]}" -eq 0 ]]; then
        if [[ "$rootfs_provides_node_pack" != '1' ]]; then
            sillydroid_fail '当前 server 启动依赖 node 运行时，请在 includeDependencyPacks 中包含 node。'
        fi

        sillydroid_progress_stage 1 4 '未选择 dependency packs；将仅使用 rootfs 运行时。'
        : > "$selected_list_path"
        cat > "$env_file_path" <<'EOF'
#!/bin/sh
set -eu
EOF
        cat > "$post_extract_hook_path" <<'EOF'
#!/bin/sh
set -eu
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"
if [ -f "./tavern-entrypoint.sh" ]; then chmod 0755 "./tavern-entrypoint.sh"; fi
EOF
        cat > "$selection_manifest_path" <<'EOF'
{
  "selected": []
}
EOF
    else
        if ! command -v python3 >/dev/null 2>&1; then
            sillydroid_fail "组合 dependency packs 需要 python3 用于 manifest 校验。"
        fi

        sillydroid_progress_stage 1 4 "开始校验 dependency manifests 并组合 server payload：${dependency_pack_names[*]}"
        python3 - "$dependency_root" "$selected_list_path" "$env_file_path" "$post_extract_hook_path" "$selection_manifest_path" "$rootfs_provides_node_pack" "${dependency_pack_names[@]}" <<'PY'
import json
import pathlib
import sys

dep_root = pathlib.Path(sys.argv[1])
selected_list_path = pathlib.Path(sys.argv[2])
env_path = pathlib.Path(sys.argv[3])
post_extract_hook_path = pathlib.Path(sys.argv[4])
selection_manifest_path = pathlib.Path(sys.argv[5])
rootfs_provides_node_pack = str(sys.argv[6]).strip() == "1"
requested = [item.strip() for item in sys.argv[7:] if item.strip()]

manifests = {}
for manifest_file in sorted(dep_root.glob("*.manifest.json")):
    payload = json.loads(manifest_file.read_text(encoding="utf-8"))
    name = (payload.get("name") or "").strip()
    if not name:
        continue
    payload["__manifestFile"] = manifest_file.name
    manifests[name] = payload

missing = [name for name in requested if name not in manifests]
if missing:
    raise SystemExit(f"缺少依赖包 manifest: {', '.join(missing)}")

selected = [manifests[name] for name in requested]

conflicts = []
selected_names = {entry.get("name") for entry in selected}
for entry in selected:
    name = entry.get("name")
    for conflict in entry.get("conflicts") or []:
        if conflict in selected_names:
            conflicts.append(f"{name} conflicts with {conflict}")

if conflicts:
    raise SystemExit("依赖冲突: " + "; ".join(conflicts))

file_owner = {}
env_vars = {}
path_prepend = []
post_extract_scripts = []
selected_archives = []
selection_summary = []

for entry in selected:
    name = entry.get("name")
    version = str(entry.get("version") or "")
    archive_file = (entry.get("archiveFile") or "").strip()
    if not archive_file:
        raise SystemExit(f"依赖包 {name} 缺少 archiveFile")

    archive_path = dep_root / archive_file
    if not archive_path.exists():
        raise SystemExit(f"依赖包 {name} 缺少归档文件: {archive_file}")

    for file_path in entry.get("files") or []:
        key = str(file_path).strip()
        if not key:
            continue
        previous = file_owner.get(key)
        if previous and previous != name:
            raise SystemExit(f"文件路径冲突: {key} 同时属于 {previous} 和 {name}")
        file_owner[key] = name

    env = entry.get("env") or {}
    for item in env.get("pathPrepend") or []:
        value = str(item).strip()
        if value and value not in path_prepend:
            path_prepend.append(value)

    for key, value in (env.get("variables") or {}).items():
        key = str(key).strip()
        value = str(value)
        if not key:
            continue
        previous = env_vars.get(key)
        if previous is not None and previous != value:
            raise SystemExit(f"环境变量冲突: {key} 的值同时出现 {previous} 与 {value}")
        env_vars[key] = value

    for item in entry.get("postExtractScripts") or []:
        value = str(item).strip().lstrip("./")
        if value and value not in post_extract_scripts:
            post_extract_scripts.append(value)

    selected_archives.append(archive_file)
    selection_summary.append({
        "name": name,
        "version": version,
        "archiveFile": archive_file,
        "manifestFile": entry.get("__manifestFile"),
    })

if not rootfs_provides_node_pack and "node" not in {entry.get("name") for entry in selected}:
    raise SystemExit("当前 server 启动依赖 node 运行时，请在 includeDependencyPacks 中包含 node。")

selected_list_path.write_text("\n".join(selected_archives) + "\n", encoding="utf-8")

env_lines = [
    "#!/bin/sh",
    "set -eu",
]
if path_prepend:
    joined = ":".join(path_prepend)
    env_lines.append(f'export PATH="{joined}${{PATH:+:${{PATH}}}}"')
for key in sorted(env_vars):
    value = env_vars[key].replace('"', '\\"')
    env_lines.append(f'export {key}="{value}"')
env_path.write_text("\n".join(env_lines) + "\n", encoding="utf-8")

post_extract_lines = [
    "#!/bin/sh",
    "set -eu",
    'SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"',
    'cd "$SCRIPT_DIR"',
    'if [ -f "./tavern-entrypoint.sh" ]; then chmod 0755 "./tavern-entrypoint.sh"; fi',
]
for script in post_extract_scripts:
    escaped = script.replace('"', '\\"')
    post_extract_lines.append(f'if [ -f "./{escaped}" ]; then sh "./{escaped}"; fi')
post_extract_hook_path.write_text("\n".join(post_extract_lines) + "\n", encoding="utf-8")

selection_manifest_path.write_text(
    json.dumps({"selected": selection_summary}, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
PY
    fi

    sillydroid_progress_stage 2 4 "开始解包 server source"
    sillydroid_extract_archive_with_progress "$server_source_path" "$compose_root" 'server-source'
    while IFS= read -r archive_file; do
        [[ -z "$archive_file" ]] && continue
        sillydroid_log "导入 dependency pack：$archive_file"
        sillydroid_extract_archive_with_progress "$dependency_root/$archive_file" "$compose_root" "$archive_file"
    done < "$selected_list_path"

mkdir -p "$(dirname "$output_package_path")"
rm -f "$output_package_path"
    sillydroid_progress_stage 3 4 "开始归档组合后的 server payload"
"$JAVA_HOME/bin/jar" --create --file "$output_package_path" --no-manifest -C "$compose_root" .
    output_archive_size_bytes="$(stat -c '%s' "$output_package_path")"

    if command -v python3 >/dev/null 2>&1; then
        python3 - "$selection_manifest_path" "$composed_manifest_path" "$runtime_rid" "$(basename "$output_package_path")" "$output_archive_size_bytes" <<'PY'
import json
import sys
from datetime import datetime, timezone

selection = json.loads(open(sys.argv[1], "r", encoding="utf-8").read())
manifest_path = sys.argv[2]
runtime_rid = sys.argv[3]
archive_file = sys.argv[4]
archive_size_bytes = int(sys.argv[5])

payload = {
    "package": "SillyTavern",
    "payloadVersion": "composed",
    "runtimeRid": runtime_rid,
    "tag": "composed",
    "nodeVersion": next((item.get("version", "") for item in selection.get("selected", []) if item.get("name") == "node"), ""),
    "sourceArchive": "",
    "syncedAtUtc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "archiveFile": archive_file,
    "defaultDependencyPacks": [item.get("name") for item in selection.get("selected", []) if item.get("name")],
    "archivedFileCount": 0,
    "archiveSizeBytes": archive_size_bytes,
}

with open(manifest_path, "w", encoding="utf-8") as handle:
    handle.write(json.dumps(payload, ensure_ascii=False, indent=2))
    handle.write("\n")
PY
    fi

    sillydroid_progress_stage 4 4 "server payload 组合完成"
}

if [[ -z "$build_type" || "$build_type" == 'auto' ]]; then
    build_type="$configured_build_type"
fi

if [[ -z "$build_type" || "$build_type" == 'auto' ]]; then
    build_type='release'
fi

if [[ -z "$tavern_tag" || "$tavern_tag" == 'auto' ]]; then
    tavern_tag="$configured_tavern_tag"
fi

case "$runtime_rid" in
    linux-arm64)
        ;;
    *)
        echo "Unsupported runtime RID: $runtime_rid" >&2
        exit 1
        ;;
esac

case "$build_type" in
    debug|release)
        ;;
    *)
        echo "Unsupported build type: $build_type" >&2
        exit 1
        ;;
esac

if [[ -z "$runtime_image_path" ]]; then
    runtime_image_path="$workspace_root/artifacts/releases/rootfs/$runtime_rid/tavern-rootfs-$runtime_rid.zip"
fi

runtime_image_path="$(realpath -m "$runtime_image_path")"

rootfs_base_flavor="$(read_rootfs_base_flavor)"
if [[ "$rootfs_base_flavor" == 'termux' ]]; then
    while IFS= read -r termux_package_name; do
        case "$termux_package_name" in
            git)
                rootfs_provides_git_pack='1'
                ;;
            nodejs|nodejs-lts|nodejs-current)
                rootfs_provides_node_pack='1'
                ;;
        esac
    done < <(read_rootfs_termux_packages)
fi

if [[ -z "$server_package_path" ]]; then
    generated_server_root="$workspace_root/artifacts/releases/server-source/$runtime_rid/$tavern_tag"
    dependency_packs_root="$workspace_root/artifacts/releases/dependency-packs/$runtime_rid"

    if ! generated_server_source_satisfy_request "$generated_server_root"; then
        sillydroid_fail "缺少可复用的 Tavern server source：$generated_server_root。请先运行 $(server_source_prepare_hint)，或显式传 --server-package。"
    fi
    sillydroid_log "复用已存在的 Tavern server source：$generated_server_root"

    if ! dependency_packs_satisfy_request "$dependency_packs_root"; then
        sillydroid_fail "缺少可复用的 dependency packs：$dependency_packs_root。请先运行 $(dependency_packs_prepare_hint)，或显式传 --server-package。"
    fi
    sillydroid_log "复用已存在的 dependency packs：$dependency_packs_root"

    composed_server_package_path="$(realpath -m "$default_android_build_root/server-compose-output/$runtime_rid/$tavern_tag/server-payload.zip")"
    sillydroid_progress_stage 1 3 "开始组合 Tavern server payload"
    compose_server_payload_from_components "$generated_server_root" "$dependency_packs_root" "$composed_server_package_path"
    server_package_path="$composed_server_package_path"
fi

apply_runtime_image() {
    local image_path="$1"
    local project_root="$2"
    local extract_root="$(realpath -m "$default_android_build_root/runtime-image/$runtime_rid")"
    local bootstrap_root="$project_root/app/src/main/assets/bootstrap"
    local rootfs_root="$bootstrap_root/rootfs"
    local jni_lib_root="$project_root/app/src/main/jniLibs/arm64-v8a"

    sillydroid_assert_path_exists "$image_path" "缺少 Android runtime image：$image_path"
    sillydroid_require_command unzip

    sillydroid_log "开始应用 Tavern runtime image 到 Android 工程：$image_path"
    rm -rf "$extract_root" "$rootfs_root"
    mkdir -p "$extract_root" "$bootstrap_root" "$jni_lib_root"
    sillydroid_extract_archive_with_progress "$image_path" "$extract_root" 'runtime-image'

    sillydroid_assert_path_exists "$extract_root/assets/bootstrap/rootfs/rootfs-fs.zip" "runtime image 缺少 rootfs 资产：$image_path"
    sillydroid_assert_path_exists "$extract_root/jniLibs/arm64-v8a/libproot.so" "runtime image 缺少 jniLibs 资产：$image_path"

    cp -R "$extract_root/assets/bootstrap/rootfs" "$bootstrap_root/"

    rm -f "$jni_lib_root"/libproot.so "$jni_lib_root"/libproot-loader.so "$jni_lib_root"/libproot-loader32.so "$jni_lib_root"/libtalloc_2.so
    cp -R "$extract_root/jniLibs/arm64-v8a/." "$jni_lib_root/"

    sillydroid_log "已应用 Tavern runtime image：$image_path"
}

apply_server_package() {
    local package_path="$1"
    local project_root="$2"
    local server_root="$project_root/app/src/main/assets/bootstrap/server"
    local manifest_path="$server_root/bootstrap-manifest.json"
    local source_manifest_path="$(dirname "$package_path")/bootstrap-manifest.json"
    local archive_file_name='server-payload.zip'
    local archive_path=''
    local archive_size_bytes='0'
    local synced_at_utc="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

    sillydroid_assert_path_exists "$package_path" "缺少 Tavern server 底包：$package_path"

    if [[ -f "$source_manifest_path" ]] && command -v python3 >/dev/null 2>&1; then
        archive_file_name="$(python3 - "$source_manifest_path" <<'PY'
import json
import sys

payload = json.loads(open(sys.argv[1], 'r', encoding='utf-8').read())
archive_file = str(payload.get('archiveFile') or '').strip()
print(archive_file or 'server-payload.zip')
PY
)"
    fi
    archive_path="$server_root/$archive_file_name"

    sillydroid_log "开始应用 Tavern server payload 到 Android 工程：$package_path"
    mkdir -p "$server_root"

    # 服务器 assets 目录只能保留当前生效的一个 payload zip，旧归档残留会被一并打进 APK。
    find "$server_root" -maxdepth 1 -type f -name '*.zip' ! -name "$archive_file_name" -delete

    cp -f "$package_path" "$archive_path"
    archive_size_bytes="$(stat -c '%s' "$archive_path")"

    if [[ -f "$source_manifest_path" ]]; then
        cp -f "$source_manifest_path" "$manifest_path"
    else
        cat > "$manifest_path" <<EOF
{
  "runtimeRid": "$runtime_rid",
  "sourcePackage": "$(basename "$package_path")",
  "syncedAtUtc": "$synced_at_utc",
    "archiveFile": "$archive_file_name",
  "archiveSizeBytes": $archive_size_bytes
}
EOF
    fi

    sillydroid_log "已应用 Tavern server 底包：$package_path"
}

android_sdk_root="$(sillydroid_resolve_linux_android_sdk_root)"
sillydroid_ensure_linux_android_sdk "$android_sdk_root"
prepare_staged_android_project "$workspace_android_root" "$android_root"
sillydroid_write_android_local_properties "$android_root" "$android_sdk_root"
sillydroid_ensure_java_home

gradle_task=":app:assemble${build_type^}"
android_build_root="$(realpath -m "$default_android_build_root")"
apk_output_dir="$android_build_root/app/outputs/apk/$build_type"
apk_path="$apk_output_dir/app-$build_type.apk"
apksigner_path="$android_sdk_root/build-tools/$SILLYDROID_ANDROID_BUILD_TOOLS_VERSION/apksigner"
workspace_apk_root="$workspace_root/artifacts/releases/android-apk"
workspace_apk_path="$workspace_apk_root/app-$build_type.apk"

export SILLYDROID_TAVERN_ANDROID_BUILD_ROOT="$android_build_root"
mkdir -p "$workspace_apk_root"

if [[ ! -f "$runtime_image_path" ]]; then
    sillydroid_fail "缺少可复用的 Tavern runtime image：$runtime_image_path。请先运行 $(runtime_image_prepare_hint)，或显式传 --runtime-image。"
fi
sillydroid_log "复用 Tavern runtime image：$runtime_image_path"

sillydroid_progress_stage 2 3 "开始把 runtime image 和 server payload 写入缓存 Android 工程"
apply_runtime_image "$runtime_image_path" "$android_root"
apply_server_package "$server_package_path" "$android_root"

sillydroid_progress_stage 3 3 "开始执行 Gradle 任务：$gradle_task"
(
    cd "$android_root"
    bash "$workspace_root/gradlew" --no-daemon --console=plain -p "$android_root" "$gradle_task"
)
sillydroid_log "Gradle 任务完成：$gradle_task"

if [[ ! -f "$apk_path" ]]; then
    mapfile -t apk_candidates < <(find "$apk_output_dir" -maxdepth 1 -type f -name '*.apk' | sort)
    case "${#apk_candidates[@]}" in
        1)
            apk_path="${apk_candidates[0]}"
            ;;
        0)
            sillydroid_warn "Tavern APK 构建完成但未找到产物：$apk_path"
            exit 1
            ;;
        *)
            printf '检测到多个 Tavern APK 产物，无法自动判定目标文件：\n' >&2
            printf '  %s\n' "${apk_candidates[@]}" >&2
            exit 1
            ;;
    esac
fi

sillydroid_assert_path_exists "$apk_path" "Tavern APK 构建完成但未找到产物：$apk_path"

if [[ "$build_type" == 'release' ]]; then
    if [[ "$(basename "$apk_path")" == *-unsigned.apk ]]; then
        sillydroid_fail "release APK 仍为 unsigned：$(basename "$apk_path")。请提供正式签名参数。"
    fi

    sillydroid_assert_path_exists "$apksigner_path" "缺少 apksigner：$apksigner_path"
    "$apksigner_path" verify "$apk_path"
    sillydroid_log "已验证 Tavern release APK 签名：$apk_path"
fi

cp "$apk_path" "$workspace_apk_path"
printf 'Built Tavern APK: %s\n' "$(realpath "$workspace_apk_path")"