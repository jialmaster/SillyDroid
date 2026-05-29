#!/usr/bin/env bash
set -euo pipefail

print_usage() {
    cat <<'USAGE'
Usage:
  publish-sillydroid-site-release-state.sh --repository OWNER/REPO --event-action ACTION --output PATH

Builds the SillyDroid website latest-release.json from GitHub Releases.
ACTION must be one of: published, edited, deleted, manual-rebuild.
USAGE
}

repository=''
event_action=''
output_path=''

while [[ $# -gt 0 ]]; do
    case "$1" in
        --repository)
            repository="${2:-}"
            shift 2
            ;;
        --event-action)
            event_action="${2:-}"
            shift 2
            ;;
        --output)
            output_path="${2:-}"
            shift 2
            ;;
        -h|--help)
            print_usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            print_usage >&2
            exit 2
            ;;
    esac
done

if [[ -z "$repository" || -z "$event_action" || -z "$output_path" ]]; then
    print_usage >&2
    exit 2
fi

case "$event_action" in
    published) trigger='release-published' ;;
    edited) trigger='release-edited' ;;
    deleted) trigger='release-deleted' ;;
    manual-rebuild) trigger='manual-rebuild' ;;
    *)
        echo "Unsupported release action: $event_action" >&2
        exit 2
        ;;
esac

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "Missing required command: $1" >&2
        exit 127
    fi
}

require_command curl
require_command python3

updated_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

releases_path="$tmp_dir/releases.jsonl"
release_state_path="$tmp_dir/latest-release.json"
api_header=(-H 'Accept: application/vnd.github+json' -H 'User-Agent: SillyDroid-Site-Release-State-Publisher')

if [[ -n "${GITHUB_TOKEN:-${GH_TOKEN:-}}" ]]; then
    api_token="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
    api_header+=(-H "Authorization: Bearer $api_token")
fi

page=1
while :; do
    page_path="$tmp_dir/releases-page-$page.json"
    curl -fsSL "${api_header[@]}" "https://api.github.com/repos/$repository/releases?per_page=100&page=$page" -o "$page_path"

    if python3 - "$page_path" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
sys.exit(0 if isinstance(payload, list) and payload else 1)
PY
    then
        python3 - "$page_path" >> "$releases_path" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as handle:
    for release in json.load(handle):
        print(json.dumps(release, ensure_ascii=False, separators=(",", ":")))
PY
        page=$((page + 1))
    else
        break
    fi
done

python3 - "$repository" "$updated_at" "$trigger" "$releases_path" "$release_state_path" <<'PY'
import json
import re
import sys
import urllib.request

repository, updated_at, trigger, releases_path, output_path = sys.argv[1:6]


def assert_string(value, field_name):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"Invalid {field_name}: expected non-empty string.")
    return value.strip()


def assert_sha256(value, field_name):
    normalized = assert_string(value, field_name).lower()
    if not re.fullmatch(r"[a-f0-9]{64}", normalized):
        raise ValueError(f"Invalid {field_name}: expected 64-char hex sha256.")
    return normalized


def assert_non_negative_integer(value, field_name):
    if not isinstance(value, int) or value < 0:
        raise ValueError(f"Invalid {field_name}: expected non-negative integer.")
    return value


def fetch_json(url):
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json",
            "User-Agent": "SillyDroid-Site-Release-State-Publisher",
        },
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def build_unavailable_state():
    return {
        "schemaVersion": 1,
        "channel": "stable",
        "repository": repository,
        "updatedAt": updated_at,
        "source": {
            "provider": "github-release",
            "trigger": trigger,
        },
        "status": {
            "code": "unavailable",
            "reason": trigger,
        },
        "release": None,
    }


def build_ready_state(release, metadata, apk_asset):
    return {
        "schemaVersion": 1,
        "channel": "stable",
        "repository": repository,
        "updatedAt": updated_at,
        "source": {
            "provider": "github-release",
            "trigger": trigger,
        },
        "status": {
            "code": "ready",
            "reason": trigger,
        },
        "release": {
            "tag": assert_string(release.get("tag_name"), "release.tag_name"),
            "title": assert_string(release.get("name") or release.get("tag_name"), "release.title"),
            "url": assert_string(release.get("html_url"), "release.html_url"),
            "publishedAt": assert_string(release.get("published_at") or release.get("created_at"), "release.published_at"),
            "isPrerelease": release.get("prerelease") is True,
            "notesMarkdown": assert_string(release.get("body"), "release.body"),
            "buildType": "release",
            "versionName": assert_string(metadata.get("version_name"), "metadata.version_name"),
            "hostVersion": assert_string(metadata.get("host_version"), "metadata.host_version"),
            "upstreamVersion": assert_string(metadata.get("upstream_version"), "metadata.upstream_version"),
            "apk": {
                "assetName": assert_string(metadata.get("apk_asset_name"), "metadata.apk_asset_name"),
                "downloadUrl": assert_string(apk_asset.get("browser_download_url"), "apk.browser_download_url"),
                "sha256": assert_sha256(metadata.get("apk_sha256"), "metadata.apk_sha256"),
                "sizeBytes": assert_non_negative_integer(metadata.get("apk_size_bytes"), "metadata.apk_size_bytes"),
                "updatedAt": updated_at,
            },
        },
    }


releases = []
try:
    with open(releases_path, encoding="utf-8") as handle:
        releases = [json.loads(line) for line in handle if line.strip()]
except FileNotFoundError:
    releases = []

stable_releases = [
    release for release in releases
    if release and release.get("draft") is not True and release.get("prerelease") is not True
]
stable_releases.sort(key=lambda release: release.get("published_at") or release.get("created_at") or "", reverse=True)

state = None
for release in stable_releases:
    assets = release.get("assets") if isinstance(release.get("assets"), list) else []
    # 只有带 update metadata 的正式 release 才能成为站点公开 latest 指针。
    metadata_asset = next(
        (asset for asset in assets if isinstance(asset.get("name"), str) and asset["name"].endswith(".update.json")),
        None,
    )
    metadata_url = metadata_asset.get("browser_download_url") if metadata_asset else None
    if not metadata_url:
        continue

    metadata = fetch_json(metadata_url)
    if metadata.get("build_type") != "release":
        continue

    apk_asset_name = metadata.get("apk_asset_name")
    apk_asset = next((asset for asset in assets if asset.get("name") == apk_asset_name), None)
    if not apk_asset or not apk_asset.get("browser_download_url"):
        continue

    state = build_ready_state(release, metadata, apk_asset)
    break

if state is None:
    state = build_unavailable_state()

with open(output_path, "w", encoding="utf-8", newline="\n") as handle:
    json.dump(state, handle, ensure_ascii=False, indent=2)
    handle.write("\n")
PY

mkdir -p "$(dirname "$output_path")"
cp "$release_state_path" "$output_path"

echo "release-state=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["status"]["code"])' "$output_path")"
echo "release-trigger=$trigger"
echo "release-output=$output_path"
