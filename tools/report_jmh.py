#!/usr/bin/env python3
"""Create a readable CSV and standalone HTML report from Sudoku JMH results."""

import argparse
import csv
import html
import json
import math
import statistics
import sys
from pathlib import Path


COLORS = ["#2563eb", "#dc2626", "#059669", "#9333ea", "#ea580c", "#0891b2"]


def percentile(values, percentile_value):
    ordered = sorted(values)
    if not ordered:
        raise ValueError("Cannot calculate a percentile from no values.")
    position = (len(ordered) - 1) * percentile_value / 100
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] * (upper - position) + ordered[upper] * (position - lower)


def flatten_fork_samples(raw_data, metric_name):
    if not isinstance(raw_data, list) or not raw_data:
        raise ValueError(f"{metric_name} has no per-iteration rawData.")
    samples = []
    for fork in raw_data:
        if not isinstance(fork, list):
            raise ValueError(f"{metric_name} rawData must contain one list per fork.")
        samples.extend(float(value) for value in fork)
    return samples


def summarize_record(record):
    params = record.get("params") or {}
    version = int(params["versionNumber"])
    difficulty = int(params["difficulty"])
    secondary = record.get("secondaryMetrics") or {}

    allocation_samples = flatten_fork_samples(
        secondary["allocatedBytes"]["rawData"], "allocatedBytes"
    )
    operation_samples = flatten_fork_samples(
        secondary["operationCount"]["rawData"], "operationCount"
    )
    removed_samples = flatten_fork_samples(
        secondary["removedCells"]["rawData"], "removedCells"
    )
    if not (
        len(allocation_samples) == len(operation_samples) == len(removed_samples)
    ):
        raise ValueError(
            f"Raw iteration counts do not match for V{version}, difficulty {difficulty}."
        )

    b_per_op_samples = []
    for allocated, operations in zip(allocation_samples, operation_samples):
        if operations > 0:
            b_per_op_samples.append(allocated / operations)
    if len(b_per_op_samples) != len(allocation_samples):
        raise ValueError(
            f"Found an iteration with no operations for V{version}, difficulty {difficulty}."
        )

    operations_total = sum(operation_samples)
    if operations_total <= 0:
        raise ValueError(
            f"No benchmark operations found for V{version}, difficulty {difficulty}."
        )

    primary = record["primaryMetric"]
    confidence = primary.get("scoreConfidence") or [None, None]
    confidence = [
        float(value) if value is not None and math.isfinite(float(value)) else None
        for value in confidence
    ]
    removed_total = sum(removed_samples)
    return {
        "version": version,
        "difficulty": difficulty,
        "samples": len(b_per_op_samples),
        "operations": int(operations_total),
        "allocated_bytes": int(sum(allocation_samples)),
        "allocated_b_per_op": sum(allocation_samples) / operations_total,
        "median_allocated_b_per_op": statistics.median(b_per_op_samples),
        "p95_allocated_b_per_op": percentile(b_per_op_samples, 95),
        "runtime_ns_per_op": float(primary["score"]),
        "runtime_ci_low_ns_per_op": confidence[0],
        "runtime_ci_high_ns_per_op": confidence[1],
        "removed_cells_per_op": removed_total / operations_total,
        "jmh_version": record.get("jmhVersion", ""),
        "jdk_version": record.get("jdkVersion", ""),
        "vm_name": record.get("vmName", ""),
        "vm_version": record.get("vmVersion", ""),
    }


def summarize_retained_record(record):
    params = record.get("params") or {}
    version = int(params["versionNumber"])
    difficulty = int(params["difficulty"])
    secondary = record.get("secondaryMetrics") or {}

    retained_samples = flatten_fork_samples(
        secondary["retainedGraphBytes"]["rawData"], "retainedGraphBytes"
    )
    operation_samples = flatten_fork_samples(
        secondary["operationCount"]["rawData"], "retained operationCount"
    )
    removed_samples = flatten_fork_samples(
        secondary["removedCells"]["rawData"], "retained removedCells"
    )
    if not (
        len(retained_samples) == len(operation_samples) == len(removed_samples)
    ):
        raise ValueError(
            f"Retained-memory iteration counts do not match for V{version}, difficulty {difficulty}."
        )

    retained_per_op = []
    for retained_bytes, operations in zip(retained_samples, operation_samples):
        if operations > 0:
            retained_per_op.append(retained_bytes / operations)
    if len(retained_per_op) != len(retained_samples):
        raise ValueError(
            f"Found a retained-memory iteration with no operations for V{version}, difficulty {difficulty}."
        )

    operation_count = sum(operation_samples)
    if operation_count <= 0:
        raise ValueError(
            f"No retained-memory operations found for V{version}, difficulty {difficulty}."
        )

    return {
        "version": version,
        "difficulty": difficulty,
        "retained_samples": len(retained_per_op),
        "retained_graph_bytes_per_op": sum(retained_samples) / operation_count,
        "median_retained_graph_bytes_per_op": statistics.median(retained_per_op),
        "p95_retained_graph_bytes_per_op": percentile(retained_per_op, 95),
        "retained_removed_cells_per_op": sum(removed_samples) / operation_count,
    }


PEAK_COUNTERS = (
    "peakLiveHeapBytes",
    "gridBytesBefore",
    "gcSamples",
    "sampleStepBytes",
    "operationCount",
    "removedCells",
    "discardedOps",
)
# Keep in sync with SudokuPeakLiveHeapBenchmark.DEFAULT_SAMPLES_PER_OP.
DEFAULT_PEAK_SAMPLES_PER_OP = 500


def peak_samples_target(record):
    """Return the -Dpeak.samplesPerOp value the peak benchmark fork ran with."""
    prefix = "-Dpeak.samplesPerOp="
    for argument in record.get("jvmArgs") or []:
        if argument.startswith(prefix):
            return int(argument[len(prefix):])
    return DEFAULT_PEAK_SAMPLES_PER_OP


def summarize_peak_record(record):
    """Per-iteration peak data for one SudokuPeakLiveHeapBenchmark method and case."""
    params = record.get("params") or {}
    version = int(params["versionNumber"])
    difficulty = int(params["difficulty"])
    secondary = record.get("secondaryMetrics") or {}

    missing = [name for name in PEAK_COUNTERS if name not in secondary]
    if missing:
        raise ValueError(
            f"Peak live heap results for V{version}, difficulty {difficulty} come from an older "
            f"version of the benchmark (missing {', '.join(missing)}). Rerun "
            "'.\\gradlew.bat jmh -PpeakOnly' and pass --peak-input build\\results\\jmh\\peak.json."
        )
    samples = {
        name: flatten_fork_samples(secondary[name]["rawData"], f"peak {name}")
        for name in PEAK_COUNTERS
    }
    lengths = {len(values) for values in samples.values()}
    if len(lengths) != 1:
        raise ValueError(
            f"Peak-heap iteration counts do not match for V{version}, difficulty {difficulty}."
        )
    operations = samples["operationCount"]
    # An iteration can have no counted operation if its only operation failed the heap check.
    counted = [index for index, count in enumerate(operations) if count > 0]
    if not counted:
        raise ValueError(
            f"No counted peak-heap operations for V{version}, difficulty {difficulty}."
        )
    operation_count = sum(operations)

    def mean_per_op(name):
        return sum(samples[name]) / operation_count

    return {
        "version": version,
        "difficulty": difficulty,
        "target": peak_samples_target(record),
        "iterations": len(counted),
        "discarded": int(sum(samples["discardedOps"])),
        "peak_per_iteration": [
            samples["peakLiveHeapBytes"][index] / operations[index] for index in counted
        ],
        "peak_mean": mean_per_op("peakLiveHeapBytes"),
        "grid_mean": mean_per_op("gridBytesBefore"),
        "gc_samples_mean": mean_per_op("gcSamples"),
        "step_mean": mean_per_op("sampleStepBytes"),
        "removed_mean": mean_per_op("removedCells"),
    }


def combine_peak(playable, noise):
    """Subtract the harness-noise control from the playable() peak for one case."""
    # Memory the measurement adds by itself: control peak minus the grid it starts from.
    noise_bytes = noise["peak_mean"] - noise["grid_mean"]
    corrected = [value - noise_bytes for value in playable["peak_per_iteration"]]
    return {
        "version": playable["version"],
        "difficulty": playable["difficulty"],
        "peak_target_samples": playable["target"],
        "peak_samples": playable["iterations"],
        "peak_live_heap_bytes_per_op": playable["peak_mean"] - noise_bytes,
        "median_peak_live_heap_bytes_per_op": statistics.median(corrected),
        "p95_peak_live_heap_bytes_per_op": percentile(corrected, 95),
        "raw_peak_live_heap_bytes_per_op": playable["peak_mean"],
        "peak_harness_noise_bytes": noise_bytes,
        "peak_grid_bytes_before": playable["grid_mean"],
        "peak_gc_samples_per_op": playable["gc_samples_mean"],
        "peak_sample_step_bytes": playable["step_mean"],
        "peak_discarded_ops": playable["discarded"],
        "peak_removed_cells_per_op": playable["removed_mean"],
    }


def collect_peak_rows(records):
    """Return {samples target: {(version, difficulty): combined row}} from peak records."""
    playable = {}
    noise = {}
    for record in records:
        benchmark = record.get("benchmark", "")
        if "SudokuPeakLiveHeapBenchmark.playable" in benchmark:
            target = playable
        elif "SudokuPeakLiveHeapBenchmark.harnessNoise" in benchmark:
            target = noise
        else:
            continue
        row = summarize_peak_record(record)
        target[(row["target"], row["version"], row["difficulty"])] = row

    missing_noise = playable.keys() - noise.keys()
    if missing_noise:
        raise ValueError(
            "Peak live heap results need a matching harnessNoise result for every case. "
            f"Missing: {sorted(missing_noise)}."
        )
    by_target = {}
    for key, row in playable.items():
        by_target.setdefault(key[0], {})[key[1:]] = combine_peak(row, noise[key])
    return by_target


def svg_chart(rows, value_key, title, y_label, format_value):
    width, height = 900, 430
    left, right, top, bottom = 155, 24, 50, 100
    plot_width = width - left - right
    plot_height = height - top - bottom
    difficulties = sorted({row["difficulty"] for row in rows})
    versions = sorted({row["version"] for row in rows})
    values = [row[value_key] for row in rows]
    maximum = max(values) if values else 1
    y_max = maximum * 1.12 if maximum else 1

    parts = [
        f'<svg class="chart" viewBox="0 0 {width} {height}" role="img" aria-label="{html.escape(title)}">',
        '<rect width="100%" height="100%" fill="white"/>',
        f'<text x="{width / 2}" y="27" text-anchor="middle" class="chart-title">{html.escape(title)}</text>',
    ]

    def x_position(difficulty):
        if len(difficulties) == 1:
            return left + plot_width / 2
        index = difficulties.index(difficulty)
        return left + index * plot_width / (len(difficulties) - 1)

    for tick in range(6):
        value = y_max * tick / 5
        y = top + plot_height - plot_height * tick / 5
        parts.append(f'<line x1="{left}" y1="{y:.1f}" x2="{left + plot_width}" y2="{y:.1f}" class="grid"/>')
        parts.append(f'<text x="{left - 10}" y="{y + 4:.1f}" text-anchor="end" class="tick">{html.escape(format_value(value))}</text>')

    for difficulty in difficulties:
        x = x_position(difficulty)
        parts.append(f'<line x1="{x:.1f}" y1="{top + plot_height}" x2="{x:.1f}" y2="{top + plot_height + 5}" class="axis"/>')
        parts.append(f'<text x="{x:.1f}" y="{top + plot_height + 23}" text-anchor="middle" class="tick">{difficulty}</text>')

    parts.extend([
        f'<line x1="{left}" y1="{top}" x2="{left}" y2="{top + plot_height}" class="axis"/>',
        f'<line x1="{left}" y1="{top + plot_height}" x2="{left + plot_width}" y2="{top + plot_height}" class="axis"/>',
        f'<text x="{left + plot_width / 2}" y="{height - 15}" text-anchor="middle" class="axis-label">Target difficulty (cells removed)</text>',
        f'<text x="20" y="{top + plot_height / 2}" text-anchor="middle" class="axis-label" transform="rotate(-90 20 {top + plot_height / 2})">{html.escape(y_label)}</text>',
    ])

    legend_x = left
    for index, version in enumerate(versions):
        color = COLORS[(version - 1) % len(COLORS)]
        series = sorted((row for row in rows if row["version"] == version), key=lambda row: row["difficulty"])
        points = []
        for row in series:
            x = x_position(row["difficulty"])
            y = top + plot_height - plot_height * row[value_key] / y_max
            points.append((x, y))
        if points:
            point_text = " ".join(f"{x:.1f},{y:.1f}" for x, y in points)
            parts.append(f'<polyline points="{point_text}" fill="none" stroke="{color}" stroke-width="2.5"/>')
            for x, y in points:
                parts.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="4" fill="{color}"/>')
        lx = legend_x + index * 100
        parts.append(f'<line x1="{lx}" y1="{height - 42}" x2="{lx + 22}" y2="{height - 42}" stroke="{color}" stroke-width="3"/>')
        parts.append(f'<text x="{lx + 28}" y="{height - 38}" class="tick">V{version}</text>')

    parts.append("</svg>")
    return "\n".join(parts)


def format_num(value, places=1):
    return f"{value:,.{places}f}"


def convergence_section(rows, convergence):
    """HTML table comparing the peak at different sample counts per operation."""
    if not convergence:
        return (
            '<p class="note">Sampling check not included. Run <code>.\\gradlew.bat jmh '
            '-PpeakSamples=125</code> and <code>-PpeakSamples=250</code>, then pass the '
            'files with <code>--peak-convergence-input</code>.</p>'
        )
    targets = sorted(convergence)
    header = "".join(
        f"<th>Peak, target {target} samples/op</th><th>Actual samples/op</th>"
        for target in targets
    )
    body = []
    for row in rows:
        key = (row["version"], row["difficulty"])
        cells = []
        for target in targets:
            value = convergence[target].get(key)
            if value is None:
                cells.append("<td>n/a</td><td>n/a</td>")
            else:
                cells.append(
                    f"<td>{format_num(value['peak_live_heap_bytes_per_op'], 0)}</td>"
                    f"<td>{value['peak_gc_samples_per_op']:,.1f}</td>"
                )
        body.append(
            f"<tr><td>V{row['version']}</td><td>{row['difficulty']}</td>{''.join(cells)}</tr>"
        )
    return (
        "<h3>Sampling check</h3>"
        "<p>The same peak benchmark with fewer full-GC samples per operation. Samples are exact, "
        "so fewer samples can only miss a high point, never add one. If the value barely changes "
        "as the sample count doubles, the sampling is fine enough to catch the peak. Each run "
        "uses the same seeds, but the number of operations per iteration differs between runs, "
        "so a few percent of variation comes from different inputs, not sampling.</p>"
        '<div class="table-wrap"><table>'
        f"<thead><tr><th>Version</th><th>Difficulty</th>{header}</tr></thead>"
        f"<tbody>{''.join(body)}</tbody></table></div>"
    )


def write_report(rows, output_dir, convergence=None):
    output_dir.mkdir(parents=True, exist_ok=True)
    rows.sort(key=lambda row: (row["difficulty"], row["version"]))
    baseline_by_difficulty = {
        row["difficulty"]: row["allocated_b_per_op"]
        for row in rows
        if row["version"] == 1
    }
    retained_baseline_by_difficulty = {
        row["difficulty"]: row["retained_graph_bytes_per_op"]
        for row in rows
        if row["version"] == 1
    }
    peak_baseline_by_difficulty = {
        row["difficulty"]: row["peak_live_heap_bytes_per_op"]
        for row in rows
        if row["version"] == 1
    }

    fields = [
        "version",
        "difficulty",
        "samples",
        "operations",
        "removed_cells_per_op",
        "allocated_bytes",
        "allocated_b_per_op",
        "median_allocated_b_per_op",
        "p95_allocated_b_per_op",
        "allocated_vs_v1_percent",
        "retained_samples",
        "retained_graph_bytes_per_op",
        "median_retained_graph_bytes_per_op",
        "p95_retained_graph_bytes_per_op",
        "retained_vs_v1_percent",
        "retained_removed_cells_per_op",
        "peak_samples",
        "peak_live_heap_bytes_per_op",
        "median_peak_live_heap_bytes_per_op",
        "p95_peak_live_heap_bytes_per_op",
        "peak_vs_v1_percent",
        "raw_peak_live_heap_bytes_per_op",
        "peak_harness_noise_bytes",
        "peak_grid_bytes_before",
        "peak_gc_samples_per_op",
        "peak_target_samples",
        "peak_sample_step_bytes",
        "peak_discarded_ops",
        "peak_removed_cells_per_op",
        "runtime_ns_per_op",
        "runtime_ci_low_ns_per_op",
        "runtime_ci_high_ns_per_op",
        "jmh_version",
        "jdk_version",
        "vm_name",
        "vm_version",
    ]
    for row in rows:
        baseline = baseline_by_difficulty.get(row["difficulty"])
        row["allocated_vs_v1_percent"] = (
            (row["allocated_b_per_op"] / baseline - 1) * 100
            if baseline
            else None
        )
        retained_baseline = retained_baseline_by_difficulty.get(row["difficulty"])
        row["retained_vs_v1_percent"] = (
            (row["retained_graph_bytes_per_op"] / retained_baseline - 1) * 100
            if retained_baseline
            else None
        )
        peak_baseline = peak_baseline_by_difficulty.get(row["difficulty"])
        row["peak_vs_v1_percent"] = (
            (row["peak_live_heap_bytes_per_op"] / peak_baseline - 1) * 100
            if peak_baseline
            else None
        )

    csv_path = output_dir / "memory-analysis-summary.csv"
    with csv_path.open("w", newline="", encoding="utf-8-sig") as file:
        writer = csv.DictWriter(file, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)

    allocation_chart = svg_chart(
        rows,
        "allocated_b_per_op",
        "Allocated bytes per puzzle reduction",
        "Allocated bytes / operation (B/op)",
        lambda value: format_num(value, 0),
    )
    runtime_chart = svg_chart(
        rows,
        "runtime_ns_per_op",
        "Average time per puzzle reduction",
        "Nanoseconds / operation",
        lambda value: format_num(value, 0),
    )
    retained_chart = svg_chart(
        rows,
        "retained_graph_bytes_per_op",
        "Reachable grid memory after puzzle reduction",
        "Reachable bytes from grid (JOL estimate)",
        lambda value: format_num(value, 0),
    )
    peak_chart = svg_chart(
        rows,
        "peak_live_heap_bytes_per_op",
        "Peak live heap during puzzle reduction",
        "Peak live bytes held by the grid",
        lambda value: format_num(value, 0),
    )

    table_rows = []
    for row in rows:
        difference = row["allocated_vs_v1_percent"]
        difference_text = "baseline" if difference is None else f"{difference:+.1f}%"
        retained_difference = row["retained_vs_v1_percent"]
        retained_difference_text = (
            "baseline"
            if retained_difference is None
            else f"{retained_difference:+.1f}%"
        )
        peak_difference = row["peak_vs_v1_percent"]
        peak_difference_text = (
            "baseline"
            if peak_difference is None
            else f"{peak_difference:+.1f}%"
        )
        low = row["runtime_ci_low_ns_per_op"]
        high = row["runtime_ci_high_ns_per_op"]
        confidence_text = (
            f"{format_num(low, 0)}–{format_num(high, 0)}"
            if low is not None and high is not None
            else "n/a"
        )
        table_rows.append(
            "<tr>"
            f"<td>V{row['version']}</td>"
            f"<td>{row['difficulty']}</td>"
            f"<td>{row['samples']}</td>"
            f"<td>{format_num(row['allocated_b_per_op'], 0)}</td>"
            f"<td>{format_num(row['median_allocated_b_per_op'], 0)}</td>"
            f"<td>{format_num(row['p95_allocated_b_per_op'], 0)}</td>"
            f"<td>{html.escape(difference_text)}</td>"
            f"<td>{format_num(row['retained_graph_bytes_per_op'], 0)}</td>"
            f"<td>{format_num(row['median_retained_graph_bytes_per_op'], 0)}</td>"
            f"<td>{format_num(row['p95_retained_graph_bytes_per_op'], 0)}</td>"
            f"<td>{html.escape(retained_difference_text)}</td>"
            f"<td>{row['peak_samples']}</td>"
            f"<td>{format_num(row['peak_live_heap_bytes_per_op'], 0)}</td>"
            f"<td>{format_num(row['median_peak_live_heap_bytes_per_op'], 0)}</td>"
            f"<td>{format_num(row['p95_peak_live_heap_bytes_per_op'], 0)}</td>"
            f"<td>{html.escape(peak_difference_text)}</td>"
            f"<td>{format_num(row['peak_grid_bytes_before'], 0)}</td>"
            f"<td>{format_num(row['peak_harness_noise_bytes'], 0)}</td>"
            f"<td>{row['peak_gc_samples_per_op']:,.1f}</td>"
            f"<td>{format_num(row['peak_sample_step_bytes'], 0)}</td>"
            f"<td>{row['peak_discarded_ops']}</td>"
            f"<td>{format_num(row['runtime_ns_per_op'], 0)}</td>"
            f"<td>{html.escape(confidence_text)}</td>"
            f"<td>{row['removed_cells_per_op']:.2f}</td>"
            f"<td>{row['retained_removed_cells_per_op']:.2f}</td>"
            f"<td>{row['peak_removed_cells_per_op']:.2f}</td>"
            "</tr>"
        )

    peak_target_text = ", ".join(
        str(target) for target in sorted({row["peak_target_samples"] for row in rows})
    )
    convergence_html = convergence_section(rows, convergence)

    jdk_versions = ", ".join(sorted({row["jdk_version"] for row in rows if row["jdk_version"]}))
    jmh_versions = ", ".join(sorted({row["jmh_version"] for row in rows if row["jmh_version"]}))
    report = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Sudoku memory analysis report</title>
<style>
body{{font:15px/1.5 system-ui,Segoe UI,Arial,sans-serif;color:#172033;max-width:1200px;margin:32px auto;padding:0 20px}}
h1,h2{{line-height:1.2}} .note{{background:#eff6ff;border-left:4px solid #2563eb;padding:12px 16px}}
.chart{{width:100%;height:auto;margin:8px 0 24px}} .chart-title{{font:bold 18px system-ui}} .tick{{font:12px system-ui;fill:#334155}}
.axis-label{{font:13px system-ui;fill:#172033}} .axis{{stroke:#475569;stroke-width:1}} .grid{{stroke:#e2e8f0;stroke-width:1}}
.table-wrap{{overflow-x:auto}} table{{border-collapse:collapse;width:100%;font-size:13px;white-space:nowrap}}
th,td{{border:1px solid #cbd5e1;padding:7px 9px;text-align:right}} th{{position:sticky;top:0;background:#f1f5f9}}
th:first-child,td:first-child{{text-align:left}} tbody tr:nth-child(even){{background:#f8fafc}}
code{{background:#f1f5f9;padding:2px 4px}} footer{{margin-top:24px;color:#475569;font-size:13px}}
</style>
</head>
<body>
<h1>Sudoku memory analysis</h1>
<p>JMH {html.escape(jmh_versions or "unknown")} · {html.escape(jdk_versions or "JDK unknown")} · {len(rows)} version/difficulty cases</p>
<p class="note"><strong>How to read this:</strong> B/op is the total bytes allocated inside <code>playable()</code>, divided by operations. Retained graph bytes are measured after reduction by traversing the Sudoku grid's reachable object graph with JOL; this includes reachable grid state such as caches, but excludes unrelated JVM heap objects and is not peak heap. Retained graph sampling runs as a separate benchmark with grid creation, loading, reduction, and graph measurement outside the reported operation time. The runtime and allocation series come from the separate allocation benchmark. Both benchmarks use the same solved grid and target difficulty, but their random seed sequences differ; compare actual cells removed in both columns. V1 is the baseline at the same difficulty.</p>
<p class="note"><strong>Peak live heap:</strong> the most memory the grid held at one time during <code>playable()</code>, including the grid itself and any cache, so it can be compared with reachable memory after the operation. It is measured in its own benchmark, in a JVM using the Serial collector set to compact fully on every full GC (<code>-XX:MarkSweepDeadRatio=0</code>, <code>-XX:MarkSweepAlwaysCompactCount=1</code>). Every sample is a full GC, which then leaves only reachable objects, so each sample is the exact live heap at that moment. A sampler thread runs one each time <code>playable()</code> has allocated a fixed number of bytes, aiming for about {html.escape(peak_target_text)} samples per operation (at least every 8 KB; the step doubles if an input allocates much more than the calibration run). A full GC right before the operation is the baseline and one right after it is the final sample. Value = grid size before the operation (JOL, as in the retained benchmark) + highest sample − baseline − harness noise. Harness noise comes from a control run (<code>harnessNoise</code>) that does every measurement step except calling <code>playable()</code>. An operation is discarded if the heap's growth from baseline to end does not match the grid's own growth within 1 KB, because then something else in the JVM changed the heap during it. A high point between two samples can be missed, which can only make a value too low; the sampling check below shows how much the value changes with fewer samples.</p>
<h2>Allocation</h2>
{allocation_chart}
<h2>Reachable memory after operation</h2>
{retained_chart}
<h2>Peak live heap during operation</h2>
{peak_chart}
{convergence_html}
<h2>Runtime</h2>
{runtime_chart}
<h2>Detailed results</h2>
<div class="table-wrap"><table>
<thead><tr><th>Version</th><th>Difficulty</th><th>Allocation iterations</th><th>Mean B/op</th><th>Median B/op</th><th>P95 B/op</th><th>Allocation vs V1</th><th>Retained samples</th><th>Mean reachable bytes</th><th>Median reachable bytes</th><th>P95 reachable bytes</th><th>Retained vs V1</th><th>Peak iterations</th><th>Mean peak live bytes</th><th>Median peak live bytes</th><th>P95 peak live bytes</th><th>Peak vs V1</th><th>Grid bytes before</th><th>Harness noise subtracted</th><th>Full-GC samples/op</th><th>Bytes allocated between samples (start)</th><th>Discarded ops</th><th>Mean ns/op</th><th>Runtime CI</th><th>Cells removed/op (allocation run)</th><th>Cells removed/sample (retained run)</th><th>Cells removed/op (peak run)</th></tr></thead>
<tbody>{''.join(table_rows)}</tbody>
</table></div>
<footer>CSV data: memory-analysis-summary.csv. JOL reachable-graph sizes are object-layout estimates from the Sudoku grid root, not total process heap or a minimum device-RAM requirement. Differences describe measured output, not the cause.</footer>
</body>
</html>
"""
    html_path = output_dir / "memory-analysis-report.html"
    html_path.write_text(report, encoding="utf-8")
    return csv_path, html_path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input",
        type=Path,
        default=Path("build/results/jmh/results.json"),
        help="JMH allocation results file (default: build/results/jmh/results.json)",
    )
    parser.add_argument(
        "--retained-input",
        type=Path,
        help="Optional separate JMH JSON file containing retained-memory results",
    )
    parser.add_argument(
        "--peak-input",
        type=Path,
        help="Optional separate JMH JSON file containing peak live heap results; "
        "when given, peak results in the other input files are ignored",
    )
    parser.add_argument(
        "--peak-convergence-input",
        type=Path,
        nargs="*",
        default=[],
        help="Optional JMH JSON files from -PpeakSamples runs, shown in the sampling check",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("build/reports/jmh"),
        help="Directory for CSV and HTML reports (default: build/reports/jmh)",
    )
    args = parser.parse_args()

    def load_records(input_path):
        with input_path.open(encoding="utf-8") as file:
            loaded_records = json.load(file)
        if not isinstance(loaded_records, list):
            raise ValueError(f"{input_path} must contain a JSON array.")
        return loaded_records

    def is_peak(record):
        return "SudokuPeakLiveHeapBenchmark." in record.get("benchmark", "")

    try:
        records = []
        input_paths = [args.input]
        if args.retained_input:
            input_paths.append(args.retained_input)
        for input_path in input_paths:
            records.extend(load_records(input_path))
        if args.peak_input:
            # A separate peak file replaces any older peak results in the main input.
            records = [record for record in records if not is_peak(record)]
            records.extend(load_records(args.peak_input))
        if not isinstance(records, list) or not records:
            raise ValueError("JMH results file must contain a non-empty JSON array.")

        peak_by_target = collect_peak_rows([r for r in records if is_peak(r)])
        if len(peak_by_target) > 1:
            raise ValueError(
                "Main peak results mix sample counts "
                f"{sorted(peak_by_target)}; pass extra runs with --peak-convergence-input."
            )
        peak_rows = next(iter(peak_by_target.values()), {})

        convergence = {}
        for target, rows_by_case in peak_by_target.items():
            convergence[target] = rows_by_case
        for input_path in args.peak_convergence_input:
            extra = collect_peak_rows([r for r in load_records(input_path) if is_peak(r)])
            if not extra:
                raise ValueError(f"{input_path} contains no peak live heap results.")
            for target, rows_by_case in extra.items():
                convergence.setdefault(target, {}).update(rows_by_case)
        if len(convergence) < 2:
            convergence = {}

        allocation_rows = {}
        retained_rows = {}
        for record in records:
            benchmark = record.get("benchmark", "")
            if "SudokuAllocationBenchmark.playable" in benchmark:
                row = summarize_record(record)
                allocation_rows[(row["version"], row["difficulty"])] = row
            elif "SudokuRetainedMemoryBenchmark.measureRetainedGraph" in benchmark:
                row = summarize_retained_record(record)
                retained_rows[(row["version"], row["difficulty"])] = row

        if not allocation_rows:
            raise ValueError("No SudokuAllocationBenchmark.playable results found.")
        if not retained_rows:
            raise ValueError("No SudokuRetainedMemoryBenchmark.measureRetainedGraph results found.")
        if not peak_rows:
            raise ValueError(
                "No SudokuPeakLiveHeapBenchmark.playable results found. "
                "Run '.\\gradlew.bat jmh -PpeakOnly' and pass --peak-input build\\results\\jmh\\peak.json."
            )

        missing_retained = allocation_rows.keys() - retained_rows.keys()
        missing_allocation = retained_rows.keys() - allocation_rows.keys()
        if missing_retained or missing_allocation:
            raise ValueError(
                "Allocation and retained-memory results must cover the same version/difficulty cases. "
                f"Missing retained cases: {sorted(missing_retained)}; "
                f"missing allocation cases: {sorted(missing_allocation)}."
            )
        missing_peak = allocation_rows.keys() - peak_rows.keys()
        extra_peak = peak_rows.keys() - allocation_rows.keys()
        if missing_peak or extra_peak:
            raise ValueError(
                "Allocation and peak live heap results must cover the same version/difficulty cases. "
                f"Missing peak cases: {sorted(missing_peak)}; "
                f"peak cases without allocation results: {sorted(extra_peak)}."
            )

        rows = []
        for key, row in allocation_rows.items():
            row.update(
                {
                    name: value
                    for name, value in retained_rows[key].items()
                    if name not in ("version", "difficulty")
                }
            )
            row.update(
                {
                    name: value
                    for name, value in peak_rows[key].items()
                    if name not in ("version", "difficulty")
                }
            )
            rows.append(row)

        csv_path, html_path = write_report(rows, args.output_dir, convergence)
    except (OSError, json.JSONDecodeError, KeyError, TypeError, ValueError) as error:
        print(f"Could not create JMH report: {error}", file=sys.stderr)
        return 1

    print(f"CSV summary: {csv_path.resolve()}")
    print(f"HTML report: {html_path.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
