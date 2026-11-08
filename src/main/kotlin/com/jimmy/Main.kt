package com.jimmy

import com.sun.management.ThreadMXBean
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

    when {
        config.plot -> {
            val cSVs = File("benchmarks").listFiles {
                it.extension == "csv"
            } ?: emptyArray()

            val df = cSVs.map { DataFrame.readCsv(it).convertTo<BenchmarkResult>() }.concat()

            makePlot(config, df)
        }

        config.test -> test(config)

        else -> {
            val n = config.difficulty?.firstOrNull() ?: 0

            require(n <= 64) {
                "n must be less than or equal to 64 but provided value is $n"
            }

            val generator = GridGenerator(Clock.System.now().epochSeconds)
            val generatedGrid: SudokuGrid?

            val time = measureTimeMillis {
                generatedGrid = generator.generateRandom(V1SudokuGridBase())?.also { it.playable(n) }
            }

            if (config.printTime) println("Time taken: $time milliseconds")

            generatedGrid?.let {
                if (config.printNice) it.printNiceGrid()
                else it.print()

                if (config.printDebug) println(it.debugString())
            }
        }
    }
}

fun test(config: Config) {
    val seed = Clock.System.now().epochSeconds
    val generator = GridGenerator(seed)
    val versions = listOf(
        V1SudokuGridBase(),
        V2SudokuGridPacked(),
        V3SudokuGridFilteredSelection(),
        V4SudokuGridCaching(),
        V5SudokuGridLimitedSolutions(),
        V6SudokuGridBetterRandomSelection(),
    ).filter {
        config.version == null || it.v in config.version
    }

    val difficulties = config.difficulty ?: (1..64).toSet()

    ProgressPrinter.doPrint(config.printProgress)
    ProgressPrinter["Iteration"] = ProgressPrinter.Progress(config.iterations)
    ProgressPrinter["Difficulty"] = ProgressPrinter.Progress(difficulties.size)
    ProgressPrinter["Version"] = ProgressPrinter.Progress(versions.size)

    for (version in versions) {
        val results = mutableListOf<BenchmarkResult>()

        ProgressPrinter.print()
        ProgressPrinter["Difficulty"]?.reset()

        var generatedGrid: SudokuGrid? = null
        for (difficulty in difficulties.sorted()) {
            ProgressPrinter.print()
            ProgressPrinter["Iteration"]?.reset()

            repeat(config.iterations) { i ->
                ProgressPrinter.print()

                version.setSeed(seed + i)
                var removed = 0
                val (time, peakMemoryBytes) = measureMemoryAndTime {
                    generatedGrid = generator.generateRandom(version,config.grid)?.also {
                        removed = it.playable(difficulty)
                    } ?: throw IllegalStateException("Failed to generate grid")
                }
                if(removed < difficulty) println("Failed to remove ${difficulty - removed} cells for v${version.v} d${difficulty}")

                generatedGrid?.let {
                    val gridString = it.debugString()
                    results.add(BenchmarkResult(version.v, gridString, difficulty, gridString.count { c -> c == '.' }, time / 1_000_000f, peakMemoryBytes))
                }

                version.reset()
                ProgressPrinter["Iteration"]?.update()
            }

            ProgressPrinter["Difficulty"]?.update()
        }

        ProgressPrinter["Version"]?.update()

        Path("benchmarks/benchmark_results_v${version.v}.csv").let { path ->
            if (!path.exists()) path.createParentDirectories().createFile()
            results.toDataFrame().writeCsv(path)
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

    val runtimePath = runtimePlot.save("runtime.svg", 2.0, 300, "plots")
    val memPath = memoryPlot.save("memory.svg", 2.0, 300, "plots")

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
    val options = setOf("v", "difficulty", "i", "start", "end", "n", "grid")
    var i = 0

    while (i < args.size) {
        when (val arg = args[i++].trimStart('-')) {
            in flags -> map[arg] = "true"
            in options -> {
                require(i < args.size) {
                    "Missing value for argument: $arg"
                }
                map[arg] = args[i++]
            }

            else -> throw IllegalArgumentException("Unknown argument: $arg")
        }
    }

    return map
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
    val argsMap = extractArgs(args)

    return Config(
        test = "test" in argsMap,
        plot = "plot" in argsMap,
        version = argsMap["v"]?.let(::parseIntSelection),
        difficulty = argsMap["difficulty"]?.let(::parseIntSelection),
        iterations = argsMap["i"]?.toIntOrNull() ?: 100,
        grid = argsMap["grid"],
        printProgress = "p" in argsMap,
        printTime = "t" in argsMap,
        printNice = "b" in argsMap,
        printDebug = "d" in argsMap
    )
}

