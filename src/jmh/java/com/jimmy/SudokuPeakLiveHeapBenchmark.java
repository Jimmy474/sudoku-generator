package com.jimmy;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
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

/**
 * Peak live heap during {@code playable()}.
 *
 * <p>Every sample is a full garbage collection. With the Serial collector, and with dead-space
 * retention turned off (see the JVM flags), a full GC leaves only reachable objects in the heap,
 * so the heap used right after it is the exact live heap at that moment. A sampler thread watches how many bytes the benchmark thread
 * has allocated and runs a full GC each time a fixed step is reached, so samples are spread
 * evenly through the operation. A full GC right before the operation gives the baseline, and one
 * right after it gives a final sample while the reduced grid and any cache are still reachable.
 *
 * <p>The step is calibrated once per fork: one untimed {@code playable()} run measures the
 * operation's total allocation, and the step is that total divided by
 * {@code -Dpeak.samplesPerOp} (default {@value #DEFAULT_SAMPLES_PER_OP}), but never below
 * {@value #MIN_STEP_BYTES} bytes. Inputs vary a lot, so if one operation reaches twice the target
 * sample count, the step doubles (and again at four times, and so on) to bound the run time.
 *
 * <p>{@code peakLiveHeapBytes} = grid size before the operation (JOL, as in the retained-memory
 * benchmark) + highest sample - baseline. It is the total memory the grid held at its busiest
 * sampled moment, so it is comparable with the retained-memory benchmark. {@code harnessNoise}
 * runs every measurement step except {@code playable()}; the report subtracts what it measures.
 *
 * <p>Each operation is checked afterwards: the heap's growth from baseline to end must match the
 * grid's own growth measured by JOL. If something else in the JVM changed the heap in between,
 * the operation is not counted ({@code discardedOps}).
 *
 * <p>A high point between two samples can still be missed, which can only make the value too
 * low. The report compares runs with different sample counts to show whether it has settled.
 *
 * <p>No state helper method takes another state as a parameter: in this setup that made JMH
 * create a second {@code BenchmarkState}, so the teardown measured a different grid.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Fork(
    value = 3,
    jvmArgs = {
        "-XX:+UseSerialGC",
        "-Xms256m",
        "-Xmx256m",
        // By default a Serial full GC may leave up to 5% of the old generation as dead objects
        // ("dead wood") instead of compacting them away, and that space still counts as used.
        // These two flags make every full GC compact completely, so used = live.
        "-XX:MarkSweepDeadRatio=0",
        "-XX:MarkSweepAlwaysCompactCount=1"
    }
)
@Threads(1)
public class SudokuPeakLiveHeapBenchmark {
    private static final long INITIAL_SEED = 20261125L;
    static final int DEFAULT_SAMPLES_PER_OP = 500;
    static final long MIN_STEP_BYTES = 8 * 1024;
    static final long MAX_DRIFT_BYTES = 1024;

    @State(Scope.Thread)
    public static class BenchmarkState {
        @Param({"1", "2", "3", "4", "5", "6"})
        public int versionNumber;

        @Param({"40", "50", "57", "60"})
        public int difficulty;

        private long invocation;
        private SudokuGrid grid;
        private LiveHeapSampler sampler;
        private long stepBytes;
        private int samplesPerOp;
        private long gridBytesBefore;
        private long baselineLiveBytes;

        @Setup(Level.Trial)
        public void calibrate() {
            sampler = LiveHeapSampler.start();

            // One untimed run on the first invocation's input to size the sampling step.
            SudokuGrid probe = loadGrid(versionNumber, INITIAL_SEED);
            long before = sampler.allocatedBytes(Thread.currentThread().threadId());
            probe.playable(difficulty);
            long allocated = sampler.allocatedBytes(Thread.currentThread().threadId()) - before;
            samplesPerOp = Integer.getInteger("peak.samplesPerOp", DEFAULT_SAMPLES_PER_OP);
            if (samplesPerOp <= 0) {
                throw new IllegalArgumentException("peak.samplesPerOp must be positive.");
            }
            stepBytes = Math.max(MIN_STEP_BYTES, allocated / samplesPerOp);
        }

        /**
         * JMH's own threads do work around iteration boundaries. Wait here until the live heap
         * has stopped changing for a while, so the first invocation's baseline is not affected.
         */
        @Setup(Level.Iteration)
        public void settle() {
            sampler.waitUntilQuiet();
        }

        @Setup(Level.Invocation)
        public void prepareGrid() {
            grid = loadGrid(versionNumber, INITIAL_SEED + invocation++);
            // Same JOL measurement as the retained-memory benchmark. It runs before the baseline
            // GC, so JOL's own temporary objects are not counted.
            gridBytesBefore = graphSize(grid);
            baselineLiveBytes = sampler.startRecording(
                Thread.currentThread().threadId(), stepBytes, samplesPerOp
            );
        }

        /** End-of-operation sample; called inside the benchmark method so it uses this instance. */
        void finish(PeakHeapCounters counters, int removed) {
            LiveHeapSampler.Result result = sampler.stopRecording();

            // Check that nothing outside the grid changed the heap between the baseline and the
            // end sample: the heap's growth must match the grid's own growth (both measured
            // after the operation, so they exclude the operation's temporary objects). If other
            // JVM objects were freed or created in between, every sample would be shifted.
            long heapGrowth = result.endLiveBytes - baselineLiveBytes;
            long gridGrowth = graphSize(grid) - gridBytesBefore;
            if (Math.abs(heapGrowth - gridGrowth) > MAX_DRIFT_BYTES) {
                counters.discardedOps++;
                return;
            }

            counters.peakLiveHeapBytes += gridBytesBefore + result.peakLiveBytes - baselineLiveBytes;
            counters.gridBytesBefore += gridBytesBefore;
            counters.gcSamples += result.samples;
            counters.sampleStepBytes += stepBytes;
            counters.operationCount++;
            counters.removedCells += removed;
        }

        @TearDown(Level.Invocation)
        public void releaseGrid() {
            // Drop the reduced grid so it is not part of the next invocation's baseline.
            grid = null;
        }

        @TearDown(Level.Trial)
        public void stopSampler() {
            sampler.shutdown();
        }
    }

    static long graphSize(Object root) {
        return GraphLayout.parseInstance(root).totalSize();
    }

    static SudokuGrid loadGrid(int version, long seed) {
        SudokuGrid grid = SudokuAllocationBenchmark.createVersion(version);
        grid.setSeed(seed);
        SudokuGrid loaded = new GridGenerator(seed).generateRandom(
            grid,
            SudokuAllocationBenchmark.SOLVED_GRID
        );
        if (loaded == null || loaded.nonEmptyCount() != 81 || !loaded.isValid()) {
            throw new IllegalStateException("Benchmark input must be a complete valid Sudoku grid.");
        }
        return grid;
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class PeakHeapCounters {
        public long peakLiveHeapBytes;
        public long gridBytesBefore;
        public long gcSamples;
        public long sampleStepBytes;
        public long operationCount;
        public long removedCells;
        public long discardedOps;

        @Setup(Level.Iteration)
        public void reset() {
            discardedOps = 0;
            peakLiveHeapBytes = 0;
            gridBytesBefore = 0;
            gcSamples = 0;
            sampleStepBytes = 0;
            operationCount = 0;
            removedCells = 0;
        }
    }

    // The end-of-operation full GC runs inside the benchmark method, so it is part of the reported
    // time. This benchmark's time is not used; runtime comes from SudokuAllocationBenchmark.
    @Benchmark
    public int playable(BenchmarkState state, PeakHeapCounters counters) {
        int removed = state.grid.playable(state.difficulty);
        state.finish(counters, removed);
        return removed;
    }

    /** Same setup and measurement as {@link #playable}, without the operation. */
    @Benchmark
    public int harnessNoise(BenchmarkState state, PeakHeapCounters counters) {
        state.finish(counters, 0);
        return state.grid.getV();
    }

    /**
     * Runs full GCs while the benchmark thread works and keeps the highest live heap seen.
     * One instance per forked JVM.
     */
    static final class LiveHeapSampler implements Runnable {
        private static final int STABLE_ATTEMPTS = 50;
        private static final long STABLE_PAUSE_NANOS = TimeUnit.MILLISECONDS.toNanos(2);
        private static final long STABLE_TOLERANCE_BYTES = 64;
        private static LiveHeapSampler instance;

        private final com.sun.management.ThreadMXBean threads;
        private final MemoryPoolMXBean oldPool;
        private final GarbageCollectorMXBean fullCollector;
        private final List<GarbageCollectorMXBean> collectors;
        private final Thread thread;

        private volatile boolean running = true;
        private volatile boolean active;
        private volatile boolean busy;
        private volatile long epoch;
        private volatile long targetThreadId;
        private volatile long stepBytes;
        private volatile int targetSamples;
        private volatile long peakLiveBytes;
        private volatile long samples;

        static final class Result {
            final long peakLiveBytes;
            final long endLiveBytes;
            final long samples;

            Result(long peakLiveBytes, long endLiveBytes, long samples) {
                this.peakLiveBytes = peakLiveBytes;
                this.endLiveBytes = endLiveBytes;
                this.samples = samples;
            }
        }

        static synchronized LiveHeapSampler start() {
            if (instance == null) {
                instance = new LiveHeapSampler();
                instance.thread.start();
            }
            return instance;
        }

        private LiveHeapSampler() {
            java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (!(bean instanceof com.sun.management.ThreadMXBean)) {
                throw new IllegalStateException(
                    "This JVM does not expose com.sun.management.ThreadMXBean."
                );
            }
            threads = (com.sun.management.ThreadMXBean) bean;
            if (!threads.isThreadAllocatedMemorySupported()) {
                throw new IllegalStateException(
                    "This JVM does not support per-thread allocation measurement."
                );
            }
            threads.setThreadAllocatedMemoryEnabled(true);

            collectors = ManagementFactory.getGarbageCollectorMXBeans();
            List<String> names = new ArrayList<>();
            for (GarbageCollectorMXBean collector : collectors) {
                names.add(collector.getName());
            }
            if (!names.contains("Copy") || !names.contains("MarkSweepCompact")) {
                throw new IllegalStateException(
                    "Peak live heap benchmark expects -XX:+UseSerialGC; found collectors " + names
                );
            }
            GarbageCollectorMXBean full = null;
            for (GarbageCollectorMXBean collector : collectors) {
                if (collector.getName().equals("MarkSweepCompact")) {
                    full = collector;
                }
            }
            fullCollector = full;
            MemoryPoolMXBean old = null;
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP && pool.getName().equals("Tenured Gen")) {
                    old = pool;
                }
            }
            if (old == null) {
                throw new IllegalStateException("Serial old generation pool 'Tenured Gen' not found.");
            }
            oldPool = old;

            thread = new Thread(this, "peak-live-heap-sampler");
            thread.setDaemon(true);
        }

        long allocatedBytes(long threadId) {
            return threads.getThreadAllocatedBytes(threadId);
        }

        /**
         * Live heap while no operation is running, once the rest of the JVM is quiet.
         *
         * <p>JMH's runner thread does work of its own between iterations (collecting and printing
         * results), and the objects it holds for that would otherwise be counted. Full GCs are
         * repeated, a few milliseconds apart, until two in a row agree.
         */
        long liveBytesNow() {
            long previous = requireFullGc();
            for (int attempt = 0; attempt < STABLE_ATTEMPTS; attempt++) {
                LockSupport.parkNanos(STABLE_PAUSE_NANOS);
                long current = requireFullGc();
                if (Math.abs(current - previous) <= STABLE_TOLERANCE_BYTES) {
                    return current;
                }
                previous = current;
            }
            throw new IllegalStateException(
                "Live heap did not settle after " + STABLE_ATTEMPTS + " full GCs."
            );
        }

        /** Full GCs 10 ms apart until five in a row agree, or 3 seconds pass. */
        void waitUntilQuiet() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            long previous = requireFullGc();
            int agreeing = 0;
            while (agreeing < 4 && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                long current = requireFullGc();
                agreeing = Math.abs(current - previous) <= STABLE_TOLERANCE_BYTES ? agreeing + 1 : 0;
                previous = current;
            }
        }

        private long requireFullGc() {
            long live = fullGcLiveBytes();
            if (live < 0) {
                throw new IllegalStateException("Full GC did not run.");
            }
            return live;
        }

        /** Quiet full GC before the operation; returns the live heap and starts sampling. */
        long startRecording(long threadId, long step, int target) {
            long baseline = liveBytesNow();
            targetThreadId = threadId;
            stepBytes = step;
            targetSamples = target;
            peakLiveBytes = Long.MIN_VALUE;
            samples = 0;
            epoch++;
            active = true;
            LockSupport.unpark(thread);
            return baseline;
        }

        /** Stops sampling, takes the end-of-operation sample, and returns the highest sample. */
        Result stopRecording() {
            active = false;
            while (busy) {
                Thread.onSpinWait();
            }
            long end = liveBytesNow();
            record(end);
            return new Result(peakLiveBytes, end, samples);
        }

        void shutdown() {
            running = false;
            LockSupport.unpark(thread);
        }

        @Override
        public void run() {
            long nextSampleAt = 0;
            long seenEpoch = 0;
            long step = 0;
            long doubleStepAt = 0;
            while (running) {
                if (!active) {
                    LockSupport.park(this);
                    continue;
                }
                busy = true;
                try {
                    if (!active) {
                        continue;
                    }
                    long allocated = threads.getThreadAllocatedBytes(targetThreadId);
                    if (seenEpoch != epoch) {
                        // First poll of a new operation.
                        seenEpoch = epoch;
                        step = stepBytes;
                        doubleStepAt = 2L * targetSamples;
                        nextSampleAt = allocated + step;
                    } else if (allocated >= nextSampleAt) {
                        long live = fullGcLiveBytes();
                        if (live >= 0) {
                            record(live);
                        }
                        // Some inputs allocate far more than the calibration run. Past twice the
                        // target sample count, double the step so the run time stays bounded.
                        if (samples >= doubleStepAt) {
                            step *= 2;
                            doubleStepAt *= 2;
                        }
                        nextSampleAt = threads.getThreadAllocatedBytes(targetThreadId) + step;
                    }
                } finally {
                    busy = false;
                }
                // Busy-wait: parkNanos sleeps about 1 ms on Windows, far longer than a step.
                Thread.onSpinWait();
            }
        }

        private void record(long live) {
            if (live > peakLiveBytes) {
                peakLiveBytes = live;
            }
            samples++;
        }

        /**
         * Runs a full GC and returns the live heap right after it, or -1 if no full GC ran.
         *
         * <p>A Serial full GC compacts every live object, young ones included, into the old
         * generation and leaves the young generation empty. The old pool's collection usage is
         * recorded at the end of that GC and young collections do not change it, so it is the
         * exact live heap even if the benchmark thread starts allocating again immediately.
         */
        private long fullGcLiveBytes() {
            long fullBefore = fullCollector.getCollectionCount();
            System.gc();
            long live = oldPool.getCollectionUsage().getUsed();
            return fullCollector.getCollectionCount() > fullBefore ? live : -1;
        }
    }
}
