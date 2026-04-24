package cn.altawk.test.scripting.engine

import cn.altawk.test.scripting.ScriptEngineAdapter
import cn.altawk.test.scripting.ScriptSample
import org.apache.commons.jexl3.JexlBuilder
import org.apache.commons.jexl3.JexlContext
import org.apache.commons.jexl3.JexlFeatures
import org.apache.commons.jexl3.JexlScript
import org.apache.commons.jexl3.introspection.JexlPermissions

/** Apache Commons JEXL 适配器；共享 JexlEngine，编译产物绑定独立上下文。 */
object JexlScriptEngineAdapter : ScriptEngineAdapter<JexlPreparedScript> {

    private val engine = JexlBuilder()
        .cache(16)
        .strict(true)
        .features(JexlFeatures.createAll())
        .permissions(JexlPermissions.UNRESTRICTED)
        .create()

    override val engineName: String = "Jexl"
    override val sampleDirectory: String = "jexl"
    override val sampleExtension: String = ".jexl"

    override fun compile(sample: ScriptSample): JexlPreparedScript {
        val script = engine.createScript(sample.content)
        val context = BenchmarkJexlContext(sample.bindingsFactory())
        return JexlPreparedScript(context, script)
    }

    override fun runCompiled(compiled: JexlPreparedScript): Any? {
        return compiled.script.execute(compiled.context)
    }

    override fun interpret(sample: ScriptSample): Any? {
        return engine.createScript(sample.content)
            .execute(BenchmarkJexlContext(sample.bindingsFactory()))
    }
}

data class JexlPreparedScript(
    val context: BenchmarkJexlContext,
    val script: JexlScript,
)

class BenchmarkJexlContext(
    private val bindings: MutableMap<String, Any?> = linkedMapOf(),
) : JexlContext {

    override fun get(name: String): Any? = bindings[name]

    override fun has(name: String): Boolean = bindings.containsKey(name)

    override fun set(name: String, value: Any?) {
        bindings[name] = value
    }
}
