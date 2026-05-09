#!/usr/bin/env bash
set -euo pipefail

# Stage Contract: Local One-Click Orchestrator
# Responsibilities:
# - Act as the normal local entrypoint for a full build.
# - Reuse the shared build plan, then run stage 1 -> 4 in order with the same paths and semantics as CI.
# Must not:
# - Add one-off packaging logic that diverges from the stage scripts.
# - Collapse stage boundaries or silently bypass a prerequisite stage.

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workspace_root="$(cd "$script_dir/.." && pwd)"

runtime_rid='linux-arm64'
requested_tag='auto'
requested_build_type='auto'

usage() {
    cat <<'EOF'
用法：bash ./scripts/build-tavern-android-local.sh [选项]

说明：
- 复用 resolve-tavern-build-plan.sh 解析版本与统一输出路径。
- 顺序执行 rootfs、dependency packs、server source、apk 四个阶段。
- 最终产物统一写入 artifacts/releases/...，与 CI workflow 保持同一套路径语义。

选项：
  --tavern-tag <tag>     指定上游 SillyTavern tag；默认 auto
  --build-type <type>    指定 APK 构建类型：debug 或 release；默认 auto
  --runtime-rid <rid>    指定运行时 RID；默认 linux-arm64
  -h, --help             显示帮助
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --tavern-tag)
            shift
            requested_tag="${1:-}"
            ;;
        --build-type)
            shift
            requested_build_type="${1:-}"
            ;;
        --runtime-rid)
            shift
            runtime_rid="${1:-}"
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "未知参数：$1" >&2
            usage >&2
            exit 1
            ;;
    esac
    shift

done

if [ -z "$requested_tag" ]; then
    requested_tag='auto'
fi

if [ -z "$requested_build_type" ]; then
    requested_build_type='auto'
fi

case "$runtime_rid" in
    linux-arm64)
        ;;
    *)
        echo "当前仅支持 runtime RID: linux-arm64，收到：$runtime_rid" >&2
        exit 1
        ;;
esac

plan_file="$(mktemp)"
trap 'rm -f "$plan_file"' EXIT

bash "$workspace_root/scripts/resolve-tavern-build-plan.sh" \
    --tavern-tag "$requested_tag" \
    --build-type "$requested_build_type" > "$plan_file"

get_plan_value() {
    local key="$1"
    sed -n "s/^${key}=//p" "$plan_file" | tail -n 1
}

tavern_tag="$(get_plan_value tavern_tag)"
build_type="$(get_plan_value build_type)"
host_version="$(get_plan_value host_version)"
version_name="$(get_plan_value version_name)"
version_code="$(get_plan_value version_code)"
artifact_name="$(get_plan_value artifact_name)"
rootfs_release_dir="$(get_plan_value rootfs_release_dir)"
dependency_release_dir="$(get_plan_value dependency_release_dir)"
server_release_dir="$(get_plan_value server_release_dir)"
apk_release_dir="$(get_plan_value apk_release_dir)"
rootfs_asset_name="$(get_plan_value rootfs_asset_name)"
rootfs_metadata_name="$(get_plan_value rootfs_metadata_name)"

if [ -z "$tavern_tag" ] || [ -z "$build_type" ] || [ -z "$artifact_name" ]; then
    echo '无法从构建计划解析关键输出。' >&2
    exit 1
fi

rootfs_dir="$workspace_root/$rootfs_release_dir"
dependency_dir="$workspace_root/$dependency_release_dir"
server_dir="$workspace_root/$server_release_dir"
apk_dir="$workspace_root/$apk_release_dir"

mkdir -p "$rootfs_dir" "$dependency_dir" "$server_dir" "$apk_dir"

rootfs_path="$rootfs_dir/$rootfs_asset_name"
rootfs_metadata_path="$rootfs_dir/$rootfs_metadata_name"
source_apk_path="$apk_dir/app-$build_type.apk"
release_apk_path="$apk_dir/$artifact_name.apk"
release_sha256_path="$release_apk_path.sha256"
release_metadata_path="$apk_dir/$artifact_name.update.json"

printf '==> build plan\n'
printf 'tavern_tag=%s\n' "$tavern_tag"
printf 'build_type=%s\n' "$build_type"
printf 'host_version=%s\n' "$host_version"
printf 'artifact_name=%s\n' "$artifact_name"
printf 'rootfs_dir=%s\n' "$rootfs_dir"
printf 'dependency_dir=%s\n' "$dependency_dir"
printf 'server_dir=%s\n' "$server_dir"
printf 'apk_dir=%s\n' "$apk_dir"

printf '\n==> stage 1/4 rootfs\n'
bash "$workspace_root/scripts/build-tavern-android-runtime-image.sh" \
    --runtime-rid "$runtime_rid" \
    --output "$rootfs_path" \
    --artifact-name "tavern-rootfs-$runtime_rid" \
    --metadata-out "$rootfs_metadata_path"

printf '\n==> stage 2/4 dependency packs\n'
bash "$workspace_root/scripts/build-tavern-dependency-packs.sh" \
    --runtime-rid "$runtime_rid" \
    --target-root "$dependency_dir"

printf '\n==> stage 3/4 server source\n'
bash "$workspace_root/scripts/sync-tavern-android-bootstrap.sh" \
    --runtime-rid "$runtime_rid" \
    --tag "$tavern_tag" \
    --target-root "$server_dir"

printf '\n==> stage 4/4 apk\n'
export SILLYDROID_ANDROID_HOST_VERSION="$host_version"
export SILLYDROID_ANDROID_UPSTREAM_VERSION="$tavern_tag"
export SILLYDROID_ANDROID_VERSION_NAME="$version_name"
export SILLYDROID_ANDROID_VERSION_CODE="$version_code"

bash "$workspace_root/scripts/build-tavern-android-apk.sh" \
    --runtime-rid "$runtime_rid" \
    --build-type "$build_type" \
    --tag "$tavern_tag"

cp "$source_apk_path" "$release_apk_path"
apk_sha256="$(sha256sum "$release_apk_path" | awk '{print $1}')"
apk_size_bytes="$(stat -c '%s' "$release_apk_path")"
printf '%s\n' "$apk_sha256" > "$release_sha256_path"

cat > "$release_metadata_path" <<EOF
{
  "host_version": "$host_version",
  "upstream_version": "$tavern_tag",
  "build_type": "$build_type",
  "version_name": "$version_name",
  "version_code": $version_code,
  "apk_asset_name": "$(basename "$release_apk_path")",
  "apk_sha256": "$apk_sha256",
  "apk_size_bytes": $apk_size_bytes
}
EOF

printf '\n==> done\n'
printf 'apk=%s\n' "$release_apk_path"
printf 'sha256=%s\n' "$apk_sha256"
printf 'update_json=%s\n' "$release_metadata_path"
