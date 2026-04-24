package cn.altawk.test.scripting.engine

import cn.altawk.test.scripting.ScriptEngineAdapter
import cn.altawk.test.scripting.ScriptSample
import org.tabooproject.fluxon.Fluxon
import org.tabooproject.fluxon.compiler.CompilationContext
import org.tabooproject.fluxon.interpreter.bytecode.FluxonClassLoader
import org.tabooproject.fluxon.runtime.Environment
import org.tabooproject.fluxon.runtime.FluxonRuntime
import org.tabooproject.fluxon.runtime.RuntimeScriptBase
import java.util.UUID

/** Fluxon 适配器；compile 阶段生成 JVM 类，执行阶段创建新的运行环境注入绑定。 */
object FluxonScriptEngineAdapter : ScriptEngineAdapter<FluxonPreparedScript> {

    override val engineName: String = "Fluxon"
    override val sampleDirectory: String = "fluxon"
    override val sampleExtension: String = ".fs"

    override fun compile(sample: ScriptSample): FluxonPreparedScript {
        val className = sample.path.substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9_]"), "_") + "_" + UUID.randomUUID().toString().replace("-", "")
        val compiled = Fluxon.compile(
            newEnvironment(sample.bindingsFactory()),
            newCompilationContext(sample.content, sample.path),
            className,
            this::class.java.classLoader,
        )
        val definedClass = compiled.defineClass(FluxonClassLoader())
        val runtime = definedClass.getDeclaredConstructor().newInstance() as RuntimeScriptBase
        return FluxonPreparedScript(runtime, sample.bindingsFactory)
    }

    override fun runCompiled(compiled: FluxonPreparedScript): Any? {
        return compiled.runtime.eval(newEnvironment(compiled.bindingsFactory()))
    }

    override fun interpret(sample: ScriptSample): Any? {
        val interpretEnv = newEnvironment(sample.bindingsFactory())
        val interpretCtx = newCompilationContext(sample.content, sample.path)
        return Fluxon.parse(interpretCtx, interpretEnv).eval(interpretEnv)
    }

    private fun newCompilationContext(source: String, path: String): CompilationContext {
        return CompilationContext(source, path.substringAfterLast('/')).apply {
            setAllowJavaConstruction(true)
            setAllowReflectionAccess(true)
        }
    }

    private fun newEnvironment(bindings: MutableMap<String, Any?>): Environment {
        return FluxonRuntime.getInstance().newEnvironment().apply {
            rootVariables.putAll(bindings)
        }
    }
}

data class FluxonPreparedScript(
    val runtime: RuntimeScriptBase,
    val bindingsFactory: () -> MutableMap<String, Any?>,
)
