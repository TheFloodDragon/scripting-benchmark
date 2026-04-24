package cn.altawk.test.scripting

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.TimeUnit

/**
 * JMH 基准入口。
 *
 * `engineName` 与 `scriptCaseId` 由 [ScriptBenchmarkMain] 通过 `OptionsBuilder.param(...)` 动态传入，
 * 因此新增引擎或脚本样本时只需要维护 [SCRIPT_ENGINE_ADAPTERS] / [BENCHMARK_SCRIPT_CASES]。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
open class ScriptEngineJmhBenchmark {

    /** 每个 JMH 线程独立持有脚本样本与引擎适配器，避免状态互相污染。 */
    @State(Scope.Thread)
    open class ScriptState {

        // JMH 注解处理器要求 @Param 必须有默认值；实际值由启动入口动态覆盖。
        @JvmField
        @Param("__dynamic_engine__")
        var engineName: String = "__dynamic_engine__"

        @JvmField
        @Param("__dynamic_case__")
        var scriptCaseId: String = "__dynamic_case__"

        protected lateinit var engineAdapter: ScriptEngineAdapter<Any>
        protected lateinit var scriptCase: BenchmarkScriptCase
        protected lateinit var sample: ScriptSample

        protected fun initialize() {
            engineAdapter = SCRIPT_ENGINE_ADAPTERS.byEngineName(engineName)
            scriptCase = BENCHMARK_SCRIPT_CASES.byCaseId(scriptCaseId)
            sample = scriptCase.sampleFor(engineAdapter)
        }
    }

    /** 单独衡量 compile()：计时结束后会执行一次释放，避免堆积上下文/类加载器。 */
    @State(Scope.Thread)
    open class CompileState : ScriptState() {
        private var compiled: Any? = null

        @Setup(Level.Trial)
        fun setup() = initialize()

        fun compile(): Any = engineAdapter.compile(sample).also { compiled = it }

        @TearDown(Level.Invocation)
        fun disposeCompiled() {
            compiled?.let(engineAdapter::disposeCompiled)
            compiled = null
        }
    }

    /** 先编译一次，再重复衡量已编译脚本的执行耗时。 */
    @State(Scope.Thread)
    open class CompiledExecutionState : ScriptState() {
        private lateinit var compiled: Any

        @Setup(Level.Trial)
        fun setup() {
            initialize()
            compiled = engineAdapter.compile(sample)
        }

        @TearDown(Level.Trial)
        fun tearDown() {
            if (::compiled.isInitialized) engineAdapter.disposeCompiled(compiled)
        }

        fun runCompiled(): Any? = engineAdapter.runCompiled(compiled)
    }

    /** 每次 invocation 都走源码解释路径，用于衡量未缓存/未预编译场景。 */
    @State(Scope.Thread)
    open class InterpretedExecutionState : ScriptState() {
        @Setup(Level.Trial)
        fun setup() = initialize()

        fun interpret(): Any? = engineAdapter.interpret(sample)
    }

    @Benchmark
    open fun compile(state: CompileState): Any = state.compile()

    @Benchmark
    open fun compiledExecution(state: CompiledExecutionState): Any? = state.runCompiled()

    @Benchmark
    open fun interpretedExecution(state: InterpretedExecutionState): Any? = state.interpret()
}

@Suppress("UNCHECKED_CAST")
private fun List<ScriptEngineAdapter<out Any>>.byEngineName(engineName: String): ScriptEngineAdapter<Any> =
    (firstOrNull { it.engineName == engineName }
        ?: error("未知脚本引擎: $engineName，可选值: ${joinToString { it.engineName }}")) as ScriptEngineAdapter<Any>

private fun List<BenchmarkScriptCase>.byCaseId(id: String): BenchmarkScriptCase =
    firstOrNull { it.id == id } ?: error("未知脚本样本: $id，可选值: ${joinToString { it.id }}")
