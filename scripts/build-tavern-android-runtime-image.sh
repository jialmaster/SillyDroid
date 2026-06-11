#!/usr/bin/env bash
set -euo pipefail

# Stage Contract: 1/4 Runtime Image
# Responsibilities:
# - Produce only the reusable runtime image/rootfs artifact and its metadata.
# - Write stage-1 outputs to artifacts/releases/rootfs/<rid>/...
# Must not:
# - Build dependency packs.
# - Sync Tavern server source.
# - Compose final server package or assemble the APK.

runtime_rid='linux-arm64'
output_path=''
artifact_name=''
metadata_path=''

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workspace_root="$(cd "$script_dir/.." && pwd)"
android_tavern_root="$workspace_root/android-tavern"
rootfs_sync_script="$workspace_root/scripts/sync-android-rootfs.sh"

source_android_build_common() {
    local common_script="$workspace_root/scripts/android-build-common.sh"

    # Windows 工作树里的公共 bash 脚本可能是 CRLF；这里用临时 LF 副本加载，避免回写旧脚本。
    # shellcheck disable=SC1090
    source <(tr -d '\r' < "$common_script")
}

source_android_build_common

usage() {
    cat <<'EOF'
Usage: build-tavern-android-runtime-image.sh [--runtime-rid linux-arm64] [--output <path>] [--artifact-name <name>] [--metadata-out <path>]
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --runtime-rid)
            runtime_rid="$2"
            shift 2
            ;;
        --output)
            output_path="$2"
            shift 2
            ;;
        --artifact-name)
            artifact_name="$2"
            shift 2
            ;;
        --metadata-out)
            metadata_path="$2"
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

resolve_default_metadata_path() {
    local image_path="$1"
    local image_dir="$(dirname "$image_path")"
    local image_base="$(basename "$image_path")"
    image_base="${image_base%.zip}"
    printf '%s/%s.metadata.json\n' "$image_dir" "$image_base"
}

write_runtime_image_metadata() {
    local image_path="$1"
    local output_metadata_path="$2"
    local resolved_image_path="$(realpath "$image_path")"
    local image_file_name="$(basename "$resolved_image_path")"
    local generated_at_utc="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    local image_sha256="$(sha256sum "$resolved_image_path" | awk '{print $1}')"
    local image_size_bytes="$(stat -c '%s' "$resolved_image_path")"

    mkdir -p "$(dirname "$output_metadata_path")"
    cat > "$output_metadata_path" <<EOF
{
  "artifactName": "$artifact_name",
  "runtimeRid": "$runtime_rid",
  "imagePath": "$resolved_image_path",
  "imageFileName": "$image_file_name",
  "imageSha256": "$image_sha256",
  "imageSizeBytes": $image_size_bytes,
  "generatedAtUtc": "$generated_at_utc"
}
EOF
}

patch_sync_script_for_tavern() {
    local generated_script_path="$1"
    local tavern_jni_lib_root="$2"
    local tavern_runtime_prefix='/data/data/com.jm.sillydroid/files/usr'
    local escaped_scripts_dir='' 
    local escaped_workspace_root=''
    local escaped_jni_root=''
    local escaped_runtime_prefix=''

    tr -d '\r' < "$rootfs_sync_script" > "$generated_script_path"
    escaped_scripts_dir="$(printf '%s' "$script_dir" | sed 's/[\/&]/\\&/g')"
    escaped_workspace_root="$(printf '%s' "$workspace_root" | sed 's/[\/&]/\\&/g')"
    escaped_jni_root="$(printf '%s' "$tavern_jni_lib_root" | sed 's/[\/&]/\\&/g')"
    escaped_runtime_prefix="$(printf '%s' "$tavern_runtime_prefix" | sed 's/[\/&]/\\&/g')"

    # 旧 rootfs 同步脚本把 jniLibs 和 runtime_prefix 硬编码到了旧 app；这里生成一份临时副本，只改这几个绑定点，复用其余构建逻辑。
    sed -i "s|^script_dir=.*$|script_dir='$escaped_scripts_dir'|" "$generated_script_path"
    sed -i "s|^workspace_root=.*$|workspace_root='$escaped_workspace_root'|" "$generated_script_path"
    sed -i "s|^jni_libs_root=.*$|jni_libs_root='$escaped_jni_root'|" "$generated_script_path"
    sed -i "s|^runtime_prefix=.*$|runtime_prefix='$escaped_runtime_prefix'|" "$generated_script_path"
    sed -i 's|^source ".*android-build-common\.sh"$|source <(tr -d '\''\r'\'' < "$workspace_root/scripts/android-build-common.sh")|' "$generated_script_path"
    perl -0pi -e 's/offline_runtime_packages=\(\n    ca-certificates\n    libfontconfig1\n    libgomp1\n/offline_runtime_packages=(\n    ca-certificates\n    libfontconfig1\n    libgomp1\n    libatomic1\n/s' "$generated_script_path"
    chmod +x "$generated_script_path"
}

generate_tavern_rootfs_assets() {
    local working_root="$1"
    local tavern_rootfs_root="$2"
    local tavern_jni_lib_root="$3"
    local generated_sync_script="$working_root/generated-sync-android-rootfs.sh"

    rm -rf "$tavern_rootfs_root" "$tavern_jni_lib_root"
    mkdir -p "$tavern_rootfs_root" "$tavern_jni_lib_root"
    patch_sync_script_for_tavern "$generated_sync_script" "$tavern_jni_lib_root"
    bash "$generated_sync_script" --target-root "$tavern_rootfs_root"
}

stage_runtime_image() {
    local stage_root="$1"
    local tavern_rootfs_root="$2"
    local tavern_jni_lib_root="$3"
    local manifest_path="$stage_root/runtime-image-manifest.json"
    local native_library_path=''

    rm -rf "$stage_root"
    mkdir -p "$stage_root/assets/bootstrap" "$stage_root/jniLibs/arm64-v8a"

    sillydroid_assert_path_exists "$tavern_rootfs_root/rootfs-fs.zip" "缺少 Tavern rootfs 归档：$tavern_rootfs_root/rootfs-fs.zip"
    sillydroid_assert_path_exists "$tavern_rootfs_root/rootfs-manifest.json" "缺少 Tavern rootfs manifest：$tavern_rootfs_root/rootfs-manifest.json"
    sillydroid_assert_path_exists "$tavern_jni_lib_root/libtermux-node.so" "缺少 Tavern Termux node 入口：$tavern_jni_lib_root/libtermux-node.so"
    sillydroid_assert_path_exists "$tavern_jni_lib_root/libtermux-git.so" "缺少 Tavern Termux git 入口：$tavern_jni_lib_root/libtermux-git.so"
    sillydroid_assert_path_exists "$tavern_jni_lib_root/libtermux-git-remote-http.so" "缺少 Tavern Termux git HTTPS helper 入口：$tavern_jni_lib_root/libtermux-git-remote-http.so"
    sillydroid_assert_path_exists "$tavern_jni_lib_root/libtermux-sh.so" "缺少 Tavern Termux shell 入口：$tavern_jni_lib_root/libtermux-sh.so"

    # 宿主启动脚本属于 Stage 4/APK 外层轻量资产；runtime image 只包含可复用 rootfs 与 native runtime。
    cp -R "$tavern_rootfs_root" "$stage_root/assets/bootstrap/rootfs"
    shopt -s nullglob
    for native_library_path in "$tavern_jni_lib_root"/lib*.so*; do
        cp -f "$native_library_path" "$stage_root/jniLibs/arm64-v8a/"
    done
    shopt -u nullglob

    cat > "$manifest_path" <<EOF
{
  "runtimeRid": "$runtime_rid",
  "generatedAtUtc": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "rootfsManifest": "assets/bootstrap/rootfs/rootfs-manifest.json"
}
EOF
}

case "$runtime_rid" in
    linux-arm64)
        ;;
    *)
        echo "Unsupported runtime RID: $runtime_rid" >&2
        exit 1
        ;;
esac

if [[ -z "$output_path" ]]; then
    output_path="$workspace_root/artifacts/releases/rootfs/$runtime_rid/tavern-rootfs-$runtime_rid.zip"
fi

if [[ -z "$artifact_name" ]]; then
    artifact_name="tavern-rootfs-$runtime_rid"
fi

output_path="$(realpath -m "$output_path")"
if [[ -z "$metadata_path" ]]; then
    metadata_path="$(resolve_default_metadata_path "$output_path")"
fi
metadata_path="$(realpath -m "$metadata_path")"

sillydroid_assert_path_exists "$rootfs_sync_script" "缺少 rootfs 同步脚本：$rootfs_sync_script"
sillydroid_require_command bash
sillydroid_require_command sha256sum
sillydroid_ensure_java_home

# Stage 1 的中间解压、整理和归档目录落到本地缓存，避免 DrvFs 慢路径并避免污染工作区。
working_root="${SILLYDROID_TAVERN_ANDROID_RUNTIME_IMAGE_WORK_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/sillydroid-tavern-runtime-image-$runtime_rid}"
tavern_rootfs_root="$working_root/rootfs"
tavern_jni_lib_root="$working_root/jniLibs/arm64-v8a"
stage_root="$working_root/stage"
output_directory="$(dirname "$output_path")"

mkdir -p "$output_directory"
sillydroid_progress_stage 1 3 "开始生成 Tavern rootfs 资产"
generate_tavern_rootfs_assets "$working_root" "$tavern_rootfs_root" "$tavern_jni_lib_root"
sillydroid_progress_stage 2 3 "开始整理 runtime image stage"
stage_runtime_image "$stage_root" "$tavern_rootfs_root" "$tavern_jni_lib_root"

rm -f "$output_path"
sillydroid_progress_stage 3 3 "开始归档 Tavern Android runtime image"
"$JAVA_HOME/bin/jar" --create --file "$output_path" --no-manifest -C "$stage_root" .
sillydroid_assert_path_exists "$output_path" "Tavern Android runtime image 打包失败：$output_path"
write_runtime_image_metadata "$output_path" "$metadata_path"
sillydroid_log "已生成 Tavern Android runtime image：$(realpath "$output_path")"
sillydroid_log "已生成 Tavern Android runtime image metadata：$(realpath "$metadata_path")"
