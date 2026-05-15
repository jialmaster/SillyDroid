#!/usr/bin/env bash
set -euo pipefail

# Build Plan Contract
# Responsibilities:
# - Compute versioning, changed flags, release naming, and canonical artifacts/releases paths.
# - Provide the single shared plan consumed by local orchestration and CI jobs.
# Must not:
# - Build, download, or mutate any stage artifact.
# - Hide stage-boundary changes inside plan logic without also updating the documented contracts.

requested_tavern_tag='auto'
requested_build_type='auto'
compare_base=''
compare_head='HEAD'
upstream_repo='SillyTavern/SillyTavern'

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workspace_root="$(cd "$script_dir/.." && pwd)"
build_config_path="$workspace_root/sillydroid-build-config.json"
gradle_properties_path="$workspace_root/android-tavern/gradle.properties"

usage() {
    cat <<'EOF'
Usage: resolve-tavern-build-plan.sh [--tavern-tag <tag|auto|latest>] [--build-type <debug|release|auto>] [--compare-base <git-ref>] [--compare-head <git-ref>]
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tavern-tag)
            requested_tavern_tag="$2"
            shift 2
            ;;
        --build-type)
            requested_build_type="$2"
            shift 2
            ;;
        --compare-base)
            compare_base="$2"
            shift 2
            ;;
        --compare-head)
            compare_head="$2"
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

    python3 - "$upstream_repo" <<'PY'
import json
import sys
import urllib.request

repo = sys.argv[1]
request = urllib.request.Request(
    f'https://api.github.com/repos/{repo}/releases/latest',
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

count_upstream_release_index() {
    local normalized_tag="$1"

    if ! command -v python3 >/dev/null 2>&1; then
        printf '0\n'
        return
    fi

    if ! python3 - "$upstream_repo" "$normalized_tag" <<'PY'
import json
import sys
import urllib.request
import urllib.error

repo = sys.argv[1]
target = sys.argv[2]
page = 1
releases = []

while True:
    request = urllib.request.Request(
        f'https://api.github.com/repos/{repo}/releases?per_page=100&page={page}',
        headers={'User-Agent': 'SillyDroid-Android-Build'}
    )
    try:
        with urllib.request.urlopen(request) as response:
            payload = json.load(response)
    except urllib.error.URLError:
        print('0')
        raise SystemExit(0)
    if not payload:
        break
    releases.extend(payload)
    page += 1

matched_position = 0
for index, release in enumerate(releases, start=1):
    tag_name = str(release.get('tag_name') or '').strip().removeprefix('refs/tags/')
    if matched_position == 0 and tag_name == target:
        matched_position = index

if matched_position == 0:
    print('0')
else:
    print(str(len(releases) - matched_position + 1))
PY
    then
        printf '0\n'
    fi
}

sanitize_tag() {
    printf '%s' "$1" | sed 's#[^0-9A-Za-z._-]#-#g'
}

resolve_current_repo_slug() {
    local remote_url=''

    remote_url="$(git -C "$workspace_root" config --get remote.origin.url 2>/dev/null || true)"
    remote_url="${remote_url%.git}"

    if [[ "$remote_url" =~ github\.com[:/]([^/]+)/([^/]+)$ ]]; then
        printf '%s/%s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
    fi
}

resolve_apk_release_state() {
    local host_version_base="$1"
    local safe_tag="$2"
    local build_type="$3"
    local current_head_sha="$4"
    local repo_slug="$5"

    python3 - "$workspace_root" "$host_version_base" "$safe_tag" "$build_type" "$current_head_sha" "$repo_slug" <<'PY'
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request

workspace_root, host_version_base, safe_tag, build_type, current_head_sha, repo_slug = sys.argv[1:]

tag_pattern = re.compile(
    rf"^sillydroid-v{re.escape(host_version_base)}(?:\.(\d+))?-[0-9A-Za-z._-]+-(?:debug|release)$"
)
exact_tag_pattern = re.compile(
    rf"^sillydroid-v{re.escape(host_version_base)}(?:\.(\d+))?-{re.escape(safe_tag)}-{re.escape(build_type)}$"
)


def run_git(*args: str) -> list[str]:
    completed = subprocess.run(
        ["git", "-C", workspace_root, *args],
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        return []
    return [line.strip() for line in completed.stdout.splitlines() if line.strip()]


def extract_counter(tag_name: str):
    matched = tag_pattern.match(tag_name)
    if not matched:
        return None
    counter = matched.group(1)
    return int(counter) if counter is not None else 0


matched_any = False
max_counter = 0

for tag_name in run_git("tag", "--list", f"sillydroid-v{host_version_base}*"):
    counter = extract_counter(tag_name)
    if counter is None:
        continue
    matched_any = True
    max_counter = max(max_counter, counter)

for tag_name in run_git(
    "tag",
    "--points-at",
    current_head_sha,
    "--list",
    f"sillydroid-v{host_version_base}*-{safe_tag}-{build_type}",
):
    if not exact_tag_pattern.match(tag_name):
        continue
    counter = extract_counter(tag_name)
    if counter is None:
        continue
    host_version = host_version_base if counter == 0 else f"{host_version_base}.{counter}"
    print(host_version)
    print(counter)
    raise SystemExit(0)

if repo_slug:
    page = 1
    while True:
        request = urllib.request.Request(
            f"https://api.github.com/repos/{repo_slug}/releases?per_page=100&page={page}",
            headers={"User-Agent": "SillyDroid-Android-Build"},
        )
        try:
            with urllib.request.urlopen(request) as response:
                payload = json.load(response)
        except urllib.error.URLError:
            payload = []
            break

        if not payload:
            break

        for release in payload:
            tag_name = str(release.get("tag_name") or "").strip().removeprefix("refs/tags/")
            counter = extract_counter(tag_name)
            if counter is None:
                continue
            matched_any = True
            max_counter = max(max_counter, counter)

        page += 1

next_counter = max_counter + 1 if matched_any else 1
print(f"{host_version_base}.{next_counter}")
print(next_counter)
PY
}

compute_android_version_code() {
    local host_version="$1"
    local major='0'
    local minor='0'
    local patch='0'
    local apk_release_index='0'
    local remainder=''

    IFS='.' read -r major minor patch apk_release_index remainder <<< "$host_version"

    if [[ -n "$remainder" ]]; then
        echo "Android 版本号格式非法：$host_version" >&2
        exit 1
    fi

    if [[ -z "$major" || -z "$minor" || -z "$patch" ]]; then
        echo "Android 版本号格式非法：$host_version" >&2
        exit 1
    fi

    if [[ -z "$apk_release_index" ]]; then
        apk_release_index='0'
    fi

    for segment in "$major" "$minor" "$patch" "$apk_release_index"; do
        if ! printf '%s' "$segment" | grep -E '^[0-9]+$' >/dev/null 2>&1; then
            echo "Android 版本号格式非法：$host_version" >&2
            exit 1
        fi
    done

    printf '%s\n' "$((1800000000 + major * 10000000 + minor * 100000 + patch * 1000 + apk_release_index))"
}

emit() {
    # 统一去掉 Windows CRLF 残留，避免计划文件里的路径值带 \r，
    # 进而在下游拼接 artifacts 路径时出现 cp/stat 找不到文件。
    printf '%s=%s\n' "$1" "$(printf '%s' "$2" | tr -d '\r')"
}

config_tavern_tag="$(read_build_config_value 'build.tavernVersion' 'latest')"
config_build_type="$(read_build_config_value 'build.buildType' 'release')"

build_type="$requested_build_type"
if [[ -z "$build_type" || "$build_type" == 'auto' ]]; then
    build_type="$config_build_type"
fi
if [[ -z "$build_type" || "$build_type" == 'auto' ]]; then
    build_type='release'
fi

case "$build_type" in
    debug|release)
        ;;
    *)
        echo "Unsupported build type: $build_type" >&2
        exit 1
        ;;
esac

tavern_tag="$requested_tavern_tag"
if [[ -z "$tavern_tag" || "$tavern_tag" == 'auto' ]]; then
    tavern_tag="$config_tavern_tag"
fi
if [[ -z "$tavern_tag" || "$tavern_tag" == 'auto' || "$tavern_tag" == 'latest' ]]; then
    tavern_tag="$(resolve_latest_tavern_tag)"
fi

normalized_tag="${tavern_tag#refs/tags/}"
safe_tag="$(sanitize_tag "$normalized_tag")"

host_version_base="$(sed -n 's/^staiAndroidHostVersion=//p' "$gradle_properties_path" | head -n 1 | tr -d '\r' | tr -d '[:space:]')"
if [[ -z "$host_version_base" ]]; then
    echo '无法解析 Android 宿主版本基线。' >&2
    exit 1
fi

upstream_release_index="$(count_upstream_release_index "$normalized_tag")"
if ! printf '%s' "$upstream_release_index" | grep -E '^[0-9]+$' >/dev/null 2>&1; then
    upstream_release_index='0'
fi

current_head_sha="$(git -C "$workspace_root" rev-parse "$compare_head" 2>/dev/null | tr -d '\r')"
if [[ -z "$current_head_sha" ]]; then
    current_head_sha="$(git -C "$workspace_root" rev-parse HEAD | tr -d '\r')"
fi

repo_slug="$(resolve_current_repo_slug)"
mapfile -t apk_release_state < <(resolve_apk_release_state "$host_version_base" "$safe_tag" "$build_type" "$current_head_sha" "$repo_slug")

if [[ "${#apk_release_state[@]}" -lt 2 ]]; then
    echo '无法解析 APK 发布版本计数。' >&2
    exit 1
fi

host_version="${apk_release_state[0]}"
apk_release_index="${apk_release_state[1]}"
version_name="${host_version}+tavern.${normalized_tag}"
version_code="$(compute_android_version_code "$host_version")"

rootfs_changed='false'
dependency_changed='false'
server_changed='false'
apk_changed='false'

if [[ -n "$compare_base" && "$compare_base" != '0000000000000000000000000000000000000000' ]]; then
    changed_files="$(git -C "$workspace_root" diff --name-only "$compare_base" "$compare_head")"

    if printf '%s\n' "$changed_files" | grep -E '^(scripts/(android-build-common\.sh|build-tavern-android-runtime-image\.sh|sync-android-rootfs\.sh)|android-tavern/app/src/main/assets/bootstrap/scripts/)' >/dev/null 2>&1; then
        rootfs_changed='true'
    fi

    if printf '%s\n' "$changed_files" | grep -E '^(scripts/(android-build-common\.sh|build-tavern-dependency-packs\.sh)|sillydroid-build-config\.json)' >/dev/null 2>&1; then
        dependency_changed='true'
    fi

    if printf '%s\n' "$changed_files" | grep -E '^(scripts/(android-build-common\.sh|sync-tavern-android-bootstrap\.sh)|android-tavern/server-overlay/)' >/dev/null 2>&1; then
        server_changed='true'
    fi

    if printf '%s\n' "$changed_files" | grep -E '^(android-tavern/|gradle/|gradlew|gradlew\.bat|scripts/(android-build-common\.sh|build-tavern-android-apk\.sh)|sillydroid-build-config\.json|\.github/workflows/sillydroid-upstream-apk\.yml)' >/dev/null 2>&1; then
        apk_changed='true'
    fi
fi

rootfs_release_tag='tavern-rootfs-linux-arm64'
rootfs_release_title="Tavern rootfs linux-arm64 (${host_version})"
rootfs_asset_name='tavern-rootfs-linux-arm64.zip'
rootfs_metadata_name='tavern-rootfs-linux-arm64.metadata.json'

dependency_release_tag='tavern-dependency-packs-linux-arm64'
dependency_release_title="Tavern dependency packs linux-arm64 (${host_version})"
dependency_index_name='component-index.json'

server_release_tag="tavern-server-source-linux-arm64-${safe_tag}"
server_release_title="Tavern server source ${normalized_tag} linux-arm64 (${host_version})"
server_asset_name='server-source.zip'
server_manifest_name='server-source-manifest.json'

artifact_name="sillydroid-android-v${host_version}-${safe_tag}-${build_type}"
release_tag="sillydroid-v${host_version}-${safe_tag}-${build_type}"
release_title="SillyDroid Android v${host_version} / Tavern ${normalized_tag} ${build_type}"
release_prerelease='true'
if [[ "$build_type" == 'release' ]]; then
    release_prerelease='false'
fi

emit tavern_tag "$normalized_tag"
emit safe_tag "$safe_tag"
emit build_type "$build_type"
emit host_version_base "$host_version_base"
emit host_version "$host_version"
emit apk_release_index "$apk_release_index"
emit upstream_release_index "$upstream_release_index"
emit version_name "$version_name"
emit version_code "$version_code"
emit rootfs_changed "$rootfs_changed"
emit dependency_changed "$dependency_changed"
emit server_changed "$server_changed"
emit apk_changed "$apk_changed"
emit rootfs_release_tag "$rootfs_release_tag"
emit rootfs_release_title "$rootfs_release_title"
emit rootfs_asset_name "$rootfs_asset_name"
emit rootfs_metadata_name "$rootfs_metadata_name"
emit dependency_release_tag "$dependency_release_tag"
emit dependency_release_title "$dependency_release_title"
emit dependency_index_name "$dependency_index_name"
emit server_release_tag "$server_release_tag"
emit server_release_title "$server_release_title"
emit server_asset_name "$server_asset_name"
emit server_manifest_name "$server_manifest_name"
emit artifact_name "$artifact_name"
emit release_tag "$release_tag"
emit release_title "$release_title"
emit release_prerelease "$release_prerelease"
emit rootfs_release_dir "artifacts/releases/rootfs/linux-arm64"
emit dependency_release_dir "artifacts/releases/dependency-packs/linux-arm64"
emit server_release_dir "artifacts/releases/server-source/linux-arm64/${normalized_tag}"
emit apk_release_dir "artifacts/releases/android-apk"
