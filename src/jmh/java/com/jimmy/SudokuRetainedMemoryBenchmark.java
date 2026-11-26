package com.jimmy;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jol.info.GraphLayout;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
public class SudokuRetainedMemoryBenchmark {
    private static final long INITIAL_SEED = 20261125L;

    @State(Scope.Thread)
    public static class BenchmarkState {
        @Param({"1", "2", "3", "4", "5", "6"})
        public int versionNumber;

        @Param({"40", "50", "57", "60"})
        public int difficulty;

        private long invocation;
        private SudokuGrid grid;
        private int removedCells;

        @Setup(Level.Invocation)
        public void prepareReducedGrid() {
            long seed = INITIAL_SEED + invocation++;
            grid = SudokuAllocationBenchmark.createVersion(versionNumber);
            grid.setSeed(seed);

            SudokuGrid loaded = new GridGenerator(seed).generateRandom(
                grid,
                SudokuAllocationBenchmark.SOLVED_GRID
            );
            if (loaded == null || loaded.nonEmptyCount() != 81 || !loaded.isValid()) {
                throw new IllegalStateException("Benchmark input must be a complete valid Sudoku grid.");
            }

            removedCells = grid.playable(difficulty);
        }
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class RetainedMemoryCounters {
        public long retainedGraphBytes;
        public long operationCount;
        public long removedCells;

        @Setup(Level.Iteration)
        public void reset() {
            retainedGraphBytes = 0;
            operationCount = 0;
            removedCells = 0;
        }

        @TearDown(Level.Invocation)
        public void recordReachableGraph(BenchmarkState state) {
            retainedGraphBytes += GraphLayout.parseInstance(state.grid).totalSize();
            operationCount++;
            removedCells += state.removedCells;
        }
    }

    @Benchmark
    public int measureRetainedGraph(BenchmarkState state, RetainedMemoryCounters counters) {
        return state.removedCells;
    }
}
