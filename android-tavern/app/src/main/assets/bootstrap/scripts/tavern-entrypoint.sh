#!/bin/sh
set -eu

TAVERN_PORT="${TAVERN_PORT:?TAVERN_PORT is required}"
TAVERN_DATA_ROOT="${TAVERN_DATA_ROOT:?TAVERN_DATA_ROOT is required}"
TAVERN_SERVER_DIR="${TAVERN_SERVER_DIR:?TAVERN_SERVER_DIR is required}"
BOOTSTRAP_ROOT="${BOOTSTRAP_ROOT:?BOOTSTRAP_ROOT is required}"
ANDROID_SYSTEM_PATH="/system/bin:/system/xbin"
SILLYDROID_HOST_COMMAND_PATH="${SILLYDROID_HOST_COMMAND_PATH:-}"

# no-proot 启动时入口由 Android /system/bin/sh 执行，基础工具必须优先走系统目录；
# Termux prefix 中的 ELF 只能通过外层注入的 native lib 入口执行，不能让 PATH 隐式命中。
# termux-host 会传入只包含 nativeLibraryDir symlink 的命令目录，让 simple-git 等库继续按命令名调用 git。
if [ -n "$SILLYDROID_HOST_COMMAND_PATH" ]; then
    PATH="$SILLYDROID_HOST_COMMAND_PATH:$ANDROID_SYSTEM_PATH"
else
    PATH="$ANDROID_SYSTEM_PATH"
fi
export PATH

cd "$TAVERN_SERVER_DIR"

# 用户数据必须落到宿主持久目录，避免 APK 覆盖安装后把角色、聊天和配置一起替换掉。
mkdir -p "$TAVERN_DATA_ROOT/config" "$TAVERN_DATA_ROOT/data" "$TAVERN_DATA_ROOT/plugins" "$TAVERN_DATA_ROOT/extensions"

# 根 config.yaml 是唯一真实配置文件；旧 data/server/config/config.yaml 只在迁移时读取一次，不再维护。
sh "$BOOTSTRAP_ROOT/scripts/migrate-tavern-root-config.sh"

directory_has_child() {
    find "$1" -mindepth 1 -maxdepth 1 -print 2>/dev/null | read first_child
}

# 如果上游底包自带默认插件，首次启动先复制到持久目录，再切到外置目录，避免更新后覆盖用户改动。
if [ -d plugins ] && [ ! -L plugins ] && ! directory_has_child "$TAVERN_DATA_ROOT/plugins"; then
    cp -R plugins/. "$TAVERN_DATA_ROOT/plugins/"
fi
rm -rf plugins
ln -sfn "$TAVERN_DATA_ROOT/plugins" plugins

mkdir -p public/scripts/extensions
if [ -d public/scripts/extensions/third-party ] && [ ! -L public/scripts/extensions/third-party ] && ! directory_has_child "$TAVERN_DATA_ROOT/extensions"; then
    cp -R public/scripts/extensions/third-party/. "$TAVERN_DATA_ROOT/extensions/"
fi
rm -rf public/scripts/extensions/third-party
ln -sfn "$TAVERN_DATA_ROOT/extensions" public/scripts/extensions/third-party

if [ -f ./dependency-env.sh ]; then
    # dependency-env.sh 由 stage 4 根据 dependency manifests 聚合生成。
    # shellcheck disable=SC1091
    . ./dependency-env.sh
fi

# Android 包内已经预装运行依赖；不要回退到上游 start.sh，避免冷启动重新执行 npm install。
export NODE_ENV=production

NODE_BIN="${TAVERN_NODE_BIN:-./node/bin/node}"
if [ ! -x "$NODE_BIN" ]; then
    NODE_BIN="$(command -v node || true)"
fi

if [ -z "$NODE_BIN" ] || [ ! -x "$NODE_BIN" ]; then
    echo "缺少可执行的 Node runtime，请确认已导入 node 依赖包。" >&2
    exit 1
fi

# V8 老生代堆上限由宿主设置页注入：TAVERN_NODE_MAX_OLD_SPACE_MB 为正数时才追加
# --max-old-space-size，0/未设表示交给 V8 自适应（保持历史默认行为）。
# 必须在这里显式拼进 NODE_OPTIONS，否则宿主 ProcessBuilder 之外 export 的环境进不到本进程，
# 用户在别处设置的内存上限会“看起来没反应”。
case "${TAVERN_NODE_MAX_OLD_SPACE_MB:-0}" in
    ''|*[!0-9]*)
        ;;
    0)
        ;;
    *)
        NODE_OPTIONS="--max-old-space-size=${TAVERN_NODE_MAX_OLD_SPACE_MB} ${NODE_OPTIONS:-}"
        export NODE_OPTIONS
        ;;
esac

# V8 新生代 semi-space 上限同样由宿主设置页注入：TAVERN_NODE_MAX_SEMI_SPACE_MB 为正数时
# 才追加 --max-semi-space-size，0/未设表示交给 V8 自适应。调大新生代可降低 Scavenge（小 GC）
# 频率，长聊天/大列表场景用内存换 GC 频率缓解周期性卡顿。
case "${TAVERN_NODE_MAX_SEMI_SPACE_MB:-0}" in
    ''|*[!0-9]*)
        ;;
    0)
        ;;
    *)
        NODE_OPTIONS="--max-semi-space-size=${TAVERN_NODE_MAX_SEMI_SPACE_MB} ${NODE_OPTIONS:-}"
        export NODE_OPTIONS
        ;;
esac

# libuv 线程池（fs/dns/crypto/zlib 等阻塞型 IO）默认固定为 4，不随 CPU 核数变化。
# SillyTavern 冷启动要批量读取角色卡 PNG、扫描扩展目录，4 个线程容易排队拖慢吞吐。
# 这里仅在用户未显式设置 UV_THREADPOOL_SIZE 时，按设备 CPU 核数自动取一个更合理的值；
# clamp 到 4..16，避免单核机器退化到比默认更小、巨核机器设过大反而多占内存。
if [ -z "${UV_THREADPOOL_SIZE:-}" ]; then
    uv_cpu_count="$(nproc 2>/dev/null || true)"
    case "$uv_cpu_count" in
        ''|*[!0-9]*)
            uv_cpu_count="$(grep -c '^processor' /proc/cpuinfo 2>/dev/null || echo 0)"
            ;;
    esac
    case "$uv_cpu_count" in
        ''|*[!0-9]*|0)
            # 探测不到核数就不设，沿用 Node 默认 4。
            ;;
        *)
            if [ "$uv_cpu_count" -lt 4 ]; then
                uv_cpu_count=4
            elif [ "$uv_cpu_count" -gt 16 ]; then
                uv_cpu_count=16
            fi
            export UV_THREADPOOL_SIZE="$uv_cpu_count"
            ;;
    esac
fi

# Android 运行时把 plugins 持久化到 TAVERN_DATA_ROOT 后再软链接回 SillyTavern 根目录。
# Node 默认会把 ESM 依赖解析到软链接真实路径，导致 server plugin 无法向上找到上游根 node_modules。
# 保留 symlink 路径可维持上游 `SillyTavern/plugins/<plugin>` 布局语义，同时不影响插件自带 node_modules。
case " ${NODE_OPTIONS:-} " in
    *" --preserve-symlinks "*)
        ;;
    *)
        NODE_OPTIONS="--preserve-symlinks ${NODE_OPTIONS:-}"
        export NODE_OPTIONS
        ;;
esac

# 监听开关与监听地址必须交由用户 config.yaml 决定，不能在宿主入口里写死；
# 否则“启用外部访问”会被 CLI 参数覆盖，最终只能监听 127.0.0.1。
exec "$NODE_BIN" server.js \
    --port "$TAVERN_PORT" \
    --browserLaunchEnabled false \
    --dataRoot "$TAVERN_DATA_ROOT/data" \
    --configPath "$TAVERN_SERVER_DIR/config.yaml"
