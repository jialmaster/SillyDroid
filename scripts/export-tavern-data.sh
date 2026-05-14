#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: export-tavern-data.sh [--output-dir <dir>] [--install-root <dir>]

One-click export for official SillyTavern installs running inside Termux.
The script detects the install root, normalizes data into config/data/plugins/public,
    creates a zip backup, and publishes it through shared storage, Android DownloadManager, or the system share sheet.
EOF
}

ensure_zip_available() {
    if command -v zip >/dev/null 2>&1; then
        return 0
    fi

    if command -v pkg >/dev/null 2>&1; then
        echo "未检测到 zip，正在自动安装：pkg install -y zip"
        pkg install -y zip >/dev/null
    fi

    if ! command -v zip >/dev/null 2>&1; then
        echo "缺少 zip 命令，且自动安装失败。请手工执行：pkg install zip" >&2
        exit 1
    fi
}

# 简洁日志函数（不带时间戳）
log() {
    printf '%s\n' "$*"
}

STEP=0
TOTAL_STEPS=7
step() {
    # 在 set -e 下避免 ((STEP++)) 初始值为 0 时返回 1 导致脚本提前退出
    STEP=$((STEP + 1))
    local percent
    percent=$((STEP * 100 / TOTAL_STEPS))
    # 单行刷新进度，不持续追加新行
    printf '\r[%3d%%] %s' "$percent" "$*"
}

is_termux_environment() {
    if [[ -n "${TERMUX_VERSION:-}" ]]; then
        return 0
    fi
    if [[ "${PREFIX:-}" == */data/data/com.termux/files/usr ]]; then
        return 0
    fi
    return 1
}

canonical_path() {
    local target="$1"
    (
        cd "$target" >/dev/null 2>&1
        pwd -P
    )
}

is_sillytavern_root() {
    local candidate="$1"
    [[ -d "$candidate" ]] || return 1
    [[ -f "$candidate/package.json" ]] || return 1
    [[ -f "$candidate/start.sh" || -d "$candidate/public" || -d "$candidate/src" ]] || return 1
    [[ -f "$candidate/config.yaml" || -d "$candidate/config" || -d "$candidate/data" || -d "$candidate/plugins" || -d "$candidate/extensions" || -d "$candidate/public/scripts/extensions/third-party" ]]
}

directory_has_entries() {
    local directory="$1"
    [[ -d "$directory" ]] || return 1
    find "$directory" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null | grep -q .
}

score_install_root() {
    local candidate="$1"
    local score=0

    if directory_has_entries "$candidate/data"; then
        score=$((score + 8))
    elif [[ -d "$candidate/data" ]]; then
        score=$((score + 3))
    fi

    if [[ -f "$candidate/config.yaml" ]]; then
        score=$((score + 4))
    elif directory_has_entries "$candidate/config"; then
        score=$((score + 4))
    elif [[ -d "$candidate/config" ]]; then
        score=$((score + 1))
    fi

    if directory_has_entries "$candidate/plugins"; then
        score=$((score + 3))
    elif [[ -d "$candidate/plugins" ]]; then
        score=$((score + 1))
    fi

    if directory_has_entries "$candidate/extensions" || directory_has_entries "$candidate/public/scripts/extensions/third-party"; then
        score=$((score + 3))
    elif [[ -d "$candidate/extensions" || -d "$candidate/public/scripts/extensions/third-party" ]]; then
        score=$((score + 1))
    fi

    if [[ -f "$candidate/start.sh" ]]; then
        score=$((score + 1))
    fi

    printf '%s\n' "$score"
}

detect_install_root() {
    local explicit_root="${1:-}"
    if [[ -n "$explicit_root" ]]; then
        if is_sillytavern_root "$explicit_root"; then
            canonical_path "$explicit_root"
            return 0
        fi
        echo "指定的安装目录不是有效的 SillyTavern 根目录：$explicit_root" >&2
        return 1
    fi

    local candidate best_candidate='' best_score=-1
    for candidate in "$HOME/SillyTavern" "$HOME/sillytavern"; do
        if is_sillytavern_root "$candidate"; then
            local score
            score="$(score_install_root "$candidate")"
            if (( score > best_score )); then
                best_candidate="$candidate"
                best_score="$score"
            fi
        fi
    done

    while IFS= read -r candidate; do
        candidate="$(dirname "$candidate")"
        if is_sillytavern_root "$candidate"; then
            local score
            score="$(score_install_root "$candidate")"
            if (( score > best_score )); then
                best_candidate="$candidate"
                best_score="$score"
            fi
        fi
    done < <(find "$HOME" -maxdepth 4 -type f -name package.json 2>/dev/null)

    if [[ -n "$best_candidate" ]]; then
        canonical_path "$best_candidate"
        return 0
    fi

    echo "未找到 SillyTavern 安装目录。可用 --install-root 手工指定。" >&2
    return 1
}

detect_output_dir() {
    local explicit_dir="${1:-}"
    if [[ -n "$explicit_dir" ]]; then
        if mkdir -p "$explicit_dir" >/dev/null 2>&1; then
            canonical_path "$explicit_dir"
            return 0
        fi
        echo "指定输出目录不可写：$explicit_dir" >&2
        return 1
    fi

    local candidate
    for candidate in "$HOME/storage/shared/Download" "$HOME/storage/downloads" "$HOME/storage/shared"; do
        if [[ -d "$candidate" && -w "$candidate" ]]; then
            canonical_path "$candidate"
            return 0
        fi
    done

    echo "未找到可写的共享存储目录。请先在 Termux 中执行 termux-setup-storage 并授权，或使用 --output-dir 显式指定可写目录。" >&2
    return 1
}

ensure_storage_access() {
    local explicit_dir="${1:-}"
    if [[ -n "$explicit_dir" ]]; then
        return 0
    fi

    if [[ -d "$HOME/storage/shared" ]]; then
        return 0
    fi

    if command -v termux-setup-storage >/dev/null 2>&1; then
        log "请求 Termux 存储权限..."
        # 不重定向，让 termux-setup-storage 的交互/提示可见，以免脚本静默挂起
        termux-setup-storage || true
    fi

    if [[ ! -d "$HOME/storage/shared" ]]; then
        log "Termux 共享存储未就绪，将尝试使用 Termux:API 发布导出文件。"
    fi
}

confirm_export_method() {
    local prompt="$1"
    local answer

    if [[ ! -t 0 ]]; then
        echo "当前不是交互式终端，无法确认导出方式：$prompt" >&2
        return 1
    fi

    while true; do
        printf '%s [Y/n]: ' "$prompt" >&2
        IFS= read -r answer || return 1
        case "$answer" in
            '')
                return 0
                ;;
            y|Y|yes|YES|Yes)
                return 0
                ;;
            n|N|no|NO|No)
                return 1
                ;;
            *)
                printf '请输入 Y 或 N，直接回车默认 Y。\n' >&2
                ;;
        esac
    done
}

find_python_command() {
    if command -v python3 >/dev/null 2>&1; then
        command -v python3
        return 0
    fi
    if command -v python >/dev/null 2>&1; then
        command -v python
        return 0
    fi
    return 1
}

choose_local_http_port() {
    local python_bin="$1"
    "$python_bin" - <<'PY'
import socket

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

resolve_archive_target() {
    local explicit_dir="$1"
    local archive_name="$2"
    local resolved_output_dir
    local direct_failure_reason=''

    EXPORT_METHOD=''
    ARCHIVE_PATH=''
    EXPORT_LABEL=''

    if [[ -n "$explicit_dir" ]]; then
        if ! resolved_output_dir="$(detect_output_dir "$explicit_dir")"; then
            return 1
        fi
        EXPORT_METHOD='direct'
        ARCHIVE_PATH="$resolved_output_dir/$archive_name"
        EXPORT_LABEL="$resolved_output_dir"
        return 0
    fi

    if resolved_output_dir="$(detect_output_dir '')"; then
        EXPORT_METHOD='direct'
        ARCHIVE_PATH="$resolved_output_dir/$archive_name"
        EXPORT_LABEL="$resolved_output_dir"
        return 0
    fi
    direct_failure_reason="共享存储目录不可写或不存在，无法直接保存到 Downloads。"
    log "直接保存不可用：$direct_failure_reason"

    local export_cache_dir="$HOME/.sillytavern/export-cache"
    mkdir -p "$export_cache_dir"

    if command -v termux-download >/dev/null 2>&1 && find_python_command >/dev/null 2>&1; then
        log "回退方案：使用 Android 系统下载服务。原因：$direct_failure_reason"
        if confirm_export_method "是否使用系统下载服务导出？选择 N 将继续尝试下一个回退方案。"; then
            EXPORT_METHOD='download'
            ARCHIVE_PATH="$export_cache_dir/$archive_name"
            EXPORT_LABEL='Android 系统下载服务'
            return 0
        fi
        log "用户选择不使用系统下载服务，继续尝试下一个回退方案。"
    else
        local missing_download_reasons=()
        command -v termux-download >/dev/null 2>&1 || missing_download_reasons+=("未检测到 termux-download")
        find_python_command >/dev/null 2>&1 || missing_download_reasons+=("未检测到 python/python3")
        log "系统下载服务不可用：$(IFS='；'; printf '%s' "${missing_download_reasons[*]}")。"
    fi

    if command -v termux-share >/dev/null 2>&1; then
        log "回退方案：使用 Android 系统分享面板。原因：共享存储不可写，且未使用系统下载服务。"
        if confirm_export_method "是否使用系统分享面板导出？选择 N 将中止导出。"; then
            EXPORT_METHOD='share'
            ARCHIVE_PATH="$export_cache_dir/$archive_name"
            EXPORT_LABEL='Android 系统分享面板'
            return 0
        fi
        log "用户选择不使用系统分享面板。"
    else
        log "系统分享面板不可用：未检测到 termux-share。"
    fi

    echo "无法发布导出文件：共享存储不可写，且未检测到可用的 termux-download 或 termux-share。" >&2
    echo "可执行：pkg install termux-api，并安装 Termux:API 应用；或修复 termux-setup-storage 后重试。" >&2
    return 1
}

publish_with_download_manager() {
    local archive_path="$1"
    local archive_name="$2"
    local python_bin port archive_dir server_log server_pid url

    if ! python_bin="$(find_python_command)"; then
        echo "无法使用系统下载服务：未检测到 python/python3 用于临时本地 HTTP 服务。" >&2
        return 1
    fi

    if ! port="$(choose_local_http_port "$python_bin")"; then
        echo "无法分配本地 HTTP 端口。" >&2
        return 1
    fi

    archive_dir="$(dirname "$archive_path")"
    server_log="$archive_dir/.sillytavern-export-http.log"

    (
        cd "$archive_dir"
        nohup "$python_bin" -m http.server "$port" --bind 127.0.0.1 >"$server_log" 2>&1 &
        printf '%s\n' "$!"
    ) >"$archive_dir/.sillytavern-export-http.pid"

    server_pid="$(cat "$archive_dir/.sillytavern-export-http.pid")"
    sleep 1

    if ! kill -0 "$server_pid" >/dev/null 2>&1; then
        echo "临时本地 HTTP 服务启动失败，日志：$server_log" >&2
        return 1
    fi

    url="http://127.0.0.1:${port}/${archive_name}"
    if ! termux-download -t "$archive_name" -d "SillyTavern 数据备份" "$url"; then
        kill "$server_pid" >/dev/null 2>&1 || true
        echo "调用 termux-download 失败。" >&2
        return 1
    fi

    nohup sh -c "sleep 600; kill '$server_pid' >/dev/null 2>&1; rm -f '$archive_dir/.sillytavern-export-http.pid'" >/dev/null 2>&1 &
    log "已交给 Android 系统下载服务：$archive_name"
    log "如果系统下载失败，可在 10 分钟内重新尝试；临时源文件保留在：$archive_path"
}

publish_with_share_sheet() {
    local archive_path="$1"
    if ! termux-share -a send -c application/zip "$archive_path"; then
        echo "调用 termux-share 失败。" >&2
        return 1
    fi
    log "已打开 Android 系统分享面板，请选择文件管理器、网盘或聊天应用保存 ZIP。"
    log "临时源文件保留在：$archive_path"
}

publish_archive() {
    local archive_path="$1"
    local archive_name="$2"

    case "$EXPORT_METHOD" in
        direct)
            log "导出文件已保存到：$archive_path"
            ;;
        download)
            publish_with_download_manager "$archive_path" "$archive_name"
            ;;
        share)
            publish_with_share_sheet "$archive_path"
            ;;
        *)
            echo "未知导出发布方式：$EXPORT_METHOD" >&2
            return 1
            ;;
    esac
}

copy_or_create_empty_dir() {
    local source_dir="$1"
    local target_dir="$2"
    if [[ -d "$source_dir" ]]; then
        mkdir -p "$target_dir"
        cp -a "$source_dir"/. "$target_dir"/
    else
        mkdir -p "$target_dir"
    fi
}

copy_config_payload() {
    local install_root="$1"
    local target_dir="$2"

    local config_dir="$install_root/config"
    local config_file="$install_root/config.yaml"

    mkdir -p "$target_dir"
    if [[ -d "$config_dir" ]]; then
        cp -a "$config_dir"/. "$target_dir"/
        return 0
    fi

    if [[ -f "$config_file" ]]; then
        cp -a "$config_file" "$target_dir/config.yaml"
        return 0
    fi
}

copy_extensions_payload() {
    local install_root="$1"
    local target_public_root="$2"

    local legacy_extensions_dir="$install_root/extensions"
    local full_extensions_dir="$install_root/public/scripts/extensions"
    local third_party_target="$target_public_root/scripts/extensions/third-party"

    mkdir -p "$target_public_root/scripts/extensions"

    if [[ -d "$full_extensions_dir" ]]; then
        cp -a "$full_extensions_dir"/. "$target_public_root/scripts/extensions"/
        return 0
    fi

    if [[ -d "$legacy_extensions_dir" ]]; then
        mkdir -p "$third_party_target"
        cp -a "$legacy_extensions_dir"/. "$third_party_target"/
        return 0
    fi
}

main() {
    local output_dir=''
    local install_root_arg=''

    while (($# > 0)); do
        case "$1" in
            --output-dir)
                output_dir="${2:-}"
                shift 2
                ;;
            --install-root)
                install_root_arg="${2:-}"
                shift 2
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
    done

    log "开始导出 SillyTavern 数据"
    if ! is_termux_environment; then
        log "当前环境不是 Termux，脚本终止。"
        exit 1
    fi

    ensure_zip_available

    ensure_storage_access "$output_dir"

    local install_root
    if ! install_root="$(detect_install_root "$install_root_arg")"; then
        log "未找到 SillyTavern 安装目录。请使用 --install-root 指定安装路径，或在 Termux 中确保 SillyTavern 已安装。脚本中止。"
        exit 1
    fi

    local timestamp archive_name archive_path
    timestamp="$(date +%Y%m%d-%H%M%S)"
    archive_name="sillytavern-termux-backup-${timestamp}.zip"

    local EXPORT_METHOD ARCHIVE_PATH EXPORT_LABEL
    if ! resolve_archive_target "$output_dir" "$archive_name"; then
        log "无法确定导出发布方式，脚本中止。"
        exit 1
    fi
    archive_path="$ARCHIVE_PATH"

    # 在导出进度开始前先打印关键路径信息
    log "安装目录：$install_root"
    log "发布方式：$EXPORT_LABEL"

    local data_root plugins_root
    data_root="$install_root/data"
    plugins_root="$install_root/plugins"

    step "准备临时目录"
    local temp_root stage_root
    temp_root="$(mktemp -d "${TMPDIR:-${PREFIX:-/tmp}}/st-export.XXXXXX")"
    stage_root="$temp_root/payload"
    mkdir -p "$stage_root/config" "$stage_root/data" "$stage_root/plugins" "$stage_root/public"

    trap '[[ -n "${temp_root:-}" ]] && rm -rf "$temp_root"' EXIT

    step "拷贝配置"
    copy_config_payload "$install_root" "$stage_root/config"

    step "拷贝数据"
    copy_or_create_empty_dir "$data_root" "$stage_root/data"

    step "拷贝插件"
    copy_or_create_empty_dir "$plugins_root" "$stage_root/plugins"

    step "拷贝扩展"
    copy_extensions_payload "$install_root" "$stage_root/public"

    step "正在打包为 zip"
    (
        cd "$stage_root"
        zip -qr "$archive_path" config data plugins public
    )

    step "发布导出文件"
    if ! publish_archive "$archive_path" "$archive_name"; then
        printf '\n'
        log "导出文件发布失败，私有源文件保留在：$archive_path"
        exit 1
    fi

    step "导出完成"
    printf '\n'
    printf '导出结果：成功\n'
    case "$EXPORT_METHOD" in
        direct)
            printf 'ZIP 路径：%s\n' "$archive_path"
            ;;
        download)
            printf 'ZIP 已交给系统下载服务：%s\n' "$archive_name"
            printf '临时源文件：%s\n' "$archive_path"
            ;;
        share)
            printf 'ZIP 已打开系统分享面板：%s\n' "$archive_name"
            printf '临时源文件：%s\n' "$archive_path"
            ;;
    esac
}

main "$@"
