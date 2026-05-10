#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: export-tavern-data.sh [--output-dir <dir>] [--install-root <dir>]

One-click export for official SillyTavern installs running inside Termux.
The script detects the install root, normalizes data into config/data/plugins/public,
creates a zip backup, and prints the final archive path.
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

# 简单日志与分步提示函数
log() {
    printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"
}

STEP=0
TOTAL_STEPS=11
step() {
    # 在 set -e 下避免 ((STEP++)) 初始值为 0 时返回 1 导致脚本提前退出
    STEP=$((STEP + 1))
    log "Step ${STEP}/${TOTAL_STEPS}: $*"
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
    [[ -f "$candidate/start.sh" || -d "$candidate/public" || -d "$candidate/src" ]]
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

    local candidate
    for candidate in "$HOME/SillyTavern" "$HOME/sillytavern"; do
        if is_sillytavern_root "$candidate"; then
            canonical_path "$candidate"
            return 0
        fi
    done

    while IFS= read -r candidate; do
        candidate="$(dirname "$candidate")"
        if is_sillytavern_root "$candidate"; then
            canonical_path "$candidate"
            return 0
        fi
    done < <(find "$HOME" -maxdepth 4 -type f -name package.json 2>/dev/null)

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
        # 指定目录无权限时回退到 HOME，避免中断导出流程
        canonical_path "$HOME"
        return 0
    fi

    local candidate
    for candidate in "$HOME/storage/shared/Download" "$HOME/storage/downloads" "$HOME/storage/shared"; do
        if [[ -d "$candidate" && -w "$candidate" ]]; then
            canonical_path "$candidate"
            return 0
        fi
    done

    canonical_path "$HOME"
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
        log "未检测到已授权的共享存储目录，正在尝试请求 Termux 存储权限（如果出现授权提示，请选择允许）..."
        # 不重定向，让 termux-setup-storage 的交互/提示可见，以免脚本静默挂起
        termux-setup-storage || true
    fi
}

bool_to_text() {
    if [[ "$1" == "1" ]]; then
        printf '是\n'
    else
        printf '否\n'
    fi
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

describe_extensions_sources() {
    local install_root="$1"

    if [[ -d "$install_root/public/scripts/extensions" ]]; then
        printf '%s\n' "$install_root/public/scripts/extensions"
        return 0
    fi

    if [[ -d "$install_root/extensions" ]]; then
        printf '%s\n' "$install_root/extensions (legacy; exported as public/scripts/extensions/third-party)"
        return 0
    fi

    printf '%s\n' "未检测到（已按空目录导出）"
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
    step "检测 Termux 环境"
    if ! is_termux_environment; then
        log "当前环境不是 Termux，脚本终止。"
        exit 1
    fi

    step "检查 zip 可用性"
    ensure_zip_available

    step "检查存储授权"
    ensure_storage_access "$output_dir"

    step "解析安装目录"
    local install_root
    if ! install_root="$(detect_install_root "$install_root_arg")"; then
        log "未找到 SillyTavern 安装目录。请使用 --install-root 指定安装路径，或在 Termux 中确保 SillyTavern 已安装。脚本中止。"
        exit 1
    fi

    local config_root data_root plugins_root extensions_sources
    config_root="$install_root/config"
    data_root="$install_root/data"
    plugins_root="$install_root/plugins"
    extensions_sources="$(describe_extensions_sources "$install_root")"

    local resolved_output_dir
    resolved_output_dir="$(detect_output_dir "$output_dir")"

    if [[ -n "$output_dir" && "$resolved_output_dir" != "$(canonical_path "$output_dir" 2>/dev/null || printf '%s' "$output_dir")" ]]; then
        log "指定输出目录不可写，已回退到：$resolved_output_dir"
    fi

    local direct_save_available=0
    case "$resolved_output_dir" in
        "$HOME/storage"/*|/storage/*)
            direct_save_available=1
            ;;
    esac

    local timestamp archive_name archive_path
    timestamp="$(date +%Y%m%d-%H%M%S)"
    archive_name="sillytavern-termux-backup-${timestamp}.zip"
    archive_path="$resolved_output_dir/$archive_name"

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
        log "开始打包到：$archive_path"
        zip -r "$archive_path" config data plugins public
    )

    step "导出完成"
    log "导出文件已保存到：$archive_path"

    printf '环境检查：%s\n' "$(bool_to_text 1 | tr -d '\n')"
    printf 'SillyTavern 安装目录：%s\n' "$install_root"
    if [[ -d "$config_root" ]]; then
        printf '配置目录：%s\n' "$config_root"
    elif [[ -f "$install_root/config.yaml" ]]; then
        printf '配置文件：%s\n' "$install_root/config.yaml"
    else
        printf '配置目录：未检测到（已按空目录导出）\n'
    fi
    printf '数据目录：%s\n' "$data_root"
    printf '插件目录：%s\n' "$plugins_root"
    printf '扩展来源（导出到 public/scripts/extensions）：%s\n' "$extensions_sources"
    printf '可直接保存到手机共享存储：%s\n' "$(bool_to_text "$direct_save_available" | tr -d '\n')"
    if [[ "$direct_save_available" != '1' ]]; then
        printf '提示：未检测到已授权的共享存储目录；如需直接导出到手机文件管理器可先执行 termux-setup-storage。\n'
    fi
    printf '导出结果：成功\n'
    printf 'ZIP 路径：%s\n' "$archive_path"
}

main "$@"
