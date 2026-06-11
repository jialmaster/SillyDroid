#!/usr/bin/env bash

if [[ -n "${SILLYDROID_ANDROID_BUILD_COMMON_LOADED:-}" ]]; then
    return 0 2>/dev/null || exit 0
fi
readonly SILLYDROID_ANDROID_BUILD_COMMON_LOADED=1

: "${SILLYDROID_ANDROID_API_LEVEL:=36}"
: "${SILLYDROID_ANDROID_BUILD_TOOLS_VERSION:=36.0.0}"
: "${SILLYDROID_ANDROID_CMDLINE_TOOLS_URL:=https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip}"
: "${SILLYDROID_ANDROID_NDK_VERSION:=android-ndk-r27}"
: "${SILLYDROID_ANDROID_NDK_LINUX_URL:=https://dl.google.com/android/repository/android-ndk-r27-linux.zip}"
: "${SILLYDROID_DOTNET_CHANNEL:=10.0}"
: "${SILLYDROID_DOTNET_INSTALL_SCRIPT_URL:=https://dot.net/v1/dotnet-install.sh}"
: "${SILLYDROID_NODE_VERSION:=25.2.1}"
: "${SILLYDROID_ROOTFS_VERSION:=1.0.0}"
: "${SILLYDROID_DOWNLOAD_PARALLELISM:=4}"

if [[ -n "${workspace_root:-}" && -d "$workspace_root/scripts" ]]; then
    readonly SILLYDROID_ANDROID_BUILD_SCRIPTS_DIR="$workspace_root/scripts"
else
    readonly SILLYDROID_ANDROID_BUILD_SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi
readonly SILLYDROID_DOWNLOAD_PROGRESS_SCRIPT_PATH="$SILLYDROID_ANDROID_BUILD_SCRIPTS_DIR/download-with-progress.py"
readonly SILLYDROID_EXTRACT_PROGRESS_SCRIPT_PATH="$SILLYDROID_ANDROID_BUILD_SCRIPTS_DIR/extract-with-progress.py"

declare -ag SILLYDROID_DOWNLOAD_QUEUE=()

sillydroid_log() {
    printf '%s\n' "$*" >&2
}

sillydroid_progress_stage() {
    local current_step="$1"
    local total_steps="$2"
    local message="$3"
    local percent='0'

    if [[ "$total_steps" =~ ^[0-9]+$ && "$total_steps" -gt 0 && "$current_step" =~ ^[0-9]+$ ]]; then
        percent="$((current_step * 100 / total_steps))"
        sillydroid_log "[$current_step/$total_steps ${percent}%] $message"
        return
    fi

    sillydroid_log "$message"
}

sillydroid_warn() {
    printf '[warn] %s\n' "$*" >&2
}

sillydroid_fail() {
    sillydroid_warn "$*"
    exit 1
}

sillydroid_assert_path_exists() {
    local path="$1"
    local message="$2"

    if [[ ! -e "$path" ]]; then
        sillydroid_fail "$message"
    fi
}

sillydroid_find_command() {
    command -v "$1" 2>/dev/null || true
}

sillydroid_require_command() {
    if [[ -z "$(sillydroid_find_command "$1")" ]]; then
        sillydroid_fail "缺少基础命令：$1"
    fi
}

sillydroid_prepend_path() {
    local directory="$1"

    if [[ -z "$directory" || ! -d "$directory" ]]; then
        return
    fi

    case ":$PATH:" in
        *":$directory:"*)
            ;;
        *)
            export PATH="$directory:$PATH"
            ;;
    esac
}

sillydroid_toolchain_root() {
    printf '%s\n' "${SILLYDROID_ANDROID_TOOLCHAIN_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/sillydroid-android-toolchain}"
}

sillydroid_detect_linux_arch() {
    case "$(uname -m)" in
        x86_64|amd64)
            printf 'x64\n'
            ;;
        aarch64|arm64)
            printf 'aarch64\n'
            ;;
        *)
            sillydroid_fail "当前 Linux 架构不支持 Android 打包：$(uname -m)"
            ;;
    esac
}

sillydroid_detect_node_arch() {
    case "$(sillydroid_detect_linux_arch)" in
        x64)
            printf 'x64\n'
            ;;
        aarch64)
            printf 'arm64\n'
            ;;
    esac
}

sillydroid_download_progress_script() {
    printf '%s\n' "$SILLYDROID_DOWNLOAD_PROGRESS_SCRIPT_PATH"
}

sillydroid_extract_progress_script() {
    printf '%s\n' "$SILLYDROID_EXTRACT_PROGRESS_SCRIPT_PATH"
}

sillydroid_download_queue_reset() {
    SILLYDROID_DOWNLOAD_QUEUE=()
}

sillydroid_queue_download_if_missing() {
    local uri="$1"
    local destination_path="$2"
    local label="${3:-$(basename "$destination_path")}"

    if [[ -f "$destination_path" ]]; then
        sillydroid_log "复用下载缓存：$destination_path"
        return
    fi

    mkdir -p "$(dirname "$destination_path")"
    SILLYDROID_DOWNLOAD_QUEUE+=("$label"$'\t'"$uri"$'\t'"$destination_path")
}

sillydroid_run_download_queue() {
    local manifest_path=''
    local entry=''
    local label=''
    local uri=''
    local destination_path=''
    local progress_script="$SILLYDROID_DOWNLOAD_PROGRESS_SCRIPT_PATH"

    if [[ "${#SILLYDROID_DOWNLOAD_QUEUE[@]}" -eq 0 ]]; then
        return
    fi

    sillydroid_log "开始下载 ${#SILLYDROID_DOWNLOAD_QUEUE[@]} 个文件"

    if command -v python3 >/dev/null 2>&1 && [[ -f "$progress_script" ]]; then
        manifest_path="$(mktemp)"
        printf '%s\n' "${SILLYDROID_DOWNLOAD_QUEUE[@]}" > "$manifest_path"
        python3 "$progress_script" --manifest "$manifest_path" --jobs "$SILLYDROID_DOWNLOAD_PARALLELISM"
        rm -f "$manifest_path"
        SILLYDROID_DOWNLOAD_QUEUE=()
        return
    fi

    for entry in "${SILLYDROID_DOWNLOAD_QUEUE[@]}"; do
        IFS=$'\t' read -r label uri destination_path <<< "$entry"
        sillydroid_log "下载依赖：$uri"
        if [[ -n "$(sillydroid_find_command curl)" ]]; then
            curl -L --fail --progress-bar --output "$destination_path" "$uri"
            continue
        fi

        if [[ -n "$(sillydroid_find_command wget)" ]]; then
            wget -O "$destination_path" "$uri"
            continue
        fi

        sillydroid_fail "缺少 curl 或 wget，无法下载：$uri"
    done

    SILLYDROID_DOWNLOAD_QUEUE=()
}

sillydroid_download_file_if_missing() {
    local uri="$1"
    local destination_path="$2"
    local label="${3:-$(basename "$destination_path")}"

    sillydroid_download_queue_reset
    sillydroid_queue_download_if_missing "$uri" "$destination_path" "$label"
    sillydroid_run_download_queue
}

sillydroid_extract_archive_with_progress() {
    local archive_path="$1"
    local destination_root="$2"
    local label="$3"
    local strip_components="${4:-0}"
    local progress_script="$SILLYDROID_EXTRACT_PROGRESS_SCRIPT_PATH"

    mkdir -p "$destination_root"

    if command -v python3 >/dev/null 2>&1 && [[ -f "$progress_script" ]]; then
        if python3 "$progress_script" \
            --archive "$archive_path" \
            --destination "$destination_root" \
            --label "$label" \
            --strip-components "$strip_components"; then
            return
        fi

        sillydroid_log "进度解压器失败，回退到系统解压：$label"
    fi

    sillydroid_log "开始解压：$label"
    if [[ "$archive_path" == *.zip ]]; then
        unzip -q -o "$archive_path" -d "$destination_root"
    else
        tar -xf "$archive_path" -C "$destination_root"
    fi
}

sillydroid_is_valid_java_home() {
    local candidate="$1"
    [[ -n "$candidate" && -x "$candidate/bin/java" && -x "$candidate/bin/jar" ]]
}

sillydroid_activate_java_home() {
    local java_home="$1"

    export JAVA_HOME="$java_home"
    sillydroid_prepend_path "$JAVA_HOME/bin"
    sillydroid_log "使用 JDK：$JAVA_HOME"
}

sillydroid_find_java_home() {
    local candidate=""
    local java_bin=""

    if sillydroid_is_valid_java_home "${JAVA_HOME:-}"; then
        printf '%s\n' "$JAVA_HOME"
        return
    fi

    java_bin="$(sillydroid_find_command java)"
    if [[ -n "$java_bin" ]]; then
        candidate="$(dirname "$(dirname "$(readlink -f "$java_bin")")")"
        if sillydroid_is_valid_java_home "$candidate"; then
            printf '%s\n' "$candidate"
            return
        fi
    fi

    while IFS= read -r candidate; do
        if sillydroid_is_valid_java_home "$candidate"; then
            printf '%s\n' "$candidate"
            return
        fi
    done < <(
        find /usr/lib/jvm -mindepth 1 -maxdepth 1 -type d \( -name '*21*' -o -name '*jdk*' \) 2>/dev/null | sort
    )

    candidate="$(sillydroid_toolchain_root)/java/current"
    if sillydroid_is_valid_java_home "$candidate"; then
        printf '%s\n' "$candidate"
    fi
}

sillydroid_ensure_java_home() {
    local java_home=""
    local toolchain_root="$(sillydroid_toolchain_root)"
    local install_root="$toolchain_root/java"
    local current_root="$install_root/current"
    local archive_path="$install_root/downloads/temurin-21-$(sillydroid_detect_linux_arch).tar.gz"
    local extract_root="$install_root/extracted"
    local extracted_dir=""
    local archive_url="https://api.adoptium.net/v3/binary/latest/21/ga/linux/$(sillydroid_detect_linux_arch)/jdk/hotspot/normal/eclipse?project=jdk"

    java_home="$(sillydroid_find_java_home || true)"
    if sillydroid_is_valid_java_home "$java_home"; then
        sillydroid_activate_java_home "$java_home"
        return
    fi

    sillydroid_require_command tar
    sillydroid_download_file_if_missing "$archive_url" "$archive_path"
    rm -rf "$extract_root" "$current_root"
    mkdir -p "$extract_root"
    sillydroid_extract_archive_with_progress "$archive_path" "$extract_root" 'temurin-jdk'
    extracted_dir="$(find "$extract_root" -mindepth 1 -maxdepth 1 -type d | sort | head -n 1)"
    sillydroid_assert_path_exists "$extracted_dir" "JDK 解压失败：$archive_path"
    mv "$extracted_dir" "$current_root"
    sillydroid_activate_java_home "$current_root"
}

sillydroid_dotnet_has_required_sdk() {
    local dotnet_bin="$1"
    "$dotnet_bin" --list-sdks 2>/dev/null | grep -Eq '^10\.'
}

sillydroid_ensure_dotnet_sdk() {
    local dotnet_bin=""
    local toolchain_root="$(sillydroid_toolchain_root)"
    local installer_path="$toolchain_root/downloads/dotnet-install.sh"
    local install_root="$toolchain_root/dotnet"

    dotnet_bin="$(sillydroid_find_command dotnet)"
    if [[ -n "$dotnet_bin" ]]; then
        if sillydroid_dotnet_has_required_sdk "$dotnet_bin"; then
            sillydroid_log "使用 dotnet：$($dotnet_bin --version) ($dotnet_bin)"
            return
        fi
    fi

    sillydroid_download_file_if_missing "$SILLYDROID_DOTNET_INSTALL_SCRIPT_URL" "$installer_path"
    chmod +x "$installer_path"
    bash "$installer_path" --channel "$SILLYDROID_DOTNET_CHANNEL" --install-dir "$install_root" --no-path
    sillydroid_assert_path_exists "$install_root/dotnet" "dotnet SDK 安装失败：$install_root"
    if ! sillydroid_dotnet_has_required_sdk "$install_root/dotnet"; then
        sillydroid_fail "本地 dotnet SDK 不包含 10.x：$install_root"
    fi

    export DOTNET_ROOT="$install_root"
    sillydroid_prepend_path "$DOTNET_ROOT"
    sillydroid_log "使用本地 dotnet：$($DOTNET_ROOT/dotnet --version) ($DOTNET_ROOT)"
}

sillydroid_node_meets_requirement() {
    local node_bin="$1"
    local major_version=""

    major_version="$($node_bin -p "process.versions.node.split('.')[0]")"
    [[ "$major_version" =~ ^[0-9]+$ && "$major_version" -ge 20 ]]
}

sillydroid_ensure_node() {
    local node_bin=""
    local npm_bin=""
    local toolchain_root="$(sillydroid_toolchain_root)"
    local install_root="$toolchain_root/node"
    local current_root="$install_root/current"
    local archive_path="$install_root/downloads/node-v${SILLYDROID_NODE_VERSION}-linux-$(sillydroid_detect_node_arch).tar.xz"
    local extract_root="$install_root/extracted"
    local extracted_dir=""
    local archive_url="https://nodejs.org/dist/v${SILLYDROID_NODE_VERSION}/node-v${SILLYDROID_NODE_VERSION}-linux-$(sillydroid_detect_node_arch).tar.xz"

    node_bin="$(sillydroid_find_command node)"
    npm_bin="$(sillydroid_find_command npm)"
    if [[ -n "$node_bin" && -n "$npm_bin" ]]; then
        if sillydroid_node_meets_requirement "$node_bin"; then
            sillydroid_log "使用 Node.js：$($node_bin --version) ($node_bin)"
            return
        fi
    fi

    sillydroid_require_command tar
    sillydroid_download_file_if_missing "$archive_url" "$archive_path"
    rm -rf "$extract_root" "$current_root"
    mkdir -p "$extract_root"
    sillydroid_extract_archive_with_progress "$archive_path" "$extract_root" 'node-runtime'
    extracted_dir="$(find "$extract_root" -mindepth 1 -maxdepth 1 -type d | sort | head -n 1)"
    sillydroid_assert_path_exists "$extracted_dir" "Node.js 解压失败：$archive_path"
    mv "$extracted_dir" "$current_root"
    sillydroid_prepend_path "$current_root/bin"
    sillydroid_log "使用本地 Node.js：$($current_root/bin/node --version) ($current_root/bin/node)"
}

sillydroid_ensure_frontend_dist() {
    local client_root="$1"
    local force_rebuild="${2:-0}"
    local dist_index="$client_root/dist/pwa/index.html"
    local install_mode="ci"

    if [[ "$force_rebuild" != "1" && -f "$dist_index" ]]; then
        sillydroid_log "复用前端产物：$dist_index"
        return
    fi

    sillydroid_ensure_node
    if [[ ! -f "$client_root/package-lock.json" ]]; then
        install_mode="install"
    fi

    # 前端 dist 是 Android server source 的一部分；缺失时这里直接补齐，避免后续归档阶段才报错。
    sillydroid_log "安装前端依赖：$client_root"
    (
        cd "$client_root"
        npm "$install_mode" --no-fund
    )
    sillydroid_log "构建前端 PWA：$client_root"
    (
        cd "$client_root"
        npm run build
    )
    sillydroid_assert_path_exists "$dist_index" "前端构建完成但缺少产物：$dist_index"
}

sillydroid_is_valid_linux_android_sdk_root() {
    local sdk_root="$1"
    [[ -n "$sdk_root" && -d "$sdk_root" ]]
}

sillydroid_resolve_linux_android_sdk_root() {
    local candidate=""
    local toolchain_root="$(sillydroid_toolchain_root)"

    for candidate in \
        "${SILLYDROID_ANDROID_SDK_ROOT:-}" \
        "${ANDROID_SDK_ROOT:-}" \
        "${ANDROID_HOME:-}" \
        "$HOME/Android/Sdk" \
        "/opt/android-sdk" \
        "/usr/lib/android-sdk" \
        "$toolchain_root/android-sdk"
    do
        if sillydroid_is_valid_linux_android_sdk_root "$candidate"; then
            printf '%s\n' "$(realpath -m "$candidate")"
            return
        fi
    done

    printf '%s\n' "$(realpath -m "$toolchain_root/android-sdk")"
}

sillydroid_has_linux_android_build_tools() {
    local sdk_root="$1"
    [[ -x "$sdk_root/build-tools/$SILLYDROID_ANDROID_BUILD_TOOLS_VERSION/aapt" ]]
}

sillydroid_write_android_sdk_licenses() {
    local sdk_root="$1"
    local licenses_root="$sdk_root/licenses"

    mkdir -p "$licenses_root"
    # Linux 构建链保持无交互，license 直接写入本地 SDK 目录，避免每台机器重复人工确认。
    cat > "$licenses_root/android-sdk-license" <<'EOF'
24333f8a63b6825ea9c5514f83c2829b004d1fee
EOF
    cat > "$licenses_root/android-sdk-preview-license" <<'EOF'
84831b9409646a918e30573bab4c9c91346d8abd
EOF
}

sillydroid_activate_android_sdk_root() {
    local sdk_root="$1"

    export ANDROID_SDK_ROOT="$sdk_root"
    export ANDROID_HOME="$sdk_root"
    sillydroid_prepend_path "$sdk_root/platform-tools"
    sillydroid_log "使用 Android SDK：$sdk_root"
}

sillydroid_ensure_linux_android_sdk() {
    local sdk_root="$1"
    local cmdline_tools_root="$sdk_root/cmdline-tools/latest"
    local sdkmanager_path="$cmdline_tools_root/bin/sdkmanager"
    local archive_path="$sdk_root/downloads/$(basename "$SILLYDROID_ANDROID_CMDLINE_TOOLS_URL")"

    sillydroid_ensure_java_home
    sillydroid_require_command unzip

    if [[ ! -x "$sdkmanager_path" ]]; then
        sillydroid_download_file_if_missing "$SILLYDROID_ANDROID_CMDLINE_TOOLS_URL" "$archive_path"
        rm -rf "$cmdline_tools_root" "$sdk_root/cmdline-tools/cmdline-tools"
        mkdir -p "$sdk_root/cmdline-tools"
        sillydroid_extract_archive_with_progress "$archive_path" "$sdk_root/cmdline-tools" 'android-cmdline-tools'
        mv "$sdk_root/cmdline-tools/cmdline-tools" "$cmdline_tools_root"
    fi

    sillydroid_activate_android_sdk_root "$sdk_root"
    if sillydroid_has_linux_android_build_tools "$sdk_root"; then
        return
    fi

    sillydroid_write_android_sdk_licenses "$sdk_root"
    sillydroid_log "安装 Android SDK 组件：platform-tools, platforms;android-${SILLYDROID_ANDROID_API_LEVEL}, build-tools;${SILLYDROID_ANDROID_BUILD_TOOLS_VERSION}"
    "$sdkmanager_path" --sdk_root="$sdk_root" \
        "platform-tools" \
        "platforms;android-${SILLYDROID_ANDROID_API_LEVEL}" \
        "build-tools;${SILLYDROID_ANDROID_BUILD_TOOLS_VERSION}"
}

sillydroid_write_android_local_properties() {
    local android_root="$1"
    local sdk_root="$2"

    mkdir -p "$android_root"
    printf 'sdk.dir=%s\n' "$sdk_root" > "$android_root/local.properties"
    sillydroid_log "写入 Android local.properties：$android_root/local.properties"
}

sillydroid_is_valid_linux_android_ndk_root() {
    local ndk_root="$1"
    [[ -n "$ndk_root" && -d "$ndk_root/toolchains/llvm/prebuilt/linux-x86_64" ]]
}

sillydroid_ensure_linux_android_ndk_root() {
    local sdk_root="$1"
    local toolchain_root="$(sillydroid_toolchain_root)"
    local archive_path="$toolchain_root/downloads/$(basename "$SILLYDROID_ANDROID_NDK_LINUX_URL")"
    local ndk_root=""
    local candidate=""

    sillydroid_require_command unzip
    for candidate in \
        "${SILLYDROID_ANDROID_NDK_ROOT:-}" \
        "${ANDROID_NDK_ROOT:-}" \
        "${ANDROID_NDK_HOME:-}" \
        "$sdk_root/ndk/$SILLYDROID_ANDROID_NDK_VERSION" \
        "$HOME/Android/Sdk/ndk/$SILLYDROID_ANDROID_NDK_VERSION" \
        "$toolchain_root/android-sdk/ndk/$SILLYDROID_ANDROID_NDK_VERSION"
    do
        if sillydroid_is_valid_linux_android_ndk_root "$candidate"; then
            ndk_root="$(realpath "$candidate")"
            export SILLYDROID_ANDROID_NDK_ROOT="$ndk_root"
            sillydroid_log "使用 Android NDK：$ndk_root"
            printf '%s\n' "$ndk_root"
            return
        fi
    done

    sillydroid_download_file_if_missing "$SILLYDROID_ANDROID_NDK_LINUX_URL" "$archive_path"
    rm -rf "$sdk_root/ndk/$SILLYDROID_ANDROID_NDK_VERSION"
    mkdir -p "$sdk_root/ndk"
    sillydroid_extract_archive_with_progress "$archive_path" "$sdk_root/ndk" 'android-ndk'
    ndk_root="$sdk_root/ndk/$SILLYDROID_ANDROID_NDK_VERSION"
    sillydroid_assert_path_exists "$ndk_root/toolchains/llvm/prebuilt/linux-x86_64" "Android NDK 安装失败：$ndk_root"
    export SILLYDROID_ANDROID_NDK_ROOT="$ndk_root"
    sillydroid_log "使用本地 Android NDK：$ndk_root"
    printf '%s\n' "$ndk_root"
}
