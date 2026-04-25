package cn.altawk.test.scripting.engine

import cn.altawk.test.scripting.ScriptEngineAdapter
import cn.altawk.test.scripting.ScriptSample
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import kotlin.script.experimental.api.CompiledScript as KotlinCompiledScript
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.ScriptExecutionWrapper
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.previousSnippets
import kotlin.script.experimental.api.providedProperties
import kotlin.script.experimental.api.scriptExecutionWrapper
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvm.impl.getOrCreateActualClassloader
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

/**
 * Kotlin Scripting 优化适配器。
 *
 * compile 阶段仍保留 `wholeClasspath = true`，但额外完成脚本类加载、构造器/返回字段解析与参数数组构建；
 * compiledExecution 阶段绕过 BasicJvmScriptEvaluator，直接实例化脚本类并读取返回值。
 */
object KotlinScriptingOptimizedScriptEngineAdapter : ScriptEngineAdapter<KotlinOptimizedPreparedScript> {

    override val engineName: String = "KotlinScriptingOptimized"
    override val sampleDirectory: String = "kotlin"
    override val sampleExtension: String = ".kts"

    override fun compile(sample: ScriptSample): KotlinOptimizedPreparedScript {
        val bindings = sample.bindingsFactory()
        val compilationConfiguration = benchmarkCompilationConfiguration(bindings)
        val evaluationConfiguration = benchmarkEvaluationConfiguration(bindings)
        val script = BenchmarkKtsHost.compile(sample.toSourceCode(), compilationConfiguration)
            .valueOrThrow()
            .asKJvmCompiledScript()
        val classLoader = script.getOrCreateActualClassloader(evaluationConfiguration)
        val constructorArguments = KotlinConstructorArguments.from(evaluationConfiguration)

        return script.prepareOptimized(
            classLoader = classLoader,
            constructorArguments = constructorArguments,
            executionWrapper = evaluationConfiguration[ScriptEvaluationConfiguration.scriptExecutionWrapper],
        )
    }

    override fun runCompiled(compiled: KotlinOptimizedPreparedScript): Any? {
        val currentThread = Thread.currentThread()
        val previousClassLoader = currentThread.contextClassLoader
        currentThread.contextClassLoader = compiled.classLoader
        return try {
            compiled.evaluate(mutableMapOf()).value
        } finally {
            currentThread.contextClassLoader = previousClassLoader
        }
    }

    override fun interpret(sample: ScriptSample): Any? {
        return KotlinScriptingScriptEngineAdapter.interpret(sample)
    }
}

data class KotlinPreparedScript(
    val script: KotlinCompiledScript,
    val evaluationConfiguration: ScriptEvaluationConfiguration,
)

data class KotlinOptimizedPreparedScript(
    val classLoader: ClassLoader,
    val scriptClass: Class<*>,
    val constructor: Constructor<*>,
    val constructorArguments: KotlinConstructorArguments,
    val resultField: Field?,
    val otherScripts: List<KotlinOptimizedPreparedScript>,
    val executionWrapper: ScriptExecutionWrapper<*>?,
)

data class KotlinConstructorArguments(
    val previousSnippets: List<Any?>?,
    val constructorArgs: List<Any?>,
    val providedProperties: List<Any?>,
    val implicitReceivers: List<Any?>,
) {
    fun toArray(importedScriptInstances: List<Any?>): Array<Any?> {
        return buildList {
            previousSnippets?.let { add(it.toTypedArray()) }
            addAll(constructorArgs)
            addAll(providedProperties)
            addAll(importedScriptInstances)
            addAll(implicitReceivers)
        }.toTypedArray()
    }

    companion object {
        fun from(evaluationConfiguration: ScriptEvaluationConfiguration): KotlinConstructorArguments {
            return KotlinConstructorArguments(
                previousSnippets = evaluationConfiguration[ScriptEvaluationConfiguration.previousSnippets],
                constructorArgs = evaluationConfiguration[ScriptEvaluationConfiguration.constructorArgs].orEmpty(),
                providedProperties = evaluationConfiguration[ScriptEvaluationConfiguration.providedProperties]
                    ?.values
                    ?.toList()
                    .orEmpty(),
                implicitReceivers = evaluationConfiguration[ScriptEvaluationConfiguration.implicitReceivers].orEmpty(),
            )
        }
    }
}

private data class KotlinOptimizedEvaluation(
    val value: Any?,
    val instance: Any,
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

private fun KJvmCompiledScript.prepareOptimized(
    classLoader: ClassLoader,
    constructorArguments: KotlinConstructorArguments,
    executionWrapper: ScriptExecutionWrapper<*>?,
): KotlinOptimizedPreparedScript {
    val scriptClass = classLoader.loadClass(scriptClassFQName)
    val returnField = resultField?.first?.let { fieldName ->
        scriptClass.getDeclaredField(fieldName).apply { isAccessible = true }
    }
    return KotlinOptimizedPreparedScript(
        classLoader = classLoader,
        scriptClass = scriptClass,
        constructor = scriptClass.constructors.single(),
        constructorArguments = constructorArguments,
        resultField = returnField,
        otherScripts = otherScripts.map { importedScript ->
            importedScript.asKJvmCompiledScript().prepareOptimized(
                classLoader = classLoader,
                constructorArguments = constructorArguments,
                executionWrapper = executionWrapper,
            )
        },
        executionWrapper = executionWrapper,
    )
}

private fun KotlinOptimizedPreparedScript.evaluate(
    sharedEvaluations: MutableMap<Class<*>, KotlinOptimizedEvaluation>,
): KotlinOptimizedEvaluation {
    sharedEvaluations[scriptClass]?.let { return it }

    val importedEvaluations = otherScripts.map { it.evaluate(sharedEvaluations) }
    val constructorArgs = constructorArguments.toArray(importedEvaluations.map { it.instance })
    val instance = createInstance(constructorArgs)
    val evaluation = KotlinOptimizedEvaluation(
        value = if (resultField != null) resultField.get(instance) else Unit,
        instance = instance,
    )
    sharedEvaluations[scriptClass] = evaluation
    return evaluation
}

@Suppress("UNCHECKED_CAST")
private fun KotlinOptimizedPreparedScript.createInstance(constructorArgs: Array<Any?>): Any {
    return try {
        val wrapper = executionWrapper as ScriptExecutionWrapper<Any>?
        wrapper?.invoke { constructor.newInstance(*constructorArgs) }
            ?: constructor.newInstance(*constructorArgs)
    } catch (e: InvocationTargetException) {
        throw (e.targetException ?: e)
    }
}

private fun KotlinCompiledScript.asKJvmCompiledScript(): KJvmCompiledScript {
    return this as? KJvmCompiledScript
        ?: error("Kotlin 优化适配器仅支持 JVM 编译产物，实际类型: ${this::class.qualifiedName}")
}

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
