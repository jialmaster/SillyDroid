#!/usr/bin/env python3
from __future__ import annotations

import argparse
import concurrent.futures
import os
import pathlib
import sys
import threading
import time
import urllib.request


class DownloadTask:
    def __init__(self, index: int, label: str, url: str, destination: str) -> None:
        self.index = index
        self.label = label
        self.url = url
        self.destination = destination
        self.status = "queued"
        self.downloaded = 0
        self.total = 0
        self.speed = 0.0
        self.error = ""
        self._samples: list[tuple[float, int]] = []
        self._lock = threading.Lock()

    def update(self, downloaded: int, total: int) -> None:
        now = time.monotonic()
        with self._lock:
            self.status = "running"
            self.downloaded = downloaded
            self.total = total
            self._samples.append((now, downloaded))
            cutoff = now - 3.0
            self._samples = [sample for sample in self._samples if sample[0] >= cutoff]
            if len(self._samples) >= 2:
                delta_time = self._samples[-1][0] - self._samples[0][0]
                delta_bytes = self._samples[-1][1] - self._samples[0][1]
                self.speed = (delta_bytes / delta_time) if delta_time > 0 else 0.0

    def complete(self, downloaded: int) -> None:
        with self._lock:
            self.status = "completed"
            self.downloaded = downloaded
            if self.total <= 0:
                self.total = downloaded
            self.speed = 0.0

    def fail(self, message: str) -> None:
        with self._lock:
            self.status = "failed"
            self.error = message.strip()
            self.speed = 0.0

    def snapshot(self) -> dict[str, object]:
        with self._lock:
            return {
                "index": self.index,
                "label": self.label,
                "status": self.status,
                "downloaded": self.downloaded,
                "total": self.total,
                "speed": self.speed,
                "error": self.error,
            }


def parse_manifest(path: str) -> list[DownloadTask]:
    tasks: list[DownloadTask] = []
    manifest_path = pathlib.Path(path)
    for index, line in enumerate(manifest_path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            raise ValueError(f"Invalid manifest line: {line}")
        label, url, destination = parts
        tasks.append(DownloadTask(index, label, url, destination))
    return tasks


def human_bytes(value: float) -> str:
    units = ["B", "KB", "MB", "GB", "TB"]
    size = float(value)
    unit_index = 0
    while size >= 1024.0 and unit_index < len(units) - 1:
        size /= 1024.0
        unit_index += 1
    if unit_index == 0:
        return f"{int(size)} {units[unit_index]}"
    return f"{size:.1f} {units[unit_index]}"


def format_line(snapshot: dict[str, object]) -> str:
    index = snapshot["index"]
    label = str(snapshot["label"])
    status = snapshot["status"]
    downloaded = int(snapshot["downloaded"])
    total = int(snapshot["total"])
    speed = float(snapshot["speed"])
    error = str(snapshot["error"])

    prefix = f"{index}.{label}"
    if status == "completed":
        return f"{prefix} 完成 {human_bytes(downloaded)}"
    if status == "failed":
        return f"{prefix} 失败 {error}"
    if status == "running":
        if total > 0:
            percent = downloaded * 100.0 / total
            return f"{prefix} 进行中 {percent:5.1f}% {human_bytes(downloaded)}/{human_bytes(total)} {human_bytes(speed)}/s"
        return f"{prefix} 进行中 {human_bytes(downloaded)} {human_bytes(speed)}/s"
    return f"{prefix} 等待中"


def trim_line(line: str, width: int) -> str:
    if width <= 0 or len(line) <= width:
        return line
    if width <= 1:
        return line[:width]
    return line[: width - 1] + "…"


def render(tasks: list[DownloadTask], previous_line_count: int, tty: bool) -> int:
    snapshots = [task.snapshot() for task in tasks]
    width = 0
    if tty:
        try:
            width = os.get_terminal_size(sys.stderr.fileno()).columns - 1
        except OSError:
            width = 0

    if tty and previous_line_count > 0:
        sys.stderr.write(f"\x1b[{previous_line_count}F")

    for snapshot in snapshots:
        line = format_line(snapshot)
        if tty:
            sys.stderr.write("\x1b[2K\r" + trim_line(line, width) + "\n")
        else:
            sys.stderr.write(line + "\n")

    sys.stderr.flush()
    return len(snapshots)


def download_one(task: DownloadTask) -> None:
    destination_path = pathlib.Path(task.destination)
    temp_path = pathlib.Path(f"{task.destination}.part")
    destination_path.parent.mkdir(parents=True, exist_ok=True)

    request = urllib.request.Request(task.url, headers={"User-Agent": "SillyDroid-Android-Build"})
    try:
        with urllib.request.urlopen(request) as response, open(temp_path, "wb") as handle:
            total = int(response.headers.get("Content-Length") or "0")
            downloaded = 0
            while True:
                chunk = response.read(256 * 1024)
                if not chunk:
                    break
                handle.write(chunk)
                downloaded += len(chunk)
                task.update(downloaded, total)
        os.replace(temp_path, destination_path)
        task.complete(downloaded)
    except Exception as exc:
        try:
            temp_path.unlink(missing_ok=True)
        except Exception:
            pass
        task.fail(str(exc))
        raise


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()

    tasks = parse_manifest(args.manifest)
    if not tasks:
        return 0

    tty = sys.stderr.isatty()
    previous_line_count = 0
    if tty:
        sys.stderr.write("\x1b[?25l")
        sys.stderr.flush()

    stop_render = threading.Event()

    def render_loop() -> None:
        nonlocal previous_line_count
        while not stop_render.is_set():
            previous_line_count = render(tasks, previous_line_count, tty)
            time.sleep(0.2)
        previous_line_count = render(tasks, previous_line_count, tty)

    renderer = threading.Thread(target=render_loop, daemon=True)
    renderer.start()

    failed = False
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.jobs)) as executor:
            futures = [executor.submit(download_one, task) for task in tasks]
            for future in concurrent.futures.as_completed(futures):
                try:
                    future.result()
                except Exception:
                    failed = True
        return 1 if failed else 0
    finally:
        stop_render.set()
        renderer.join()
        if tty:
            sys.stderr.write("\x1b[?25h")
            sys.stderr.flush()


if __name__ == "__main__":
    raise SystemExit(main())