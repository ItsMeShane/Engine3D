package engineTester;

import entities.Camera;
import entities.Entity;
import entities.Light;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import particles.ParticleMaster;
import renderEngine.DisplayManager;
import renderEngine.FramePipeline;
import renderEngine.FrameProfiler;
import renderEngine.GraphicsSettings;
import renderEngine.GraphicsSettings.Setting;
import terrains.Terrain;
import water.WaterTile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Measures how fast the engine draws a scene, to compare a change before and after.
 *
 * Plays the scene's b-roll shots (broll/&lt;scene&gt;.txt) in the window as fast as the machine can draw them: no
 * frame cap, no vsync. The world advances by a fixed step each frame, so every run draws exactly the same
 * frames whatever the frame rate. Reports frames per second for every shot and, for the whole run, where the
 * time goes: CPU time, GPU time, draw calls and triangles of each pass (see FrameProfiler).
 * Results are printed, written to bench/&lt;scene&gt;_&lt;label&gt;.txt and added to bench/history.csv.
 *
 * Other programs drawing on the same graphics card (the desktop, an editor, a browser) change the frame rate
 * from one run to the next by several percent. To compare two settings, let one run alternate between them
 * (--ab): both then see the same views and the same disturbances, and the difference is what the setting costs.
 *
 * Program arguments (after the scene name):
 *   --bench            all shots
 *   --bench=a+b        only the shots named a and b
 *   --label=baseline   name of the run in the results (default: date and time)
 *   --fps=30           frames drawn per second of shot (default 30); more makes the run longer and steadier
 *   --set=...          settings changed for the whole run, on top of graphics.properties (see below)
 *   --ab=...           compare: every 20 frames the run swaps between the settings as they are (A) and the
 *                      same with these changes (B)
 *
 * A list of changes is separated by commas. Each is either a switch's key, which turns it off (1 anti-aliasing
 * ... 8 normal mapping, c culling; see GraphicsSettings), or setting=value with any setting's name as in
 * graphics.properties, e.g.  --ab=shadowMapSize=3072,anisotropy=4  or  --set=5,msaaSamples=2
 */
public class Benchmark {

    /** Seconds the world runs before the first frame, so smoke, mist and fireflies are already there (as in BRollRecorder). */
    private static final int WARM_UP_SECONDS = 25;
    /** Frames drawn before measuring starts, while the driver finishes preparing shaders and textures. */
    private static final int UNTIMED_FRAMES = 60;
    /**
     * When comparing: frames one setting keeps before the other takes over, and how many of them at the start are
     * not timed, because the graphics card is still busy with frames of the other setting.
     */
    private static final int BLOCK = 20, SETTLE = 4;

    /** A setting and the value a run gives it. */
    private record Change(Setting setting, int value) {
    }

    private Set<String> only; // null = all shots
    private String label = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"));
    private int fps = 30;
    private final List<Change> changes = new ArrayList<>(); // for the whole run
    private final List<Change> compare = new ArrayList<>(); // setting B
    private int[] compareBase;                               // setting A's values of what B changes
    private final String[] settingsText = new String[2];     // what A and B are, for the reports

    private record ShotResult(String name, float[] frameMs, double draws, double triangles) {
        double seconds() {
            double sum = 0;
            for (float ms : frameMs) sum += ms / 1000;
            return sum;
        }

        double fps() {
            return frameMs.length / seconds();
        }
    }

    private Benchmark() {
    }

    /** @return null unless the arguments ask for --bench */
    public static Benchmark fromArgs(String[] args) {
        Benchmark benchmark = new Benchmark();
        boolean requested = false;
        for (String arg : args) {
            String value = arg.contains("=") ? arg.substring(arg.indexOf('=') + 1) : "";
            if (arg.equals("--bench")) requested = true;
            else if (arg.startsWith("--bench=")) {
                requested = true;
                benchmark.only = new HashSet<>(Arrays.asList(value.split("[,+]")));
            } else if (arg.startsWith("--label=")) benchmark.label = value.replaceAll("[^A-Za-z0-9._-]", "_");
            else if (arg.startsWith("--fps=")) benchmark.fps = Integer.parseInt(value);
            else if (arg.startsWith("--set=")) benchmark.changes.addAll(changes(value));
            else if (arg.startsWith("--ab=")) benchmark.compare.addAll(changes(value));
        }
        return requested ? benchmark : null;
    }

    /** "5,c" (switches by their key, turned off), "shadowMapSize=2048,msaaSamples=2" (any setting), or both mixed. */
    private static List<Change> changes(String list) {
        List<Change> result = new ArrayList<>();
        for (String item : list.split(",")) {
            item = item.trim();
            int equals = item.indexOf('=');
            Setting setting = GraphicsSettings.find(equals < 0 ? item : item.substring(0, equals).trim());
            if (setting == null || equals < 0 && !setting.isSwitch()) {
                throw new IllegalArgumentException("\"" + item + "\" is not a switch key or setting=value. Settings: "
                        + GraphicsSettings.ALL.stream().map(s -> s.key() + (s.hotkey() != null ? " (" + s.hotkey() + ")" : "")).toList());
            }
            result.add(new Change(setting, equals < 0 ? 0 : setting.parse(item.substring(equals + 1))));
        }
        return result;
    }

    /** Setting 0 (A) is the run's settings, setting 1 (B) the same with the compared changes. */
    private void apply(int setting) {
        for (int i = 0; i < compare.size(); i++) compare.get(i).setting().set(setting == 0 ? compareBase[i] : compare.get(i).value());
    }

    private String describe(int setting) {
        return settingsText[setting];
    }

    /** Runs the shots and reports; the caller shuts the engine down. */
    public void run(String sceneName, BRoll bRoll, GameScene scene, FramePipeline pipeline, List<Entity> entities, Terrain[][] terrains,
                    List<Light> lights, Camera camera, List<WaterTile> waterTiles) throws IOException {
        List<BRoll.Shot> shots = new ArrayList<>();
        for (BRoll.Shot shot : bRoll.shots()) if (only == null || only.contains(shot.name())) shots.add(shot);
        if (shots.isEmpty()) {
            System.err.println("No shot matches " + only + ". Shots in this scene: " + bRoll.shots().stream().map(BRoll.Shot::name).toList());
            return;
        }
        Frame frame = () -> {
            scene.update();
            ParticleMaster.update();
            pipeline.render(entities, terrains, lights, camera, waterTiles, scene.getSun());
            DisplayManager.updateDisplay();
            return !Display.isCloseRequested();
        };
        boolean comparing = !compare.isEmpty();
        int settings = comparing ? 2 : 1;

        for (Change change : changes) change.setting().set(change.value());
        compareBase = compare.stream().mapToInt(change -> change.setting().get()).toArray();
        settingsText[0] = GraphicsSettings.summary();
        apply(1);
        settingsText[1] = GraphicsSettings.summary();
        apply(0);
        Display.setVSyncEnabled(false);
        DisplayManager.setFixedFrameTime(1f / fps); // also switches the frame cap off
        for (int i = 0; i < WARM_UP_SECONDS * fps; i++) {
            scene.update();
            ParticleMaster.update();
            DisplayManager.advanceFixedFrame();
        }
        Display.setTitle("Benchmark: warming up   (close the window to stop)");
        for (int i = 0; i < UNTIMED_FRAMES; i++) {
            bRoll.place(shots.get(0), 0);
            if (!frame.draw()) return;
        }

        System.out.printf(Locale.ROOT, "%nbenchmark: %s, %d shots, %d frames per second of shot%s%n", sceneName, shots.size(), fps,
                comparing ? ", comparing A (" + describe(0) + ") with B (" + describe(1) + ")" : "");
        FrameProfiler.setTiming(true);
        FrameProfiler.reset();
        List<List<ShotResult>> results = new ArrayList<>();
        for (int s = 0; s < settings; s++) results.add(new ArrayList<>());
        for (BRoll.Shot shot : shots) {
            leadIn(scene, shot);
            int frames = Math.max(comparing ? 2 * BLOCK : 2, Math.round(shot.seconds() * fps));
            float[][] frameMs = new float[settings][frames];
            int[] timed = new int[settings];
            long[] draws = new long[settings], triangles = new long[settings];
            long last = System.nanoTime();
            for (int i = 0; i < frames; i++) {
                int setting = comparing ? (i / BLOCK) % 2 : 0;
                boolean settling = comparing && i % BLOCK < SETTLE;
                if (comparing && i % BLOCK == 0) apply(setting);
                FrameProfiler.setGroup(settling ? -1 : setting);
                float t = i / (float) (frames - 1);
                bRoll.place(shot, t);
                scene.shotTime(shot.name(), t * shot.seconds());
                if (!frame.draw()) {
                    System.out.println("Stopped: the window was closed.");
                    FrameProfiler.setTiming(false);
                    return;
                }
                long now = System.nanoTime();
                if (!settling) {
                    frameMs[setting][timed[setting]++] = (now - last) / 1e6f;
                    draws[setting] += FrameProfiler.lastFrameDraws();
                    triangles[setting] += FrameProfiler.lastFrameTriangles();
                }
                last = now;
                if (i % fps == 0)
                    Display.setTitle(String.format(Locale.ROOT, "Benchmark: shot %d of %d, %s   (close the window to stop)", results.get(0).size() + 1, shots.size(), shot.name()));
            }
            for (int s = 0; s < settings; s++) {
                ShotResult result = new ShotResult(shot.name(), Arrays.copyOf(frameMs[s], timed[s]), (double) draws[s] / timed[s], (double) triangles[s] / timed[s]);
                results.get(s).add(result);
            }
            System.out.println(comparing ? compareLine(results.get(0).get(results.get(0).size() - 1), results.get(1).get(results.get(1).size() - 1))
                    : shotLine(results.get(0).get(results.get(0).size() - 1)));
        }
        apply(0);
        FrameProfiler.flush();
        FrameProfiler.setTiming(false);
        FrameProfiler.setGroup(0);
        scene.shotTime(null, 0);

        String report = comparing ? comparisonReport(sceneName, results.get(0), results.get(1)) : report(sceneName, results.get(0));
        System.out.println();
        System.out.println(report);
        Path dir = Path.of("bench");
        Files.createDirectories(dir);
        Path file = dir.resolve(sceneName + "_" + label + ".txt");
        Files.writeString(file, report, StandardCharsets.UTF_8);
        for (int s = 0; s < settings; s++) appendHistory(dir.resolve("history.csv"), sceneName, comparing ? label + (s == 0 ? "_A" : "_B") : label, s, results.get(s));
        System.out.println("saved " + file.toAbsolutePath() + " and " + dir.resolve("history.csv"));
    }

    private interface Frame {
        /** Advances the world one step and draws it to the window. @return false once the window was closed */
        boolean draw();
    }

    /** As BRollRecorder: runs the world, undrawn, through the lead-in the scene asks for before a shot starts. */
    private void leadIn(GameScene scene, BRoll.Shot shot) {
        int frames = Math.round(scene.shotLeadIn(shot.name()) * fps);
        for (int i = frames; i > 0; i--) {
            scene.shotTime(shot.name(), -i / (float) fps);
            scene.update();
            ParticleMaster.update();
            DisplayManager.advanceFixedFrame();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // results

    private static double averageFps(List<ShotResult> results) {
        double seconds = 0;
        int frames = 0;
        for (ShotResult r : results) {
            seconds += r.seconds();
            frames += r.frameMs().length;
        }
        return frames / seconds;
    }

    /** Frame rate of the slowest 1% of frames: the stutter you notice, which an average hides. */
    private static double onePercentLow(float[] frameMs) {
        float[] sorted = frameMs.clone();
        Arrays.sort(sorted);
        return 1000 / sorted[Math.max(0, (int) Math.ceil(sorted.length * 0.99) - 1)];
    }

    private static float[] allFrames(List<ShotResult> results) {
        int count = 0;
        for (ShotResult r : results) count += r.frameMs().length;
        float[] all = new float[count];
        int i = 0;
        for (ShotResult r : results) {
            System.arraycopy(r.frameMs(), 0, all, i, r.frameMs().length);
            i += r.frameMs().length;
        }
        return all;
    }

    /** Per-frame average over the whole run of a per-shot average. */
    private static double perFrame(List<ShotResult> results, ToDoubleFunction<ShotResult> perShot) {
        double sum = 0;
        int frames = 0;
        for (ShotResult r : results) {
            sum += perShot.applyAsDouble(r) * r.frameMs().length;
            frames += r.frameMs().length;
        }
        return sum / frames;
    }

    private static String shotLine(ShotResult r) {
        return String.format(Locale.ROOT, "%-24s %7d %9.1f %8.1f %8.2f %11.0f %11.2f", r.name(), r.frameMs().length, r.fps(), onePercentLow(r.frameMs()), 1000 / r.fps(),
                r.draws(), r.triangles() / 1e6);
    }

    private static String compareLine(ShotResult a, ShotResult b) {
        return String.format(Locale.ROOT, "%-24s %9.1f %9.1f %+8.1f%% %10.1f %10.1f", a.name(), a.fps(), b.fps(), 100 * (b.fps() / a.fps() - 1),
                onePercentLow(a.frameMs()), onePercentLow(b.frameMs()));
    }

    private String header(String sceneName, int frames) {
        return String.format(Locale.ROOT, "Engine 3D benchmark: %s, \"%s\", %s%n%s, window %dx%d, %,d timed frames (%d per second of shot)%n",
                sceneName, label, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                GL11.glGetString(GL11.GL_RENDERER), DisplayManager.renderWidth(), DisplayManager.renderHeight(), frames, fps);
    }

    private static final String NOTES = """
            CPU ms: time the engine spends sending a pass to the graphics card. GPU ms: time the card spends on it.
            Waiting: the CPU had its work done and sat idle until the card caught up. A lot of waiting means the
            graphics card is the limit (pixels, texture reads, shadow map size); next to none means the CPU is
            (draw calls, per-entity work), and then a pass's GPU time is mostly the card waiting for that pass.
            """;

    private String report(String sceneName, List<ShotResult> results) {
        float[] all = allFrames(results);
        double fps = averageFps(results), frameMs = 1000 / fps;
        StringBuilder out = new StringBuilder(header(sceneName, all.length));
        out.append(describe(0)).append("\n\n");
        out.append(String.format(Locale.ROOT, "%-24s %7s %9s %8s %8s %11s %11s%n", "shot", "frames", "avg fps", "1% low", "avg ms", "draw calls", "M triangles"));
        for (ShotResult r : results) out.append(shotLine(r)).append('\n');
        out.append(String.format(Locale.ROOT, "%-24s %7d %9.1f %8.1f %8.2f %11.0f %11.2f%n%n", "whole run", all.length, fps, onePercentLow(all), frameMs,
                perFrame(results, ShotResult::draws), perFrame(results, ShotResult::triangles) / 1e6));

        out.append(String.format(Locale.ROOT, "%-40s %8s %8s %11s %11s%n", "where a frame's time goes (average)", "CPU ms", "GPU ms", "draw calls", "M triangles"));
        double cpuTotal = 0, gpuTotal = 0;
        for (int i = 0; i < FrameProfiler.PASSES.length; i++) {
            cpuTotal += FrameProfiler.cpuMs(0, i);
            gpuTotal += FrameProfiler.gpuMs(0, i);
            out.append(String.format(Locale.ROOT, "%-40s %8.2f %8.2f %11.0f %11.2f%n", FrameProfiler.PASSES[i], FrameProfiler.cpuMs(0, i), FrameProfiler.gpuMs(0, i),
                    FrameProfiler.draws(0, i), FrameProfiler.triangles(0, i) / 1e6));
        }
        double wait = FrameProfiler.gpuWaitMs(0);
        out.append(String.format(Locale.ROOT, "%-40s %8.2f %8.2f%n", "all passes", cpuTotal, gpuTotal));
        out.append(String.format(Locale.ROOT, "%-40s %8.2f%n", "CPU waiting for the GPU to catch up", wait));
        out.append(String.format(Locale.ROOT, "%-40s %8.2f%n", "rest (scene update, buffer swap)", frameMs - cpuTotal - wait));
        out.append(String.format(Locale.ROOT, "%-40s %8.2f%n%n", "frame", frameMs));
        return out.append(NOTES).toString();
    }

    private String comparisonReport(String sceneName, List<ShotResult> a, List<ShotResult> b) {
        float[] allA = allFrames(a), allB = allFrames(b);
        double fpsA = averageFps(a), fpsB = averageFps(b);
        StringBuilder out = new StringBuilder(header(sceneName, allA.length + allB.length));
        out.append(String.format(Locale.ROOT, "A: %s%nB: %s%nThe run swaps between them every %d frames.%n%n", describe(0), describe(1), BLOCK));
        out.append(String.format(Locale.ROOT, "%-24s %9s %9s %9s %10s %10s%n", "shot", "A fps", "B fps", "B vs A", "A 1% low", "B 1% low"));
        for (int i = 0; i < a.size(); i++) out.append(compareLine(a.get(i), b.get(i))).append('\n');
        out.append(String.format(Locale.ROOT, "%-24s %9.1f %9.1f %+8.1f%% %10.1f %10.1f%n", "whole run", fpsA, fpsB, 100 * (fpsB / fpsA - 1), onePercentLow(allA), onePercentLow(allB)));
        out.append(String.format(Locale.ROOT, "%-24s %9.2f %9.2f %+8.2f ms%n%n", "frame time (ms)", 1000 / fpsA, 1000 / fpsB, 1000 / fpsB - 1000 / fpsA));

        out.append(String.format(Locale.ROOT, "%-30s %7s %7s %7s %7s %8s %8s %8s %8s%n", "per frame (average)", "A CPU", "B CPU", "A GPU", "B GPU", "A draws", "B draws", "A Mtri", "B Mtri"));
        double[] cpu = new double[2], gpu = new double[2];
        for (int i = 0; i < FrameProfiler.PASSES.length; i++) {
            for (int g = 0; g < 2; g++) {
                cpu[g] += FrameProfiler.cpuMs(g, i);
                gpu[g] += FrameProfiler.gpuMs(g, i);
            }
            out.append(String.format(Locale.ROOT, "%-30s %7.2f %7.2f %7.2f %7.2f %8.0f %8.0f %8.2f %8.2f%n", FrameProfiler.PASSES[i],
                    FrameProfiler.cpuMs(0, i), FrameProfiler.cpuMs(1, i), FrameProfiler.gpuMs(0, i), FrameProfiler.gpuMs(1, i),
                    FrameProfiler.draws(0, i), FrameProfiler.draws(1, i), FrameProfiler.triangles(0, i) / 1e6, FrameProfiler.triangles(1, i) / 1e6));
        }
        out.append(String.format(Locale.ROOT, "%-30s %7.2f %7.2f %7.2f %7.2f%n", "all passes", cpu[0], cpu[1], gpu[0], gpu[1]));
        out.append(String.format(Locale.ROOT, "%-30s %7.2f %7.2f%n%n", "CPU waiting for the GPU", FrameProfiler.gpuWaitMs(0), FrameProfiler.gpuWaitMs(1)));
        return out.append(NOTES).toString();
    }

    private void appendHistory(Path csv, String sceneName, String runLabel, int group, List<ShotResult> results) throws IOException {
        StringBuilder line = new StringBuilder();
        if (!Files.exists(csv)) {
            line.append("date,scene,label,settings,frames,avg_fps,low_1pct_fps,avg_ms,draw_calls,triangles,cpu_waiting_for_gpu");
            for (String pass : FrameProfiler.PASSES) line.append(",cpu_").append(pass.replace(' ', '_'));
            for (String pass : FrameProfiler.PASSES) line.append(",gpu_").append(pass.replace(' ', '_'));
            line.append(",gpu\n");
        }
        float[] all = allFrames(results);
        double fps = averageFps(results);
        line.append(String.format(Locale.ROOT, "%s,%s,%s,\"%s\",%d,%.1f,%.1f,%.2f,%.0f,%.0f,%.2f", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                sceneName, runLabel, describe(group), all.length, fps, onePercentLow(all), 1000 / fps, perFrame(results, ShotResult::draws),
                perFrame(results, ShotResult::triangles), FrameProfiler.gpuWaitMs(group)));
        for (int i = 0; i < FrameProfiler.PASSES.length; i++) line.append(String.format(Locale.ROOT, ",%.2f", FrameProfiler.cpuMs(group, i)));
        for (int i = 0; i < FrameProfiler.PASSES.length; i++) line.append(String.format(Locale.ROOT, ",%.2f", FrameProfiler.gpuMs(group, i)));
        line.append(",\"").append(GL11.glGetString(GL11.GL_RENDERER)).append("\"\n");
        Files.writeString(csv, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
