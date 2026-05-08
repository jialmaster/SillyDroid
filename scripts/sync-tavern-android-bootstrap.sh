#!/usr/bin/env bash
set -euo pipefail

runtime_rid="linux-arm64"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workspace_root="$(cd "$script_dir/.." && pwd)"
target_root="$workspace_root/artifacts/validation/android-tavern-server-package/$runtime_rid"
working_root="$workspace_root/artifacts/tmp/android-tavern-bootstrap"
server_overlay_root="$workspace_root/android-tavern/server-overlay"
extensions_source_root="$workspace_root/android-tavern/extensions"
build_config_path="$workspace_root/stai-build-config.json"

read_build_config_value() {
    local key_path="$1"
    local default_value="$2"

    if ! command -v python3 >/dev/null 2>&1; then
        printf '%s\n' "$default_value"
        return
    fi

    python3 "$workspace_root/scripts/read-stai-build-config.py" "$build_config_path" "$key_path" "$default_value"
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
    headers={'User-Agent': 'STAI-Android-Build'}
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
- 未传 --tag 时优先读取仓库根目录 stai-build-config.json 的 build.tavernVersion。
- build.tavernVersion 为 latest 或 auto 时，会自动解析上游最新 GitHub Release tag。
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag)
            STAI_TAVERN_TAG="$2"
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

if [[ -z "${STAI_TAVERN_TAG:-}" ]]; then
    STAI_TAVERN_TAG="$configured_tavern_tag"
fi

case "${STAI_TAVERN_TAG,,}" in
    ''|auto|latest)
        STAI_TAVERN_TAG="$(resolve_latest_tavern_tag)"
        ;;
esac

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

stai_ensure_node
stai_ensure_java_home
stai_require_command tar
stai_require_command find

resolved_target_root="$(realpath -m "$target_root")"
resolved_working_root="$(realpath -m "$working_root")"
downloads_root="$resolved_working_root/downloads"
source_root="$resolved_working_root/source"
payload_root="$resolved_working_root/payload"
archive_name="sillytavern-${STAI_TAVERN_TAG}.tar.gz"
source_archive_path="$downloads_root/$archive_name"
source_archive_url="https://github.com/SillyTavern/SillyTavern/archive/refs/tags/${STAI_TAVERN_TAG}.tar.gz"
node_archive_path="$downloads_root/node-v${STAI_NODE_VERSION}-linux-arm64.tar.xz"
node_archive_url="https://nodejs.org/dist/v${STAI_NODE_VERSION}/node-v${STAI_NODE_VERSION}-linux-arm64.tar.xz"

mkdir -p "$downloads_root" "$resolved_target_root"
stai_download_file_if_missing "$source_archive_url" "$source_archive_path"
stai_download_file_if_missing "$node_archive_url" "$node_archive_path"

rm -rf "$source_root" "$payload_root"
mkdir -p "$source_root" "$payload_root"
tar -xf "$source_archive_path" -C "$source_root"
resolved_source_root="$(find "$source_root" -mindepth 1 -maxdepth 1 -type d | sort | head -n 1)"
stai_assert_path_exists "$resolved_source_root/package.json" "SillyTavern 源码解压失败：$source_archive_path"

# 在构建机上提前安装运行依赖并裁掉 devDependencies，避免 Android 设备首次启动时再联网安装 npm 包。
(
    cd "$resolved_source_root"
    if [[ -f package-lock.json ]]; then
        npm ci --omit=dev --no-fund
    else
        npm install --omit=dev --no-fund
    fi

    # 上游 1.18.0 的 package-lock 在当前构建环境里可能遗漏 command-exists，
    # 但扩展安装会直接 import src/git/client.js，缺这个依赖会在 clone 前直接崩掉。
    command_exists_version="$(node -p "const pkg = require('./package.json'); (pkg.dependencies && pkg.dependencies['command-exists']) || ''")"
    if [[ -z "$command_exists_version" ]]; then
        echo '无法解析运行依赖 command-exists 的版本声明。' >&2
        exit 1
    fi

    if [[ ! -d node_modules/command-exists ]]; then
        npm install --omit=dev --no-fund --no-save "command-exists@${command_exists_version}"
    fi

    if [[ ! -d node_modules/command-exists ]]; then
        echo '缺少运行依赖 command-exists，扩展安装功能将不可用。' >&2
        exit 1
    fi
)

cp -R "$resolved_source_root/." "$payload_root/"
rm -rf "$payload_root/data" "$payload_root/backups"

if [[ -d "$server_overlay_root" ]]; then
    cp -R "$server_overlay_root/." "$payload_root/"
fi

if [[ -d "$extensions_source_root" ]]; then
    mkdir -p "$payload_root/bundled-extensions"
    cp -R "$extensions_source_root/." "$payload_root/bundled-extensions/"
fi

if [[ -f "$build_config_path" ]]; then
    mkdir -p "$payload_root/bundled-extensions"
    cp -f "$build_config_path" "$payload_root/bundled-extensions/stai-build-config.json"
fi

node_extract_root="$resolved_working_root/node-runtime"
rm -rf "$node_extract_root"
mkdir -p "$node_extract_root"
tar -xf "$node_archive_path" -C "$node_extract_root"
resolved_node_root="$(find "$node_extract_root" -mindepth 1 -maxdepth 1 -type d | sort | head -n 1)"
stai_assert_path_exists "$resolved_node_root/bin/node" "Node runtime 解压失败：$node_archive_path"
mv "$resolved_node_root" "$payload_root/node"

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

exec ./node/bin/node server.js \
    --listen false \
    --port "$TAVERN_PORT" \
    --browserLaunchEnabled false \
    --dataRoot "$TAVERN_DATA_ROOT/data" \
    --configPath "$TAVERN_DATA_ROOT/config/config.yaml"
EOF
chmod +x "$payload_root/tavern-entrypoint.sh"

jar_path="$JAVA_HOME/bin/jar"
stai_assert_path_exists "$jar_path" "缺少 jar 命令：$jar_path"
server_archive_path="$resolved_target_root/server-payload.zip"
manifest_path="$resolved_target_root/bootstrap-manifest.json"
mapfile -t packaged_files < <(find "$payload_root" -type f -printf '%P\n' | LC_ALL=C sort)
rm -f "$server_archive_path"
"$jar_path" --create --file "$server_archive_path" --no-manifest -C "$payload_root" .
server_archive_size_bytes="$(stat -c '%s' "$server_archive_path")"
payload_version="$STAI_TAVERN_TAG"
if [[ -n "$STAI_NODE_VERSION" ]]; then
    payload_version="$payload_version+node.$STAI_NODE_VERSION"
fi

{
    printf '{\n'
    printf '  "package": "%s",\n' "$(json_escape "SillyTavern")"
    printf '  "payloadVersion": "%s",\n' "$(json_escape "$payload_version")"
    printf '  "runtimeRid": "%s",\n' "$(json_escape "$runtime_rid")"
    printf '  "tag": "%s",\n' "$(json_escape "$STAI_TAVERN_TAG")"
    printf '  "nodeVersion": "%s",\n' "$(json_escape "$STAI_NODE_VERSION")"
    printf '  "sourceArchive": "%s",\n' "$(json_escape "$archive_name")"
    printf '  "syncedAtUtc": "%s",\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    printf '  "archiveFile": "%s",\n' "$(json_escape "$(basename "$server_archive_path")")"
    printf '  "archivedFileCount": %s,\n' "${#packaged_files[@]}"
    printf '  "archiveSizeBytes": %s\n' "$server_archive_size_bytes"
    printf '}\n'
} > "$manifest_path"

printf 'Packed %s tavern files for tag %s into %s\n' "${#packaged_files[@]}" "$STAI_TAVERN_TAG" "$server_archive_path"