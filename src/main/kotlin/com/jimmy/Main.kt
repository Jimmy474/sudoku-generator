package com.jimmy

import com.sun.management.ThreadMXBean
import com.typesafe.config.ConfigFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.hocon.Hocon
import kotlinx.serialization.hocon.decodeFromConfig
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.*
import org.jetbrains.kotlinx.dataframe.io.readCsv
import org.jetbrains.kotlinx.dataframe.io.writeCsv
import org.jetbrains.kotlinx.kandy.dsl.plot
import org.jetbrains.kotlinx.kandy.letsplot.export.save
import org.jetbrains.kotlinx.kandy.letsplot.feature.layout
import org.jetbrains.kotlinx.kandy.letsplot.layers.line
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.io.path.Path
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis
import kotlin.time.Clock

//379284615428561739561793428213657984786149253945328176157436892692875341834912567
//.......15.2..6..........4.8..3...9.....1..........8...15.4.........7.3..8......6.
fun main(args: Array<String>) {
    val config = extractConfig(args)

    if (config.test) test(config)

    if (config.plot) {
        val cSVs = File(config.path).listFiles {
            it.extension == "csv"
        } ?: emptyArray()

        val df = cSVs.map { DataFrame.readCsv(it).convertTo<BenchmarkResult>() }.concat()

        makePlot(config, df)
    }

    if (!config.test && !config.plot) {
        val n = config.difficulty?.firstOrNull() ?: 0

        require(n <= 64) {
            "n must be less than or equal to 64 but provided value is $n"
        }

        val generator = GridGenerator(Clock.System.now().epochSeconds)
        val generatedGrid: SudokuGrid?
        var removed = 0

        val time = measureTimeMillis {
            generatedGrid = generator.generateRandom(V1SudokuGridBase())?.also {
                removed = it.playable(n)
            }
        }

        if (removed < n) println("Failed to remove ${n - removed} cells for d$n")

        if (config.printTime) println("Time taken: $time milliseconds")

        generatedGrid?.let {
            if (config.printNice) it.printNiceGrid()
            else it.print()

            if (config.printDebug) println(it.debugString())
        }
    }
}

fun test(config: Config) {
    val seed = Clock.System.now().epochSeconds

    val versions = listOf(
        ::V1SudokuGridBase,
        ::V2SudokuGridPacked,
        ::V3SudokuGridFilteredSelection,
        ::V4SudokuGridCaching,
        ::V5SudokuGridLimitedSolutions,
        ::V6SudokuGridBetterRandomSelection,
    ).filter {
        config.version == null || it().v in config.version
    }

    val difficulties = config.difficulty ?: (1..64).toSet()

    val threadCount = Runtime.getRuntime().availableProcessors()
    val dispatcher = Dispatchers.Default.limitedParallelism(threadCount)

    ProgressPrinter.doPrint(config.printProgress)
    ProgressPrinter["Iteration"] = ProgressPrinter.Progress(config.iterations)
    ProgressPrinter["Difficulty"] = ProgressPrinter.Progress(difficulties.size)
    ProgressPrinter["Version"] = ProgressPrinter.Progress(versions.size)

    runBlocking {
        for (versionFactory in versions) {
            val results = mutableListOf<BenchmarkResult>()
            val versionNumber = versionFactory().v

            ProgressPrinter.print()
            ProgressPrinter["Difficulty"]?.reset()

            for (difficulty in difficulties.sorted()) {
                ProgressPrinter.print()
                ProgressPrinter["Iteration"]?.reset()

                val tasks = (0 until config.iterations).map { i ->
                    async(dispatcher) {
                        val version = versionFactory()
                        val generator = GridGenerator(seed)

                        version.setSeed(seed + i)

                        var generatedGrid: SudokuGrid? = null
                        var removed = 0

                        val (time, peakMemoryBytes) = measureMemoryAndTime {
                            generatedGrid = generator.generateRandom(
                                version,
                                config.grid
                            )?.also {
                                removed = it.playable(difficulty)
                            } ?: throw IllegalStateException(
                                "Failed to generate grid"
                            )
                        }

                        if (removed < difficulty) {
                            println("Failed to remove ${difficulty - removed} cells for v${version.v} d$difficulty")
                        }

                        val gridString = generatedGrid!!.debugString()

                        val result = BenchmarkResult(
                            version.v,
                            gridString,
                            difficulty,
                            gridString.count { it == '.' },
                            time.toFloat(),
                            peakMemoryBytes
                        )

                        version.reset()

                        result
                    }
                }

                tasks.forEach { task ->
                    results.add(task.await())
                    ProgressPrinter["Iteration"]?.update()
                }

                ProgressPrinter["Difficulty"]?.update()
            }

            ProgressPrinter["Version"]?.update()

            Path("${config.path}/benchmark_results_v$versionNumber.csv").let { path ->
                if (!path.exists()) {
                    path.createParentDirectories().createFile()
                }

                results.toDataFrame().writeCsv(path)
            }
        }
    }

    ProgressPrinter.print()
}

fun makePlot(config: Config, df: DataFrame<BenchmarkResult>) {
    val aggregatedDf = df.filter {
        (config.version == null || it.version in config.version) && (config.difficulty == null || it.difficulty in config.difficulty)
    }.groupBy("version", "difficulty").aggregate {
        meanOf { runtimeMs } into "avgRuntimeMs"
        meanOf { peakMemUsage } into "avgMemoryUsage"
    }

    val runtimePlot = aggregatedDf.groupBy("version").plot {
        line {
            x(difficulty)
            y(avgRuntimeMs)
            color(version) {
                legend.name = "Code Version"
            }
        }
        layout {
            title = "Execution Scale by Optimization Version"
            xAxisLabel = "Grid Difficulty Level"
            yAxisLabel = "Runtime Duration (ms)"
            size = 850 to 500
        }
    }

    val memoryPlot = aggregatedDf.groupBy("version").plot {
        line {
            x(difficulty)
            y(avgMemoryUsage / 1024)
            color(version) {
                legend.name = "Code Version"
            }
        }
        layout {
            title = "Execution Memory by Optimization Version"
            xAxisLabel = "Grid Difficulty Level"
            yAxisLabel = "Peak Memory Usage (KB)"
            size = 850 to 500
        }
    }

    val runtimePath = runtimePlot.save("runtime.svg", 2.0, 300, config.plotPath)
    val memPath = memoryPlot.save("memory.svg", 2.0, 300, config.plotPath)

    println("Runtime Benchmark plot saved to: $runtimePath")
    println("Memory Benchmark plot saved to: $memPath")
}

fun measureMemoryAndTime(block: () -> Unit): Pair<Long, Long> {
    val threadMXBean = ManagementFactory.getThreadMXBean() as ThreadMXBean

    if (!threadMXBean.isThreadAllocatedMemorySupported) {
        error("Thread memory allocation measurement is not supported by this JVM.")
    }
    if (!threadMXBean.isThreadAllocatedMemoryEnabled) {
        threadMXBean.isThreadAllocatedMemoryEnabled = true
    }

    val bytesBefore = threadMXBean.currentThreadAllocatedBytes
    val time = measureNanoTime {
        block()
    }
    val bytesAfter = threadMXBean.currentThreadAllocatedBytes

    return time to bytesAfter - bytesBefore
}

fun extractArgs(args: Array<String>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    val flags = setOf("test", "plot", "p", "t", "b", "d")
    val options = setOf("v", "version", "difficulty", "i", "iterations", "start", "end", "n", "grid", "path", "plotPath", "c", "config")
    var i = 0

    while (i < args.size) {
        when (val arg = args[i++].trimStart('-')) {
            in flags -> map[arg] = "true"
            in options -> {
                require(i < args.size) {
                    "Missing value for argument: -$arg"
                }
                map[arg] = args[i++]
            }

            else -> throw IllegalArgumentException("Unknown argument: -$arg")
        }
    }

    return map
}

@OptIn(ExperimentalSerializationApi::class)
fun loadConfigFile(path: String): Config {
    val file = File(path)
    require(file.exists()) { "Config file does not exist: $path" }
    require(file.isFile) { "Config path is not a file: $path" }

    var typesafeConfig = ConfigFactory.parseFile(file).resolve()
    val aliasMappings = mapOf(
        "v" to "version",
        "i" to "iterations",
        "p" to "printProgress",
        "progress" to "printProgress",
        "t" to "printTime",
        "time" to "printTime",
        "b" to "printNice",
        "board" to "printNice",
        "nice" to "printNice",
        "d" to "printDebug",
        "debug" to "printDebug"
    )
    for ((alias, canonical) in aliasMappings) {
        if (typesafeConfig.hasPath(alias) && !typesafeConfig.hasPath(canonical)) {
            typesafeConfig = typesafeConfig.withValue(canonical, typesafeConfig.getValue(alias))
        }
    }
    return Hocon.decodeFromConfig(typesafeConfig)
}

fun parseIntSelection(value: String): Set<Int> {
    return value.split(",").flatMap { part ->
        val range = part.trim().split("-")

        when (range.size) {
            1 -> listOf(range[0].toInt())
            2 -> {
                val start = range[0].toInt()
                val end = range[1].toInt()

                require(start <= end) {
                    "Invalid range: $part"
                }

                start..end
            }

            else -> throw IllegalArgumentException("Invalid selection: $part")
        }
    }.toSet()
}

fun extractConfig(args: Array<String>): Config {
    val cliMap = extractArgs(args)
    val configFilePath = cliMap["c"] ?: cliMap["config"]

    val baseConfig = if (configFilePath != null) {
        loadConfigFile(configFilePath)
    } else {
        Config()
    }

    return baseConfig.copy(
        test = "test" in cliMap || baseConfig.test,
        plot = "plot" in cliMap || baseConfig.plot,
        version = (cliMap["v"] ?: cliMap["version"])?.let(::parseIntSelection) ?: baseConfig.version,
        difficulty = cliMap["difficulty"]?.let(::parseIntSelection) ?: baseConfig.difficulty,
        iterations = (cliMap["i"] ?: cliMap["iterations"])?.toIntOrNull() ?: baseConfig.iterations,
        grid = cliMap["grid"] ?: baseConfig.grid,
        printProgress = "p" in cliMap || "printProgress" in cliMap || "progress" in cliMap || baseConfig.printProgress,
        printTime = "t" in cliMap || "printTime" in cliMap || "time" in cliMap || baseConfig.printTime,
        printNice = "b" in cliMap || "printNice" in cliMap || "board" in cliMap || "nice" in cliMap || baseConfig.printNice,
        printDebug = "d" in cliMap || "printDebug" in cliMap || "debug" in cliMap || baseConfig.printDebug,
        path = cliMap["path"] ?: baseConfig.path,
        plotPath = cliMap["plotPath"] ?: baseConfig.plotPath,
    )
}

