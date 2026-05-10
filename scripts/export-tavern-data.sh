#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: export-tavern-data.sh [--output-dir <dir>] [--install-root <dir>]

One-click export for official SillyTavern installs running inside Termux.
The script detects the install root, normalizes data into config/data/plugins/extensions,
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
    [[ -d "$candidate/config" ]] || return 1
    [[ -d "$candidate/data" ]] || return 1
    [[ -f "$candidate/start.sh" || -d "$candidate/public" ]]
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

detect_extensions_root() {
    local install_root="$1"
    if [[ -d "$install_root/extensions" ]]; then
        printf '%s\n' "$install_root/extensions"
        return 0
    fi

    if [[ -d "$install_root/public/scripts/extensions/third-party" ]]; then
        printf '%s\n' "$install_root/public/scripts/extensions/third-party"
        return 0
    fi

    printf '%s\n' "$install_root/public/scripts/extensions/third-party"
}

detect_output_dir() {
    local explicit_dir="${1:-}"
    if [[ -n "$explicit_dir" ]]; then
        mkdir -p "$explicit_dir"
        canonical_path "$explicit_dir"
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
        cp -a "$source_dir"/. "$target_dir"/
    else
        mkdir -p "$target_dir"
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

    if ! is_termux_environment; then
        echo "当前环境不是 Termux，脚本终止。" >&2
        exit 1
    fi

    ensure_zip_available
    ensure_storage_access "$output_dir"

    local install_root
    install_root="$(detect_install_root "$install_root_arg")"

    local config_root data_root plugins_root extensions_root
    config_root="$install_root/config"
    data_root="$install_root/data"
    plugins_root="$install_root/plugins"
    extensions_root="$(detect_extensions_root "$install_root")"

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

    local temp_root stage_root
    temp_root="$(mktemp -d "${TMPDIR:-${PREFIX:-/tmp}}/st-export.XXXXXX")"
    stage_root="$temp_root/payload"
    mkdir -p "$stage_root/config" "$stage_root/data" "$stage_root/plugins" "$stage_root/extensions"

    trap 'rm -rf "$temp_root"' EXIT

    copy_or_create_empty_dir "$config_root" "$stage_root/config"
    copy_or_create_empty_dir "$data_root" "$stage_root/data"
    copy_or_create_empty_dir "$plugins_root" "$stage_root/plugins"
    copy_or_create_empty_dir "$extensions_root" "$stage_root/extensions"

    (
        cd "$stage_root"
        zip -qr "$archive_path" config data plugins extensions
    )

    printf '环境检查：%s\n' "$(bool_to_text 1 | tr -d '\n')"
    printf 'SillyTavern 安装目录：%s\n' "$install_root"
    printf '配置目录：%s\n' "$config_root"
    printf '数据目录：%s\n' "$data_root"
    printf '插件目录：%s\n' "$plugins_root"
    printf '扩展目录：%s\n' "$extensions_root"
    printf '可直接保存到手机共享存储：%s\n' "$(bool_to_text "$direct_save_available" | tr -d '\n')"
    if [[ "$direct_save_available" != '1' ]]; then
        printf '提示：未检测到已授权的共享存储目录；如需直接导出到手机文件管理器可先执行 termux-setup-storage。\n'
    fi
    printf '导出结果：成功\n'
    printf 'ZIP 路径：%s\n' "$archive_path"
}

main "$@"