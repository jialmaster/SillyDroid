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
    ((STEP++))
    log "Step ${STEP}/${TOTAL_STEPS}: $*"
}

count_files() {
    local dir="$1"
    if [[ -d "$dir" ]]; then
        find "$dir" -type f 2>/dev/null | wc -l
    else
        echo 0
    fi
}

is_termux_environment() {
    [[ -n "${TERMUX_VERSION:-}" ]] || [[ "${PREFIX:-}" == */data/data/com.termux/files/usr ]]
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

copy_extensions_payload() {
    local install_root="$1"
    local target_public_root="$2"

    local legacy_extensions_dir="$install_root/extensions"
    local full_extensions_dir="$install_root/public/scripts/extensions"
    local third_party_target="$target_public_root/scripts/extensions/third-party"

    mkdir -p "$target_public_root/scripts/extensions"

    if [[ -d "$full_extensions_dir" ]]; then
        local n_files
        n_files=$(count_files "$full_extensions_dir")
        log "检测到扩展目录：$full_extensions_dir （$n_files 个文件），开始复制到 public/scripts/extensions..."
        cp -a "$full_extensions_dir"/. "$target_public_root/scripts/extensions"/
        log "扩展复制完成。"
        return 0
    fi

    if [[ -d "$legacy_extensions_dir" ]]; then
        local n_files
        n_files=$(count_files "$legacy_extensions_dir")
        log "检测到 legacy 扩展目录：$legacy_extensions_dir （$n_files 个文件），开始复制到 third-party..."
        mkdir -p "$third_party_target"
        cp -a "$legacy_extensions_dir"/. "$third_party_target"/
        log "legacy 扩展复制完成。"
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

detect_output_dir() {
    local explicit_dir="${1:-}"
    if [[ -n "$explicit_dir" ]]; then
        log "detect_install_root: explicit root requested: $explicit_root"
        if is_sillytavern_root "$explicit_root"; then
            log "detect_install_root: explicit root valid: $explicit_root"
            canonical_path "$explicit_root"
            return 0
        fi
        log "detect_install_root: explicit root invalid: $explicit_root"
        echo "指定的安装目录不是有效的 SillyTavern 根目录：$explicit_root" >&2
        return 1
    for candidate in "$HOME/storage/shared/Download" "$HOME/storage/downloads" "$HOME/storage/shared"; do
        if [[ -d "$candidate" && -w "$candidate" ]]; then
    local candidate

    # 检查常见候选路径
    for candidate in "$HOME/SillyTavern" "$HOME/sillytavern"; do
        log "detect_install_root: checking candidate: $candidate"
        if is_sillytavern_root "$candidate"; then
            log "detect_install_root: found at $candidate"
            canonical_path "$candidate"
            return 0
        fi
    done

    # 扫描 $HOME 下的 package.json（深度 4），并显示前若干匹配以便排查
    log "detect_install_root: scanning $HOME for package.json (maxdepth=4)"
    local matches
    matches=$(find "$HOME" -maxdepth 4 -type f -name package.json 2>/dev/null || true)
    if [[ -n "$matches" ]]; then
        log "detect_install_root: find returned matches (showing up to 10):"
        printf '%s
    if command -v termux-setup-storage >/dev/null 2>&1; then
        while IFS= read -r match; do
            candidate="$(dirname "$match")"
            log "detect_install_root: checking find-candidate: $candidate"
            if is_sillytavern_root "$candidate"; then
                log "detect_install_root: found at $candidate"
                canonical_path "$candidate"
                return 0
            fi
        done <<< "$matches"
    else
        log "detect_install_root: no package.json found in $HOME (maxdepth=4)"
    fi

    echo "未找到 SillyTavern 安装目录。可用 --install-root 手工指定。" >&2
    return 1
        echo "未检测到已授权的共享存储目录，正在尝试请求 Termux 存储权限..."
        termux-setup-storage >/dev/null 2>&1 || true
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
        local n_files
        n_files=$(count_files "$source_dir")
        log "拷贝目录：$source_dir → $target_dir （$n_files 个文件）"
        mkdir -p "$target_dir"
        cp -a "$source_dir"/. "$target_dir"/
        log "目录拷贝完成：$target_dir"
    else
        log "源目录不存在，创建空目录：$target_dir"
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
        local n_files
        n_files=$(count_files "$config_dir")
        log "检测到配置目录：$config_dir （$n_files 个文件），开始复制..."
        cp -a "$config_dir"/. "$target_dir"/
        log "配置目录复制完成。"
        return 0
    fi

    if [[ -f "$config_file" ]]; then
        log "检测到配置文件：$config_file，复制到导出目录..."
        cp -a "$config_file" "$target_dir/config.yaml"
        log "配置文件复制完成。"
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