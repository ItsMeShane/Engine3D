package engineTester;

import editor.Editor;
import entities.Camera;
import org.lwjgl.input.Keyboard;
import org.lwjgl.util.vector.Vector3f;
import renderEngine.DisplayManager;
import terrains.HeightSource;
import water.WaterTile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scripted camera moves ("b-roll"). A shot is a list of camera keyframes and a duration; the camera glides
 * through the keyframes on a smooth curve. Shots are read from the text file broll/&lt;scene&gt;.txt (format
 * described at the top of that file), so they can be changed without recompiling.
 *
 * In the running game:
 *   B  play all shots one after the other (press again to stop); the file is re-read every time
 *   N  skip to the next shot
 *   K  print the current camera view as a "key" line (also appended to broll/captured_keys.txt),
 *      ready to paste into the shot file
 * To render the shots to video files instead, start the game with --record (see BRollRecorder).
 */
public class BRoll {

    /**
     * One camera keyframe.
     *
     * @param x        world position of the point the camera looks at
     * @param z        world position of the point the camera looks at
     * @param rotation degrees around that point (180 = camera south of it, looking north; 90 = looking east)
     * @param tilt     degrees the camera looks down
     * @param zoom     distance from the point
     * @param height   raises (or, negative, lowers) the point relative to the ground or water under it
     */
    public record Key(float x, float z, float rotation, float tilt, float zoom, float height) {
    }

    /** @param ease true = the move starts and ends gently; false = constant speed, which cuts together better */
    public record Shot(String name, float seconds, boolean ease, List<Key> keys) {
    }

    private final Path file;
    private final Camera camera;
    private final HeightSource ground;
    private final GameScene scene; // told which shot is playing (may be null)
    private final Shot fallback;
    private List<Shot> shots;

    // live playback
    private int playing = -1;
    private float elapsed;
    private boolean editorWasVisible;
    private boolean playKeyDown, nextKeyDown, captureKeyDown;

    public BRoll(String sceneName, Camera camera, HeightSource ground) {
        this(sceneName, camera, ground, null);
    }

    /** @param scene receives {@link GameScene#shotTime} during live playback, so it can stage things for a shot */
    public BRoll(String sceneName, Camera camera, HeightSource ground, GameScene scene) {
        this.file = Path.of("broll", sceneName + ".txt");
        this.camera = camera;
        this.ground = ground;
        this.scene = scene;
        // used when the scene has no shot file: one slow turn around the opening view
        Key start = currentView();
        this.fallback = new Shot("orbit", 24, false, List.of(start,
                new Key(start.x, start.z, start.rotation + 120, start.tilt, start.zoom, 0),
                new Key(start.x, start.z, start.rotation + 240, start.tilt, start.zoom, 0),
                new Key(start.x, start.z, start.rotation + 360, start.tilt, start.zoom, 0)));
        reload();
    }

    public List<Shot> shots() {
        return shots;
    }

    /** Reads the shot file again. Lines that can't be understood are reported and skipped. */
    public void reload() {
        List<Shot> loaded = new ArrayList<>();
        if (Files.exists(file)) {
            try {
                String name = null;
                float seconds = 0;
                boolean ease = false;
                List<Key> keys = new ArrayList<>();
                int lineNumber = 0;
                for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    lineNumber++;
                    String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
                    String[] p = line.trim().split("\\s+");
                    if (p[0].isEmpty()) continue;
                    try {
                        if (p[0].equals("shot")) {
                            if (name != null) addShot(loaded, name, seconds, ease, keys);
                            name = p[1];
                            seconds = Float.parseFloat(p[2]);
                            ease = p.length > 3 && p[3].equals("ease");
                            keys = new ArrayList<>();
                        } else if (p[0].equals("key") && name != null) {
                            float rotation = Float.parseFloat(p[3]);
                            if (!keys.isEmpty()) {
                                // turn the short way round from the previous key: 350 followed by 10 means 20 degrees
                                // onward, not 340 back (a bigger turn needs keys in between, less than 180 apart)
                                float previous = keys.get(keys.size() - 1).rotation();
                                rotation = previous + (((rotation - previous) % 360 + 540) % 360 - 180);
                            }
                            keys.add(new Key(Float.parseFloat(p[1]), Float.parseFloat(p[2]), rotation, Float.parseFloat(p[4]), Float.parseFloat(p[5]),
                                    p.length > 6 ? Float.parseFloat(p[6]) : 0));
                        } else {
                            System.err.println(file + " line " + lineNumber + ": expected \"shot <name> <seconds>\" or \"key ...\" after a shot: " + raw.trim());
                        }
                    } catch (RuntimeException e) {
                        System.err.println(file + " line " + lineNumber + ": can't read \"" + raw.trim() + "\"");
                    }
                }
                if (name != null) addShot(loaded, name, seconds, ease, keys);
            } catch (IOException e) {
                System.err.println("Could not read " + file + ": " + e.getMessage());
            }
        }
        if (loaded.isEmpty()) {
            System.out.println("b-roll: no shots in " + file + ", using a single orbit shot");
            loaded.add(fallback);
        }
        shots = loaded;
    }

    private void addShot(List<Shot> into, String name, float seconds, boolean ease, List<Key> keys) {
        if (keys.isEmpty() || seconds <= 0) {
            System.err.println(file + ": shot \"" + name + "\" needs a duration and at least one key; skipped");
            return;
        }
        if (keys.size() == 1) keys.add(keys.get(0)); // a single key is a still shot
        into.add(new Shot(name, seconds, ease, List.copyOf(keys)));
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Puts the camera where the shot has it at time t (0 = start, 1 = end). */
    public void place(Shot shot, float t) {
        List<Key> keys = shot.keys();
        t = Math.max(0, Math.min(1, t));
        float position = (shot.ease() ? ease(t) : t) * (keys.size() - 1);
        int i = Math.min((int) position, keys.size() - 2);
        float f = position - i;
        Key a = keys.get(Math.max(i - 1, 0)), b = keys.get(i), c = keys.get(i + 1), d = keys.get(Math.min(i + 2, keys.size() - 1));

        // the looked-at point's height is taken from the ground at the keyframes and interpolated like everything
        // else, so the camera doesn't bob over every bump it passes
        camera.setView(
                spline(a.x, b.x, c.x, d.x, f),
                spline(lookAtHeight(a), lookAtHeight(b), lookAtHeight(c), lookAtHeight(d), f),
                spline(a.z, b.z, c.z, d.z, f),
                spline(a.rotation, b.rotation, c.rotation, d.rotation, f),
                spline(a.tilt, b.tilt, c.tilt, d.tilt, f),
                spline(a.zoom, b.zoom, c.zoom, d.zoom, f),
                ground);
    }

    private float lookAtHeight(Key key) {
        return Math.max(ground.heightAt(key.x, key.z), WaterTile.level()) + key.height;
    }

    /** Catmull-Rom: a smooth curve from b to c that continues the direction of travel set by a and d. */
    private static float spline(float a, float b, float c, float d, float t) {
        return 0.5f * (2 * b + (c - a) * t + (2 * a - 5 * b + 4 * c - d) * t * t + (3 * b - a - 3 * c + d) * t * t * t);
    }

    /** Speeds up over the first 15% of the shot and slows down over the last 15%. */
    private static float ease(float t) {
        final float ramp = 0.15f;
        if (t < ramp) return t * t / (2 * ramp * (1 - ramp));
        if (t > 1 - ramp) return 1 - (1 - t) * (1 - t) / (2 * ramp * (1 - ramp));
        return (t - ramp / 2) / (1 - ramp);
    }

    // ------------------------------------------------------------------------------------------------------------
    // live playback

    /**
     * Call once per frame. Handles the B / N / K keys and, while shots are playing, moves the camera.
     *
     * @param editor hidden while shots play (may be null)
     * @return true while a shot is playing: the caller must then leave the camera alone
     */
    public boolean update(Editor editor) {
        boolean play = pressed(Keyboard.KEY_B, playKeyDown), next = pressed(Keyboard.KEY_N, nextKeyDown), capture = pressed(Keyboard.KEY_K, captureKeyDown);
        playKeyDown = Keyboard.isKeyDown(Keyboard.KEY_B);
        nextKeyDown = Keyboard.isKeyDown(Keyboard.KEY_N);
        captureKeyDown = Keyboard.isKeyDown(Keyboard.KEY_K);

        if (playing < 0) {
            if (capture) captureKey();
            if (!play) return false;
            reload();
            editorWasVisible = editor != null && editor.isVisible();
            if (editorWasVisible) editor.setVisible(false);
            startShot(0);
        } else if (play) {
            stop(editor);
            return false;
        } else if (next) {
            if (playing + 1 >= shots.size()) {
                stop(editor);
                return false;
            }
            startShot(playing + 1);
        } else {
            elapsed += DisplayManager.getFrameTimeSeconds();
            if (elapsed >= shots.get(playing).seconds()) {
                if (playing + 1 >= shots.size()) {
                    stop(editor);
                    return false;
                }
                startShot(playing + 1);
            }
        }
        Shot shot = shots.get(playing);
        place(shot, elapsed / shot.seconds());
        if (scene != null) scene.shotTime(shot.name(), elapsed);
        return true;
    }

    private static boolean pressed(int key, boolean wasDown) {
        return Keyboard.isKeyDown(key) && !wasDown;
    }

    private void startShot(int index) {
        playing = index;
        elapsed = 0;
        System.out.println("b-roll: shot " + (index + 1) + "/" + shots.size() + "  " + shots.get(index).name() + "  (" + shots.get(index).seconds() + " s)   B = stop, N = next");
    }

    private void stop(Editor editor) {
        playing = -1;
        if (scene != null) scene.shotTime(null, 0);
        if (editorWasVisible && editor != null) editor.setVisible(true);
        System.out.println("b-roll: stopped");
    }

    private Key currentView() {
        Vector3f focus = camera.getFocusPoint().getPosition();
        return new Key(focus.x, focus.z, camera.getAngleAroundFocusPoint(), camera.getTilt(), camera.getDistanceFromFocusPoint(), 0);
    }

    /** Writes the current view as a keyframe line: frame a shot with the mouse, press K, paste the lines into the shot file. */
    private void captureKey() {
        Key k = currentView();
        String line = String.format(Locale.ROOT, "  key  %.0f %.0f   %.1f %.1f %.1f", k.x, k.z, k.rotation, k.tilt, k.zoom);
        System.out.println("b-roll keyframe:" + line);
        try {
            Path captured = file.resolveSibling("captured_keys.txt");
            Files.createDirectories(captured.getParent());
            Files.writeString(captured, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Could not write captured_keys.txt: " + e.getMessage());
        }
    }
}
