#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import posixpath
import shlex
import subprocess
import sys
import textwrap
import time
from dataclasses import dataclass


@dataclass(frozen=True)
class AdbContext:
    package_name: str
    serial: str | None
    app_root: str
    native_lib_dir: str

    @property
    def rootfs_dir(self) -> str:
        return f"{self.app_root}/files/android-tavern/bootstrap/rootfs"

    @property
    def server_dir(self) -> str:
        return f"{self.app_root}/files/android-tavern/bootstrap/server"

    @property
    def app_data_root(self) -> str:
        return f"{self.app_root}/files/android-tavern/data/server"

    @property
    def logs_dir(self) -> str:
        return f"{self.app_root}/files/android-tavern/logs"

    @property
    def host_tmp_dir(self) -> str:
        return f"{self.app_root}/files/usr/tmp"

    @property
    def host_prefix_dir(self) -> str:
        return f"{self.app_root}/files/usr"

    @property
    def start_server_script(self) -> str:
        return f"{self.app_root}/files/android-tavern/bootstrap/scripts/start-server.sh"

    @property
    def server_entrypoint(self) -> str:
        return f"{self.server_dir}/tavern-entrypoint.sh"

    @property
    def termux_node_bin(self) -> str:
        return f"{self.native_lib_dir}/libtermux-node.so"

    @property
    def termux_git_bin(self) -> str:
        return f"{self.native_lib_dir}/libtermux-git.so"

    @property
    def termux_git_remote_http_bin(self) -> str:
        return f"{self.native_lib_dir}/libtermux-git-remote-http.so"

    @property
    def termux_sh_bin(self) -> str:
        return f"{self.native_lib_dir}/libtermux-sh.so"

    @property
    def rootfs_manifest(self) -> str:
        return f"{self.rootfs_dir}/rootfs-manifest.json"

    @property
    def android_resolv_conf(self) -> str:
        return f"{self.rootfs_dir}/android-resolv.conf"

    @property
    def startup_log(self) -> str:
        return f"{self.logs_dir}/startup.log"


def build_adb_command(serial: str | None, *args: str) -> list[str]:
    command = ["adb"]
    if serial:
        command.extend(["-s", serial])
    command.extend(args)
    return command


def run_adb(serial: str | None, *args: str, input_text: str | None = None, check: bool = True) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        build_adb_command(serial, *args),
        input=input_text,
        text=True,
        capture_output=True,
        encoding="utf-8",
        errors="replace",
    )
    if check and completed.returncode != 0:
        command_text = " ".join(shlex.quote(part) for part in build_adb_command(serial, *args))
        raise RuntimeError(
            f"adb command failed ({completed.returncode}): {command_text}\n"
            f"stdout:\n{completed.stdout}\n"
            f"stderr:\n{completed.stderr}"
        )
    return completed


def resolve_app_root(serial: str | None, package_name: str) -> str:
    return run_adb(serial, "shell", "run-as", package_name, "pwd").stdout.strip()


def resolve_native_lib_dir(serial: str | None, package_name: str) -> str:
    package_path_output = run_adb(serial, "shell", "pm", "path", package_name).stdout.strip()
    package_path = package_path_output.removeprefix("package:").strip()
    if not package_path:
        raise RuntimeError(f"Unable to resolve package path for {package_name}: {package_path_output!r}")

    install_root = posixpath.dirname(package_path)
    lib_root = f"{install_root}/lib"
    candidates_output = run_adb(serial, "shell", "ls", "-1", lib_root).stdout
    candidates = [line.strip() for line in candidates_output.splitlines() if line.strip()]
    if not candidates:
        raise RuntimeError(f"No native library directory found under {lib_root}")
    if len(candidates) == 1:
        return f"{lib_root}/{candidates[0]}"

    abi = run_adb(serial, "shell", "getprop", "ro.product.cpu.abi").stdout.strip()
    abi_directory_map = {
        "arm64-v8a": "arm64",
        "armeabi-v7a": "arm",
        "x86_64": "x86_64",
        "x86": "x86",
    }
    preferred = abi_directory_map.get(abi)
    if preferred and preferred in candidates:
        return f"{lib_root}/{preferred}"

    raise RuntimeError(
        f"Ambiguous native library directory under {lib_root}: {candidates} (device ABI: {abi or 'unknown'})"
    )


def build_probe_script(context: AdbContext, repo_url: str, branch: str | None, port: int) -> str:
    probes: list[dict[str, object]] = [
        {"label": "git-version", "args": ["--version"]},
        {"label": "git-ls-remote", "args": ["ls-remote", "--heads", repo_url]},
    ]
    if branch:
        probes.append(
            {
                "label": "git-ls-remote-branch",
                "args": ["ls-remote", "--heads", repo_url, f"refs/heads/{branch}"],
            }
        )

    quoted = shlex.quote
    probe_js = " ".join(
        [
            'const cp = require("node:child_process");',
            f"const probes = {json.dumps(probes, ensure_ascii=True)};",
            "for (const probe of probes) {",
            'const result = cp.spawnSync("git", probe.args, { encoding: "utf8" });',
            "console.log(JSON.stringify({",
            "label: probe.label,",
            "args: probe.args,",
            "status: result.status,",
            "signal: result.signal,",
            "error: result.error ? String(result.error) : null,",
            "stdout: result.stdout,",
            "stderr: result.stderr,",
            "}));",
            "if (result.error || result.status !== 0) {",
            "process.exit(result.status || 1);",
            "}",
            "}",
        ]
    )
    entrypoint_lines = [
        "#!/bin/sh",
        "set -eu",
        "cd /tavern/server",
        'if [ -n "${SILLYDROID_HOST_COMMAND_PATH:-}" ]; then',
        '    PATH="$SILLYDROID_HOST_COMMAND_PATH:$PATH"',
        "fi",
        'NODE_BIN="$(command -v node || true)"',
        'if [ -z "$NODE_BIN" ] || [ ! -x "$NODE_BIN" ]; then',
        '    NODE_BIN="${TAVERN_NODE_BIN:-./node/bin/node}"',
        "fi",
        'if [ -z "$NODE_BIN" ] || [ ! -x "$NODE_BIN" ]; then',
        '    echo "missing node" >&2',
        "    exit 1",
        "fi",
        f'exec "$NODE_BIN" -e {quoted(probe_js)}',
    ]
    entrypoint_printf = " ".join(quoted(line) for line in entrypoint_lines)

    return textwrap.dedent(
        f"""
        set -eu
        APP_ROOT={quoted(context.app_root)}
        SERVER_ENTRY={quoted(context.server_entrypoint)}
        BACKUP="${{SERVER_ENTRY}}.sillydroid-validation.bak"
        cp "$SERVER_ENTRY" "$BACKUP"
        trap 'if [ -f "$BACKUP" ]; then mv -f "$BACKUP" "$SERVER_ENTRY"; fi' EXIT
        printf '%s\\n' {entrypoint_printf} > "$SERVER_ENTRY"
        chmod 700 "$SERVER_ENTRY"
        export ROOTFS_DIR={quoted(context.rootfs_dir)}
        export SERVER_DIR={quoted(context.server_dir)}
        export APP_DATA_ROOT={quoted(context.app_data_root)}
        export LOGS_DIR={quoted(context.logs_dir)}
        export HOST_PREFIX_DIR={quoted(context.host_prefix_dir)}
        export TAVERN_PORT={port}
        export HOST_NATIVE_LIB_DIR={quoted(context.native_lib_dir)}
        export HOST_TMP_DIR={quoted(context.host_tmp_dir)}
        export TERMUX_NODE_BIN={quoted(context.termux_node_bin)}
        export TERMUX_GIT_BIN={quoted(context.termux_git_bin)}
        export TERMUX_GIT_REMOTE_HTTP_BIN={quoted(context.termux_git_remote_http_bin)}
        export TERMUX_SH_BIN={quoted(context.termux_sh_bin)}
        /system/bin/sh {quoted(context.start_server_script)}
        """
    ).strip() + "\n"


def ensure_remote_file_exists(context: AdbContext, remote_path: str) -> None:
    run_adb(context.serial, "shell", "run-as", context.package_name, "ls", remote_path)


def remote_path_exists(context: AdbContext, remote_path: str) -> bool:
    completed = run_adb(
        context.serial,
        "shell",
        "run-as",
        context.package_name,
        "ls",
        remote_path,
        check=False,
    )
    return completed.returncode == 0


def read_remote_text(context: AdbContext, remote_path: str) -> str:
    completed = run_adb(
        context.serial,
        "shell",
        "run-as",
        context.package_name,
        "cat",
        remote_path,
        check=False,
    )
    if completed.returncode != 0:
        return ""
    return completed.stdout


def extract_startup_log_tail(context: AdbContext, max_lines: int = 12) -> str:
    startup_log = read_remote_text(context, context.startup_log).strip()
    if not startup_log:
        return ""
    lines = [line.rstrip() for line in startup_log.splitlines() if line.strip()]
    return "\n".join(lines[-max_lines:])


def wait_for_bootstrap_ready(context: AdbContext, timeout_seconds: int) -> None:
    required_paths = [
        context.start_server_script,
        context.server_entrypoint,
        context.host_prefix_dir,
        context.rootfs_manifest,
        context.android_resolv_conf,
    ]
    deadline = time.monotonic() + timeout_seconds

    while time.monotonic() < deadline:
        missing_paths = [path for path in required_paths if not remote_path_exists(context, path)]
        if not missing_paths:
            return

        startup_tail = extract_startup_log_tail(context)
        if "state=BLOCKED" in startup_tail or "state=ERROR" in startup_tail:
            raise RuntimeError(
                "Bootstrap entered a failure state before validation.\n"
                f"Missing paths: {missing_paths}\n"
                f"startup.log tail:\n{startup_tail}"
            )

        time.sleep(1.0)

    startup_tail = extract_startup_log_tail(context)
    raise RuntimeError(
        "Timed out waiting for Android bootstrap to finish before validation.\n"
        f"Missing paths: {[path for path in required_paths if not remote_path_exists(context, path)]}\n"
        f"startup.log tail:\n{startup_tail}"
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="在真机上通过真实 start-server 链路验证 Node 对系统 git 的调用是否可用。"
    )
    parser.add_argument("--package", default="com.jm.sillydroid", help="Android 应用包名。")
    parser.add_argument("--serial", help="adb 设备序列号。")
    parser.add_argument(
        "--repo-url",
        default="https://github.com/octocat/Hello-World.git",
        help="用于 git ls-remote 探针的仓库地址。",
    )
    parser.add_argument("--branch", help="可选：额外验证某个远端分支是否能被 ls-remote 命中。")
    parser.add_argument("--port", type=int, default=7889, help="探针运行时使用的临时 Tavern 端口。")
    parser.add_argument("--timeout", type=int, default=300, help="等待 Android bootstrap 就绪的超时时间（秒）。")
    args = parser.parse_args()

    context = AdbContext(
        package_name=args.package,
        serial=args.serial,
        app_root=resolve_app_root(args.serial, args.package),
        native_lib_dir=resolve_native_lib_dir(args.serial, args.package),
    )

    wait_for_bootstrap_ready(context, args.timeout)

    ensure_remote_file_exists(context, context.start_server_script)
    ensure_remote_file_exists(context, context.server_entrypoint)
    ensure_remote_file_exists(context, context.host_prefix_dir)
    ensure_remote_file_exists(context, context.rootfs_manifest)
    ensure_remote_file_exists(context, context.android_resolv_conf)

    print(f"package={context.package_name}")
    print(f"app_root={context.app_root}")
    print(f"native_lib_dir={context.native_lib_dir}")
    print(f"host_prefix_dir={context.host_prefix_dir}")
    print(f"rootfs_manifest={context.rootfs_manifest}")
    print(f"android_resolv_conf={context.android_resolv_conf}")

    probe_result = run_adb(
        context.serial,
        "shell",
        "run-as",
        context.package_name,
        "sh",
        input_text=build_probe_script(context, args.repo_url, args.branch, args.port),
    )

    parsed_lines: list[dict[str, object]] = []
    for raw_line in probe_result.stdout.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        try:
            parsed_lines.append(json.loads(line))
        except json.JSONDecodeError as exc:
            raise RuntimeError(f"Unexpected probe output: {line!r}") from exc

    if not parsed_lines:
        raise RuntimeError("Probe produced no JSON output.")

    for entry in parsed_lines:
        label = str(entry.get("label") or "probe")
        args_repr = " ".join(str(part) for part in entry.get("args") or [])
        status = entry.get("status")
        print(f"[{label}] status={status} git {args_repr}")
        stdout_text = str(entry.get("stdout") or "").rstrip()
        stderr_text = str(entry.get("stderr") or "").rstrip()
        if stdout_text:
            print(stdout_text)
        if stderr_text:
            print(stderr_text, file=sys.stderr)

    print("Validation succeeded: Node spawnSync('git', ...) worked through the real start-server chain.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
