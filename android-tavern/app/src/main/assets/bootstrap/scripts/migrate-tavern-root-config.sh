#!/system/bin/sh
set -eu

TAVERN_DATA_ROOT="${TAVERN_DATA_ROOT:?TAVERN_DATA_ROOT is required}"
TAVERN_SERVER_DIR="${TAVERN_SERVER_DIR:?TAVERN_SERVER_DIR is required}"

fail() {
    echo "$1" >&2
    return 1 2>/dev/null || exit 1
}

copy_root_config_atomically() {
    source_path="$1"
    target_path="$2"
    target_dir="$(dirname "$target_path")"
    temp_path="$target_dir/.config.yaml.sillydroid-$$.tmp"

    mkdir -p "$target_dir"
    rm -f "$temp_path"
    if ! cp "$source_path" "$temp_path"; then
        rm -f "$temp_path"
        fail "写入 Tavern 根配置临时文件失败：$temp_path"
    fi

    # 先完成临时文件复制，再替换根路径；旧版本可能把根配置做成 symlink，mv 会替换链接本身。
    if ! mv -f "$temp_path" "$target_path"; then
        rm -f "$temp_path"
        fail "替换 Tavern 根配置失败：$target_path"
    fi
}

root_config_path="$TAVERN_SERVER_DIR/config.yaml"
legacy_config_path="$TAVERN_DATA_ROOT/config/config.yaml"
default_config_path="$TAVERN_SERVER_DIR/default/config.yaml"

if [ -f "$root_config_path" ] && [ ! -L "$root_config_path" ]; then
    exit 0
fi

if [ -d "$root_config_path" ] && [ ! -L "$root_config_path" ]; then
    fail "Tavern 根配置路径是目录，无法安全迁移：$root_config_path"
fi

if [ -L "$root_config_path" ]; then
    if [ ! -f "$root_config_path" ]; then
        fail "Tavern 根配置链接目标不可读：$root_config_path"
    fi
    copy_root_config_atomically "$root_config_path" "$root_config_path"
    exit 0
fi

if [ -e "$root_config_path" ]; then
    fail "Tavern 根配置路径不是普通文件，无法安全迁移：$root_config_path"
fi

if [ -f "$legacy_config_path" ]; then
    copy_root_config_atomically "$legacy_config_path" "$root_config_path"
    exit 0
fi

if [ -e "$legacy_config_path" ] || [ -L "$legacy_config_path" ]; then
    fail "旧 Tavern 配置存在但不是可读文件，无法安全迁移：$legacy_config_path"
fi

if [ ! -f "$default_config_path" ]; then
    fail "缺少默认 Tavern 配置模板：$default_config_path"
fi

copy_root_config_atomically "$default_config_path" "$root_config_path"
