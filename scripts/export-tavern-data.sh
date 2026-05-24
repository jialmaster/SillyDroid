#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: export-tavern-data.sh [--output-dir <dir>] [--install-root <dir>]

One-click export for official SillyTavern installs running inside Termux.
The script scans all detected install roots, lets the user choose the target instance when needed,
    normalizes data into config/data/plugins/public, creates a zip backup,
    and publishes it through shared storage, Android DownloadManager, or the system share sheet.
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
        echo "缺少 zip 命令，且自动安装失败。请手工执行：pkg install zip" >&2
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

# 简洁日志函数（不带时间戳）
log() {
    printf '%s\n' "$*"
}

print_banner() {
    cat <<'EOF'
 /\_/\
( o.o )  SillyTavern 数据导出小助手 🐾
 > ^ <   会先帮你找出所有酒馆，再让你挑要搬家的那一个喵 (ฅ'ω'ฅ)
EOF
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

    while IFS= read -r -d '' package_json_path; do
        register_install_root_candidate "$(dirname "$package_json_path")"
    done < <(
        find "$search_root" \
            \( -path "$search_root/node_modules" -o -path "$search_root/.git" -o -path "$search_root/.cache" \) -prune \
            -o -maxdepth 5 -type f -name package.json -print0 2>/dev/null
    )
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

    scan_running_install_roots
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
        printf 'Termux HOME'
        return 0
    fi

    case "$install_root" in
        "$termux_prefix"/var/lib/proot-distro/containers/*/rootfs/*)
            relative_path="${install_root#"$termux_prefix"/var/lib/proot-distro/containers/}"
            container_name="${relative_path%%/*}"
            printf 'proot-distro:%s' "$container_name"
            return 0
            ;;
        "$termux_prefix"/var/lib/proot-distro/*/rootfs/*)
            relative_path="${install_root#"$termux_prefix"/var/lib/proot-distro/}"
            container_name="${relative_path%%/*}"
            printf 'proot-distro:%s' "$container_name"
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

    local candidate_count selected_index
    local active_count active_index candidate_root origin stats_line role_card_count dialogue_count active_label
    local -a candidate_origins=()
    local -a candidate_role_counts=()
    local -a candidate_dialogue_counts=()
    local -a candidate_active_labels=()

    scan_install_roots
    candidate_count="${#INSTALL_ROOT_CANDIDATES[@]}"

    if (( candidate_count == 0 )); then
        echo "未找到 SillyTavern 安装目录。可用 --install-root 手工指定。" >&2
        return 1
    fi

    if (( candidate_count == 1 )); then
        printf '只找到 1 个 SillyTavern，直接选中：%s\n' "${INSTALL_ROOT_CANDIDATES[0]}" >&2
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

    printf '\n' >&2
    printf '喵呜，找到 %s 个 SillyTavern 实例，请选一个要导出的目标：\n' "$candidate_count" >&2
    printf '────────────────────────────────────────\n' >&2
    for ((selected_index = 0; selected_index < candidate_count; selected_index++)); do
        printf '  [%d] %s  %s\n' \
            $((selected_index + 1)) \
            "${candidate_active_labels[$selected_index]}" \
            "${candidate_origins[$selected_index]}" >&2
        printf '      🐱 角色卡：%s 张 | 📖 聊天历史：%s 条\n' \
            "${candidate_role_counts[$selected_index]}" \
            "${candidate_dialogue_counts[$selected_index]}" >&2
        printf '      📍 路径：%s\n' "${INSTALL_ROOT_CANDIDATES[$selected_index]}" >&2
    done
    printf '────────────────────────────────────────\n' >&2

    if ! prompt_available; then
        if (( active_count == 1 )); then
            printf '当前不是可交互终端，已使用唯一运行中的实例。\n' >&2
            printf '%s\n' "${INSTALL_ROOT_CANDIDATES[$active_index]}"
            return 0
        fi
        echo "当前不是可交互终端，且检测到多个安装目录。请使用 --install-root 指定路径。" >&2
        return 1
    fi

    local answer
    local prompt_text="请输入编号 [1-$candidate_count]（直接回车取消）："
    if (( active_count == 1 )); then
        prompt_text="请输入编号 [1-$candidate_count]（直接回车使用运行中实例 $((active_index + 1))）："
    fi

    while true; do
        if ! answer="$(read_prompt_line "$prompt_text")"; then
            echo "当前无法读取用户选择。请改用 --install-root 指定路径。" >&2
            return 1
        fi

        if [[ -z "$answer" ]]; then
            if (( active_count == 1 )); then
                printf '%s\n' "${INSTALL_ROOT_CANDIDATES[$active_index]}"
                return 0
            fi
            echo "已取消导出。" >&2
            return 1
        fi

        if [[ "$answer" =~ ^[0-9]+$ ]]; then
            selected_index=$((answer - 1))
            if (( selected_index >= 0 && selected_index < candidate_count )); then
                printf '%s\n' "${INSTALL_ROOT_CANDIDATES[$selected_index]}"
                return 0
            fi
        fi

        printf '无效编号，请重新输入。\n' >&2
    done
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
    direct_failure_reason="共享存储目录不可写或不存在，无法直接保存到 Downloads。"
    log "直接保存不可用：$direct_failure_reason"

    local export_cache_dir="$HOME/.sillytavern/export-cache"
    mkdir -p "$export_cache_dir"

    ensure_termux_api_tools_available || true

    if command -v termux-share >/dev/null 2>&1; then
        log "回退方案：使用 Android 系统分享面板。原因：$direct_failure_reason"
        if confirm_export_method "是否使用系统分享面板导出？选择 N 将继续尝试最后的系统下载服务方案。"; then
            EXPORT_METHOD='share'
            ARCHIVE_PATH="$export_cache_dir/$archive_name"
            EXPORT_LABEL='Android 系统分享面板'
            return 0
        fi
        log "用户选择不使用系统分享面板，继续尝试最后的系统下载服务方案。"
    else
        log "系统分享面板不可用：未检测到 termux-share。"
    fi

    ensure_python_available || true

    if command -v termux-download >/dev/null 2>&1 && find_python_command >/dev/null 2>&1; then
        log "最后回退方案：使用 Android 系统下载服务。原因：共享存储不可写，且未使用系统分享面板。"
        if confirm_export_method "是否使用系统下载服务导出？选择 N 将中止导出。"; then
            EXPORT_METHOD='download'
            ARCHIVE_PATH="$export_cache_dir/$archive_name"
            EXPORT_LABEL='Android 系统下载服务'
            return 0
        fi
        log "用户选择不使用系统下载服务。"
    else
        local missing_download_reasons=()
        command -v termux-download >/dev/null 2>&1 || missing_download_reasons+=("未检测到 termux-download")
        find_python_command >/dev/null 2>&1 || missing_download_reasons+=("未检测到 python/python3")
        log "系统下载服务不可用：$(IFS='；'; printf '%s' "${missing_download_reasons[*]}")。如果刚才自动安装 termux-api 成功但仍不可用，请确认已安装 Android 端 Termux:API 应用。"
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

    print_banner
    log "开始导出 SillyTavern 数据"
    if ! is_termux_environment; then
        log "当前环境不是 Termux，脚本终止。"
        exit 1
    fi

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
        log "无法确定导出发布方式，脚本中止。"
        exit 1
    fi
    archive_path="$ARCHIVE_PATH"

    # 在导出进度开始前先打印关键路径信息
    log "安装目录：$install_root"
    local selected_stats role_card_count dialogue_count
    selected_stats="$(count_tavern_content_stats "$install_root")"
    role_card_count="${selected_stats%% *}"
    dialogue_count="${selected_stats##* }"
    log "内容统计：角色卡 $role_card_count，聊天历史 $dialogue_count"
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
