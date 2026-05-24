#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
用法：export-tavern-data.sh [--output-dir <目录>] [--install-root <目录>]

一键导出 Termux、普通系统、容器里的 SillyTavern 数据。
脚本会先扫描所有可能的酒馆目录，需要时让你选择目标，
再整理配置、数据、插件和扩展，最后生成迁移压缩包。
EOF
}

ensure_termux_package_installed() {
    local package_name="$1"
    local command_name="${2:-$1}"

    if command -v "$command_name" >/dev/null 2>&1; then
        return 0
    fi

    if command -v pkg >/dev/null 2>&1; then
        log "未检测到 $command_name，正在自动安装：pkg install -y $package_name"
        pkg install -y "$package_name" >/dev/null || true
    fi

    command -v "$command_name" >/dev/null 2>&1
}

ensure_zip_available() {
    if ! ensure_termux_package_installed zip zip; then
        error "缺少 zip 命令，且自动安装失败。Termux 可执行：pkg install zip；普通系统请用对应发行版的安装命令。"
        exit 1
    fi
}

ensure_termux_api_tools_available() {
    if command -v termux-download >/dev/null 2>&1 && command -v termux-share >/dev/null 2>&1; then
        return 0
    fi

    ensure_termux_package_installed termux-api termux-download || true

    command -v termux-download >/dev/null 2>&1 || command -v termux-share >/dev/null 2>&1
}

ensure_python_available() {
    if find_python_command >/dev/null 2>&1; then
        return 0
    fi

    ensure_termux_package_installed python python || true

    find_python_command >/dev/null 2>&1
}

COLOR_RESET=''
COLOR_BOLD=''
COLOR_DIM=''
COLOR_RED=''
COLOR_GREEN=''
COLOR_YELLOW=''
COLOR_BLUE=''
COLOR_MAGENTA=''
COLOR_CYAN=''
COLOR_REVERSE=''
TUI_MENU_RENDERED=0
TUI_MENU_LINES=0

init_colors() {
    if [[ "${NO_COLOR:-}" == '1' ]]; then
        return 0
    fi
    if [[ -t 1 || -t 2 || -n "${FORCE_COLOR:-}" ]]; then
        COLOR_RESET=$'\033[0m'
        COLOR_BOLD=$'\033[1m'
        COLOR_DIM=$'\033[2m'
        COLOR_RED=$'\033[31m'
        COLOR_GREEN=$'\033[32m'
        COLOR_YELLOW=$'\033[33m'
        COLOR_BLUE=$'\033[34m'
        COLOR_MAGENTA=$'\033[35m'
        COLOR_CYAN=$'\033[36m'
        COLOR_REVERSE=$'\033[7m'
    fi
}

color_text() {
    local color="$1"
    shift
    printf '%s%s%s' "$color" "$*" "$COLOR_RESET"
}

color_rgb_text() {
    local red="$1"
    local green="$2"
    local blue="$3"
    shift 3
    if [[ -z "$COLOR_RESET" ]]; then
        printf '%s' "$*"
        return 0
    fi

    printf '\033[38;2;%s;%s;%sm%s%s' "$red" "$green" "$blue" "$*" "$COLOR_RESET"
}

print_gradient_tokens_inline() {
    local token_count="$#"
    local index=0
    local segment=0
    local segment_count=3
    local progress=0
    local segment_progress=0
    local red green blue
    local token
    local start_red start_green start_blue end_red end_green end_blue
    local stops=(
        '255 115 213'
        '175 104 255'
        '83 173 255'
        '74 233 225'
    )

    for token in "$@"; do
        if (( token_count > 1 )); then
            progress=$((index * segment_count * 1000 / (token_count - 1)))
            segment=$((progress / 1000))
            segment_progress=$((progress - segment * 1000))
            if (( segment >= segment_count )); then
                segment=$((segment_count - 1))
                segment_progress=1000
            fi
        fi

        read -r start_red start_green start_blue <<< "${stops[$segment]}"
        read -r end_red end_green end_blue <<< "${stops[$((segment + 1))]}"
        red=$((start_red + (end_red - start_red) * segment_progress / 1000))
        green=$((start_green + (end_green - start_green) * segment_progress / 1000))
        blue=$((start_blue + (end_blue - start_blue) * segment_progress / 1000))

        color_rgb_text "$red" "$green" "$blue" "$token"
        index=$((index + 1))
    done
}

print_gradient_tokens() {
    print_gradient_tokens_inline "$@"
    printf '\n'
}

# 简洁日志函数（不带时间戳）
log() {
    printf '%s\n' "$*"
}

warn() {
    printf '%s\n' "$(color_text "$COLOR_YELLOW" "$*")" >&2
}

error() {
    printf '%s\n' "$(color_text "$COLOR_RED" "$*")" >&2
}

success() {
    printf '%s\n' "$(color_text "$COLOR_GREEN" "$*")"
}

format_active_label() {
    local active_label="$1"
    if [[ "$active_label" == '运行中' ]]; then
        color_text "$COLOR_GREEN" "$active_label"
        return 0
    fi

    color_text "$COLOR_DIM" "$active_label"
}

SCAN_ANIMATION_PID=''

terminal_columns() {
    local columns
    columns="$(stty size < /dev/tty 2>/dev/null | awk '{print $2}' || true)"
    if [[ "$columns" =~ ^[0-9]+$ && "$columns" -gt 20 ]]; then
        printf '%s\n' "$columns"
        return 0
    fi

    printf '80\n'
}

fit_path_for_terminal() {
    local path="$1"
    local reserved_columns="${2:-18}"
    local columns max_length tail candidate
    columns="$(terminal_columns)"
    # 只在路径分隔符处压缩，避免在 LC_ALL=C 的 Termux/proot 环境里把中文 UTF-8 字节切半。
    # 这里也为颜色、emoji、选中标记和行尾动画预留宽度，防止手机窄屏自动折行留下残影。
    max_length=$((columns - reserved_columns))
    if (( max_length < 12 )); then
        max_length=12
    fi

    if ((${#path} <= max_length)); then
        printf '%s\n' "$path"
        return 0
    fi

    tail="${path#/}"
    while [[ "$tail" == */* ]]; do
        candidate=".../$tail"
        if ((${#candidate} <= max_length)); then
            printf '%s\n' "$candidate"
            return 0
        fi
        tail="${tail#*/}"
    done

    candidate=".../$tail"
    if ((${#candidate} <= max_length)); then
        printf '%s\n' "$candidate"
        return 0
    fi

    printf '...\n'
}

start_scan_animation() {
    local label="$1"
    local detail="${2:-}"
    local full_message="$label$detail"
    if ! interactive_tui_available; then
        printf '%s\n' "$(color_text "$COLOR_CYAN" "$full_message")" >&2
        return 0
    fi

    (
        local frames='|/-\'
        local index=0
        local frame line detail_text
        if [[ -n "$detail" ]]; then
            detail_text="$(fit_path_for_terminal "$detail" 24)"
            line="$(color_text "$COLOR_CYAN" "$label")$detail_text"
        else
            line="$(color_text "$COLOR_CYAN" "$label")"
        fi
        while true; do
            frame="${frames:index % ${#frames}:1}"
            printf '\r\033[K%s %s' "$line" "$(color_text "$COLOR_CYAN" "$frame")" >&2
            index=$((index + 1))
            sleep 0.12
        done
    ) &
    SCAN_ANIMATION_PID="$!"
}

stop_scan_animation() {
    local final_message="${1:-}"
    if [[ -n "${SCAN_ANIMATION_PID:-}" ]]; then
        kill "$SCAN_ANIMATION_PID" >/dev/null 2>&1 || true
        wait "$SCAN_ANIMATION_PID" >/dev/null 2>&1 || true
        SCAN_ANIMATION_PID=''
        if interactive_tui_available; then
            printf '\r\033[K' >&2
        fi
    fi
    if [[ -n "$final_message" ]]; then
        printf '%s\n' "$final_message" >&2
    fi
}

print_banner() {
    # 猫猫头固定成独立图标，右侧文案单独横向渐变，避免脸部字符挤占标题色相区间。
    printf '\n'
    printf '%s\n' "$(color_rgb_text 255 170 224 '  /\_/\')"
    printf '%s' "$(color_rgb_text 255 170 224 ' (｡•ᴗ•｡)  ')"
    print_gradient_tokens \
        'S' 'i' 'l' 'l' 'y' 'T' 'a' 'v' 'e' 'r' 'n' ' ' \
        '数' '据' '导' '出' '小' '助' '手'
    printf '%s' "$(color_rgb_text 255 170 224 '  /づ♡    ')"
    print_gradient_tokens \
        '会' '先' '帮' '你' '找' '出' '所' '有' '酒' '馆' '，' \
        '再' '让' '你' '挑' '要' '搬' '家' '的' '那' '一' '个' '喵'
    printf '\n'
}

STEP=0
TOTAL_STEPS=8
step() {
    # 在 set -e 下避免 ((STEP++)) 初始值为 0 时返回 1 导致脚本提前退出
    STEP=$((STEP + 1))
    local percent
    percent=$((STEP * 100 / TOTAL_STEPS))
    # 单行刷新进度，不持续追加新行
    printf '\r[%3d%%] %s' "$percent" "$*"
}

tty_prompt_available() {
    [[ -c /dev/tty ]] || return 1
    (: < /dev/tty) >/dev/null 2>&1 || return 1
    (: > /dev/tty) >/dev/null 2>&1 || return 1
    return 0
}

prompt_available() {
    tty_prompt_available || [[ -t 0 ]]
}

interactive_tui_available() {
    tty_prompt_available && [[ -t 2 ]] && [[ "${TERM:-}" != 'dumb' ]]
}

read_prompt_line() {
    local prompt="$1"
    local answer=''

    # README 里的推荐用法是 curl | bash；这会把 stdin 占掉。
    # 交互统一优先走 /dev/tty，保证多实例选择和导出确认仍能正常读到用户输入。
    if tty_prompt_available; then
        printf '%s' "$prompt" > /dev/tty
        IFS= read -r answer < /dev/tty || return 1
        printf '%s\n' "$answer"
        return 0
    fi

    if [[ -t 0 ]]; then
        printf '%s' "$prompt" >&2
        IFS= read -r answer || return 1
        printf '%s\n' "$answer"
        return 0
    fi

    return 1
}

read_tty_key() {
    local key rest
    IFS= read -rsn1 key < /dev/tty || return 1
    if [[ "$key" == $'\033' ]]; then
        IFS= read -rsn2 -t 0.05 rest < /dev/tty || true
        key+="$rest"
    fi
    printf '%s' "$key"
}

is_termux_environment() {
    if [[ -n "${TERMUX_VERSION:-}" ]]; then
        return 0
    fi
    if [[ "${PREFIX:-}" == */data/data/com.termux/files/usr ]]; then
        return 0
    fi
    if command -v termux-setup-storage >/dev/null 2>&1 || command -v termux-share >/dev/null 2>&1 || command -v termux-download >/dev/null 2>&1; then
        return 0
    fi
    return 1
}

describe_runtime_environment() {
    if is_termux_environment; then
        printf 'Termux 主环境'
        return 0
    fi

    printf '普通系统 / 容器'
}

canonical_path() {
    local target="$1"
    (
        cd "$target" >/dev/null 2>&1
        pwd -P
    )
}

termux_prefix_path() {
    local prefix_path="${PREFIX:-/data/data/com.termux/files/usr}"
    printf '%s\n' "${prefix_path%/}"
}

is_sillytavern_root() {
    local candidate="$1"
    [[ -d "$candidate" ]] || return 1
    [[ -f "$candidate/package.json" ]] || return 1
    [[ -f "$candidate/start.sh" || -d "$candidate/public" || -d "$candidate/src" ]] || return 1
    [[ -f "$candidate/config.yaml" || -d "$candidate/config" || -d "$candidate/data" || -d "$candidate/plugins" || -d "$candidate/extensions" || -d "$candidate/public/scripts/extensions/third-party" ]]
}

declare -a INSTALL_ROOT_CANDIDATES=()
declare -A KNOWN_INSTALL_ROOTS=()
declare -A ACTIVE_INSTALL_ROOT_IDENTITIES=()

install_root_identity_key() {
    local install_root="$1"
    local identity

    if identity="$(stat -c '%d:%i' "$install_root" 2>/dev/null)"; then
        printf 'inode:%s\n' "$identity"
        return 0
    fi

    printf 'path:%s\n' "$install_root"
}

register_install_root_candidate() {
    local candidate="$1"
    if ! is_sillytavern_root "$candidate"; then
        return 0
    fi

    local canonical_candidate
    canonical_candidate="$(canonical_path "$candidate")" || return 0

    local identity_key
    identity_key="$(install_root_identity_key "$canonical_candidate")"

    if [[ -n "${KNOWN_INSTALL_ROOTS[$identity_key]:-}" ]]; then
        return 0
    fi

    KNOWN_INSTALL_ROOTS["$identity_key"]=1
    INSTALL_ROOT_CANDIDATES+=("$canonical_candidate")
}

mark_active_install_root_candidate() {
    local candidate="$1"
    if ! is_sillytavern_root "$candidate"; then
        return 0
    fi

    local canonical_candidate identity_key
    canonical_candidate="$(canonical_path "$candidate")" || return 0
    identity_key="$(install_root_identity_key "$canonical_candidate")"

    # 运行中实例也注册进候选，覆盖“安装目录不在 HOME 里，但当前正在跑”的迁移场景。
    ACTIVE_INSTALL_ROOT_IDENTITIES["$identity_key"]=1
    register_install_root_candidate "$canonical_candidate"
}

is_active_install_root() {
    local install_root="$1"
    local identity_key
    identity_key="$(install_root_identity_key "$install_root")"
    [[ -n "${ACTIVE_INSTALL_ROOT_IDENTITIES[$identity_key]:-}" ]]
}

resolve_install_root_from_path() {
    local candidate_path="$1"
    [[ -n "$candidate_path" ]] || return 1

    if [[ -f "$candidate_path" ]]; then
        candidate_path="$(dirname "$candidate_path")"
    fi

    while [[ -n "$candidate_path" && "$candidate_path" != "/" && "$candidate_path" != "." ]]; do
        if is_sillytavern_root "$candidate_path"; then
            canonical_path "$candidate_path"
            return 0
        fi
        candidate_path="$(dirname "$candidate_path")"
    done

    return 1
}

resolve_install_root_from_process_arg() {
    local process_arg="$1"
    local cwd_path="$2"
    local candidate_path="$process_arg"

    [[ -n "$candidate_path" ]] || return 1
    [[ "$candidate_path" == -* ]] && return 1

    if [[ "$candidate_path" != /* && -n "$cwd_path" ]]; then
        candidate_path="$cwd_path/$candidate_path"
    fi

    resolve_install_root_from_path "$candidate_path"
}

looks_like_tavern_process_cmdline() {
    local cmdline="$1"

    case "$cmdline" in
        *server.js*|*start.sh*|*SillyTavern*|*sillytavern*)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

scan_running_install_roots() {
    local proc_dir cmdline cwd_path resolved_root process_arg
    [[ -d /proc ]] || return 0

    for proc_dir in /proc/[0-9]*; do
        [[ -d "$proc_dir" ]] || continue

        if ! cmdline="$(tr '\0' ' ' < "$proc_dir/cmdline" 2>/dev/null)"; then
            continue
        fi
        cwd_path="$(readlink "$proc_dir/cwd" 2>/dev/null || true)"
        if resolved_root="$(resolve_install_root_from_path "$cwd_path")"; then
            mark_active_install_root_candidate "$resolved_root"
        fi

        looks_like_tavern_process_cmdline "$cmdline" || continue

        # 有些启动方式会把 server.js/start.sh 写在参数里；同时看 cwd 和参数，
        # 才能覆盖官方 Termux、proot-distro、以及用户自定义目录启动的实例。
        while IFS= read -r -d '' process_arg; do
            case "$process_arg" in
                *server.js*|*start.sh*|*SillyTavern*|*sillytavern*)
                    if resolved_root="$(resolve_install_root_from_process_arg "$process_arg" "$cwd_path")"; then
                        mark_active_install_root_candidate "$resolved_root"
                    fi
                    ;;
            esac
        done < "$proc_dir/cmdline" 2>/dev/null || true
    done
}

scan_sillytavern_roots_with_find() {
    local search_root="$1"
    [[ -d "$search_root" ]] || return 0

    start_scan_animation "正在扫描：" "$search_root"
    while IFS= read -r -d '' package_json_path; do
        register_install_root_candidate "$(dirname "$package_json_path")"
    done < <(
        find "$search_root" \
            \( -path "$search_root/node_modules" -o -path "$search_root/.git" -o -path "$search_root/.cache" \) -prune \
            -o -maxdepth 5 -type f -name package.json -print0 2>/dev/null
    )
    stop_scan_animation
}

scan_proot_distro_install_roots() {
    local termux_prefix proot_root
    termux_prefix="$(termux_prefix_path)"
    proot_root="$termux_prefix/var/lib/proot-distro"
    [[ -d "$proot_root" ]] || return 0

    while IFS= read -r -d '' rootfs_root; do
        register_install_root_candidate "$rootfs_root/root/SillyTavern"
        register_install_root_candidate "$rootfs_root/root/sillytavern"

        while IFS= read -r -d '' home_dir; do
            register_install_root_candidate "$home_dir/SillyTavern"
            register_install_root_candidate "$home_dir/sillytavern"
        done < <(find "$rootfs_root/home" -mindepth 1 -maxdepth 1 -type d -print0 2>/dev/null)

        scan_sillytavern_roots_with_find "$rootfs_root"
    done < <(find "$proot_root" -mindepth 1 -maxdepth 4 -type d -name rootfs -print0 2>/dev/null)
}

scan_install_roots() {
    INSTALL_ROOT_CANDIDATES=()
    KNOWN_INSTALL_ROOTS=()
    ACTIVE_INSTALL_ROOT_IDENTITIES=()

    local termux_prefix termux_files_root scan_root extra_scan_root
    termux_prefix="$(termux_prefix_path)"
    termux_files_root="$(dirname "$termux_prefix")"

    # 扫描可能需要遍历 HOME、proot-distro rootfs 等目录；给用户持续反馈，避免误以为脚本卡住。
    start_scan_animation "正在检查运行中的酒馆进程"
    scan_running_install_roots
    stop_scan_animation

    register_install_root_candidate "$PWD"
    register_install_root_candidate "$PWD/SillyTavern"
    register_install_root_candidate "$PWD/sillytavern"
    register_install_root_candidate "$HOME/SillyTavern"
    register_install_root_candidate "$HOME/sillytavern"
    register_install_root_candidate "$HOME/root/SillyTavern"
    register_install_root_candidate "$HOME/root/sillytavern"

    for scan_root in "$PWD" "$HOME" "$termux_files_root" /home /opt; do
        scan_sillytavern_roots_with_find "$scan_root"
    done

    if [[ -n "${SILLYTAVERN_EXPORT_SCAN_ROOTS:-}" ]]; then
        while IFS= read -r extra_scan_root; do
            [[ -n "$extra_scan_root" ]] || continue
            scan_sillytavern_roots_with_find "$extra_scan_root"
        done < <(printf '%s\n' "${SILLYTAVERN_EXPORT_SCAN_ROOTS//:/$'\n'}")
    fi

    scan_proot_distro_install_roots
}

describe_install_root_origin() {
    local install_root="$1"
    local termux_prefix home_root relative_path container_name
    termux_prefix="$(termux_prefix_path)"
    home_root="${HOME%/}"

    if [[ "$install_root" == "$home_root/"* ]]; then
        printf 'Termux 主目录'
        return 0
    fi

    case "$install_root" in
        "$termux_prefix"/var/lib/proot-distro/containers/*/rootfs/*)
            relative_path="${install_root#"$termux_prefix"/var/lib/proot-distro/containers/}"
            container_name="${relative_path%%/*}"
            printf 'proot 容器：%s' "$container_name"
            return 0
            ;;
        "$termux_prefix"/var/lib/proot-distro/*/rootfs/*)
            relative_path="${install_root#"$termux_prefix"/var/lib/proot-distro/}"
            container_name="${relative_path%%/*}"
            printf 'proot 容器：%s' "$container_name"
            return 0
            ;;
    esac

    printf '扫描结果'
}

looks_like_user_content_root() {
    local directory="$1"
    local marker
    local user_content_marker_directories=(
        "OpenAI Settings"
        "KoboldAI Settings"
        "NovelAI Settings"
        "TextGen Settings"
        "context"
        "instruct"
        "QuickReplies"
        "sysprompt"
        "reasoning"
        "characters"
        "chats"
        "group chats"
        "worlds"
    )

    for marker in "${user_content_marker_directories[@]}"; do
        if [[ -e "$directory/$marker" ]]; then
            return 0
        fi
    done

    return 1
}

looks_like_nested_user_content_root() {
    local directory="$1"
    local directory_name
    directory_name="$(basename "$directory")"

    # data/ 下除了真正用户目录，还会出现 _cache、_storage 之类宿主/上游内部目录。
    # 这里和 Android 导入预览保持同一条规则：嵌套用户根必须带 settings.json，
    # 且目录名不能是内部缓存约定，避免把旁路目录误算成一个酒馆用户。
    if [[ "$directory_name" == _* || "$directory_name" == .* ]]; then
        return 1
    fi

    [[ -f "$directory/settings.json" ]] || return 1
    looks_like_user_content_root "$directory"
}

resolve_user_content_roots() {
    local data_root="$1"
    local child_dir
    local nested_user_roots=()

    [[ -d "$data_root" ]] || return 0

    while IFS= read -r -d '' child_dir; do
        if looks_like_nested_user_content_root "$child_dir"; then
            nested_user_roots+=("$child_dir")
        fi
    done < <(find "$data_root" -mindepth 1 -maxdepth 1 -type d -print0 2>/dev/null)

    if ((${#nested_user_roots[@]} > 0)); then
        printf '%s\0' "${nested_user_roots[@]}"
        return 0
    fi

    if looks_like_user_content_root "$data_root"; then
        printf '%s\0' "$data_root"
    fi
}

count_files_by_extension() {
    local directory="$1"
    local extension="$2"
    local recursive="$3"
    local depth_args=()

    [[ -d "$directory" ]] || {
        printf '0\n'
        return 0
    }

    if [[ "$recursive" != '1' ]]; then
        depth_args=(-maxdepth 1)
    fi

    find "$directory" "${depth_args[@]}" -type f -iname "*.$extension" 2>/dev/null | wc -l | tr -d '[:space:]'
}

count_tavern_content_stats() {
    local install_root="$1"
    local user_root
    local role_card_count='0'
    local dialogue_count='0'
    local increment='0'

    # 统计口径必须和 Android 导入预览保持一致，避免“导出前看到的数量”和“导入时预览的数量”对不上：
    # - 角色卡只算 characters 根层 .png，不能把 sprites/backgrounds 等附属资源算进去。
    # - 聊天历史递归统计 chats 和 group chats 下的 .jsonl，因为角色聊天会落在子目录里。
    while IFS= read -r -d '' user_root; do
        increment="$(count_files_by_extension "$user_root/characters" png 0)"
        role_card_count=$((role_card_count + increment))

        increment="$(count_files_by_extension "$user_root/chats" jsonl 1)"
        dialogue_count=$((dialogue_count + increment))

        increment="$(count_files_by_extension "$user_root/group chats" jsonl 1)"
        dialogue_count=$((dialogue_count + increment))
    done < <(resolve_user_content_roots "$install_root/data")

    printf '%s %s\n' "$role_card_count" "$dialogue_count"
}

print_static_install_root_list() {
    local candidate_count="$1"
    local selected_index

    printf '\n' >&2
    printf '%s\n' "$(color_text "$COLOR_BOLD$COLOR_CYAN" "喵呜，找到 $candidate_count 个 SillyTavern 实例，请选一个要导出的目标：")" >&2
    printf '────────────────────────────────────────\n' >&2
    for ((selected_index = 0; selected_index < candidate_count; selected_index++)); do
        printf '  [%d] %s  %s\n' \
            $((selected_index + 1)) \
            "$(format_active_label "${candidate_active_labels[$selected_index]}")" \
            "$(color_text "$COLOR_MAGENTA" "${candidate_origins[$selected_index]}")" >&2
        printf '      🐱 角色卡：%s 张 | 📖 聊天历史：%s 条\n' \
            "$(color_text "$COLOR_GREEN" "${candidate_role_counts[$selected_index]}")" \
            "$(color_text "$COLOR_GREEN" "${candidate_dialogue_counts[$selected_index]}")" >&2
        printf '      📍 路径：%s\n' "$(color_text "$COLOR_BLUE" "${INSTALL_ROOT_CANDIDATES[$selected_index]}")" >&2
    done
    printf '────────────────────────────────────────\n' >&2
}

render_install_root_menu() {
    local candidate_count="$1"
    local selected_index="$2"
    local row marker row_color
    local reset_color
    local display_path

    if (( TUI_MENU_RENDERED == 1 )); then
        printf '\033[%dA\033[J' "$TUI_MENU_LINES" > /dev/tty
    fi

    printf '%s\n' "$(color_text "$COLOR_CYAN" "使用方向键选择要导出的酒馆，回车确认，q 取消。")" > /dev/tty
    printf '%s\n' "$(color_text "$COLOR_DIM" "也可以用 j/k 或 w/s 移动。")" > /dev/tty
    printf '────────────────────────────────────────\n' > /dev/tty

    for ((row = 0; row < candidate_count; row++)); do
        marker='  '
        row_color=''
        reset_color=''
        if (( row == selected_index )); then
            marker='▶ '
            row_color="$COLOR_REVERSE"
            reset_color="$COLOR_RESET"
        fi

        printf '%s%s[%d] %s  %s%s\n' \
            "$row_color" \
            "$marker" \
            $((row + 1)) \
            "${candidate_active_labels[$row]}" \
            "${candidate_origins[$row]}" \
            "$reset_color" > /dev/tty
        printf '%s   🐱 角色卡：%s 张 | 📖 聊天历史：%s 条%s\n' \
            "$row_color" \
            "${candidate_role_counts[$row]}" \
            "${candidate_dialogue_counts[$row]}" \
            "$reset_color" > /dev/tty
        display_path="$(fit_path_for_terminal "${INSTALL_ROOT_CANDIDATES[$row]}" 12)"
        printf '%s   📍 %s%s\n' \
            "$row_color" \
            "$display_path" \
            "$reset_color" > /dev/tty
    done

    printf '────────────────────────────────────────\n' > /dev/tty
    TUI_MENU_LINES=$((candidate_count * 3 + 4))
    TUI_MENU_RENDERED=1
}

clear_install_root_menu() {
    if (( TUI_MENU_RENDERED == 1 )); then
        printf '\033[%dA\033[J' "$TUI_MENU_LINES" > /dev/tty
        TUI_MENU_RENDERED=0
        TUI_MENU_LINES=0
    fi
}

select_install_root_with_tui() {
    local candidate_count="$1"
    local selected_index="$2"
    local key
    local saved_tty_state=''

    printf '\033[?25l' > /dev/tty
    saved_tty_state="$(stty -g < /dev/tty 2>/dev/null || true)"
    stty -echo < /dev/tty 2>/dev/null || true

    while true; do
        render_install_root_menu "$candidate_count" "$selected_index"
        if ! key="$(read_tty_key)"; then
            [[ -n "$saved_tty_state" ]] && stty "$saved_tty_state" < /dev/tty 2>/dev/null || true
            printf '\033[?25h' > /dev/tty
            clear_install_root_menu
            return 1
        fi
        case "$key" in
            $'\033[A'|k|K|w|W)
                selected_index=$(( (selected_index + candidate_count - 1) % candidate_count ))
                ;;
            $'\033[B'|j|J|s|S)
                selected_index=$(( (selected_index + 1) % candidate_count ))
                ;;
            '')
                [[ -n "$saved_tty_state" ]] && stty "$saved_tty_state" < /dev/tty 2>/dev/null || true
                printf '\033[?25h' > /dev/tty
                clear_install_root_menu
                printf '%s\n' "$(color_text "$COLOR_GREEN" "已选择：${INSTALL_ROOT_CANDIDATES[$selected_index]}")" > /dev/tty
                printf '%s\n' "${INSTALL_ROOT_CANDIDATES[$selected_index]}"
                return 0
                ;;
            q|Q|$'\003')
                [[ -n "$saved_tty_state" ]] && stty "$saved_tty_state" < /dev/tty 2>/dev/null || true
                printf '\033[?25h' > /dev/tty
                clear_install_root_menu
                warn "已取消导出。"
                return 1
                ;;
        esac
    done
}

detect_install_root() {
    local explicit_root="${1:-}"
    if [[ -n "$explicit_root" ]]; then
        if is_sillytavern_root "$explicit_root"; then
            canonical_path "$explicit_root"
            return 0
        fi
        error "指定的安装目录不是有效的 SillyTavern 根目录：$explicit_root"
        return 1
    fi

    local candidate_count selected_index
    local active_count active_index candidate_root origin stats_line role_card_count dialogue_count active_label
    local -a candidate_origins=()
    local -a candidate_role_counts=()
    local -a candidate_dialogue_counts=()
    local -a candidate_active_labels=()

    scan_install_roots
    candidate_count="${#INSTALL_ROOT_CANDIDATES[@]}"

    if (( candidate_count == 0 )); then
        error "未找到 SillyTavern 安装目录。可用 --install-root 手工指定。"
        return 1
    fi

    if (( candidate_count == 1 )); then
        printf '%s\n' "$(color_text "$COLOR_GREEN" "只找到 1 个 SillyTavern，直接选中：${INSTALL_ROOT_CANDIDATES[0]}")" >&2
        printf '%s\n' "${INSTALL_ROOT_CANDIDATES[0]}"
        return 0
    fi

    active_count=0
    active_index=-1
    for candidate_root in "${INSTALL_ROOT_CANDIDATES[@]}"; do
        origin="$(describe_install_root_origin "$candidate_root")"
        stats_line="$(count_tavern_content_stats "$candidate_root")"
        role_card_count="${stats_line%% *}"
        dialogue_count="${stats_line##* }"
        active_label='未检测到运行'
        if is_active_install_root "$candidate_root"; then
            active_label='运行中'
            active_index="${#candidate_active_labels[@]}"
            active_count=$((active_count + 1))
        fi
        candidate_origins+=("$origin")
        candidate_role_counts+=("$role_card_count")
        candidate_dialogue_counts+=("$dialogue_count")
        candidate_active_labels+=("$active_label")
    done

    if ! prompt_available; then
        print_static_install_root_list "$candidate_count"
        if (( active_count == 1 )); then
            warn "当前不是可交互终端，已使用唯一运行中的实例。"
            printf '%s\n' "${INSTALL_ROOT_CANDIDATES[$active_index]}"
            return 0
        fi
        error "当前不是可交互终端，且检测到多个安装目录。请使用 --install-root 指定路径。"
        return 1
    fi

    if interactive_tui_available; then
        selected_index=0
        if (( active_count == 1 )); then
            selected_index="$active_index"
        fi
        select_install_root_with_tui "$candidate_count" "$selected_index"
        return $?
    fi

    print_static_install_root_list "$candidate_count"

    local answer
    local prompt_text="请输入编号 [1-$candidate_count]（直接回车取消）："
    if (( active_count == 1 )); then
        prompt_text="请输入编号 [1-$candidate_count]（直接回车使用运行中实例 $((active_index + 1))）："
    fi

    while true; do
        if ! answer="$(read_prompt_line "$prompt_text")"; then
            error "当前无法读取用户选择。请改用 --install-root 指定路径。"
            return 1
        fi

        if [[ -z "$answer" ]]; then
            if (( active_count == 1 )); then
                printf '%s\n' "${INSTALL_ROOT_CANDIDATES[$active_index]}"
                return 0
            fi
            warn "已取消导出。"
            return 1
        fi

        if [[ "$answer" =~ ^[0-9]+$ ]]; then
            selected_index=$((answer - 1))
            if (( selected_index >= 0 && selected_index < candidate_count )); then
                printf '%s\n' "${INSTALL_ROOT_CANDIDATES[$selected_index]}"
                return 0
            fi
        fi

        warn "无效编号，请重新输入。"
    done
}

detect_output_dir() {
    local explicit_dir="${1:-}"
    if [[ -n "$explicit_dir" ]]; then
        if mkdir -p "$explicit_dir" >/dev/null 2>&1; then
            canonical_path "$explicit_dir"
            return 0
        fi
        error "指定输出目录不可写：$explicit_dir"
        return 1
    fi

    local candidate
    for candidate in "$HOME/storage/shared/Download" "$HOME/storage/downloads" "$HOME/storage/shared"; do
        if [[ -d "$candidate" && -w "$candidate" ]]; then
            canonical_path "$candidate"
            return 0
        fi
    done

    # 普通系统、容器和未授权共享存储的 Termux 都允许直接把压缩包落到当前目录，
    # 避免把“能打包”强行绑定到 Android 共享存储或 Termux:API。
    if [[ -d "$PWD" && -w "$PWD" ]]; then
        canonical_path "$PWD"
        return 0
    fi

    error "未找到可写输出目录。请使用 --output-dir 显式指定可写目录。"
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

    if [[ ! -d "$HOME/storage/shared" && -n "${TERMUX_VERSION:-}" ]]; then
        warn "Termux 共享存储未就绪，将优先把压缩包保存到当前目录；如需保存到下载目录，请手动执行 termux-setup-storage。"
    fi
}

confirm_export_method() {
    local prompt="$1"
    local answer

    if ! prompt_available; then
        echo "当前不是交互式终端，无法确认导出方式：$prompt" >&2
        return 1
    fi

    while true; do
        answer="$(read_prompt_line "$prompt [Y/n]: ")" || return 1
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
    direct_failure_reason="没有可写输出目录，无法直接保存压缩包。"
    warn "直接保存不可用：$direct_failure_reason"

    local export_cache_dir="$HOME/.sillytavern/export-cache"
    mkdir -p "$export_cache_dir"

    ensure_termux_api_tools_available || true

    if command -v termux-share >/dev/null 2>&1; then
        warn "回退方案：使用 Android 系统分享面板。原因：$direct_failure_reason"
        if confirm_export_method "是否使用系统分享面板导出？选择 N 将继续尝试最后的系统下载服务方案。"; then
            EXPORT_METHOD='share'
            ARCHIVE_PATH="$export_cache_dir/$archive_name"
            EXPORT_LABEL='Android 系统分享面板'
            return 0
        fi
        warn "用户选择不使用系统分享面板，继续尝试最后的系统下载服务方案。"
    else
        warn "系统分享面板不可用：未检测到 termux-share。"
    fi

    ensure_python_available || true

    if command -v termux-download >/dev/null 2>&1 && find_python_command >/dev/null 2>&1; then
        warn "最后回退方案：使用 Android 系统下载服务。原因：无法直接保存，且未使用系统分享面板。"
        if confirm_export_method "是否使用系统下载服务导出？选择 N 将中止导出。"; then
            EXPORT_METHOD='download'
            ARCHIVE_PATH="$export_cache_dir/$archive_name"
            EXPORT_LABEL='Android 系统下载服务'
            return 0
        fi
        warn "用户选择不使用系统下载服务。"
    else
        local missing_download_reasons=()
        command -v termux-download >/dev/null 2>&1 || missing_download_reasons+=("未检测到 termux-download")
        find_python_command >/dev/null 2>&1 || missing_download_reasons+=("未检测到 python/python3")
        warn "系统下载服务不可用：$(IFS='；'; printf '%s' "${missing_download_reasons[*]}")。如果刚才自动安装 termux-api 成功但仍不可用，请确认已安装 Android 端 Termux:API 应用。"
    fi

    error "无法发布导出文件：没有可写输出目录，且未检测到可用的 termux-download 或 termux-share。"
    error "可执行：使用 --output-dir 指定目录；或在 Termux 中安装 termux-api / Termux:API 应用后重试。"
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
    log "已打开 Android 系统分享面板，请选择文件管理器、网盘或聊天应用保存压缩包。"
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

    init_colors

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
                error "未知参数：$1"
                usage >&2
                exit 1
                ;;
        esac
    done

    print_banner
    log "$(color_text "$COLOR_CYAN" "开始导出 SillyTavern 数据")"
    log "运行环境：$(color_text "$COLOR_MAGENTA" "$(describe_runtime_environment)")"

    ensure_zip_available

    ensure_storage_access "$output_dir"

    local install_root
    if ! install_root="$(detect_install_root "$install_root_arg")"; then
        exit 1
    fi

    local timestamp archive_name archive_path
    timestamp="$(date +%Y%m%d-%H%M%S)"
    archive_name="sillytavern-termux-backup-${timestamp}.zip"

    local EXPORT_METHOD ARCHIVE_PATH EXPORT_LABEL
    if ! resolve_archive_target "$output_dir" "$archive_name"; then
        error "无法确定导出发布方式，脚本中止。"
        exit 1
    fi
    archive_path="$ARCHIVE_PATH"

    # 在导出进度开始前先打印关键路径信息
    log "安装目录：$(color_text "$COLOR_BLUE" "$install_root")"
    local selected_stats role_card_count dialogue_count
    selected_stats="$(count_tavern_content_stats "$install_root")"
    role_card_count="${selected_stats%% *}"
    dialogue_count="${selected_stats##* }"
    log "内容统计：角色卡 $(color_text "$COLOR_GREEN" "$role_card_count")，聊天历史 $(color_text "$COLOR_GREEN" "$dialogue_count")"
    log "发布方式：$(color_text "$COLOR_CYAN" "$EXPORT_LABEL")"

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

    step "正在打包压缩包"
    (
        cd "$stage_root"
        zip -qr "$archive_path" config data plugins public
    )

    step "发布导出文件"
    printf '\n'
    if ! publish_archive "$archive_path" "$archive_name"; then
        error "导出文件发布失败，私有源文件保留在：$archive_path"
        exit 1
    fi

    step "导出完成"
    printf '\n'
    success "导出结果：成功"
    case "$EXPORT_METHOD" in
        direct)
            printf '压缩包路径：%s\n' "$(color_text "$COLOR_GREEN" "$archive_path")"
            ;;
        download)
            printf '压缩包已交给系统下载服务：%s\n' "$(color_text "$COLOR_GREEN" "$archive_name")"
            printf '临时源文件：%s\n' "$(color_text "$COLOR_BLUE" "$archive_path")"
            ;;
        share)
            printf '压缩包已打开系统分享面板：%s\n' "$(color_text "$COLOR_GREEN" "$archive_name")"
            printf '临时源文件：%s\n' "$(color_text "$COLOR_BLUE" "$archive_path")"
            ;;
    esac
}

main "$@"
