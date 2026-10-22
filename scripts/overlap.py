#!/usr/bin/env python3

import argparse
import csv
import re
from pathlib import Path


FIXED_PATTERN = re.compile(
    r"Fixed\s+([0-9a-fA-F]{40})\s*"
    r"\([^)]*buildOnly:\s*(true|false)[^)]*\)",
    re.IGNORECASE
)


def extract_fixed_projects(log_file: Path) -> dict[str, str]:
    """
    Returns:
        {
            project_hash: "T",  # test-fixed
            project_hash: "B",  # build-fixed
        }
    """
    projects = {}

    with log_file.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            match = FIXED_PATTERN.search(line)

            if not match:
                continue

            project_hash = match.group(1).lower()
            build_only = match.group(2).lower() == "true"

            projects[project_hash] = "B" if build_only else "T"

    return projects


def main():
    parser = argparse.ArgumentParser(
        description="Create a HIDRA project-fix overlap table from a folder of logs."
    )

    parser.add_argument(
        "log_folder",
        type=Path,
        help="Folder containing HIDRA .log files"
    )

    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path("fix_overlap.csv"),
        help="Output CSV file (default: fix_overlap.csv)"
    )

    args = parser.parse_args()

    if not args.log_folder.is_dir():
        raise SystemExit(f"Not a directory: {args.log_folder}")

    log_files = sorted(args.log_folder.glob("*.log"))

    if not log_files:
        raise SystemExit(
            f"No .log files found in {args.log_folder}"
        )

    runs = {}

    for log_file in log_files:
        run_name = log_file.stem
        fixed = extract_fixed_projects(log_file)

        runs[run_name] = fixed

        test_fixed = sum(
            1 for status in fixed.values()
            if status == "T"
        )
        build_fixed = sum(
            1 for status in fixed.values()
            if status == "B"
        )

        print(
            f"{run_name}: "
            f"{len(fixed)} fixed "
            f"({test_fixed} test-fixed, "
            f"{build_fixed} build-fixed)"
        )

    # Every project fixed in at least one run
    all_projects = sorted(
        set().union(*(projects.keys() for projects in runs.values()))
    )

    with args.output.open(
        "w",
        newline="",
        encoding="utf-8"
    ) as f:

        writer = csv.writer(f, delimiter=";")

        writer.writerow(
            ["Project"] + list(runs.keys())
        )

        for project in all_projects:
            row = [project]

            for run_projects in runs.values():
                row.append(
                    run_projects.get(project, "")
                )

            writer.writerow(row)

    print()
    print(f"Wrote: {args.output}")
    print(
        f"Projects fixed in at least one run: "
        f"{len(all_projects)}"
    )

    print()
    print("Legend:")
    print("  T = Test-fixed  (buildOnly: false)")
    print("  B = Build-fixed (buildOnly: true)")
    print("    = Not fixed")


if __name__ == "__main__":
    main()