#!/usr/bin/env python3

import json
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 4:
        print("Usage: read-stai-build-config.py <config-path> <key.path> <default>", file=sys.stderr)
        return 1

    config_path = Path(sys.argv[1])
    key_path = sys.argv[2]
    default_value = sys.argv[3]

    if not config_path.is_file():
        print(default_value)
        return 0

    try:
        data = json.loads(config_path.read_text(encoding="utf-8"))
    except Exception:
        print(default_value)
        return 0

    current = data
    for part in key_path.split("."):
        if isinstance(current, dict) and part in current:
            current = current[part]
        else:
            current = default_value
            break

    if current is None:
        current = default_value
    elif isinstance(current, bool):
        current = "true" if current else "false"
    elif not isinstance(current, (str, int, float)):
        current = default_value

    print(str(current))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())