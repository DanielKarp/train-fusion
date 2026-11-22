plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Java 17 bytecode, whatever JDK builds it (Android's D8 takes 17)
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    // the tests that need ffmpeg or a local backend skip themselves when it isn't there
    systemProperty("repo.root", rootProject.projectDir.parentFile.absolutePath)
    // a local fake backend for BackendClientTest (README.md, "Tests"): an input, so results
    // from a run without one aren't reused for a run with one
    val backend = providers.environmentVariable("TRAIN_FUSION_TEST_BACKEND").orElse("")
    inputs.property("testBackend", backend)
    environment("TRAIN_FUSION_TEST_BACKEND", backend.get())
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Replay a recording through the model on a laptop, writing the same dump as the app's
// replay (`-dump`), for `python -m app.compare` (README.md, "Checking the model's port"):
//   ./gradlew :core:replay --args="<recording dir> <out.jsonl>"
tasks.register<JavaExec>("replay") {
    group = "application"
    description = "Replay a recording through the Kotlin model and write a dump for app.compare"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.danielkarp.trainfusion.core.tools.ReplayToolKt")
    systemProperty("model.json", rootProject.file("../ios/TrainRecorder/model.json").absolutePath)
    workingDir = rootProject.projectDir
}
