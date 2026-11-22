package engineTester;

import entities.Camera;
import entities.Entity;
import entities.Light;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import particles.ParticleMaster;
import postProcessing.Fbo;
import renderEngine.DisplayManager;
import renderEngine.FramePipeline;
import terrains.Terrain;
import water.WaterTile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Renders the b-roll shots of a scene to video files, frame by frame.
 *
 * Unlike a screen recording this does not run in real time: every frame advances the world by exactly 1/fps
 * seconds and is rendered off-screen at the chosen size, so the result is perfectly smooth whatever the
 * frame rate of the machine, and can be larger than the screen. Frames are piped to ffmpeg (must be installed).
 *
 * Program arguments (after the scene name):
 *   --record            every shot -> broll/out/&lt;scene&gt;/NN_name.mp4, plus &lt;scene&gt;_tour.mp4 with all of them in order
 *   --record=a+b        only the shots named a and b
 *   --preview           no video: five stills per shot (start to end) to check the framing quickly
 *   --size=2560x1440    frame size (default 1920x1080)
 *   --fps=30            frames per second (default 60)
 *   --quality=23        x264 quality (crf): lower is better and bigger. 17 (default) is near-lossless source footage
 *                       at roughly 7 MB per second of 1080p60; 23 is about a third of that and still looks good
 *   --ffmpeg=C:\path\ffmpeg.exe   if ffmpeg is not on the PATH
 */
public class BRollRecorder {

    /** Seconds the world runs before the first frame, so smoke, mist and fireflies are already there. */
    private static final int WARM_UP_SECONDS = 25;

    private boolean previewOnly;
    private Set<String> only; // null = all shots
    private int width = 1920, height = 1080, fps = 60, quality = 17;
    private String ffmpeg = "ffmpeg";

    private BRollRecorder() {
    }

    /** @return null unless the arguments ask for --record or --preview */
    public static BRollRecorder fromArgs(String[] args) {
        BRollRecorder recorder = new BRollRecorder();
        boolean requested = false;
        for (String arg : args) {
            String value = arg.contains("=") ? arg.substring(arg.indexOf('=') + 1) : "";
            if (arg.equals("--record")) requested = true;
            else if (arg.startsWith("--record=")) {
                requested = true;
                recorder.only = new HashSet<>(Arrays.asList(value.split("[,+]")));
            } else if (arg.equals("--preview")) {
                requested = true;
                recorder.previewOnly = true;
            } else if (arg.startsWith("--size=")) {
                String[] size = value.toLowerCase().split("x");
                recorder.width = Integer.parseInt(size[0]) / 2 * 2; // video encoders need even sizes
                recorder.height = Integer.parseInt(size[1]) / 2 * 2;
            } else if (arg.startsWith("--fps=")) recorder.fps = Integer.parseInt(value);
            else if (arg.startsWith("--quality=")) recorder.quality = Integer.parseInt(value);
            else if (arg.startsWith("--ffmpeg=")) recorder.ffmpeg = value;
        }
        return requested ? recorder : null;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Renders the shots, then returns; the caller shuts the engine down. */
    public void run(String sceneName, BRoll bRoll, GameScene scene, FramePipeline pipeline, List<Entity> entities, Terrain[][] terrains,
                    List<Light> lights, Camera camera, List<WaterTile> waterTiles) throws IOException {
        List<BRoll.Shot> shots = new ArrayList<>();
        for (BRoll.Shot shot : bRoll.shots()) if (only == null || only.contains(shot.name())) shots.add(shot);
        if (shots.isEmpty()) {
            System.err.println("No shot matches " + only + ". Shots in this scene: " + bRoll.shots().stream().map(BRoll.Shot::name).toList());
            return;
        }
        Path outDir = Path.of("broll", "out", sceneName);
        Files.createDirectories(outDir);

        Fbo capture = new Fbo(width, height, 0);
        pipeline.setOutput(capture);
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 3);

        // from here on every frame is exactly 1/fps seconds of world time, however long it takes to draw
        DisplayManager.setFixedFrameTime(1f / fps);
        for (int i = 0; i < WARM_UP_SECONDS * fps; i++) {
            scene.update();
            ParticleMaster.update();
            DisplayManager.advanceFixedFrame();
        }

        List<Path> clips = new ArrayList<>();
        long started = System.currentTimeMillis();
        boolean aborted = false;
        for (BRoll.Shot shot : shots) {
            String fileName = String.format(Locale.ROOT, "%02d_%s", bRoll.shots().indexOf(shot) + 1, shot.name());
            if (previewOnly) {
                float[] times = {0, 0.25f, 0.5f, 0.75f, 1};
                String[] labels = {"a_start", "b", "c_middle", "d", "e_end"};
                for (int i = 0; i < times.length && !aborted; i++) {
                    leadIn(scene, shot, times[i] * shot.seconds());
                    bRoll.place(shot, times[i]);
                    scene.shotTime(shot.name(), times[i] * shot.seconds());
                    aborted = !renderFrame(scene, pipeline, entities, terrains, lights, camera, waterTiles, capture, pixels);
                    savePng(pixels, outDir.resolve("preview_" + fileName + "_" + labels[i] + ".png"));
                }
                System.out.println("preview: " + shot.name());
            } else {
                Path clip = outDir.resolve(fileName + ".mp4");
                int frames = Math.max(1, Math.round(shot.seconds() * fps));
                leadIn(scene, shot, 0);
                try (VideoWriter video = new VideoWriter(clip, outDir.resolve(fileName + ".ffmpeg.log"))) {
                    for (int frame = 0; frame < frames && !aborted; frame++) {
                        float t = frames == 1 ? 0 : frame / (float) (frames - 1);
                        bRoll.place(shot, t);
                        scene.shotTime(shot.name(), t * shot.seconds());
                        aborted = !renderFrame(scene, pipeline, entities, terrains, lights, camera, waterTiles, capture, pixels);
                        video.write(pixels);
                        if (frame % fps == 0) Display.setTitle("Recording " + shot.name() + "   " + frame / fps + " / " + Math.round(shot.seconds()) + " s   (close the window to stop)");
                    }
                }
                clips.add(clip);
                System.out.println("recorded " + clip + "  (" + frames + " frames)");
            }
            if (aborted) {
                System.out.println("Stopped: the window was closed.");
                break;
            }
        }

        scene.shotTime(null, 0);
        pipeline.setOutput(null);
        capture.cleanUp();
        if (!previewOnly && !aborted && only == null && clips.size() > 1) joinClips(clips, outDir.resolveSibling(sceneName + "_tour.mp4"), outDir);
        System.out.printf(Locale.ROOT, "Done in %.0f s. Files are in %s%n", (System.currentTimeMillis() - started) / 1000f, outDir.toAbsolutePath());
    }

    /**
     * Runs the world, without drawing it, through the seconds before a moment of a shot if the scene asks for a
     * lead-in there (GameScene.shotLeadIn): the shot time counts up to {@code shotSeconds}, which the caller renders.
     */
    private void leadIn(GameScene scene, BRoll.Shot shot, float shotSeconds) {
        int frames = Math.round(scene.shotLeadIn(shot.name()) * fps);
        for (int i = frames; i > 0; i--) {
            scene.shotTime(shot.name(), shotSeconds - i / (float) fps);
            scene.update();
            ParticleMaster.update();
            DisplayManager.advanceFixedFrame();
        }
    }

    /** Advances the world one frame, draws it into the capture buffer, copies the pixels out and shows them in the window. */
    private boolean renderFrame(GameScene scene, FramePipeline pipeline, List<Entity> entities, Terrain[][] terrains, List<Light> lights, Camera camera,
                                List<WaterTile> waterTiles, Fbo capture, ByteBuffer pixels) {
        scene.update();
        ParticleMaster.update();
        pipeline.render(entities, terrains, lights, camera, waterTiles, scene.getSun());

        capture.bindToRead();
        pixels.clear();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, pixels);
        capture.blitToWindow();
        DisplayManager.updateDisplay();
        return !Display.isCloseRequested();
    }

    /** OpenGL rows run bottom to top; images top to bottom. */
    private void savePng(ByteBuffer pixels, Path file) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int i = (x + (height - 1 - y) * width) * 3;
                image.setRGB(x, y, ((pixels.get(i) & 255) << 16) | ((pixels.get(i + 1) & 255) << 8) | (pixels.get(i + 2) & 255));
            }
        ImageIO.write(image, "png", file.toFile());
    }

    /** All clips back to back in one file; no re-encoding, the clips are simply appended. */
    private void joinClips(List<Path> clips, Path tour, Path outDir) throws IOException {
        Path list = outDir.resolve("tour_clips.txt");
        StringBuilder text = new StringBuilder();
        for (Path clip : clips) text.append("file '").append(clip.toAbsolutePath().toString().replace('\\', '/')).append("'\n");
        Files.writeString(list, text, StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(ffmpeg, "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list.toString(), "-c", "copy", "-movflags", "+faststart", tour.toString())
                .redirectErrorStream(true).redirectOutput(outDir.resolve("tour.ffmpeg.log").toFile()).start();
        try {
            if (process.waitFor() == 0) System.out.println("joined " + clips.size() + " clips into " + tour);
            else System.err.println("Could not join the clips, see " + outDir.resolve("tour.ffmpeg.log"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** An ffmpeg process that receives raw frames on its standard input and encodes them as H.264. */
    private class VideoWriter implements AutoCloseable {
        private final Process process;
        private final OutputStream stream;
        private final WritableByteChannel channel;
        private final Path log;

        VideoWriter(Path file, Path log) throws IOException {
            this.log = log;
            try {
                process = new ProcessBuilder(ffmpeg, "-y", "-loglevel", "error",
                        "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", width + "x" + height, "-r", String.valueOf(fps), "-i", "-",
                        "-vf", "vflip", // OpenGL's first row is the bottom of the picture
                        "-c:v", "libx264", "-preset", "medium", "-crf", String.valueOf(quality), "-pix_fmt", "yuv420p", "-movflags", "+faststart", file.toString())
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            } catch (IOException e) {
                throw new IOException("Could not start ffmpeg (\"" + ffmpeg + "\"). Install it or pass --ffmpeg=<path to ffmpeg.exe>; --preview works without it.", e);
            }
            stream = process.getOutputStream();
            channel = Channels.newChannel(stream);
        }

        void write(ByteBuffer frame) throws IOException {
            frame.rewind();
            try {
                while (frame.hasRemaining()) channel.write(frame);
            } catch (IOException e) {
                throw new IOException("ffmpeg stopped accepting frames, see " + log, e);
            }
        }

        @Override
        public void close() throws IOException {
            stream.close();
            try {
                if (process.waitFor() != 0) System.err.println("ffmpeg reported a problem, see " + log);
                else Files.deleteIfExists(log);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
