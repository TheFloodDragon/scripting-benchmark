#!/usr/bin/env python3
import argparse
import datetime as dt
import json
import math
from collections import defaultdict
from pathlib import Path


PHASES = {
    "compile": {"title": "编译测试", "unit": "ms"},
    "compiledExecution": {"title": "编译运行测试", "unit": "µs"},
    "interpretedExecution": {"title": "解释运行测试", "unit": "ms"},
}

CASE_NAMES = {
    "compute": "数值累加",
    "branching": "条件分支",
    "nested-loop": "嵌套循环",
    "list-index": "列表索引访问",
    "list-build": "列表构建",
    "map-build": "映射构建",
    "string-build": "字符串构建",
    "variable-expression": "变量计算（复杂表达式）",
    "host-class-access": "Java API 类元数据访问",
    "host-instance-field-read": "Java API 实例字段读取",
    "host-static-field-read": "Java API 静态字段读取",
    "host-instance-method-call": "Java API 实例方法调用",
    "host-static-method-call": "Java API 静态方法调用",
}

ENGINE_SAMPLES = {
    "GraalJS": ("javascript", ".js"),
    "Nashorn": ("javascript", ".js"),
    "Jexl": ("jexl", ".jexl"),
    "KotlinScripting": ("kotlin", ".kts"),
    "Fluxon": ("fluxon", ".fs"),
}

UNIT_TO_US = {
    "ns/op": 0.001,
    "us/op": 1.0,
    "µs/op": 1.0,
    "ms/op": 1_000.0,
    "s/op": 1_000_000.0,
}

DISPLAY_UNIT_TO_US = {
    "µs": 1.0,
    "ms": 1_000.0,
}


def to_display(value, source_unit, display_unit):
    if value is None:
        return 0.0
    try:
        number = float(value)
    except (TypeError, ValueError):
        return 0.0
    if not math.isfinite(number):
        return 0.0
    return number * UNIT_TO_US.get(source_unit, 1.0) / DISPLAY_UNIT_TO_US[display_unit]


def percentile(metric, key):
    return (metric.get("scorePercentiles") or {}).get(key)


def sample_path(engine, case):
    directory, extension = ENGINE_SAMPLES.get(engine, (engine.lower(), ""))
    return f"/samples/{directory}/{case}{extension}"


def resolve_input_files(inputs):
    files = []
    for raw_input in inputs:
        input_path = Path(raw_input)
        if input_path.is_dir():
            files.extend(sorted(path for path in input_path.rglob("*.json") if path.is_file()))
        elif input_path.is_file():
            files.append(input_path)
        else:
            raise SystemExit(f"找不到 JMH 结果文件或目录: {input_path}")

    unique_files = []
    seen = set()
    for file in files:
        key = file.resolve()
        if key in seen:
            continue
        seen.add(key)
        unique_files.append(file)

    if not unique_files:
        raise SystemExit("没有找到任何 JMH JSON 结果文件")
    return unique_files


def load_jmh_results(input_files):
    data = []
    for input_file in input_files:
        content = json.loads(input_file.read_text(encoding="utf-8"))
        if not isinstance(content, list):
            raise SystemExit(f"JMH JSON 根节点必须是数组: {input_file}")
        for item in content:
            if not isinstance(item, dict):
                raise SystemExit(f"JMH JSON 条目必须是对象: {input_file}")
            item = dict(item)
            item["_resultFile"] = str(input_file)
            data.append(item)
    return data


def parse_rows(data):
    rows = []
    for item in data:
        metric = item.get("primaryMetric", {}) or {}
        params = item.get("params", {}) or {}
        benchmark = item.get("benchmark", "")
        phase = benchmark.rsplit(".", 1)[-1]
        display_unit = PHASES.get(phase, {}).get("unit", "µs")
        source_unit = metric.get("scoreUnit", "us/op")
        score = to_display(metric.get("score"), source_unit, display_unit)
        rows.append({
            "phase": phase,
            "case": params.get("scriptCaseId", ""),
            "engine": params.get("engineName", ""),
            "score": score,
            "error": to_display(metric.get("scoreError"), source_unit, display_unit),
            "best": to_display(percentile(metric, "0.0"), source_unit, display_unit),
            "p50": to_display(percentile(metric, "50.0"), source_unit, display_unit),
            "p90": to_display(percentile(metric, "90.0"), source_unit, display_unit),
            "worst": to_display(percentile(metric, "100.0"), source_unit, display_unit),
            "unit": display_unit,
            "sample": sample_path(params.get("engineName", ""), params.get("scriptCaseId", "")),
            "forks": item.get("forks", 0),
            "warmup": item.get("warmupIterations", 0),
            "measure": item.get("measurementIterations", 0),
            "result_file": item.get("_resultFile", ""),
        })
    return rows


def append_input_files(lines, input_files):
    lines.append(f"- JMH 结果文件：`{len(input_files)}` 个")
    if len(input_files) == 1:
        lines.append(f"- 结果路径：`{input_files[0]}`")
        return

    lines.extend([
        "",
        "<details>",
        "<summary>结果文件列表</summary>",
        "",
    ])
    for input_file in input_files:
        lines.append(f"- `{input_file}`")
    lines.extend(["", "</details>"])


def append_matrix_overview(lines, rows, input_file_count):
    if input_file_count <= 1:
        return

    counts = defaultdict(lambda: defaultdict(int))
    for row in rows:
        counts[row["engine"]][row["phase"]] += 1

    phase_order = ["compile", "compiledExecution", "interpretedExecution"]
    lines.extend([
        "",
        "## 矩阵汇总",
        "",
        f"- 合并结果条目：`{len(rows)}` 条",
        "",
        "| 引擎 | 编译测试 | 编译运行测试 | 解释运行测试 |",
        "|---|---:|---:|---:|",
    ])
    for engine in sorted(counts):
        phase_counts = [counts[engine].get(phase, 0) for phase in phase_order]
        lines.append(
            f"| {engine or '未知'} "
            f"| {phase_counts[0] or '-'} "
            f"| {phase_counts[1] or '-'} "
            f"| {phase_counts[2] or '-'} |"
        )
    lines.append("")


def append_report(lines, rows):
    if not rows:
        lines.append("没有可展示的 JMH 结果。")
        return

    grouped = defaultdict(lambda: defaultdict(list))
    for row in rows:
        grouped[row["phase"]][row["case"]].append(row)

    for phase in ["compile", "compiledExecution", "interpretedExecution"]:
        if phase not in grouped:
            continue
        phase_rows = [row for case_rows in grouped[phase].values() for row in case_rows]
        settings = phase_rows[0]
        title = PHASES.get(phase, {}).get("title", phase)
        lines.extend([
            f"## {title}",
            "",
            f"forks={settings['forks']}, warmup={settings['warmup']}, measure={settings['measure']}",
            "",
        ])

        for case in sorted(grouped[phase], key=lambda case_id: CASE_NAMES.get(case_id, case_id)):
            case_rows = sorted(grouped[phase][case], key=lambda row: (row["score"], row["engine"]))
            fastest = case_rows[0]["score"] if case_rows else 0.0
            lines.extend([
                f"### {CASE_NAMES.get(case, case)}",
                "",
                "| 排名 | 引擎 | score±err | best/p50/p90 | worst | 相对最快 | 样本 |",
                "|---:|---|---:|---:|---:|---:|---|",
            ])
            for index, row in enumerate(case_rows, start=1):
                ratio = row["score"] / fastest if fastest else 1.0
                lines.append(
                    f"| {index} "
                    f"| {row['engine']} "
                    f"| {row['score']:.3f}±{row['error']:.3f} "
                    f"| {row['best']:.3f}/{row['p50']:.3f}/{row['p90']:.3f} "
                    f"| {row['worst']:.3f} {row['unit']} "
                    f"| {ratio:.2f}x "
                    f"| `{row['sample']}` |"
                )
            lines.append("")


def main():
    parser = argparse.ArgumentParser(description="将一个或多个 JMH JSON 结果转换为中文 Markdown 报告。")
    parser.add_argument(
        "--input",
        nargs="+",
        default=["build/reports/jmh/results.json"],
        help="JMH JSON 结果文件或目录；可传入多个路径，目录会递归读取 *.json",
    )
    parser.add_argument("--output", default="build/reports/jmh/report.md", help="Markdown 报告输出路径")
    parser.add_argument("--jfr", default="false", help="是否开启 JFR")
    parser.add_argument("--args", default="", help="基准测试启动参数")
    args = parser.parse_args()

    input_files = resolve_input_files(args.input)
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    data = load_jmh_results(input_files)
    rows = parse_rows(data)

    lines = [
        "# 脚本引擎 JMH 性能测试报告",
        "",
        f"- 生成时间：`{dt.datetime.now(dt.UTC).replace(microsecond=0).isoformat()}`",
        f"- 是否开启 JFR：`{args.jfr}`",
        f"- JMH 参数：`{args.args or '默认参数'}`",
    ]
    append_input_files(lines, input_files)
    lines.append("")
    append_matrix_overview(lines, rows, len(input_files))
    append_report(lines, rows)

    output_path.write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    main()
