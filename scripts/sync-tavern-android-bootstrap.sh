#!/usr/bin/env bash
set -euo pipefail

# Stage Contract: 3/4 Server Source
# Responsibilities:
# - Sync the requested SillyTavern tag, install npm runtime dependencies, and apply server overlay.
# - Produce only server-source.zip and server-source-manifest.json under artifacts/releases/server-source/<rid>/<tag>.
# Must not:
# - Build dependency packs.
# - Compose the final server-payload.
# - Assemble the APK or mutate stage-1/stage-2 outputs.

runtime_rid="linux-arm64"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workspace_root="$(cd "$script_dir/.." && pwd)"
target_root=''
working_root="${SILLYDROID_TAVERN_ANDROID_BOOTSTRAP_WORK_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/sillydroid-tavern-android-bootstrap}"
server_overlay_root="$workspace_root/android-tavern/server-overlay"
build_config_path="$workspace_root/sillydroid-build-config.json"

read_build_config_value() {
    local key_path="$1"
    local default_value="$2"

    if ! command -v python3 >/dev/null 2>&1; then
        printf '%s\n' "$default_value"
        return
    fi

    python3 "$workspace_root/scripts/read-sillydroid-build-config.py" "$build_config_path" "$key_path" "$default_value"
}

resolve_latest_tavern_tag() {
    if ! command -v python3 >/dev/null 2>&1; then
        echo '缺少 python3，无法自动解析最新 SillyTavern release tag。' >&2
        exit 1
    fi

    python3 - <<'PY'
import json
import sys
import urllib.request

request = urllib.request.Request(
    'https://api.github.com/repos/SillyTavern/SillyTavern/releases/latest',
    headers={'User-Agent': 'SillyDroid-Android-Build'}
)
with urllib.request.urlopen(request) as response:
    payload = json.load(response)

tag_name = (payload.get('tag_name') or '').strip()
if not tag_name:
    print('无法解析最新 SillyTavern release tag。', file=sys.stderr)
    raise SystemExit(1)

print(tag_name)
PY
}

patch_android_default_config() {
    local config_path="$1"

    if [[ ! -f "$config_path" ]] || ! command -v python3 >/dev/null 2>&1; then
        return
    fi

    python3 - "$config_path" <<'PY'
import re
import sys
from pathlib import Path

config_path = Path(sys.argv[1])
text = config_path.read_text(encoding="utf-8")
lines = text.splitlines(keepends=True)
section = None

for index, line in enumerate(lines):
    stripped = line.strip()

    if re.fullmatch(r'browserLaunch:\s*', stripped):
        section = 'browserLaunch'
        continue

    if re.fullmatch(r'git:\s*', stripped):
        section = 'git'
        continue

    if section == 'browserLaunch' and re.match(r'enabled:\s*(true|false)\b', stripped):
        lines[index] = re.sub(r'(enabled:\s*)(true|false)\b', r'\1false', line, count=1)
        section = None
        continue

    if section == 'git' and re.match(r'backend:\s*\S+', stripped):
        lines[index] = re.sub(r'(backend:\s*)\S+', r'\1builtin', line, count=1)
        section = None
        continue

    if stripped and not line.startswith((' ', '\t', '#')):
        section = None

config_path.write_text(''.join(lines), encoding="utf-8")
PY
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
Usage: sync-tavern-android-bootstrap.sh [--tag <sillytavern-tag>] [--runtime-rid linux-arm64] [--target-root <path>] [--working-root <path>]

说明：
- 未传 --tag 时优先读取仓库根目录 sillydroid-build-config.json 的 build.tavernVersion。
- build.tavernVersion 为 latest 或 auto 时，会自动解析上游最新 GitHub Release tag。
- 默认输出目录为 artifacts/releases/server-source/<rid>/<tag>。
- 该脚本只生成 Tavern server source；其中包含 Tavern 源码、overlay 和 npm 运行依赖，不包含 node/git dependency packs，也不生成最终 server-payload。
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag)
            SILLYDROID_TAVERN_TAG="$2"
            shift 2
            ;;
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

configured_tavern_tag="$(read_build_config_value 'build.tavernVersion' 'latest')"

if [[ -z "${SILLYDROID_TAVERN_TAG:-}" ]]; then
    SILLYDROID_TAVERN_TAG="$configured_tavern_tag"
fi

case "${SILLYDROID_TAVERN_TAG,,}" in
    ''|auto|latest)
        SILLYDROID_TAVERN_TAG="$(resolve_latest_tavern_tag)"
        ;;
esac

if [[ -z "$target_root" ]]; then
    target_root="$workspace_root/artifacts/releases/server-source/$runtime_rid/$SILLYDROID_TAVERN_TAG"
fi

case "$runtime_rid" in
    linux-arm64)
        ;;
    *)
        echo "Unsupported runtime RID: $runtime_rid" >&2
        exit 1
        ;;
esac

json_escape() {
    local value="$1"
    value=${value//\\/\\\\}
    value=${value//"/\\"}
    value=${value//$'\n'/\\n}
    value=${value//$'\r'/\\r}
    value=${value//$'\t'/\\t}
    printf '%s' "$value"
}

sillydroid_ensure_node
sillydroid_ensure_java_home
sillydroid_require_command tar
sillydroid_require_command find
sillydroid_require_command unzip

resolved_target_root="$(realpath -m "$target_root")"
resolved_working_root="$(realpath -m "$working_root")"
downloads_root="$resolved_working_root/downloads"
source_root="$resolved_working_root/source"
payload_root="$resolved_working_root/payload"
archive_name="sillytavern-${SILLYDROID_TAVERN_TAG}.tar.gz"
source_archive_path="$downloads_root/$archive_name"
source_archive_url="https://github.com/SillyTavern/SillyTavern/archive/refs/tags/${SILLYDROID_TAVERN_TAG}.tar.gz"

mkdir -p "$downloads_root" "$resolved_target_root"
sillydroid_download_file_if_missing "$source_archive_url" "$source_archive_path"

rm -rf "$source_root" "$payload_root"
mkdir -p "$source_root"
sillydroid_extract_archive_with_progress "$source_archive_path" "$source_root" 'sillytavern-source'
resolved_source_root="$(find "$source_root" -mindepth 1 -maxdepth 1 -type d | sort | head -n 1)"
sillydroid_assert_path_exists "$resolved_source_root/package.json" "SillyTavern 源码解压失败：$source_archive_path"
payload_root="$resolved_source_root"

# 在构建机上提前安装运行依赖并裁掉 devDependencies，避免 Android 设备首次启动时再联网安装 npm 包。
sillydroid_progress_stage 1 3 "开始安装 Tavern 运行依赖（npm omit=dev）"
(
    cd "$resolved_source_root"
    npm_cache_dir="$(sillydroid_toolchain_root)/npm-cache"
    mkdir -p "$npm_cache_dir"
    sillydroid_log "复用 npm 缓存目录：$npm_cache_dir"
    if [[ -f package-lock.json ]]; then
        npm ci --omit=dev --no-fund --no-audit --prefer-offline --progress=true --cache "$npm_cache_dir"
    else
        npm install --omit=dev --no-fund --no-audit --prefer-offline --progress=true --cache "$npm_cache_dir"
    fi

    # 上游 1.18.0 的 package-lock 在当前构建环境里可能遗漏 command-exists，
    # 但扩展安装会直接 import src/git/client.js，缺这个依赖会在 clone 前直接崩掉。
    command_exists_version="$(node -p "const pkg = require('./package.json'); (pkg.dependencies && pkg.dependencies['command-exists']) || ''")"
    if [[ -z "$command_exists_version" ]]; then
        echo '无法解析运行依赖 command-exists 的版本声明。' >&2
        exit 1
    fi

    if [[ ! -d node_modules/command-exists ]]; then
        npm install --omit=dev --no-fund --no-audit --prefer-offline --progress=true --cache "$npm_cache_dir" --no-save "command-exists@${command_exists_version}"
    fi

    if [[ ! -d node_modules/command-exists ]]; then
        echo '缺少运行依赖 command-exists，扩展安装功能将不可用。' >&2
        exit 1
    fi
)
sillydroid_progress_stage 2 3 "Tavern 运行依赖安装完成"

rm -rf "$payload_root/data" "$payload_root/backups"

if [[ -d "$server_overlay_root" ]]; then
    cp -R "$server_overlay_root/." "$payload_root/"
fi

patch_android_default_config "$payload_root/default/config.yaml"

cat > "$payload_root/tavern-entrypoint.sh" <<'EOF'
#!/bin/sh
set -eu

TAVERN_PORT="${TAVERN_PORT:?TAVERN_PORT is required}"
TAVERN_DATA_ROOT="${TAVERN_DATA_ROOT:?TAVERN_DATA_ROOT is required}"

cd /tavern/server

# 用户数据必须落到宿主持久目录，避免 APK 覆盖安装后把角色、聊天和配置一起替换掉。
mkdir -p "$TAVERN_DATA_ROOT/config" "$TAVERN_DATA_ROOT/data" "$TAVERN_DATA_ROOT/plugins" "$TAVERN_DATA_ROOT/extensions"

# 如果上游底包自带默认插件，首次启动先复制到持久目录，再切到外置目录，避免更新后覆盖用户改动。
if [ -d plugins ] && [ ! -L plugins ] && [ -z "$(find "$TAVERN_DATA_ROOT/plugins" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]; then
    cp -R plugins/. "$TAVERN_DATA_ROOT/plugins/"
fi
rm -rf plugins
ln -sfn "$TAVERN_DATA_ROOT/plugins" plugins

mkdir -p public/scripts/extensions
if [ -d public/scripts/extensions/third-party ] && [ ! -L public/scripts/extensions/third-party ] && [ -z "$(find "$TAVERN_DATA_ROOT/extensions" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]; then
    cp -R public/scripts/extensions/third-party/. "$TAVERN_DATA_ROOT/extensions/"
fi
rm -rf public/scripts/extensions/third-party
ln -sfn "$TAVERN_DATA_ROOT/extensions" public/scripts/extensions/third-party

if [ -f ./dependency-env.sh ]; then
    # dependency-env.sh 由 stage 4 根据 dependency manifests 聚合生成。
    # shellcheck disable=SC1091
    . ./dependency-env.sh
fi

# Android 包内已经预装运行依赖；不要回退到上游 start.sh，避免冷启动重新执行 npm install。
export NODE_ENV=production

NODE_BIN="${TAVERN_NODE_BIN:-./node/bin/node}"
if [ ! -x "$NODE_BIN" ]; then
    NODE_BIN="$(command -v node || true)"
fi

if [ -z "$NODE_BIN" ] || [ ! -x "$NODE_BIN" ]; then
    echo "缺少可执行的 Node runtime，请确认已导入 node 依赖包。" >&2
    exit 1
fi

exec "$NODE_BIN" server.js \
    --listen false \
    --port "$TAVERN_PORT" \
    --browserLaunchEnabled false \
    --dataRoot "$TAVERN_DATA_ROOT/data" \
    --configPath "$TAVERN_DATA_ROOT/config/config.yaml"
EOF
chmod +x "$payload_root/tavern-entrypoint.sh"

jar_path="$JAVA_HOME/bin/jar"
sillydroid_assert_path_exists "$jar_path" "缺少 jar 命令：$jar_path"
server_source_archive_path="$resolved_target_root/server-source.zip"
server_source_manifest_path="$resolved_target_root/server-source-manifest.json"
mapfile -t packaged_files < <(find "$payload_root" -type f -printf '%P\n' | LC_ALL=C sort)
rm -f "$server_source_archive_path" "$server_source_manifest_path"
sillydroid_progress_stage 3 3 "开始归档 Tavern server source"
"$jar_path" --create --file "$server_source_archive_path" --no-manifest -C "$payload_root" .
server_source_archive_size_bytes="$(stat -c '%s' "$server_source_archive_path")"

{
    printf '{\n'
    printf '  "package": "%s",\n' "$(json_escape "SillyTavernServerSource")"
    printf '  "payloadVersion": "%s",\n' "$(json_escape "$SILLYDROID_TAVERN_TAG")"
    printf '  "runtimeRid": "%s",\n' "$(json_escape "$runtime_rid")"
    printf '  "tag": "%s",\n' "$(json_escape "$SILLYDROID_TAVERN_TAG")"
    printf '  "sourceArchive": "%s",\n' "$(json_escape "$archive_name")"
    printf '  "syncedAtUtc": "%s",\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    printf '  "archiveFile": "%s",\n' "$(json_escape "$(basename "$server_source_archive_path")")"
    printf '  "archivedFileCount": %s,\n' "${#packaged_files[@]}"
    printf '  "archiveSizeBytes": %s\n' "$server_source_archive_size_bytes"
    printf '}\n'
} > "$server_source_manifest_path"

printf 'Packed %s tavern files for tag %s into %s\n' "${#packaged_files[@]}" "$SILLYDROID_TAVERN_TAG" "$server_source_archive_path"