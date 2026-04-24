import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinKapt)
    application
}

group = "local.scripting"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

application {
    mainClass.set("cn.altawk.test.scripting.ScriptBenchmarkMainKt")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))

    // JMH：不使用 Gradle JMH 插件，直接依赖 core + kapt annotation processor
    implementation(libs.jmh.core)
    kapt(libs.jmh.annprocess)

    // Kotlin Scripting
    implementation(kotlin("scripting-common"))
    implementation(kotlin("scripting-jvm"))
    implementation(kotlin("scripting-jvm-host"))
    implementation(kotlin("scripting-compiler-embeddable"))
    implementation(kotlin("compiler-embeddable"))
    runtimeOnly("org.jetbrains.kotlin:kotlin-script-runtime:${libs.versions.kotlin.get()}")
    runtimeOnly("org.jetbrains.kotlin:kotlin-scripting-compiler-impl-embeddable:${libs.versions.kotlin.get()}")

    // JavaScript engines
    implementation(libs.nashorn)
    implementation(libs.graalvm.polyglot)
    runtimeOnly("org.graalvm.truffle:truffle-runtime:${libs.versions.graaljs.get()}")
    runtimeOnly("org.graalvm.js:js-language:${libs.versions.graaljs.get()}")

    // Expression/script engines
    implementation(libs.jexl)
    implementation(libs.fluxon) { isTransitive = false }
}

tasks.register<JavaExec>("runScriptBenchmark") {
    description = "Runs JMH script engine benchmarks. Pass args with -PjmhArgs='--quick --engine GraalJS'."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.classes)
    mainClass.set(application.mainClass)
    classpath = sourceSets.main.get().runtimeClasspath
    doFirst {
        val jmhArgs = providers.gradleProperty("jmhArgs").orNull
            ?.split(Regex("\\s+"))
            ?.filter(String::isNotBlank)
            ?: emptyList()
        val enableJfr = providers.gradleProperty("enableJfr")
            .map { it.equals("true", ignoreCase = true) }
            .orElse(false)
            .get()
        setArgs(if (enableJfr && "--jfr" !in jmhArgs) jmhArgs + "--jfr" else jmhArgs)
    }
}

tasks.register("benchmark") {
    description = "Alias for runScriptBenchmark."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn("runScriptBenchmark")
}

tasks.register<Jar>("benchmarkJar") {
    description = "Builds a standalone executable JMH benchmark jar."
    group = LifecycleBasePlugin.BUILD_GROUP
    dependsOn(tasks.classes)
    archiveClassifier.set("benchmark")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            // GraalVM Truffle/GraalJS 依赖是 multi-release jar；合并成可执行包时必须保留该标记。
            "Multi-Release" to "true",
        )
    }
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
