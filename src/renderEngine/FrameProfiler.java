package renderEngine;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLSync;

import java.util.Arrays;

/**
 * Where the time of a frame goes. {@link FramePipeline} marks the end of each of its passes; the profiler
 * always counts the draw calls and triangles (shown in the window title), and while timing is on (the
 * benchmark switches it on) it also measures the CPU and GPU time of every pass.
 *
 * GPU times come from timestamp queries read back a few frames later, so measuring never makes the CPU wait
 * for the GPU. A GPU time includes any moments the GPU sat idle waiting for the CPU to send work, so a pass
 * whose GPU time is about its CPU time is limited by the CPU.
 *
 * While timing, the CPU is also kept at most {@link #FRAMES_AHEAD} frames ahead of the GPU, and the time it
 * waits for that is measured on its own. Otherwise the driver would make it wait somewhere inside the first
 * pass of a frame, and that pass would look slow when really the graphics card is behind.
 *
 * Measurements add up in one of {@link #GROUPS} groups (see {@link #setGroup}), so a benchmark can compare two
 * settings it alternates between.
 */
public final class FrameProfiler {

    /** The passes of a frame, in the order {@link FramePipeline} draws them. */
    public static final String[] PASSES = {"shadows", "water reflection", "water refraction", "main view", "post-processing"};
    public static final int GROUPS = 2;
    private static final int FRAMES_IN_FLIGHT = 4;
    private static final int FRAMES_AHEAD = 2;

    private static int draws, pass, passFirstDraw, lastFrameDraws;
    private static long triangles, passFirstTriangle, lastFrameTriangles;

    private static boolean timing;
    private static int group;                 // where this frame's measurements go; -1 = nowhere
    private static int[][] queries;           // [ring slot][0 = frame start, i + 1 = end of pass i]
    private static final int[] slotGroup = new int[FRAMES_IN_FLIGHT];
    private static final boolean[] pending = new boolean[FRAMES_IN_FLIGHT];
    private static final GLSync[] frameDone = new GLSync[FRAMES_IN_FLIGHT];
    private static int slot;
    private static long passStart;

    // sums since reset(), per group
    private static final double[][] cpuMs = new double[GROUPS][PASSES.length], gpuMs = new double[GROUPS][PASSES.length];
    private static final long[][] drawSum = new long[GROUPS][PASSES.length], triangleSum = new long[GROUPS][PASSES.length];
    private static final int[] cpuFrames = new int[GROUPS], gpuFrames = new int[GROUPS];
    private static final double[] waitMs = new double[GROUPS];

    private FrameProfiler() {
    }

    /** Call right after every draw call. */
    public static void countDraw(long triangleCount) {
        draws++;
        triangles += triangleCount;
    }

    /** Draw calls of the last complete frame. */
    public static int lastFrameDraws() {
        return lastFrameDraws;
    }

    public static long lastFrameTriangles() {
        return lastFrameTriangles;
    }

    public static void setTiming(boolean enabled) {
        if (enabled && queries == null) {
            queries = new int[FRAMES_IN_FLIGHT][PASSES.length + 1];
            for (int[] frame : queries)
                for (int i = 0; i < frame.length; i++) frame[i] = GL15.glGenQueries();
        }
        if (!enabled) {
            flush();
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) waitFor(i);
        }
        timing = enabled;
    }

    /** The group the next frames are measured in (0 unless set), or -1 to measure them nowhere. */
    public static void setGroup(int frameGroup) {
        group = frameGroup;
    }

    public static void beginFrame() {
        draws = passFirstDraw = pass = 0;
        triangles = passFirstTriangle = 0;
        if (!timing) return;
        slot = (slot + 1) % FRAMES_IN_FLIGHT;
        long waitStart = System.nanoTime();
        waitFor((slot + FRAMES_IN_FLIGHT - FRAMES_AHEAD) % FRAMES_IN_FLIGHT);
        passStart = System.nanoTime();
        if (group >= 0) waitMs[group] += (passStart - waitStart) / 1e6;
        if (pending[slot]) readBack(slot); // drawn FRAMES_IN_FLIGHT frames ago: long finished
        slotGroup[slot] = group;
        GL33.glQueryCounter(queries[slot][0], GL33.GL_TIMESTAMP);
    }

    /** Blocks until the GPU has finished the frame drawn in ring slot {@code frame} (if it was timed). */
    private static void waitFor(int frame) {
        if (frameDone[frame] == null) return;
        while (GL32.glClientWaitSync(frameDone[frame], GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 100_000_000L) == GL32.GL_TIMEOUT_EXPIRED) {
            Thread.onSpinWait();
        }
        GL32.glDeleteSync(frameDone[frame]);
        frameDone[frame] = null;
    }

    /** Call when a pass is done, also when it was skipped, so every frame has a mark for each of {@link #PASSES}. */
    public static void endPass() {
        if (pass >= PASSES.length) throw new IllegalStateException("more passes than FrameProfiler.PASSES");
        int passDraws = draws - passFirstDraw;
        long passTriangles = triangles - passFirstTriangle;
        passFirstDraw = draws;
        passFirstTriangle = triangles;
        if (timing) {
            long now = System.nanoTime();
            if (group >= 0) {
                cpuMs[group][pass] += (now - passStart) / 1e6;
                drawSum[group][pass] += passDraws;
                triangleSum[group][pass] += passTriangles;
            }
            passStart = now;
            GL33.glQueryCounter(queries[slot][pass + 1], GL33.GL_TIMESTAMP);
        }
        if (++pass == PASSES.length) {
            lastFrameDraws = draws;
            lastFrameTriangles = triangles;
            if (timing) {
                pending[slot] = true;
                frameDone[slot] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                if (group >= 0) cpuFrames[group]++;
            }
        }
    }

    private static void readBack(int frame) {
        int into = slotGroup[frame];
        long previous = GL33.glGetQueryObjectui64(queries[frame][0], GL15.GL_QUERY_RESULT);
        for (int i = 0; i < PASSES.length; i++) {
            long time = GL33.glGetQueryObjectui64(queries[frame][i + 1], GL15.GL_QUERY_RESULT);
            if (into >= 0) gpuMs[into][i] += (time - previous) / 1e6;
            previous = time;
        }
        pending[frame] = false;
        if (into >= 0) gpuFrames[into]++;
    }

    /** Waits for the GPU times of the frames still in flight, so the averages include every frame drawn. */
    public static void flush() {
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) if (pending[i]) readBack(i);
    }

    /** Starts new averages. */
    public static void reset() {
        flush();
        for (int g = 0; g < GROUPS; g++) {
            Arrays.fill(cpuMs[g], 0);
            Arrays.fill(gpuMs[g], 0);
            Arrays.fill(drawSum[g], 0);
            Arrays.fill(triangleSum[g], 0);
            cpuFrames[g] = gpuFrames[g] = 0;
            waitMs[g] = 0;
        }
    }

    /** Average CPU milliseconds per frame that pass {@code i} took in group {@code g} since {@link #reset} (call {@link #flush} first). */
    public static double cpuMs(int g, int i) {
        return cpuFrames[g] == 0 ? 0 : cpuMs[g][i] / cpuFrames[g];
    }

    public static double gpuMs(int g, int i) {
        return gpuFrames[g] == 0 ? 0 : gpuMs[g][i] / gpuFrames[g];
    }

    /** Average milliseconds per frame the CPU waited for the GPU to catch up: near 0 when the CPU is the limit. */
    public static double gpuWaitMs(int g) {
        return cpuFrames[g] == 0 ? 0 : waitMs[g] / cpuFrames[g];
    }

    public static double draws(int g, int i) {
        return cpuFrames[g] == 0 ? 0 : (double) drawSum[g][i] / cpuFrames[g];
    }

    public static double triangles(int g, int i) {
        return cpuFrames[g] == 0 ? 0 : (double) triangleSum[g][i] / cpuFrames[g];
    }
}
