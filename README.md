# Sudoku Generator & Benchmarking Suite

A high-performance Sudoku generator, solver, benchmarking suite, and performance visualizer written in Kotlin. This project implements multiple progressive optimization stages (from naive arrays to bit-packed representations and short-circuit search algorithms) to evaluate and visualize execution runtime and memory allocation across various grid difficulty levels.

---

## Features

- **Unique Puzzle Generation:** Generates valid, uniquely solvable Sudoku puzzles at configurable difficulty levels (0–64 removed cells).
- **Progressive Optimization Journey:** Implements 6 distinct solver and grid data representations (`V1` through `V6`) to compare performance gains.
- **Accurate Profiling:** Measures execution duration (nanoseconds/milliseconds) and peak allocated heap memory per thread via `ThreadMXBean`.
- **Interactive Multi-Level Progress Bar:** Real-time ANSI-rendered terminal progress bars for version, difficulty, and iteration tracking.
- **Data Analysis & Export:** Writes structured benchmark metrics to CSV using `kotlinx.dataframe`.
- **Automated Plotting:** Visualizes execution time and peak memory curves as SVG plots using `kandy-lets-plot`.

---

## Requirements

- **Java Development Kit (JDK):** JDK 25 or higher (configured via Gradle JVM Toolchain).
- **JVM Capabilities:** HotSpot / OpenJDK with support for thread allocated memory tracking (`com.sun.management.ThreadMXBean`).
- **Operating System:** Cross-platform (Linux, macOS, Windows). Terminal supporting ANSI escape codes for formatted output and progress bars.

---

## Building the Project

The project is built using Gradle wrapper (`./gradlew` on Unix / `gradlew.bat` on Windows).

### 1. Compile and Run Tests
```bash
./gradlew test
```

### 2. Build Standard JAR
```bash
./gradlew jar
```
The compiled archive is generated at `build/libs/sudoku-generator-1.0-SNAPSHOT.jar`.

### 3. Build Self-Contained Executable Fat JAR (Shadow JAR)
```bash
./gradlew shadowJar
```
The packaged fat JAR containing all runtime dependencies is generated at `build/libs/sudoku-generator-1.0-SNAPSHOT-all.jar`.

---

## How to Run

You can execute the program either directly via Gradle or by running the generated fat JAR.

### 1. Single Grid Generation (Default Mode)

When neither `-test` nor `-plot` is passed, the application generates a single random Sudoku puzzle.

#### Examples:

**Generate a puzzle with 25 removed cells and display execution time:**
```bash
./gradlew run --args="-t -difficulty 25"
```
*Or with JAR:*
```bash
java -jar build/libs/sudoku-generator-1.0-SNAPSHOT-all.jar -t -difficulty 25
```

**Generate a styled color board and print the compact debug string:**
```bash
./gradlew run --args="-t -b -d -difficulty 57"
```

#### Output Example (`-b` and `-d`):
```text
Time taken: 8 milliseconds
╔═══╤═══╤═══╦═══╤═══╤═══╦═══╤═══╤═══╗
║   │ 7 │   ║ 8 │   │   ║   │ 9 │   ║
╟───┼───┼───╫───┼───┼───╫───┼───┼───╢
║   │   │ 4 ║   │ 2 │ 9 ║   │   │   ║
╟───┼───┼───╫───┼───┼───╫───┼───┼───╢
║ 3 │   │   ║   │ 7 │   ║   │   │ 4 ║
╠═══╪═══╪═══╬═══╪═══╪═══╬═══╪═══╪═══╣
║   │   │   ║ 5 │   │   ║   │ 1 │   ║
╟───┼───┼───╫───┼───┼───╫───┼───┼───╢
║   │ 4 │ 9 ║   │   │ 7 ║ 8 │ 6 │   ║
╟───┼───┼───╫───┼───┼───╫───┼───┼───╢
║   │   │   ║   │   │ 6 ║ 7 │   │   ║
╠═══╪═══╪═══╬═══╪═══╪═══╬═══╪═══╪═══╣
║   │ 3 │   ║   │   │   ║   │   │   ║
╟───┼───┼───╫───┼───┼───╫───┼───┼───╢
║   │ 8 │   ║ 1 │   │ 2 ║ 4 │   │   ║
╟───┼───┼───╫───┼───┼───╫───┼───┼───╢
║ 1 │   │ 5 ║   │   │   ║   │   │   ║
╚═══╧═══╧═══╩═══╧═══╧═══╩═══╧═══╧═══╝
.7.8...9...4.29...3...7...4...5...1..49..786......67...3........8.1.24..1.5......
```

---

### 2. Benchmark Execution (`-test`)

Runs automated benchmarks across grid implementations, iterations, and difficulties, collecting execution time and memory metrics into CSV files.

#### Examples:

**Benchmark versions 1 through 6 on difficulties 10 to 40 with 50 iterations and progress display:**
```bash
./gradlew run --args="-test -v 1-6 -difficulty 10-40 -i 50 -p"
```

**Benchmark a specific initial grid:**
```bash
./gradlew run --args="-test -v 5,6 -difficulty 20-30 -grid 379284615428561739561793428213657984786149253945328176157436892692875341834912567"
```

#### Benchmark Output:
Results are written as CSV files in the destination directory (default: `benchmarks/benchmark_results_v<version>.csv`) with schema:
- `version`: Implementation version ID (`1`–`6`).
- `grid`: 81-character puzzle string representation.
- `difficulty`: Target number of removed cells.
- `actualDifficulty`: Actual number of removed cells achieved while preserving uniqueness.
- `runtimeMs`: Measured generation and solution validation time in milliseconds.
- `peakMemUsage`: Allocated heap memory in bytes during execution.

---

### 3. Benchmark Visualization & Plotting (`-plot`)

Reads benchmark CSV files from `-path`, computes group aggregations (mean runtime and mean memory usage), and generates high-resolution vector charts (SVG).

#### Example:
```bash
./gradlew run --args="-plot -v 1-6 -difficulty 1-50 -path benchmarks -plotPath plots"
```

#### Generated Plots:
- `plots/runtime.svg`: Line plot showing **Runtime Duration (ms)** versus **Grid Difficulty Level** across optimization versions.
- `plots/memory.svg`: Line plot showing **Peak Memory Usage (KB)** versus **Grid Difficulty Level** across optimization versions.

---

### 4. Running with a Configuration File (`-c` / `--config`)

Instead of passing all parameters on the command line, you can supply a HOCON/Typesafe configuration file using `-c <path>` or `--config <path>`. Configuration parsing is powered by `kotlinx-serialization-hocon`. Any manually passed command-line arguments will take precedence and override the corresponding values from the configuration file.
All options are optional
#### Example Configuration File (`sudoku.conf`):
```conf
# Sudoku Generator & Benchmark Configuration (HOCON format)
# If true, runs the benchmarks
test = false
# If true, plots the benchmark results
plot = false
# Versions and difficulties (supports list [1, 2, 3], range 1-6 or comma separated values like [1,3-6])
version = [1, 2, 3, 4, 5, 6]
difficulty = [10, 20, 30, 40]
# Number of iterations for each version and difficulty combinations
iterations = 100
# Initial Grid
grid = 379284615428561739561793428213657984786149253945328176157436892692875341834912567
# Prints the progress bars of benchmarking
printProgress = false
# Prints the time taken for single sudoku generation
printTime = false
# Prints the sudoku grid in a nicer format
printNice = false
# Prints the sudoku grid in a sequence of integer format
printDebug = false
# Path where the benchmark results will be saved, must point to a valid directory
path = "benchmarks"
# Path where the benchmark plots will be saved, must point to a valid directory
plotPath = "plots"
```

#### Examples:

**Run with configuration file:**
```bash
./gradlew run --args="-c sudoku.conf"
```

**Run benchmark tests and generate plots sequentially in one command:**
```bash
./gradlew run --args="-c sudoku.conf -test -plot"
```

**Run with configuration file and override specific settings (e.g., target higher difficulty and fewer iterations):**
```bash
./gradlew run --args="-c sudoku.conf -difficulty 50 -i 10"
```

---

## Command-Line & Program Arguments

Arguments can be passed with single (`-`) or double (`--`) leading dashes. When both `-test` and `-plot` are supplied, the benchmarking suite executes first to generate updated CSV data, and the plotting visualizer runs immediately afterward to render the latest charts.

### Argument Syntax & Range Parsing

For selection arguments (`-v` and `-difficulty`), you can specify:
- Single value: `-v 1` or `-difficulty 30`
- Range: `-v 1-4` or `-difficulty 10-50`
- Comma-separated list: `-v 1,3,5` or `-difficulty 10,20,30`
- Mixed combinations: `-v 1-3,5,6` or `-difficulty 5-10,20-25,40`

### Flags & Options Reference

| Argument | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `-c <path>` / `--config <path>` | Option | `null` | Path to a configuration file containing program options (CLI args override file values). |
| `-test` | Flag | `false` | Enables benchmark testing mode to record metrics across versions/difficulties. |
| `-plot` | Flag | `false` | Enables plotting mode to aggregate benchmark CSVs and render SVG performance plots. |
| `-p` | Flag | `false` | Enables interactive ANSI terminal progress bars during benchmark runs. |
| `-t` | Flag | `false` | Prints the elapsed generation time in milliseconds (single puzzle mode). |
| `-b` | Flag | `false` | Prints the board in a styled box-drawing grid with colored cells (single puzzle mode). |
| `-d` | Flag | `false` | Prints the compact 81-character string of the generated grid (single puzzle mode). |
| `-v <selection>` | Option | `null` (All: 1–6) | Optimization version(s) to benchmark or plot (e.g. `1`, `1-3`, `1,3,5-6`). |
| `-difficulty <selection>` | Option | Single: `0` / Benchmark: `1-64` | Target difficulty (number of removed cells, `0` to `64`). In single generation, the first integer is used. |
| `-i <number>` | Option | `100` | Number of iterations to run for each difficulty level per version during benchmarks. |
| `-grid <string>` | Option | `null` | Pre-defined 81-character puzzle string to initialize the generator with (`.` or `0` for empty cells). |
| `-path <directory>` | Option | `benchmarks` | Directory path where benchmark CSV results are read or written. |
| `-plotPath <directory>` | Option | `plots` | Directory path where generated SVG plots (`runtime.svg`, `memory.svg`) are saved. |

---

## Compiler & Build Configuration

The project build lifecycle and runtime behavior are configured via Gradle (`build.gradle.kts`) and Kotlin compiler settings.

### Kotlin Compiler Plugins & Settings

- **Kotlin JVM Plugin (`kotlin("jvm") version "2.4.21"`):** Targets the JVM using Kotlin 2.4 compiler infrastructure.
- **Kotlin Serialization Plugin (`kotlin("plugin.serialization") version "2.4.21"`):** Generates serializers at compile time for `@Serializable` configuration structures, seamlessly enabling HOCON deserialization via `kotlinx-serialization-hocon`.
- **Kotlin DataFrame Compiler Plugin (`kotlin("plugin.dataframe") version "2.4.21"`):** Enables Kotlin DataFrame schema compilation. It automatically processes data schema annotations (e.g., `@DataSchema data class BenchmarkResult`) for type-safe dataframe aggregation, filtering, and property access without reflection overhead.
- **JVM Toolchain (`jvmToolchain(25)`):** Configures the Kotlin and Java compilers to target Java 25 bytecode and ensures the build executes with a compatible JDK 25 runtime.

### Gradle Plugins & Build Tasks

- **Shadow Plugin (`com.gradleup.shadow version "9.6.1"`):**
  - Task: `./gradlew shadowJar`
  - Packages application bytecode, Kotlin runtime libraries, and third-party dependencies (`dataframe`, `kandy-lets-plot`) into a single executable fat JAR.
- **Application Plugin (`application`):**
  - Defines `mainClass.set("com.jimmy.MainKt")` as the entry point for `./gradlew run` and the application distribution.
- **Manifest Configuration (`tasks.jar`):**
  - Injects `Main-Class: com.jimmy.MainKt` into the standard JAR manifest attribute.
- **Testing (`tasks.test`):**
  - Configured with `useJUnitPlatform()` for running unit tests via JUnit 5.

### JVM Runtime & Profiling Requirements

- **Thread Memory Measurement:**
  - The benchmark runner casts `ManagementFactory.getThreadMXBean()` to `com.sun.management.ThreadMXBean` to measure `currentThreadAllocatedBytes`.
  - Enabled dynamically at runtime via `threadMXBean.isThreadAllocatedMemoryEnabled = true`.
  - Requires an OpenJDK / HotSpot-compatible JVM where `isThreadAllocatedMemorySupported` is `true`.
- **Heap & Garbage Collection:**
  - When running extensive benchmarks (e.g., `-i 1000 -difficulty 1-64`), allocate adequate heap space via JVM arguments:
    ```bash
    JAVA_OPTS="-Xms2g -Xmx4g" java -jar build/libs/sudoku-generator-1.0-SNAPSHOT-all.jar -test
    ```

---

## Optimization Versions (V1 – V6)

The project showcases progressive optimization techniques for Sudoku solving and puzzle generation:

```
V1 (Base Array) 
  └─► V2 (ULong Bit-Packed Storage)
        └─► V3 (Bit-Flag Used Cell Tracking)
              └─► V4 (Sub-grid Memoization Caching)
                    └─► V5 (Short-Circuit Solution Counting)
                          └─► V6 (Shuffled Index Pool Cell Removal)
```

1. **V1 (`V1SudokuGridBase`):**
   - **Representation:** Standard `IntArray(81)`.
   - **Characteristics:** Naive recursive backtracking solver. Validates rows, columns, and 3x3 blocks by array indexing and set exclusion.
2. **V2 (`V2SudokuGridPacked`):**
   - **Representation:** Bit-packed `Array<ULong>(9)`.
   - **Characteristics:** Packs an entire 9-cell row into a single 64-bit integer (5 bits per cell: 4 bits for cell value 0–9, 1 bit for flag). Reduces object allocations and improves CPU cache locality.
3. **V3 (`V3SudokuGridFilteredSelection`):**
   - **Representation:** Bit-packed array with active used-cell flags.
   - **Characteristics:** Utilizes the 5th bit (`USED_MASK = 16UL`) to mark cells that were previously attempted for removal, preventing duplicate random retries during puzzle creation.
4. **V4 (`V4SudokuGridCaching`):**
   - **Representation:** Bit-packed array with memoization cache (`Map<SudokuCellStorage, Int>`).
   - **Characteristics:** Caches solution counts of intermediate grid configurations to avoid redundant exploration of previously evaluated board states.
5. **V5 (`V5SudokuGridLimitedSolutions`):**
   - **Representation:** Cached bit-packed array with early-exit pruning.
   - **Characteristics:** Overrides `totalLimitCheck(total) = total > 1` during uniqueness checks. Since Sudoku generation only requires verifying if a solution is unique ($count = 1$), the backtracking solver immediately aborts once a second solution is found.
6. **V6 (`V6SudokuGridBetterRandomSelection`):**
   - **Representation:** Optimized cell sampling with short-circuiting.
   - **Characteristics:** Pre-shuffles indices `0..80` once (`randomOptions`) and removes elements sequentially, achieving $O(1)$ non-repeating cell selection without trial-and-error collisions.
