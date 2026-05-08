#!/usr/bin/env bash
set -euo pipefail

runtime_rid='linux-arm64'
build_type=''
runtime_image_path=''
server_package_path=''
tavern_tag=''
refresh_runtime_image='0'
default_android_build_root="${STAI_TAVERN_ANDROID_BUILD_ROOT:-${STAI_ANDROID_BUILD_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/stai-tavern-android-build}}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workspace_root="$(cd "$script_dir/.." && pwd)"
android_root="$workspace_root/android-tavern"
sync_server_script="$workspace_root/scripts/sync-tavern-android-bootstrap.sh"
runtime_image_script="$workspace_root/scripts/build-tavern-android-runtime-image.sh"
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

source_android_build_common() {
    local common_script="$workspace_root/scripts/android-build-common.sh"

    # Windows 工作树里的公共 bash 脚本可能是 CRLF；这里用临时 LF 副本加载，避免回写旧脚本。
    # shellcheck disable=SC1090
    source <(tr -d '\r' < "$common_script")
}

source_android_build_common

usage() {
    cat <<'EOF'
Usage: build-tavern-android-apk.sh [--runtime-image <path>] [--server-package <path> | --tag <sillytavern-tag>] [--runtime-rid linux-arm64] [--build-type debug|release] [--refresh-runtime-image]

说明：
- 若不传 --runtime-image，脚本会调用新的 tavern runtime image 构建链生成独立 image，只写入当前仓库的 android-tavern 工程。
- 若不传 --server-package，则优先读取仓库根目录 stai-build-config.json 的 build.tavernVersion，必要时自动生成 tavern server-payload.zip。
- 若不传 --build-type，则优先读取仓库根目录 stai-build-config.json 的 build.buildType。
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
        --refresh-runtime-image)
            refresh_runtime_image='1'
            shift
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
    runtime_image_path="$workspace_root/artifacts/android-runtime-images/tavern-android-runtime-$runtime_rid.zip"
fi

if [[ -z "$server_package_path" ]]; then
    generated_server_root="$workspace_root/artifacts/validation/android-tavern-server-package/$runtime_rid"
    bash "$sync_server_script" --runtime-rid "$runtime_rid" --tag "$tavern_tag" --target-root "$generated_server_root"
    server_package_path="$generated_server_root/server-payload.zip"
fi

apply_runtime_image() {
    local image_path="$1"
    local project_root="$2"
    local extract_root="$workspace_root/artifacts/tmp/android-tavern-runtime-image/$runtime_rid"
    local bootstrap_root="$project_root/app/src/main/assets/bootstrap"
    local rootfs_root="$bootstrap_root/rootfs"
    local jni_lib_root="$project_root/app/src/main/jniLibs/arm64-v8a"

    stai_assert_path_exists "$image_path" "缺少 Android runtime image：$image_path"
    stai_require_command unzip

    rm -rf "$extract_root" "$rootfs_root"
    mkdir -p "$extract_root" "$bootstrap_root" "$jni_lib_root"
    unzip -q -o "$image_path" -d "$extract_root"

    stai_assert_path_exists "$extract_root/assets/bootstrap/rootfs/rootfs-fs.zip" "runtime image 缺少 rootfs 资产：$image_path"
    stai_assert_path_exists "$extract_root/jniLibs/arm64-v8a/libproot.so" "runtime image 缺少 jniLibs 资产：$image_path"

    cp -R "$extract_root/assets/bootstrap/rootfs" "$bootstrap_root/"

    rm -f "$jni_lib_root"/libproot.so "$jni_lib_root"/libproot-loader.so "$jni_lib_root"/libproot-loader32.so "$jni_lib_root"/libtalloc_2.so
    cp -R "$extract_root/jniLibs/arm64-v8a/." "$jni_lib_root/"

    stai_log "已应用 Tavern runtime image：$image_path"
}

apply_server_package() {
    local package_path="$1"
    local project_root="$2"
    local server_root="$project_root/app/src/main/assets/bootstrap/server"
    local archive_path="$server_root/server-payload.zip"
    local manifest_path="$server_root/bootstrap-manifest.json"
    local source_manifest_path="$(dirname "$package_path")/bootstrap-manifest.json"
    local archive_size_bytes='0'
    local synced_at_utc="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

    stai_assert_path_exists "$package_path" "缺少 Tavern server 底包：$package_path"

    rm -rf "$server_root"
    mkdir -p "$server_root"
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
  "archiveFile": "server-payload.zip",
  "archiveSizeBytes": $archive_size_bytes
}
EOF
    fi

    stai_log "已应用 Tavern server 底包：$package_path"
}

android_sdk_root="$(stai_resolve_linux_android_sdk_root)"
stai_ensure_linux_android_sdk "$android_sdk_root"
stai_write_android_local_properties "$android_root" "$android_sdk_root"
stai_ensure_java_home

gradle_task=":app:assemble${build_type^}"
android_build_root="$(realpath -m "$default_android_build_root")"
apk_output_dir="$android_build_root/app/outputs/apk/$build_type"
apk_path="$apk_output_dir/app-$build_type.apk"
apksigner_path="$android_sdk_root/build-tools/$STAI_ANDROID_BUILD_TOOLS_VERSION/apksigner"
workspace_apk_root="$workspace_root/artifacts/validation/android-tavern"
workspace_apk_path="$workspace_apk_root/app-$build_type.apk"

export STAI_TAVERN_ANDROID_BUILD_ROOT="$android_build_root"
mkdir -p "$workspace_apk_root"

if [[ ! -f "$runtime_image_path" || "$refresh_runtime_image" == '1' ]]; then
    bash "$runtime_image_script" --runtime-rid "$runtime_rid" --output "$runtime_image_path"
fi

apply_runtime_image "$runtime_image_path" "$android_root"
apply_server_package "$server_package_path" "$android_root"

(
    cd "$android_root"
    bash ../gradlew --no-daemon -p "$android_root" "$gradle_task"
)

if [[ ! -f "$apk_path" ]]; then
    mapfile -t apk_candidates < <(find "$apk_output_dir" -maxdepth 1 -type f -name '*.apk' | sort)
    case "${#apk_candidates[@]}" in
        1)
            apk_path="${apk_candidates[0]}"
            ;;
        0)
            stai_warn "Tavern APK 构建完成但未找到产物：$apk_path"
            exit 1
            ;;
        *)
            printf '检测到多个 Tavern APK 产物，无法自动判定目标文件：\n' >&2
            printf '  %s\n' "${apk_candidates[@]}" >&2
            exit 1
            ;;
    esac
fi

stai_assert_path_exists "$apk_path" "Tavern APK 构建完成但未找到产物：$apk_path"

if [[ "$build_type" == 'release' ]]; then
    if [[ "$(basename "$apk_path")" == *-unsigned.apk ]]; then
        stai_fail "release APK 仍为 unsigned：$(basename "$apk_path")。请提供正式签名参数。"
    fi

    stai_assert_path_exists "$apksigner_path" "缺少 apksigner：$apksigner_path"
    "$apksigner_path" verify "$apk_path"
    stai_log "已验证 Tavern release APK 签名：$apk_path"
fi

cp "$apk_path" "$workspace_apk_path"
printf 'Built Tavern APK: %s\n' "$(realpath "$workspace_apk_path")"