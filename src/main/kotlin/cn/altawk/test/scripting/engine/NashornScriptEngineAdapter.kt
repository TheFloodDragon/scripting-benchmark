package cn.altawk.test.scripting.engine

import cn.altawk.test.scripting.ScriptEngineAdapter
import cn.altawk.test.scripting.ScriptSample
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory
import javax.script.Compilable
import javax.script.CompiledScript
import javax.script.ScriptContext
import javax.script.ScriptEngine
import javax.script.SimpleBindings

/** OpenJDK Nashorn 适配器；每个编译产物持有独立 ScriptEngine。 */
object NashornScriptEngineAdapter : ScriptEngineAdapter<NashornPreparedScript> {

    private val factory = NashornScriptEngineFactory()

    override val engineName: String = "Nashorn"
    override val sampleDirectory: String = "javascript"
    override val sampleExtension: String = ".js"

    override fun compile(sample: ScriptSample): NashornPreparedScript {
        val engine = newEngine()
        engine.setBindings(SimpleBindings(sample.bindingsFactory()), ScriptContext.GLOBAL_SCOPE)
        val compiled = (engine as Compilable).compile(sample.content)
        return NashornPreparedScript(engine, compiled)
    }

    override fun runCompiled(compiled: NashornPreparedScript): Any? {
        return compiled.script.eval(compiled.engine.context)
    }

    override fun interpret(sample: ScriptSample): Any? {
        val engine = newEngine()
        engine.setBindings(SimpleBindings(sample.bindingsFactory()), ScriptContext.GLOBAL_SCOPE)
        return engine.eval(sample.content)
    }

    private fun newEngine(): ScriptEngine {
        return factory.getScriptEngine(
            arrayOf("-Dnashorn.args=--language=es6"),
            this::class.java.classLoader,
        )
    }
}

data class NashornPreparedScript(
    val engine: ScriptEngine,
    val script: CompiledScript,
)
