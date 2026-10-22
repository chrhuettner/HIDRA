#!/usr/bin/env python3

import argparse
import csv
import re
from decimal import Decimal, ROUND_DOWN
from pathlib import Path


# ============================================================================
# Regex patterns
# ============================================================================

# Example:
# Fixed 16ae40b1e17e14ee3ae20ac211647e47399a01a9
# (Retries: 0, Iteration: 0, buildOnly: false)
FIXED_PATTERN = re.compile(
    r"Fixed\s+([0-9a-fA-F]{40})\s*\(([^)]*)\)",
    re.IGNORECASE
)

RETRIES_PATTERN = re.compile(
    r"\bRetries:\s*(\d+)",
    re.IGNORECASE
)

ITERATION_PATTERN = re.compile(
    r"\bIteration:\s*(\d+)",
    re.IGNORECASE
)

BUILD_ONLY_PATTERN = re.compile(
    r"\bbuildOnly:\s*(true|false)",
    re.IGNORECASE
)

# Example:
# Took 15604923 ms to process all projects
TOTAL_RUNTIME_PATTERN = re.compile(
    r"^Took\s+(\d+)\s+ms\s+to\s+process\s+all\s+projects\s*$",
    re.IGNORECASE | re.MULTILINE
)

# Example:
# Number of llm requests: 1861
LLM_REQUESTS_PATTERN = re.compile(
    r"^Number\s+of\s+llm\s+requests:\s*(\d+)\s*$",
    re.IGNORECASE | re.MULTILINE
)

FAILED_LLM_PATTERNS = [
    re.compile(
        r"^Number\s+of\s+failed\s+llm\s+requests:\s*(\d+)\s*$",
        re.IGNORECASE | re.MULTILINE
    ),
    re.compile(
        r"^Failed\s+llm\s+requests:\s*(\d+)\s*$",
        re.IGNORECASE | re.MULTILINE
    ),
    re.compile(
        r"^Number\s+of\s+failed\s+requests:\s*(\d+)\s*$",
        re.IGNORECASE | re.MULTILINE
    )
]

# Example:
# Fixed 19 out of 96 projects (76 of the fixable projects were not fixed)
TOTAL_PROJECTS_PATTERN = re.compile(
    r"^Fixed\s+\d+\s+out\s+of\s+(\d+)\s+projects",
    re.IGNORECASE | re.MULTILINE
)

# Statistics section headers
STAT_HEADER_PATTERN = re.compile(
    r"^(Duration|Iteration|Retry)\s+statistics\s+of\s+successful\s+fixes:\s*$",
    re.IGNORECASE
)

# Example:
# Average: 428316.7368421053
# Median: 316323.0
# Standard Deviation: 312930.2501459461
STAT_PATTERN = re.compile(
    r"^(Average|Min|Max|Count|Median|25th percentile|75th percentile|"
    r"Variance|Standard Deviation):\s*"
    r"([-+]?\d+(?:\.\d+)?(?:[Ee][-+]?\d+)?)\s*$",
    re.IGNORECASE
)


# ============================================================================
# Formatting
# ============================================================================

def truncate(value: Decimal | None, decimals: int) -> str:
    """
    Truncate instead of round and use decimal commas.

    Examples:
        428.316736 -> 428,316
        0.315789   -> 0,3157
        1.0000     -> 1
    """
    if value is None:
        return ""

    step = Decimal("1").scaleb(-decimals)

    value = value.quantize(
        step,
        rounding=ROUND_DOWN
    )

    result = format(value, "f")

    if "." in result:
        result = result.rstrip("0").rstrip(".")

    return result.replace(".", ",")


# ============================================================================
# Fixed projects
# ============================================================================

def extract_fixed_projects(text: str) -> dict:
    """
    Extract exact information from every successful Fixed line.

    Returns:
        {
            project_hash: {
                "build_only": False,
                "iteration": 1,
                "retries": 0
            }
        }
    """

    projects = {}

    for match in FIXED_PATTERN.finditer(text):
        project_hash = match.group(1).lower()
        metadata = match.group(2)

        build_match = BUILD_ONLY_PATTERN.search(metadata)
        iteration_match = ITERATION_PATTERN.search(metadata)
        retries_match = RETRIES_PATTERN.search(metadata)

        if not build_match:
            continue

        projects[project_hash] = {
            "build_only": (
                build_match.group(1).lower() == "true"
            ),
            "iteration": (
                int(iteration_match.group(1))
                if iteration_match
                else None
            ),
            "retries": (
                int(retries_match.group(1))
                if retries_match
                else None
            )
        }

    return projects


# ============================================================================
# Statistics sections
# ============================================================================

def extract_stat_sections(text: str) -> dict:
    """
    Parse Duration, Iteration and Retry statistics independently.
    """

    statistics = {
        "Duration": {},
        "Iteration": {},
        "Retry": {}
    }

    lines = text.splitlines()

    for i, line in enumerate(lines):
        header_match = STAT_HEADER_PATTERN.fullmatch(line.strip())

        if not header_match:
            continue

        section = header_match.group(1).capitalize()
        section_stats = {}
        started = False

        for next_line in lines[i + 1:]:
            stripped = next_line.strip()

            stat_match = STAT_PATTERN.fullmatch(stripped)

            if stat_match:
                started = True

                stat_name = stat_match.group(1)
                stat_value = Decimal(stat_match.group(2))

                section_stats[stat_name] = stat_value

                if len(section_stats) == 9:
                    break

                continue

            if started:
                break

        statistics[section] = section_stats

    return statistics


# ============================================================================
# Complete log parsing
# ============================================================================

def extract_log_statistics(log_file: Path) -> dict:
    with log_file.open(
        "r",
        encoding="utf-8",
        errors="replace"
    ) as f:
        text = f.read()

    # Use the complete filename, including extension.
    run_name = log_file.name

    # ------------------------------------------------------------------------
    # Fixed projects
    # ------------------------------------------------------------------------

    fixed_projects = extract_fixed_projects(text)

    build_fixed = sum(
        1
        for project in fixed_projects.values()
        if project["build_only"]
    )

    test_fixed = sum(
        1
        for project in fixed_projects.values()
        if not project["build_only"]
    )

    # Exact totals from the individual Fixed lines
    total_iterations = sum(
        project["iteration"]
        for project in fixed_projects.values()
        if project["iteration"] is not None
    )

    total_retries = sum(
        project["retries"]
        for project in fixed_projects.values()
        if project["retries"] is not None
    )

    # ------------------------------------------------------------------------
    # Total number of benchmark projects
    # ------------------------------------------------------------------------

    total_projects_match = TOTAL_PROJECTS_PATTERN.search(text)

    total_projects = (
        int(total_projects_match.group(1))
        if total_projects_match
        else None
    )

    # ------------------------------------------------------------------------
    # Overall benchmark runtime
    #
    # Uses:
    # Took 15604923 ms to process all projects
    # ------------------------------------------------------------------------

    runtime_match = TOTAL_RUNTIME_PATTERN.search(text)

    runtime_ms = (
        Decimal(runtime_match.group(1))
        if runtime_match
        else None
    )

    runtime_hours = (
        runtime_ms / Decimal("3600000")
        if runtime_ms is not None
        else None
    )

    # ------------------------------------------------------------------------
    # LLM calls
    # ------------------------------------------------------------------------

    llm_match = LLM_REQUESTS_PATTERN.search(text)

    llm_requests = (
        int(llm_match.group(1))
        if llm_match
        else None
    )

    # ------------------------------------------------------------------------
    # Failed LLM calls
    # ------------------------------------------------------------------------

    failed_llm_requests = None

    for pattern in FAILED_LLM_PATTERNS:
        match = pattern.search(text)

        if match:
            failed_llm_requests = int(match.group(1))
            break

    if failed_llm_requests is None:
        failed_llm_requests = 0

    # ------------------------------------------------------------------------
    # Duration / Iteration / Retry statistics
    # ------------------------------------------------------------------------

    statistics = extract_stat_sections(text)

    return {
        "run": run_name,

        "build_fixed": build_fixed,
        "test_fixed": test_fixed,
        "total_fixed": len(fixed_projects),
        "total_projects": total_projects,

        "llm_requests": llm_requests,
        "failed_llm_requests": failed_llm_requests,

        "runtime_hours": runtime_hours,

        "statistics": statistics,

        "total_iterations": total_iterations,
        "total_retries": total_retries
    }


# ============================================================================
# Metric rows
# ============================================================================

def metric_row(
    run_name: str,
    metric: str,
    stats: dict,
    exact_total=None
) -> list:
    """
    Duration total:
        Average × Count

    Iteration total:
        Exact sum from Fixed lines

    Retry total:
        Exact sum from Fixed lines
    """

    average = stats.get("Average")
    median = stats.get("Median")
    p25 = stats.get("25th percentile")
    p75 = stats.get("75th percentile")
    minimum = stats.get("Min")
    maximum = stats.get("Max")
    sd = stats.get("Standard Deviation")
    count = stats.get("Count")

    # ------------------------------------------------------------------------
    # Duration
    # ------------------------------------------------------------------------

    if metric == "Duration":
        divisor = Decimal("1000")

        average = (
            average / divisor
            if average is not None
            else None
        )

        median = (
            median / divisor
            if median is not None
            else None
        )

        p25 = (
            p25 / divisor
            if p25 is not None
            else None
        )

        p75 = (
            p75 / divisor
            if p75 is not None
            else None
        )

        minimum = (
            minimum / divisor
            if minimum is not None
            else None
        )

        maximum = (
            maximum / divisor
            if maximum is not None
            else None
        )

        sd = (
            sd / divisor
            if sd is not None
            else None
        )

        # Duration total = Average × Count
        total = (
            average * count
            if average is not None and count is not None
            else None
        )

        return [
            run_name,
            "Duration (s)",
            truncate(average, 3),
            truncate(median, 3),
            truncate(p25, 3),
            truncate(p75, 3),
            truncate(minimum, 3),
            truncate(maximum, 3),
            truncate(sd, 3),
            truncate(total, 3)
        ]

    # ------------------------------------------------------------------------
    # Iterations
    # ------------------------------------------------------------------------

    if metric == "Iteration":
        return [
            run_name,
            "Iterations",
            truncate(average, 4),
            truncate(median, 4),
            truncate(p25, 4),
            truncate(p75, 4),
            truncate(minimum, 4),
            truncate(maximum, 4),
            truncate(sd, 4),
            str(exact_total) if exact_total is not None else ""
        ]

    # ------------------------------------------------------------------------
    # Retries
    # ------------------------------------------------------------------------

    return [
        run_name,
        "Retries",
        truncate(average, 4),
        truncate(median, 4),
        truncate(p25, 4),
        truncate(p75, 4),
        truncate(minimum, 4),
        truncate(maximum, 4),
        truncate(sd, 4),
        str(exact_total) if exact_total is not None else ""
    ]


# ============================================================================
# Main
# ============================================================================

def main():
    parser = argparse.ArgumentParser(
        description=(
            "Extract benchmark statistics from a folder of HIDRA logs "
            "and create an Excel-friendly CSV."
        )
    )

    parser.add_argument(
        "log_folder",
        type=Path,
        help="Folder containing HIDRA log files"
    )

    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path("hidra_statistics.csv"),
        help="Output CSV file (default: hidra_statistics.csv)"
    )

    args = parser.parse_args()

    # ------------------------------------------------------------------------
    # Validate folder
    # ------------------------------------------------------------------------

    if not args.log_folder.is_dir():
        raise SystemExit(
            f"Not a directory: {args.log_folder}"
        )

    # Recursively process every regular file.
    # This does not require a .log extension.
    log_files = sorted(
        path
        for path in args.log_folder.rglob("*")
        if path.is_file()
    )

    if not log_files:
        raise SystemExit(
            f"No files found in {args.log_folder}"
        )

    # ------------------------------------------------------------------------
    # Parse logs
    # ------------------------------------------------------------------------

    runs = []

    for log_file in log_files:
        data = extract_log_statistics(log_file)

        # Ignore files that do not appear to be HIDRA benchmark logs.
        if (
            data["llm_requests"] is None
            and data["runtime_hours"] is None
            and data["total_fixed"] == 0
        ):
            continue

        runs.append(data)

        print(
            f"{data['run']}: "
            f"{data['build_fixed']} build-fixed, "
            f"{data['test_fixed']} test-fixed, "
            f"{data['total_fixed']} total, "
            f"{data['llm_requests']} LLM calls, "
            f"{data['total_iterations']} iterations, "
            f"{data['total_retries']} retries"
        )

    if not runs:
        raise SystemExit(
            "No HIDRA benchmark logs containing statistics were found."
        )

    # ------------------------------------------------------------------------
    # Determine project count for header
    # ------------------------------------------------------------------------

    project_counts = {
        run["total_projects"]
        for run in runs
        if run["total_projects"] is not None
    }

    if len(project_counts) == 1:
        project_count = next(iter(project_counts))

        project_count_text = (
            f"Projects fixed (out of {project_count})"
        )
    else:
        project_count_text = "Projects fixed"

    # ------------------------------------------------------------------------
    # Write CSV
    # ------------------------------------------------------------------------

    with args.output.open(
        "w",
        newline="",
        encoding="utf-8-sig"
    ) as f:

        writer = csv.writer(
            f,
            delimiter=";"
        )

        # ====================================================================
        # Overall benchmark statistics
        # ====================================================================

        writer.writerow([
            "",
            project_count_text,
            "",
            "",
            "LLM calls",
            "",
            ""
        ])

        writer.writerow([
            "Run",
            "Build",
            "Test",
            "Total",
            "Total",
            "failed",
            "Duration (h)"
        ])

        for data in runs:
            writer.writerow([
                data["run"],

                data["build_fixed"],
                data["test_fixed"],
                data["total_fixed"],

                (
                    data["llm_requests"]
                    if data["llm_requests"] is not None
                    else ""
                ),

                data["failed_llm_requests"],

                truncate(
                    data["runtime_hours"],
                    4
                )
            ])

        writer.writerow([])

        # ====================================================================
        # Per-fixed-project metrics
        # ====================================================================

        writer.writerow([
            "",
            "Per fixed Project Metrics",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            ""
        ])

        writer.writerow([
            "Run",
            "Metric",
            "Average",
            "Median",
            "P25",
            "P75",
            "Min",
            "Max",
            "SD",
            "Total"
        ])

        for data in runs:
            statistics = data["statistics"]

            # Duration
            writer.writerow(
                metric_row(
                    data["run"],
                    "Duration",
                    statistics["Duration"]
                )
            )

            # Iterations
            writer.writerow(
                metric_row(
                    "",
                    "Iteration",
                    statistics["Iteration"],
                    data["total_iterations"]
                )
            )

            # Retries
            writer.writerow(
                metric_row(
                    "",
                    "Retry",
                    statistics["Retry"],
                    data["total_retries"]
                )
            )

    print()
    print(f"Wrote: {args.output}")


if __name__ == "__main__":
    main()