package cn.altawk.test.scripting

import org.openjdk.jmh.results.format.ResultFormatType
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder
import org.openjdk.jmh.runner.options.OptionsBuilder
import org.openjdk.jmh.runner.options.TimeValue
import java.io.File

const val JMH_RESULT_PATH = "build/reports/jmh/results.json"
private val ENGINE_NAMES get() = SCRIPT_ENGINE_ADAPTERS.map { it.engineName }
private val SCRIPT_CASE_IDS get() = BENCHMARK_SCRIPT_CASES.map { it.id }

/** 命令行入口：解析自定义参数，然后用 JMH Runner 执行实际基准。 */
fun main(args: Array<String>) {
    val cli = BenchmarkCli.parse(args)
    System.setProperty(SAMPLE_ITERATIONS_PROPERTY, cli.sampleIterations.toString())
    File(JMH_RESULT_PATH).parentFile?.mkdirs()

    val options = OptionsBuilder()
        .include(ScriptEngineJmhBenchmark::class.java.name + ".*" + cli.phaseRegex)
        .detectJvmArgs()
        .shouldFailOnError(true)
        .result(JMH_RESULT_PATH)
        .resultFormat(ResultFormatType.JSON)
        .param("engineName", *(cli.engineValues ?: ENGINE_NAMES).toTypedArray())
        .param("scriptCaseId", *(cli.caseValues ?: SCRIPT_CASE_IDS).toTypedArray())
        .applyCliMode(cli)
        .build()

    Runner(options).run()
}

private val benchmarkForkJvmArgs = arrayOf(
    "--enable-native-access=ALL-UNNAMED",
    "--sun-misc-unsafe-memory-access=allow",
)

private fun <T : ChainedOptionsBuilder> T.applyCliMode(cli: BenchmarkCli): T = apply {
    jvmArgsAppend(*benchmarkForkJvmArgs)
    jvmArgsAppend("-D$SAMPLE_ITERATIONS_PROPERTY=${cli.sampleIterations}")
    if (cli.jfr) {
        // 追加给 JMH fork JVM，而不是当前 Gradle/启动进程；退出时自动落盘到工作目录。
        jvmArgsAppend("-XX:StartFlightRecording=settings=profile,disk=true,dumponexit=true")
    }
    if (cli.quick) {
        forks(1)
        warmupIterations(2)
        measurementIterations(3)
        timeout(TimeValue.seconds(30))
    }
    cli.forks?.let(::forks)
    cli.warmupIterations?.let(::warmupIterations)
    cli.measurementIterations?.let(::measurementIterations)
}

private data class BenchmarkCli(
    val quick: Boolean = false,
    val jfr: Boolean = false,
    val sampleIterations: Int = DEFAULT_SAMPLE_ITERATIONS,
    val forks: Int? = null,
    val warmupIterations: Int? = null,
    val measurementIterations: Int? = null,
    val engineValues: List<String>? = null,
    val caseValues: List<String>? = null,
    val phaseRegex: String = ".*",
) {
    companion object {
        fun parse(args: Array<String>): BenchmarkCli {
            var quick = false
            var jfr = false
            var sampleIterations = DEFAULT_SAMPLE_ITERATIONS
            var forks: Int? = null
            var warmupIterations: Int? = null
            var measurementIterations: Int? = null
            var engineValues: List<String>? = null
            var caseValues: List<String>? = null
            var phaseRegex = ".*"
            val reader = ArgumentReader(args)

            while (reader.hasNext()) {
                when (val option = reader.next()) {
                    "--quick" -> quick = true
                    "--jfr" -> jfr = true
                    "--engine" ->
                        engineValues = ENGINE_NAMES.filterByPattern(reader.value(option), option, "引擎")
                    "--case" ->
                        caseValues = SCRIPT_CASE_IDS.filterByPattern(reader.value(option), option, "脚本样本")
                    "--phase" -> phaseRegex = phaseRegexOf(reader.value(option))
                    "--sampleIterations" -> sampleIterations = positiveInt(reader.value(option), option)
                    "--forks" -> forks = positiveInt(reader.value(option), option)
                    "--warmup" -> warmupIterations = positiveInt(reader.value(option), option)
                    "--measure" -> measurementIterations = positiveInt(reader.value(option), option)
                    "--help", "-h" -> printUsageAndExit()
                    else -> error("未知参数: $option，使用 --help 查看用法")
                }
            }

            return BenchmarkCli(
                quick = quick,
                jfr = jfr,
                sampleIterations = sampleIterations,
                forks = forks,
                warmupIterations = warmupIterations,
                measurementIterations = measurementIterations,
                engineValues = engineValues,
                caseValues = caseValues,
                phaseRegex = phaseRegex,
            )
        }

        private fun positiveInt(value: String, option: String): Int {
            val parsed = value.toIntOrNull()
            require(parsed != null && parsed > 0) { "$option 必须是正整数" }
            return parsed
        }

        private fun List<String>.filterByPattern(pattern: String, option: String, label: String): List<String> {
            val regex = Regex(pattern)
            val values = filter(regex::matches)
            require(values.isNotEmpty()) { "$option 没有匹配任何$label：$pattern" }
            return values
        }

        private fun phaseRegexOf(value: String): String = when (value) {
            "compile" -> "compile"
            "compiled", "compiledExecution", "runCompiled" -> "compiledExecution"
            "interpret", "interpreted", "interpretedExecution" -> "interpretedExecution"
            "all" -> ".*"
            else -> error("--phase 仅支持 compile / compiledExecution / interpretedExecution / all")
        }

        private fun printUsageAndExit(): Nothing {
            println(
                """
                用法：
                  ./gradlew runScriptBenchmark [-PjmhArgs="参数..."]
                  java -jar build/libs/scripting-benchmark-1.0.0-benchmark.jar [参数...]

                参数：
                  --quick                         使用较少迭代快速冒烟测试
                  --jfr                           为 JMH fork JVM 开启 Java Flight Recorder
                  --sampleIterations <n>          设置每个脚本样本内部循环次数，默认 $DEFAULT_SAMPLE_ITERATIONS
                  --forks <n>                     覆盖 JMH fork 次数
                  --warmup <n>                    覆盖 JMH warmup 迭代次数
                  --measure <n>                   覆盖 JMH measurement 迭代次数
                  --engine <regex>                筛选引擎；精确名称也可直接写：${ENGINE_NAMES.joinToString()}
                  --case <regex>                  筛选脚本样本；精确 ID 也可直接写：${SCRIPT_CASE_IDS.joinToString()}
                  --phase <phase>                 compile / compiledExecution / interpretedExecution / all
                """.trimIndent()
            )
            kotlin.system.exitProcess(0)
        }
    }
}

private class ArgumentReader(private val args: Array<String>) {
    private var index = 0

    fun hasNext(): Boolean = index < args.size

    fun next(): String = args[index++]

    fun value(option: String): String = args.getOrNull(index++) ?: error("$option 需要一个值")
}
