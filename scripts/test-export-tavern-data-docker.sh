#!/usr/bin/env bash
set -euo pipefail

workspace_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
export_script="$workspace_root/scripts/export-tavern-data.sh"
image_name="${SILLYDROID_EXPORT_TEST_IMAGE:-bash:5.2}"
container_name="sillydroid-export-test-$$"

if ! command -v docker >/dev/null 2>&1; then
    echo "缺少 docker 命令。请先在 WSL 中安装并启动 Docker。" >&2
    exit 1
fi

python_command="$(command -v python3 || command -v python || true)"
if [[ -z "$python_command" ]]; then
    echo "缺少 python3/python，无法为交互式 Docker 测试分配伪终端。" >&2
    exit 1
fi

DOCKER=(docker)
if ! docker info >/dev/null 2>&1; then
    if command -v sudo >/dev/null 2>&1 && sudo -n docker info >/dev/null 2>&1; then
        DOCKER=(sudo docker)
    else
        echo "当前用户无法访问 Docker。请用 root 运行本脚本，或把当前用户加入 docker 组后重新登录 WSL。" >&2
        exit 1
    fi
fi

cleanup() {
    "${DOCKER[@]}" rm -f "$container_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "准备 Docker 测试镜像：$image_name"
"${DOCKER[@]}" pull "$image_name" >/dev/null

echo "启动测试容器"
"${DOCKER[@]}" run -d --name "$container_name" \
    -e TERMUX_VERSION=0.118 \
    -e PREFIX=/data/data/com.termux/files/usr \
    -v "$export_script:/opt/export-tavern-data.sh:ro" \
    "$image_name" sleep infinity >/dev/null

echo "安装容器内测试工具"
"${DOCKER[@]}" exec "$container_name" sh -lc '
    set -eu
    mkdir -p /usr/local/bin
    cat > /usr/local/bin/zip <<'"'"'EOF'"'"'
#!/usr/bin/env sh
set -eu
if [ "$#" -lt 3 ] || [ "$1" != "-qr" ]; then
    echo "fake zip only supports: zip -qr <archive> <entries...>" >&2
    exit 2
fi
archive="$2"
shift 2
{
    echo "FAKE ZIP"
    for entry in "$@"; do
        if [ -d "$entry" ]; then
            find "$entry" -print
        elif [ -e "$entry" ]; then
            echo "$entry"
        fi
    done
} > "$archive"
EOF
    chmod +x /usr/local/bin/zip
'

echo "创建多路径 SillyTavern 测试实例"
"${DOCKER[@]}" exec "$container_name" bash -lc '
    set -euo pipefail

    create_tavern() {
        local root="$1"
        local version="$2"
        local cards="$3"
        local chats="$4"
        local group_chats="$5"

        mkdir -p \
            "$root/config" \
            "$root/plugins" \
            "$root/public/scripts/extensions/third-party" \
            "$root/data/default-user/characters" \
            "$root/data/default-user/chats/role-a" \
            "$root/data/default-user/group chats/group-a"

        printf "{\"version\":\"%s\"}\n" "$version" > "$root/package.json"
        printf "#!/usr/bin/env bash\nbash -c \"sleep 1000000\" server.js\n" > "$root/start.sh"
        printf "fake server\n" > "$root/server.js"
        printf "{}\n" > "$root/data/default-user/settings.json"
        printf "port: 8000\n" > "$root/config/config.yaml"

        local index
        for index in $(seq 1 "$cards"); do
            printf "png-%s\n" "$index" > "$root/data/default-user/characters/card-$index.png"
        done
        mkdir -p "$root/data/default-user/characters/card-assets"
        printf "asset\n" > "$root/data/default-user/characters/card-assets/not-a-card.png"

        for index in $(seq 1 "$chats"); do
            printf "{}\n" > "$root/data/default-user/chats/role-a/chat-$index.jsonl"
        done
        for index in $(seq 1 "$group_chats"); do
            printf "{}\n" > "$root/data/default-user/group chats/group-a/chat-$index.jsonl"
        done
    }

    create_tavern /home/user/SillyTavern 1.16.0 1 2 0
    create_tavern /workspace/SillyTavern 1.18.0 2 1 1
    create_tavern /opt/custom/SillyTavern 1.19.0 3 3 1
    create_tavern /data/data/com.termux/files/usr/var/lib/proot-distro/containers/debian/rootfs/root/SillyTavern 1.20.0 4 4 2

    cd /data/data/com.termux/files/usr/var/lib/proot-distro/containers/debian/rootfs/root/SillyTavern
    bash -c "sleep 1000000" server.js >/tmp/running-tavern.log 2>&1 &
    printf "%s\n" "$!" > /tmp/running-tavern.pid
'

echo "运行非交互导出：应自动选择唯一运行中的 proot-distro 实例"
"${DOCKER[@]}" exec "$container_name" bash -lc '
    set -euo pipefail
    mkdir -p /tmp/export-output
    cd /workspace
    if ! /opt/export-tavern-data.sh --output-dir /tmp/export-output >/tmp/export-result.log 2>&1; then
        cat /tmp/export-result.log
        exit 1
    fi
    cat /tmp/export-result.log

    grep -q "已使用唯一运行中的实例" /tmp/export-result.log
    grep -q "proot-distro" /tmp/export-result.log
    grep -q "内容统计：角色卡 4，聊天历史 6" /tmp/export-result.log
    test "$(find /tmp/export-output -maxdepth 1 -type f -name "sillytavern-termux-backup-*.zip" | wc -l)" -eq 1
'

echo "运行普通 Linux 导出：没有 Termux 环境变量时默认保存到当前目录"
"${DOCKER[@]}" exec "$container_name" bash -lc '
    set -euo pipefail
    rm -f /workspace/sillytavern-termux-backup-*.zip
    cd /workspace
    if ! env -u TERMUX_VERSION -u PREFIX /opt/export-tavern-data.sh >/tmp/export-linux.log 2>&1; then
        cat /tmp/export-linux.log
        exit 1
    fi
    cat /tmp/export-linux.log

    ! grep -q "当前环境不是 Termux" /tmp/export-linux.log
    grep -q "运行环境：Linux/Docker" /tmp/export-linux.log
    grep -q "发布方式：/workspace" /tmp/export-linux.log
    grep -q "内容统计：角色卡 4，聊天历史 6" /tmp/export-linux.log
    test "$(find /workspace -maxdepth 1 -type f -name "sillytavern-termux-backup-*.zip" | wc -l)" -eq 1
'

echo "运行交互导出：输入编号 2，选择 /workspace/SillyTavern"
"$python_command" - "$container_name" "${DOCKER[@]}" <<'PY'
import os
import pty
import select
import subprocess
import sys
import time

container_name = sys.argv[1]
docker_command = sys.argv[2:]
command = docker_command + [
    "exec",
    "-it",
    container_name,
    "bash",
    "-lc",
    r'''
    set -euo pipefail
    rm -rf /tmp/export-output-interactive
    mkdir -p /tmp/export-output-interactive
    cd /workspace
    if ! /opt/export-tavern-data.sh --output-dir /tmp/export-output-interactive >/tmp/export-interactive.log 2>&1; then
        cat /tmp/export-interactive.log
        exit 1
    fi
    cat /tmp/export-interactive.log

    grep -q "找到 4 个 SillyTavern 实例" /tmp/export-interactive.log
    grep -q "内容统计：角色卡 2，聊天历史 2" /tmp/export-interactive.log
    test "$(find /tmp/export-output-interactive -maxdepth 1 -type f -name "sillytavern-termux-backup-*.zip" | wc -l)" -eq 1
''',
]

# 交互选择必须走真实 TTY，才能覆盖 curl | bash 场景下脚本读取 /dev/tty 的逻辑。
master_fd, slave_fd = pty.openpty()
process = subprocess.Popen(command, stdin=slave_fd, stdout=slave_fd, stderr=slave_fd, close_fds=True)
os.close(slave_fd)

sent_choice = False
captured = bytearray()
deadline = time.time() + 30
try:
    while True:
        if time.time() > deadline:
            process.kill()
            raise TimeoutError("interactive docker exec timed out")

        ready, _, _ = select.select([master_fd], [], [], 0.1)
        if ready:
            try:
                data = os.read(master_fd, 4096)
            except OSError:
                data = b""
            if data:
                captured.extend(data)
                sys.stdout.buffer.write(data)
                sys.stdout.buffer.flush()

        if not sent_choice and "请输入编号".encode("utf-8") in captured:
            os.write(master_fd, b"2\n")
            sent_choice = True

        if process.poll() is not None:
            while True:
                ready, _, _ = select.select([master_fd], [], [], 0)
                if not ready:
                    break
                try:
                    data = os.read(master_fd, 4096)
                except OSError:
                    break
                if not data:
                    break
                sys.stdout.buffer.write(data)
            sys.exit(process.returncode)
finally:
    os.close(master_fd)
PY

echo "Docker 导出链路测试通过。"
