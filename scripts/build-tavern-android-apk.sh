#!/usr/bin/env bash
set -euo pipefail

# Stage Contract: 4/4 APK Assembly
# Responsibilities:
# - Consume stage-1 runtime image, stage-2 dependency packs, and stage-3 server source.
# - Copy reusable runtime/server archives into the Android project, inject host assets, and assemble the APK.
# Must not:
# - Implicitly rebuild or refresh stage-1/stage-2/stage-3 prerequisites.
# - Move stage-3 responsibilities back into this script's callers or into earlier stages.

runtime_rid='linux-arm64'
build_type=''
runtime_image_path=''
server_source_path=''
tavern_tag=''
dependency_packs_override=''
default_android_build_root="${SILLYDROID_TAVERN_ANDROID_BUILD_ROOT:-${SILLYDROID_ANDROID_BUILD_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/sillydroid-tavern-android-build}}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workspace_root="$(cd "$script_dir/.." && pwd)"
workspace_android_root="$workspace_root/android-tavern"
android_root="$(realpath -m "$default_android_build_root/android-project/android-tavern")"
dependency_pack_script="$workspace_root/scripts/build-tavern-dependency-packs.sh"
build_config_path="$workspace_root/sillydroid-build-config.json"
rootfs_manifest_path="$workspace_android_root/app/src/main/assets/bootstrap/rootfs/rootfs-manifest.json"
runtime_image_apply_cache_version='stage4-runtime-image-apply-v1'

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
Usage: build-tavern-android-apk.sh [--runtime-image <path>] [--server-source <path> | --tag <sillytavern-tag>] [--runtime-rid linux-arm64] [--build-type debug|release] [--dependency-packs <comma-separated>]

说明：
- runtime image / dependency packs 只负责 Termux rootfs、环境依赖、环境修复脚本；server source 只负责指定上游 tag 的 Tavern 源码与 npm 运行依赖。
- Android Host 扩展与默认扩展列表在 APK build 阶段分别写入独立 assets 目录，不再混入 server source。
- 这是纯 stage 4 脚本，只消费现有 runtime image、server source 与 dependency packs，然后把它们原样写入 android-tavern 工程。
- 若不传 --runtime-image，默认读取 artifacts/releases/rootfs/<rid>/tavern-rootfs-<rid>.zip；缺失时会直接报错。
- 若不传 --server-source，则默认读取 artifacts/releases/server-source/<rid>/<tag>/server-source.zip。
- build.includeDependencyPacks 若显式配置为空数组，则跳过 dependency packs，直接依赖 rootfs 提供运行时。
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
        --server-source)
            server_source_path="$2"
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
resolve_build_plan_script="$workspace_root/scripts/resolve-tavern-build-plan.sh"

resolve_stage4_version_metadata() {
    local plan_file resolved_tavern_tag resolved_build_type
    plan_file="$(mktemp)"
    bash "$resolve_build_plan_script" \
        --tavern-tag "$tavern_tag" \
        --build-type "$build_type" > "$plan_file"

    get_stage4_plan_value() {
        local key="$1"
        sed -n "s/^${key}=//p" "$plan_file" | tail -n 1 | tr -d '\r'
    }

    resolved_tavern_tag="$(get_stage4_plan_value tavern_tag)"
    resolved_build_type="$(get_stage4_plan_value build_type)"
    stage4_host_version="$(get_stage4_plan_value host_version)"
    stage4_version_name="$(get_stage4_plan_value version_name)"
    stage4_version_code="$(get_stage4_plan_value version_code)"
    rm -f "$plan_file"

    if [[ -z "$resolved_tavern_tag" || -z "$resolved_build_type" || -z "$stage4_host_version" || -z "$stage4_version_name" || -z "$stage4_version_code" ]]; then
        sillydroid_fail 'stage-4 无法从构建计划解析 APK 版本信息。'
    fi

    tavern_tag="$resolved_tavern_tag"
    build_type="$resolved_build_type"
}

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

android_source_git() {
    local source_root="$1"
    shift

    git -c safe.directory="$workspace_root" -C "$source_root" "$@"
}

write_android_source_export_manifest() {
    local source_root="$1"
    local output_path="$2"
    local relative_path=''

    : > "$output_path"
    android_source_git "$source_root" ls-files --cached --others --exclude-standard -z \
        | while IFS= read -r -d '' relative_path; do
            [[ -e "$source_root/$relative_path" ]] || continue
            printf '%s\0' "$relative_path"
        done > "$output_path"
}

write_android_source_export_fingerprint() {
    local source_root="$1"
    local manifest_path="$2"
    local output_path="$3"

    command -v python3 >/dev/null 2>&1 || sillydroid_fail '计算 Android 工程源码缓存指纹需要 python3。'
    python3 - "$source_root" "$manifest_path" "$output_path" <<'PY'
import hashlib
import json
import pathlib
import sys

source_root = pathlib.Path(sys.argv[1])
manifest_path = pathlib.Path(sys.argv[2])
output_path = pathlib.Path(sys.argv[3])
payload = manifest_path.read_bytes()
relative_paths = [item.decode("utf-8") for item in payload.split(b"\0") if item]
entries = []
for relative_path in relative_paths:
    file_path = source_root / relative_path
    if not file_path.is_file():
        entries.append({
            "path": relative_path,
            "kind": "non-file",
        })
        continue

    digest = hashlib.sha256()
    with file_path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    entries.append({
        "path": relative_path,
        "sizeBytes": file_path.stat().st_size,
        "sha256": digest.hexdigest(),
    })

output_path.write_text(
    json.dumps({"files": entries}, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
    encoding="utf-8",
)
PY
}

assert_android_source_git() {
    local source_root="$1"

    if ! android_source_git "$source_root" rev-parse --is-inside-work-tree &>/dev/null; then
        sillydroid_fail "Android 工程源码目录必须是 Git 工作树：$source_root"
    fi
}

staged_android_project_satisfy_request() {
    local source_root="$1"
    local staged_root="$2"
    local manifest_path="$staged_root/.sillydroid-source-export.list"
    local fingerprint_path="$staged_root/.sillydroid-source-export.fingerprint.json"
    local current_manifest_path=''
    local current_fingerprint_path=''

    [[ -d "$staged_root" && -f "$manifest_path" && -f "$fingerprint_path" ]] || return 1
    assert_android_source_git "$source_root"

    current_manifest_path="$(mktemp)"
    current_fingerprint_path="$(mktemp)"
    write_android_source_export_manifest "$source_root" "$current_manifest_path"
    write_android_source_export_fingerprint "$source_root" "$current_manifest_path" "$current_fingerprint_path"

    if ! cmp -s "$current_manifest_path" "$manifest_path"; then
        rm -f "$current_manifest_path" "$current_fingerprint_path"
        return 1
    fi
    if ! cmp -s "$current_fingerprint_path" "$fingerprint_path"; then
        rm -f "$current_manifest_path" "$current_fingerprint_path"
        return 1
    fi
    rm -f "$current_manifest_path" "$current_fingerprint_path"

    return 0
}

prepare_staged_android_project() {
    local source_root="$1"
    local staged_root="$2"
    local manifest_path="$staged_root/.sillydroid-source-export.list"
    local fingerprint_path="$staged_root/.sillydroid-source-export.fingerprint.json"
    local current_manifest_path=''
    local current_fingerprint_path=''

    assert_android_source_git "$source_root"

    if staged_android_project_satisfy_request "$source_root" "$staged_root"; then
        sillydroid_log "复用已导出的缓存 Android 工程：$staged_root"
    else
        rm -rf "$staged_root"
        mkdir -p "$staged_root"

        sillydroid_log "正在导出 Android 工程源码到缓存目录..."
        # 导出 git tracked 文件以及未被 .gitignore 排除的新增文件，保留工作区当前内容。
        current_manifest_path="$(mktemp)"
        current_fingerprint_path="$(mktemp)"
        (
            cd "$source_root"
            write_android_source_export_manifest "$source_root" "$current_manifest_path"
            write_android_source_export_fingerprint "$source_root" "$current_manifest_path" "$current_fingerprint_path"
            tar --null -T "$current_manifest_path" -cf -
        ) | tar -xf - -C "$staged_root"
        mv "$current_manifest_path" "$manifest_path"
        mv "$current_fingerprint_path" "$fingerprint_path"
    fi

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

    [[ -f "$server_source_path" && -f "$server_source_manifest_path" ]] || return 1
    command -v python3 >/dev/null 2>&1 || return 1

    # Stage 3 server source is versioned upstream material. Stage 4 only verifies
    # the requested RID/tag contract here; local overlay/script mtimes do not
    # invalidate an already published server-source artifact.
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

write_runtime_image_apply_fingerprint() {
    local image_path="$1"
    local output_path="$2"

    command -v python3 >/dev/null 2>&1 || return 1
    python3 - "$output_path" "$runtime_image_apply_cache_version" "$runtime_rid" "$image_path" <<'PY'
import hashlib
import json
import pathlib
import sys

output_path = pathlib.Path(sys.argv[1])
cache_version = sys.argv[2]
runtime_rid = sys.argv[3]
image_path = pathlib.Path(sys.argv[4])

if not image_path.is_file():
    raise SystemExit(f"missing runtime image: {image_path}")

digest = hashlib.sha256()
with image_path.open("rb") as handle:
    for chunk in iter(lambda: handle.read(1024 * 1024), b""):
        digest.update(chunk)

payload = {
    "cacheVersion": cache_version,
    "runtimeRid": runtime_rid,
    "runtimeImage": {
        "path": image_path.name,
        "sizeBytes": image_path.stat().st_size,
        "sha256": digest.hexdigest(),
    },
}
output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

runtime_image_applied_satisfy_request() {
    local image_path="$1"
    local project_root="$2"
    local bootstrap_root="$project_root/app/src/main/assets/bootstrap"
    local rootfs_root="$bootstrap_root/rootfs"
    local jni_lib_root="$project_root/app/src/main/jniLibs/arm64-v8a"
    local fingerprint_path="$bootstrap_root/rootfs/.sillydroid-runtime-image-fingerprint.json"
    local requested_fingerprint_path=''

    [[ -f "$rootfs_root/rootfs-fs.zip" ]] || return 1
    [[ -f "$rootfs_root/rootfs-usr.zip" ]] || return 1
    [[ -f "$rootfs_root/rootfs-manifest.json" ]] || return 1
    [[ -f "$jni_lib_root/libtermux-node.so" ]] || return 1
    [[ -f "$jni_lib_root/libtermux-git.so" ]] || return 1
    [[ -f "$jni_lib_root/libtermux-git-remote-http.so" ]] || return 1
    [[ -f "$jni_lib_root/libtermux-sh.so" ]] || return 1
    [[ -f "$fingerprint_path" ]] || return 1

    requested_fingerprint_path="$(mktemp)"
    if ! write_runtime_image_apply_fingerprint "$image_path" "$requested_fingerprint_path"; then
        rm -f "$requested_fingerprint_path"
        return 1
    fi

    if ! cmp -s "$requested_fingerprint_path" "$fingerprint_path"; then
        rm -f "$requested_fingerprint_path"
        return 1
    fi
    rm -f "$requested_fingerprint_path"
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

stage4_host_version="${SILLYDROID_ANDROID_HOST_VERSION:-}"
stage4_upstream_version="${SILLYDROID_ANDROID_UPSTREAM_VERSION:-}"
stage4_version_name="${SILLYDROID_ANDROID_VERSION_NAME:-}"
stage4_version_code="${SILLYDROID_ANDROID_VERSION_CODE:-}"

# stage-4 允许被单独调用，因此这里也必须补齐和本地总入口一致的版本解析；
# 否则 Gradle 会回退为默认 versionCode/versionName，产出无法覆盖安装的降级包。
if [[ -z "$stage4_host_version" || -z "$stage4_version_name" || -z "$stage4_version_code" ]]; then
    resolve_stage4_version_metadata
fi

if [[ -z "$stage4_upstream_version" ]]; then
    stage4_upstream_version="$tavern_tag"
fi

export SILLYDROID_ANDROID_HOST_VERSION="$stage4_host_version"
export SILLYDROID_ANDROID_UPSTREAM_VERSION="$stage4_upstream_version"
export SILLYDROID_ANDROID_VERSION_NAME="$stage4_version_name"
export SILLYDROID_ANDROID_VERSION_CODE="$stage4_version_code"
sillydroid_log "注入 APK 版本信息：host=$stage4_host_version upstream=$stage4_upstream_version versionName=$stage4_version_name versionCode=$stage4_version_code"

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

generated_server_root="$workspace_root/artifacts/releases/server-source/$runtime_rid/$tavern_tag"
dependency_packs_root="$workspace_root/artifacts/releases/dependency-packs/$runtime_rid"

if [[ -z "$server_source_path" ]]; then
    server_source_path="$generated_server_root/server-source.zip"
fi
server_source_path="$(realpath -m "$server_source_path")"

if ! generated_server_source_satisfy_request "$(dirname "$server_source_path")"; then
    sillydroid_fail "缺少可复用的 Tavern server source：$(dirname "$server_source_path")。请先运行 $(server_source_prepare_hint)，或显式传 --server-source。"
fi
sillydroid_log "复用已存在的 Tavern server source：$(dirname "$server_source_path")"

if ! dependency_packs_satisfy_request "$dependency_packs_root"; then
    sillydroid_fail "缺少可复用的 dependency packs：$dependency_packs_root。请先运行 $(dependency_packs_prepare_hint)。"
fi
sillydroid_log "复用已存在的 dependency packs：$dependency_packs_root"

apply_runtime_image() {
    local image_path="$1"
    local project_root="$2"
    local extract_root="$(realpath -m "$default_android_build_root/runtime-image/$runtime_rid")"
    local bootstrap_root="$project_root/app/src/main/assets/bootstrap"
    local rootfs_root="$bootstrap_root/rootfs"
    local jni_lib_root="$project_root/app/src/main/jniLibs/arm64-v8a"
    local fingerprint_path="$rootfs_root/.sillydroid-runtime-image-fingerprint.json"

    sillydroid_assert_path_exists "$image_path" "缺少 Android runtime image：$image_path"
    sillydroid_require_command unzip

    if runtime_image_applied_satisfy_request "$image_path" "$project_root"; then
        sillydroid_log "复用已应用的 Tavern runtime image：$rootfs_root"
        return
    fi

    sillydroid_log "开始应用 Tavern runtime image 到 Android 工程：$image_path"
    rm -rf "$extract_root" "$rootfs_root"
    mkdir -p "$extract_root" "$bootstrap_root" "$jni_lib_root"
    sillydroid_extract_archive_with_progress "$image_path" "$extract_root" 'runtime-image'

    sillydroid_assert_path_exists "$extract_root/assets/bootstrap/rootfs/rootfs-fs.zip" "runtime image 缺少 rootfs 资产：$image_path"
    sillydroid_assert_path_exists "$extract_root/jniLibs/arm64-v8a/libtermux-node.so" "runtime image 缺少 Termux node 入口：$image_path"
    sillydroid_assert_path_exists "$extract_root/jniLibs/arm64-v8a/libtermux-git.so" "runtime image 缺少 Termux git 入口：$image_path"
    sillydroid_assert_path_exists "$extract_root/jniLibs/arm64-v8a/libtermux-git-remote-http.so" "runtime image 缺少 Termux git HTTPS helper 入口：$image_path"
    sillydroid_assert_path_exists "$extract_root/jniLibs/arm64-v8a/libtermux-sh.so" "runtime image 缺少 Termux shell 入口：$image_path"

    cp -R "$extract_root/assets/bootstrap/rootfs" "$bootstrap_root/"

    find "$jni_lib_root" -maxdepth 1 -type f \
        -name 'lib*.so*' \
        -delete
    cp -R "$extract_root/jniLibs/arm64-v8a/." "$jni_lib_root/"
    write_runtime_image_apply_fingerprint "$image_path" "$fingerprint_path"

    sillydroid_log "已应用 Tavern runtime image：$image_path"
}

apply_server_components() {
    local source_archive_path="$1"
    local dependency_root="$2"
    local project_root="$3"
    local server_root="$project_root/app/src/main/assets/bootstrap/server"
    local manifest_path="$server_root/bootstrap-manifest.json"
    local server_source_manifest_path="$(dirname "$source_archive_path")/server-source-manifest.json"
    local selection_manifest_path="$server_root/dependency-selection.json"
    local synced_at_utc="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    local -a dependency_pack_names=()

    sillydroid_assert_path_exists "$source_archive_path" "缺少 Tavern server source：$source_archive_path"
    sillydroid_assert_path_exists "$server_source_manifest_path" "缺少 Tavern server source manifest：$server_source_manifest_path"
    command -v python3 >/dev/null 2>&1 || sillydroid_fail '生成 server bootstrap manifest 需要 python3。'

    mapfile -t dependency_pack_names < <(resolve_dependency_pack_list)
    mkdir -p "$server_root/dependency-packs"

    # 这里清理的是缓存 Android 工程里的 APK assets，不是用户设备上的 serverDir。
    find "$server_root" -maxdepth 1 -type f -name '*.zip' ! -name 'server-source.zip' -delete
    find "$server_root/dependency-packs" -maxdepth 1 -type f -name '*.zip' -delete
    find "$server_root" -maxdepth 1 -type f \( -name '*.manifest.json' -o -name 'dependency-selection.json' \) -delete

    if [[ -f "$server_root/server-source.zip" ]] && cmp -s "$source_archive_path" "$server_root/server-source.zip"; then
        sillydroid_log "Tavern server source 未变化，跳过大文件复制：$server_root/server-source.zip"
    else
        cp -f "$source_archive_path" "$server_root/server-source.zip"
    fi
    cp -f "$server_source_manifest_path" "$server_root/server-source-manifest.json"

    python3 - "$dependency_root" "$selection_manifest_path" "${dependency_pack_names[@]}" <<'PY'
import json
import pathlib
import sys

dependency_root = pathlib.Path(sys.argv[1])
selection_manifest_path = pathlib.Path(sys.argv[2])
requested = [item.strip() for item in sys.argv[3:] if item.strip()]

selected = []
for name in requested:
    manifest_path = dependency_root / f"{name}.manifest.json"
    if not manifest_path.is_file():
        raise SystemExit(f"missing dependency manifest: {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    archive_file = str(manifest.get("archiveFile") or "").strip()
    if not archive_file:
        raise SystemExit(f"dependency manifest missing archiveFile: {manifest_path}")
    archive_path = dependency_root / archive_file
    if not archive_path.is_file():
        raise SystemExit(f"missing dependency archive: {archive_path}")
    selected.append({
        "name": name,
        "version": str(manifest.get("version") or ""),
        "archiveFile": archive_file,
        "manifestFile": manifest_path.name,
    })

selection_manifest_path.write_text(
    json.dumps({"selected": selected}, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
PY

    python3 - "$dependency_root" "$server_root/dependency-env.sh" "$server_root/dependency-post-extract.sh" "${dependency_pack_names[@]}" <<'PY'
import json
import pathlib
import sys

dependency_root = pathlib.Path(sys.argv[1])
env_path = pathlib.Path(sys.argv[2])
post_extract_hook_path = pathlib.Path(sys.argv[3])
requested = [item.strip() for item in sys.argv[4:] if item.strip()]

env_vars = {}
path_prepend = []
post_extract_scripts = []
for name in requested:
    manifest_path = dependency_root / f"{name}.manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    env = manifest.get("env") or {}
    for item in env.get("pathPrepend") or []:
        value = str(item).strip()
        if value and value not in path_prepend:
            path_prepend.append(value)
    for key, value in (env.get("variables") or {}).items():
        key = str(key).strip()
        value = str(value)
        if key and key not in env_vars:
            env_vars[key] = value
    for item in manifest.get("postExtractScripts") or []:
        value = str(item).strip().lstrip("./")
        if value and value not in post_extract_scripts:
            post_extract_scripts.append(value)

env_lines = ["#!/bin/sh", "set -eu"]
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
]
for script in post_extract_scripts:
    escaped = script.replace('"', '\\"')
    post_extract_lines.append(f'if [ -f "./{escaped}" ]; then sh "./{escaped}"; fi')
post_extract_hook_path.write_text("\n".join(post_extract_lines) + "\n", encoding="utf-8")
PY
    chmod 0755 "$server_root/dependency-env.sh" "$server_root/dependency-post-extract.sh"

    for pack_name in "${dependency_pack_names[@]}"; do
        local pack_manifest_path="$dependency_root/$pack_name.manifest.json"
        local archive_file
        archive_file="$(python3 - "$pack_manifest_path" <<'PY'
import json
import sys
payload = json.loads(open(sys.argv[1], encoding="utf-8").read())
print(str(payload.get("archiveFile") or "").strip())
PY
)"
        [[ -n "$archive_file" ]] || sillydroid_fail "dependency manifest 缺少 archiveFile：$pack_manifest_path"
        cp -f "$dependency_root/$archive_file" "$server_root/dependency-packs/$archive_file"
        cp -f "$pack_manifest_path" "$server_root/$pack_name.manifest.json"
    done

    python3 - "$manifest_path" "$server_root/server-source.zip" "$server_root/server-source-manifest.json" "$selection_manifest_path" "$runtime_rid" "$tavern_tag" "$synced_at_utc" <<'PY'
import json
import pathlib
import sys

manifest_path = pathlib.Path(sys.argv[1])
server_source_archive = pathlib.Path(sys.argv[2])
server_source_manifest_path = pathlib.Path(sys.argv[3])
dependency_selection_path = pathlib.Path(sys.argv[4])
runtime_rid = sys.argv[5]
tavern_tag = sys.argv[6]
synced_at_utc = sys.argv[7]

server_source_manifest = json.loads(server_source_manifest_path.read_text(encoding="utf-8"))
dependency_selection = json.loads(dependency_selection_path.read_text(encoding="utf-8"))

payload = {
    "package": "SillyTavernBootstrap",
    "runtimeRid": runtime_rid,
    "tag": tavern_tag,
    "syncedAtUtc": synced_at_utc,
    "serverSource": {
        "archiveFile": "server-source.zip",
        "manifestFile": "server-source-manifest.json",
        "archiveSizeBytes": server_source_archive.stat().st_size,
        "tag": server_source_manifest.get("tag") or server_source_manifest.get("payloadVersion"),
    },
    "dependencyPacks": dependency_selection.get("selected", []),
}
manifest_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY

    sillydroid_log "已应用 Tavern server source 与 dependency packs 到 Android 工程：$server_root"
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

sillydroid_progress_stage 2 3 "开始把 runtime image、server source 和 dependency packs 写入缓存 Android 工程"
apply_runtime_image "$runtime_image_path" "$android_root"
apply_server_components "$server_source_path" "$dependency_packs_root" "$android_root"

# Gradle may reuse an existing APK file in the staged build cache. When large stored
# assets shrink or move, stale bytes can remain in the archive and bloat the final APK.
rm -f "$apk_path" "$apk_output_dir/output-metadata.json"

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
