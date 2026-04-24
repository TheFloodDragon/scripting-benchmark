# Scripting Benchmark

基于 JMH 的 JVM 脚本引擎性能基准测试项目，用于横向对比多种脚本/表达式引擎在编译、已编译执行、解释执行等场景下的表现。

## 支持的脚本引擎

- GraalJS
- Nashorn
- Jexl
- Kotlin Scripting
- Fluxon

## 测试场景

当前内置的跨引擎样本包括：

- 数值累加：`compute`
- 条件分支：`branching`
- 嵌套循环：`nested-loop`
- 列表索引访问：`list-index`
- 列表构建：`list-build`
- 映射构建：`map-build`
- 字符串构建：`string-build`
- 复杂变量表达式：`variable-expression`
- Java API 类元数据访问：`host-class-access`
- Java API 实例字段读取：`host-instance-field-read`
- Java API 静态字段读取：`host-static-field-read`
- Java API 实例方法调用：`host-instance-method-call`
- Java API 静态方法调用：`host-static-method-call`

## 基准阶段

- `compile`：只衡量脚本编译/预处理耗时。
- `compiledExecution`：先编译一次，再衡量已编译脚本的执行耗时。
- `interpretedExecution`：每次直接解释执行源码。
- `all`：运行全部阶段。

## 可用参数

| 参数 | 说明 |
|---|---|
| `--quick` | 使用较少迭代快速运行 |
| `--jfr` | 为 JMH fork JVM 开启 Java Flight Recorder |
| `--sampleIterations <n>` | 设置每个脚本样本内部循环次数，默认 `2000` |
| `--forks <n>` | 覆盖 JMH fork 次数 |
| `--warmup <n>` | 覆盖 JMH warmup 迭代次数 |
| `--measure <n>` | 覆盖 JMH measurement 迭代次数 |
| `--engine <regex>` | 筛选脚本引擎 |
| `--case <regex>` | 筛选脚本样本 |
| `--phase <phase>` | 筛选阶段：`compile` / `compiledExecution` / `interpretedExecution` / `all` |

## 结果输出

JMH JSON 结果默认输出到：

```text
build/reports/jmh/results.json
```

Markdown 报告可通过脚本生成：

```bash
python scripts/generate-jmh-report.py \
  --input build/reports/jmh/results.json \
  --output build/reports/jmh/report.md
```

生成的报告会按阶段和测试场景汇总各引擎排名、分位数和相对最快倍数。
