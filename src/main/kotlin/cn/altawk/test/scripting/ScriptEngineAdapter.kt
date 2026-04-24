package cn.altawk.test.scripting

import cn.altawk.test.scripting.engine.FluxonScriptEngineAdapter
import cn.altawk.test.scripting.engine.GraalJsScriptEngineAdapter
import cn.altawk.test.scripting.engine.JexlScriptEngineAdapter
import cn.altawk.test.scripting.engine.KotlinScriptingScriptEngineAdapter
import cn.altawk.test.scripting.engine.NashornScriptEngineAdapter

/** 参与横向对比的脚本引擎适配器。 */
val SCRIPT_ENGINE_ADAPTERS: List<ScriptEngineAdapter<out Any>> = listOf(
    GraalJsScriptEngineAdapter,
    NashornScriptEngineAdapter,
    JexlScriptEngineAdapter,
    KotlinScriptingScriptEngineAdapter,
    FluxonScriptEngineAdapter,
)

/** 一份可被某个脚本引擎执行的样本源码。 */
data class ScriptSample(
    /** classpath 资源路径，用于报告和错误定位。 */
    val path: String,
    /** 已完成占位符替换后的脚本正文。 */
    val content: String,
    /** 每次编译/运行前创建全新绑定，避免跨迭代共享可变状态。 */
    val bindingsFactory: () -> MutableMap<String, Any?> = { linkedMapOf() },
)

/** 一类跨引擎对齐的测试场景，例如数值计算、列表构建或 Java API 调用。 */
data class BenchmarkScriptCase(
    val id: String,
    val displayName: String,
    val bindingsFactory: () -> MutableMap<String, Any?> = { linkedMapOf() },
)

/**
 * 脚本引擎适配层。
 *
 * 样本发现和加载统一由 [BenchmarkScriptCase] 相关函数负责；适配器只声明自己的样本目录/扩展名，
 * 以及如何编译、运行、解释该引擎的脚本。
 *
 * @param P 编译后可重复执行的引擎私有对象类型。
 */
interface ScriptEngineAdapter<P : Any> {
    val engineName: String
    val sampleDirectory: String
    val sampleExtension: String

    /** 只做编译/预处理，供 JMH 的 compile 阶段计时。 */
    fun compile(sample: ScriptSample): P

    /** 执行已编译脚本，供 JMH 的 compiledExecution 阶段计时。 */
    fun runCompiled(compiled: P): Any?

    /** 直接解释执行源码，供 JMH 的 interpretedExecution 阶段计时。 */
    fun interpret(sample: ScriptSample): Any?

    /** 释放编译产物持有的上下文、类加载器等资源。 */
    fun disposeCompiled(compiled: P) = Unit
}
