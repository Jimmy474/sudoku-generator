package com.jimmy;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
@Threads(1)
public class SudokuAllocationBenchmark {
    static final String SOLVED_GRID =
        "379284615428561739561793428213657984786149253945328176"
            + "157436892692875341834912567";
    private static final long INITIAL_SEED = 20261125L;

    @State(Scope.Thread)
    public static class BenchmarkState {
        @Param({"1", "2", "3", "4", "5", "6"})
        public int versionNumber;

        @Param({"40", "50", "57", "60"})
        public int difficulty;

        private long invocation;
        private SudokuGrid grid;
        private ThreadMXBean allocationBean;

        @Setup(Level.Trial)
        public void initializeAllocationCounter() {
            java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (!(bean instanceof ThreadMXBean)) {
                throw new IllegalStateException(
                    "This JVM does not expose com.sun.management.ThreadMXBean."
                );
            }

            allocationBean = (ThreadMXBean) bean;
            if (!allocationBean.isThreadAllocatedMemorySupported()) {
                throw new IllegalStateException(
                    "This JVM does not support per-thread allocation measurement."
                );
            }
            if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
                allocationBean.setThreadAllocatedMemoryEnabled(true);
            }
        }

        @Setup(Level.Invocation)
        public void prepareGrid() {
            long seed = INITIAL_SEED + invocation++;
            grid = createVersion(versionNumber);
            grid.setSeed(seed);

            SudokuGrid loaded = new GridGenerator(seed).generateRandom(grid, SOLVED_GRID);
            if (loaded == null || loaded.nonEmptyCount() != 81 || !loaded.isValid()) {
                throw new IllegalStateException("Benchmark input must be a complete valid Sudoku grid.");
            }
        }
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class OperationCounters {
        public long allocatedBytes;
        public long operationCount;
        public long removedCells;

        @Setup(Level.Iteration)
        public void reset() {
            allocatedBytes = 0;
            operationCount = 0;
            removedCells = 0;
        }
    }

    @Benchmark
    public int playable(BenchmarkState state, OperationCounters counters) {
        long before = state.allocationBean.getCurrentThreadAllocatedBytes();
        int removed = state.grid.playable(state.difficulty);
        long allocated = state.allocationBean.getCurrentThreadAllocatedBytes() - before;

        if (allocated < 0) {
            throw new IllegalStateException("Per-thread allocated-byte counter moved backwards.");
        }

        counters.allocatedBytes += allocated;
        counters.operationCount++;
        counters.removedCells += removed;
        return removed;
    }

    static SudokuGrid createVersion(int version) {
        switch (version) {
            case 1:
                return new V1SudokuGridBase();
            case 2:
                return new V2SudokuGridPacked();
            case 3:
                return new V3SudokuGridFilteredSelection();
            case 4:
                return new V4SudokuGridCaching();
            case 5:
                return new V5SudokuGridLimitedSolutions();
            case 6:
                return new V6SudokuGridBetterRandomSelection();
            default:
                throw new IllegalArgumentException("Unsupported Sudoku version: " + version);
        }
    }
}
