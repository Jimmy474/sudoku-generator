plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.dataframe") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
    id("me.champeau.jmh") version "0.7.3"
    application
}

group = "com.jimmy"
version = "1.0-SNAPSHOT"

application{
    mainClass.set("com.jimmy.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:dataframe:1.0.0-rc02")
    implementation("org.jetbrains.kotlinx:kandy-lets-plot:0.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-hocon:1.11.0")
    testImplementation(kotlin("test"))
    jmh("org.openjdk.jol:jol-core:0.17")
}

kotlin {
    jvmToolchain(25)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.jimmy.MainKt"
    }
}

tasks.test {
    useJUnitPlatform()
}

jmh {
    jmhVersion.set("1.37")
    resultFormat.set("JSON")
    threads.set(1)
    jvmArgsAppend.set(listOf("-Djdk.attach.allowAttachSelf=true"))

    if (providers.gradleProperty("retainedOnly").isPresent) {
        includes.set(listOf(".*SudokuRetainedMemoryBenchmark.measureRetainedGraph"))
        resultsFile.set(layout.buildDirectory.file("results/jmh/retained.json"))
    } else if (providers.gradleProperty("peakSamples").isPresent) {
        // Sampling check: same peak benchmark with a different number of samples per operation.
        val samples = providers.gradleProperty("peakSamples").get().toInt()
        includes.set(listOf(".*SudokuPeakLiveHeapBenchmark\\..*"))
        jvmArgsAppend.set(listOf("-Djdk.attach.allowAttachSelf=true", "-Dpeak.samplesPerOp=$samples"))
        resultsFile.set(layout.buildDirectory.file("results/jmh/peak-samples-$samples.json"))
    } else if (providers.gradleProperty("peakOnly").isPresent) {
        includes.set(listOf(".*SudokuPeakLiveHeapBenchmark\\..*"))
        resultsFile.set(layout.buildDirectory.file("results/jmh/peak.json"))
    } else if (providers.gradleProperty("allocationSmoke").isPresent) {
        resultsFile.set(layout.buildDirectory.file("results/jmh/smoke.json"))
        benchmarkParameters.put(
            "versionNumber",
            objects.listProperty(String::class.java).value(listOf("1"))
        )
        benchmarkParameters.put(
            "difficulty",
            objects.listProperty(String::class.java).value(listOf("10"))
        )
        fork.set(1)
        warmupIterations.set(0)
        iterations.set(1)
        timeOnIteration.set("100ms")
    }
}