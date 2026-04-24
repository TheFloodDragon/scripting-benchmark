package cn.altawk.test.scripting.engine

import cn.altawk.test.scripting.ScriptEngineAdapter
import cn.altawk.test.scripting.ScriptSample
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source

/** GraalVM JavaScript 适配器；复用 Engine，Context 按编译产物隔离。 */
object GraalJsScriptEngineAdapter : ScriptEngineAdapter<GraalPreparedScript> {

    private val sharedEngine: Engine = Engine.newBuilder("js")
        .allowExperimentalOptions(true)
        .option("js.ecmascript-version", "latest")
        .option("js.nashorn-compat", "true")
        .build()

    override val engineName: String = "GraalJS"
    override val sampleDirectory: String = "javascript"
    override val sampleExtension: String = ".js"

    override fun compile(sample: ScriptSample): GraalPreparedScript {
        val context = Context.newBuilder("js")
            .allowAllAccess(true)
            .engine(sharedEngine)
            .build()
        val bindings = context.getBindings("js")
        sample.bindingsFactory().forEach { (key, value) ->
            bindings.putMember(key, value)
        }
        val source = Source.newBuilder("js", sample.content, sample.path)
            .cached(true)
            .build()
        return GraalPreparedScript(context, source)
    }

    override fun runCompiled(compiled: GraalPreparedScript): Any? {
        return compiled.context.eval(compiled.source).`as`(Any::class.java)
    }

    override fun interpret(sample: ScriptSample): Any? {
        val context = Context.newBuilder("js")
            .allowAllAccess(true)
            .engine(sharedEngine)
            .build()
        try {
            val bindings = context.getBindings("js")
            sample.bindingsFactory().forEach { (key, value) ->
                bindings.putMember(key, value)
            }
            val source = Source.newBuilder("js", sample.content, sample.path)
                .cached(false)
                .build()
            return context.eval(source).`as`(Any::class.java)
        } finally {
            context.close()
        }
    }

    override fun disposeCompiled(compiled: GraalPreparedScript) {
        compiled.close()
    }
}

data class GraalPreparedScript(
    val context: Context,
    val source: Source,
) : AutoCloseable {
    override fun close() {
        context.close()
    }
}
