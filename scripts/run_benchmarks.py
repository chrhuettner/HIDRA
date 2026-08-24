#!/usr/bin/env python3
"""Run all HIDRA benchmark configurations in a directory sequentially."""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time


CONTAINER_WORKDIR = "/app/testFolder"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run one HIDRA Docker container for every JSON config in a directory."
    )
    parser.add_argument("config_dir", type=Path, help="directory containing model configs")
    parser.add_argument(
        "--workdir", type=Path,
        help=f"host directory mounted at {CONTAINER_WORKDIR}",
    )
    parser.add_argument("--image", default="chrhuettner/hidra:llm", help="Docker image")
    parser.add_argument(
        "--logs", type=Path,
        help="log directory (default: <config-dir-parent>/benchmark-logs)",
    )
    parser.add_argument("--recursive", action="store_true", help="find JSON files recursively")
    parser.add_argument("--sudo", action="store_true", help="run Docker through sudo (Linux only)")
    parser.add_argument("--stop-on-error", action="store_true", help="stop after the first failed run")
    parser.add_argument("--dry-run", action="store_true", help="validate and display runs only")
    return parser.parse_args()


def docker_path(relative_path: Path) -> str:
    return f"{CONTAINER_WORKDIR}/{relative_path.as_posix()}"


def main() -> int:
    args = parse_args()
    config_dir = args.config_dir.expanduser().resolve()
    if not config_dir.is_dir():
        print(f"error: config directory does not exist: {config_dir}", file=sys.stderr)
        return 2

    workdir = (args.workdir.expanduser().resolve() if args.workdir else config_dir.parent)
    try:
        config_dir.relative_to(workdir)
    except ValueError:
        print(f"error: config directory must be inside workdir: {workdir}", file=sys.stderr)
        return 2

    pattern = "**/*.json" if args.recursive else "*.json"
    configs = sorted((p for p in config_dir.glob(pattern) if p.is_file()), key=lambda p: str(p).lower())
    if not configs:
        print(f"error: no JSON files found in {config_dir}", file=sys.stderr)
        return 2

    for config in configs:
        try:
            with config.open(encoding="utf-8") as stream:
                json.load(stream)
        except (OSError, json.JSONDecodeError) as exc:
            print(f"error: invalid config {config}: {exc}", file=sys.stderr)
            return 2


    # Docker Desktop installs both an extensionless Unix wrapper and docker.exe.
    # shutil.which("docker") may select the wrapper on Windows, which CreateProcess
    # rejects with WinError 193.
    docker_command = "docker.exe" if os.name == "nt" else "docker"
    docker = shutil.which(docker_command)
    if not docker and not args.dry_run:
        print("error: docker was not found on PATH", file=sys.stderr)
        return 2
    if args.sudo and os.name == "nt":
        print("error: --sudo is only supported on Linux/macOS", file=sys.stderr)
        return 2

    stamp = dt.datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    log_root = args.logs.expanduser().resolve() if args.logs else config_dir.parent / "benchmark-logs"
    log_dir = log_root / stamp
    if not args.dry_run:
        log_dir.mkdir(parents=True, exist_ok=False)

    rows: list[dict[str, object]] = []
    total = len(configs)
    print(f"Found {total} config(s); logs: {log_dir}")
    for index, config in enumerate(configs, 1):
        relative_config = config.relative_to(workdir)
        safe_name = "__".join(relative_config.with_suffix("").parts)
        log_file = log_dir / f"{index:03d}_{safe_name}.log"
        command = (["sudo"] if args.sudo else []) + [
            docker or "docker", "run", "--rm",
            "--add-host=host.docker.internal:host-gateway",
            "-v", "/var/run/docker.sock:/var/run/docker.sock",
            "-v", f"{workdir}:{CONTAINER_WORKDIR}",
            "--workdir", CONTAINER_WORKDIR,
            args.image, "bump", docker_path(relative_config),
        ]
        print(f"\n[{index}/{total}] {relative_config}")
        if args.dry_run:
            print(subprocess.list2cmdline(command))
            continue

        started = dt.datetime.now().astimezone()
        start_clock = time.monotonic()
        # Line buffering plus an explicit flush keeps the on-disk log current even
        # if Docker, the child process, or this runner crashes before completion.
        with log_file.open("w", encoding="utf-8", errors="replace", buffering=1) as log:
            log.write(f"Started: {started.isoformat()}\nConfig: {config}\nImage: {args.image}\n\n")
            log.flush()
            try:
                process = subprocess.Popen(
                    command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    text=True, encoding="utf-8", errors="replace", bufsize=1,
                )
                assert process.stdout is not None
                try:
                    for line in process.stdout:
                        print(line, end="", flush=True)
                        log.write(line)
                        log.flush()
                except KeyboardInterrupt:
                    process.terminate()
                    process.wait()
                    print("\nInterrupted; the active container was stopped.", file=sys.stderr)
                    return 130
                exit_code = process.wait()
            except OSError as exc:
                message = f"Could not start Docker: {exc}"
                print(message, file=sys.stderr)
                log.write(message + "\n")
                exit_code = 127
        elapsed = round(time.monotonic() - start_clock, 3)
        status = "passed" if exit_code == 0 else "failed"
        rows.append({
            "config": str(relative_config), "status": status,
            "exit_code": exit_code, "duration_seconds": elapsed, "log": str(log_file),
        })
        print(f"[{status}] exit={exit_code}, duration={elapsed:.1f}s, log={log_file}")
        if exit_code != 0 and args.stop_on_error:
            break

    if args.dry_run:
        return 0
    summary = log_dir / "summary.csv"
    with summary.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=["config", "status", "exit_code", "duration_seconds", "log"])
        writer.writeheader()
        writer.writerows(rows)
    failures = sum(row["status"] == "failed" for row in rows)
    print(f"\nCompleted {len(rows)} run(s): {len(rows) - failures} passed, {failures} failed")
    print(f"Summary: {summary}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
