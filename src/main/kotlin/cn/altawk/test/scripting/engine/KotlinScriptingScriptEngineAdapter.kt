package cn.altawk.test.scripting.engine

import cn.altawk.test.scripting.ScriptEngineAdapter
import cn.altawk.test.scripting.ScriptSample
import kotlin.script.experimental.api.CompiledScript as KotlinCompiledScript
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.providedProperties
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

/** Kotlin Scripting 适配器；使用 BasicJvmScriptingHost 编译/执行 .kts 样本。 */
object KotlinScriptingScriptEngineAdapter : ScriptEngineAdapter<KotlinPreparedScript> {

    override val engineName: String = "KotlinScripting"
    override val sampleDirectory: String = "kotlin"
    override val sampleExtension: String = ".kts"

    override fun compile(sample: ScriptSample): KotlinPreparedScript {
        val bindings = sample.bindingsFactory()
        val compiled = BenchmarkKtsHost.compile(
            sample.toSourceCode(),
            benchmarkCompilationConfiguration(bindings),
        ).valueOrThrow()
        return KotlinPreparedScript(compiled, benchmarkEvaluationConfiguration(bindings))
    }

    override fun runCompiled(compiled: KotlinPreparedScript): Any? {
        return BenchmarkKtsHost.evalCompiled(compiled.script, compiled.evaluationConfiguration)
            .valueOrThrow()
            .returnValue
            .unwrap()
    }

    override fun interpret(sample: ScriptSample): Any? {
        val bindings = sample.bindingsFactory()
        return BenchmarkKtsHost.evalSource(
            sample.toSourceCode(),
            benchmarkCompilationConfiguration(bindings),
            benchmarkEvaluationConfiguration(bindings),
        ).valueOrThrow()
            .returnValue
            .unwrap()
    }
}

data class KotlinPreparedScript(
    val script: KotlinCompiledScript,
    val evaluationConfiguration: ScriptEvaluationConfiguration,
)

private object BenchmarkKtsHost : BasicJvmScriptingHost() {

    fun compile(
        script: SourceCode,
        compilationConfiguration: ScriptCompilationConfiguration,
    ): ResultWithDiagnostics<KotlinCompiledScript> = runInCoroutineContext {
        compiler(script, compilationConfiguration)
    }

    fun evalCompiled(
        compiled: KotlinCompiledScript,
        evaluationConfiguration: ScriptEvaluationConfiguration,
    ): ResultWithDiagnostics<EvaluationResult> = runInCoroutineContext {
        evaluator(compiled, evaluationConfiguration)
    }

    fun evalSource(
        script: SourceCode,
        compilationConfiguration: ScriptCompilationConfiguration,
        evaluationConfiguration: ScriptEvaluationConfiguration,
    ): ResultWithDiagnostics<EvaluationResult> = runInCoroutineContext {
        eval(script, compilationConfiguration, evaluationConfiguration)
    }
}

private fun ScriptSample.toSourceCode(): SourceCode {
    return content.toScriptSource(path.substringAfterLast('/'))
}

private fun benchmarkCompilationConfiguration(bindings: Map<String, Any?>): ScriptCompilationConfiguration {
    return ScriptCompilationConfiguration {
        jvm {
            dependenciesFromCurrentContext(wholeClasspath = true)
        }
        if (bindings.isNotEmpty()) {
            providedProperties(bindings.mapValues { (_, value) -> value.toKotlinType() })
        }
    }
}

private fun benchmarkEvaluationConfiguration(bindings: Map<String, Any?>): ScriptEvaluationConfiguration {
    return ScriptEvaluationConfiguration {
        if (bindings.isNotEmpty()) {
            providedProperties(bindings)
        }
    }
}

private fun Any?.toKotlinType(): KotlinType = KotlinType(this?.let { it::class } ?: Any::class)

private fun ResultValue.unwrap(): Any? {
    return when (this) {
        is ResultValue.Value -> value
        is ResultValue.Unit -> Unit
        is ResultValue.Error -> error("Kotlin 脚本执行失败: $error")
        is ResultValue.NotEvaluated -> error("Kotlin 脚本未执行")
    }
}

private fun <T> ResultWithDiagnostics<T>.valueOrThrow(): T {
    return when (this) {
        is ResultWithDiagnostics.Success -> value
        is ResultWithDiagnostics.Failure -> error(renderDiagnostics(reports))
    }
}

private fun renderDiagnostics(reports: List<ScriptDiagnostic>): String {
    return reports.joinToString(separator = "\n") { report ->
        buildString {
            append(report.severity)
            append(": ")
            append(report.message)
            report.exception?.let {
                append(" (")
                append(it::class.qualifiedName)
                append(": ")
                append(it.message)
                append(')')
            }
        }
    }
}
