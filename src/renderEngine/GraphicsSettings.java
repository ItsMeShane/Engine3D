package renderEngine;

import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

/**
 * Every adjustable rendering setting: switches for the optional features and the quality levels behind some of
 * them. With every feature off the picture is the plain forward renderer.
 *
 * All of them can change while the game runs: in the Graphics tab of the scene editor, with the keys
 * (see {@link #handleKeys}), or from the benchmark. Changes that need new GPU buffers or texture state (shadow
 * map, multisampling, water buffers, anisotropic filtering) take effect at the start of the next frame, on the
 * render thread (see FramePipeline); the rest at once. The values start from {@link #FILE} when it exists
 * (written by the editor's Save button), otherwise from the defaults below. Recordings and benchmarks use the
 * same file.
 */
public final class GraphicsSettings {

    /** 1: multisampling of the main view (see {@link #msaaSamples}). */
    public static volatile boolean antiAliasing = true;
    /** 2: anisotropic texture filtering, sharp ground textures at shallow angles (see {@link #anisotropy}). */
    public static volatile boolean anisotropicFiltering = true;
    /** 3: lighting in linear colour with sky/ground ambient, tone mapping and gamma correction. */
    public static volatile boolean linearLighting = true;
    /** 4: soft shorelines, depth tint and fresnel on the water. */
    public static volatile boolean waterEffects = true;
    /** 5: shadows cast by the sun, the first light (see {@link #shadowMapSize}). */
    public static volatile boolean shadows = true;
    /** 6: two-scale terrain texturing that hides the tiling grid. */
    public static volatile boolean terrainDetail = true;
    /** 7: bloom, colour grade and vignette. */
    public static volatile boolean postEffects = true;
    /** 8: normal maps on the terrain and on models that have one. */
    public static volatile boolean normalMapping = true;
    /** C: leave out what the camera (or, for shadows, the sun's box) cannot see. Changes nothing on screen; off only to measure what it saves. */
    public static volatile boolean culling = true;
    /** U: at most {@link #fpsCap} frames per second. */
    public static volatile boolean frameCap = true;

    /** Samples per pixel of the multisampled main view. */
    public static volatile int msaaSamples = 4;
    /** Strongest anisotropic filtering of the textures. */
    public static volatile int anisotropy = 8;
    /** Width and height of the sun's shadow map in texels: the larger, the sharper the shadows, and the more its pass costs. */
    public static volatile int shadowMapSize = 4096;
    /** Height of the buffers the water's reflection and refraction are drawn into (16:9). 180 looked too blurry at 1080p. */
    public static volatile int waterBufferHeight = 720;
    /** Frames per second while the frame cap is on. */
    public static volatile int fpsCap = 80;

    /** The file the settings are saved to and loaded from at start, in the working directory. */
    public static final Path FILE = Path.of("graphics.properties");

    /**
     * One setting, as the editor, the settings file and the benchmark see it. A switch has the values 0 (off) and 1
     * (on); a level one of its {@code choices}.
     *
     * @param key      name in the settings file and in benchmark arguments
     * @param hotkey   key that flips a switch in the game (null if none)
     * @param requires key of the switch that must be on for a level to matter (null if none)
     */
    public record Setting(String key, String label, String hotkey, int[] choices, IntFunction<String> format, String requires,
                          IntSupplier getter, IntConsumer setter, int defaultValue) {

        public boolean isSwitch() {
            return choices == null;
        }

        public int get() {
            return getter.getAsInt();
        }

        public void set(int value) {
            if (!allows(value)) throw new IllegalArgumentException(key + " cannot be " + value + "; allowed: " + allowed());
            setter.accept(value);
        }

        public boolean allows(int value) {
            return isSwitch() ? value == 0 || value == 1 : Arrays.stream(choices).anyMatch(choice -> choice == value);
        }

        /** How a value reads in the editor and in reports. */
        public String text(int value) {
            return format.apply(value);
        }

        public String allowed() {
            return isSwitch() ? "on or off" : Arrays.stream(choices).mapToObj(String::valueOf).collect(Collectors.joining(", "));
        }

        /** Reads a value as written in the settings file or a benchmark argument: on/off/true/false for switches. */
        public int parse(String text) {
            String t = text.trim().toLowerCase();
            int value;
            if (isSwitch()) {
                value = switch (t) {
                    case "on", "true", "1" -> 1;
                    case "off", "false", "0" -> 0;
                    default -> -1;
                };
            } else {
                try {
                    value = Integer.parseInt(t);
                } catch (NumberFormatException e) {
                    value = Integer.MIN_VALUE;
                }
            }
            if (!allows(value)) throw new IllegalArgumentException(key + " cannot be \"" + text + "\"; allowed: " + allowed());
            return value;
        }
    }

    /** All settings, in the order the editor lists them. */
    public static final List<Setting> ALL = List.of(
            onOff("antiAliasing", "Anti-aliasing", "1", () -> antiAliasing, on -> antiAliasing = on),
            level("msaaSamples", "Anti-aliasing samples", new int[]{2, 4, 8}, v -> v + "x", "antiAliasing", () -> msaaSamples, v -> msaaSamples = v),
            onOff("anisotropicFiltering", "Anisotropic filtering", "2", () -> anisotropicFiltering, on -> anisotropicFiltering = on),
            level("anisotropy", "Anisotropic level", new int[]{2, 4, 8, 16}, v -> v + "x", "anisotropicFiltering", () -> anisotropy, v -> anisotropy = v),
            onOff("shadows", "Shadows", "5", () -> shadows, on -> shadows = on),
            level("shadowMapSize", "Shadow map size", new int[]{1024, 2048, 3072, 4096}, v -> v + " x " + v, "shadows", () -> shadowMapSize, v -> shadowMapSize = v),
            onOff("waterEffects", "Water effects", "4", () -> waterEffects, on -> waterEffects = on),
            level("waterBufferHeight", "Water reflection size", new int[]{360, 540, 720, 1080}, v -> v * 16 / 9 + " x " + v, null, () -> waterBufferHeight, v -> waterBufferHeight = v),
            onOff("linearLighting", "Linear lighting, tone mapping", "3", () -> linearLighting, on -> linearLighting = on),
            onOff("terrainDetail", "Terrain detail", "6", () -> terrainDetail, on -> terrainDetail = on),
            onOff("postEffects", "Bloom and colour grade", "7", () -> postEffects, on -> postEffects = on),
            onOff("normalMapping", "Normal mapping", "8", () -> normalMapping, on -> normalMapping = on),
            onOff("culling", "Culling", "C", () -> culling, on -> culling = on),
            onOff("frameCap", "Frame cap", "U", () -> frameCap, on -> frameCap = on),
            level("fpsCap", "Frame cap rate", new int[]{30, 60, 75, 80, 120, 144, 165, 240}, v -> v + " fps", "frameCap", () -> fpsCap, v -> fpsCap = v));

    private interface BooleanSetter {
        void set(boolean on);
    }

    private static Setting onOff(String key, String label, String hotkey, BooleanSupplier getter, BooleanSetter setter) {
        IntSupplier get = () -> getter.getAsBoolean() ? 1 : 0;
        return new Setting(key, label, hotkey, null, v -> v != 0 ? "on" : "off", null, get, v -> setter.set(v != 0), get.getAsInt());
    }

    private static Setting level(String key, String label, int[] choices, IntFunction<String> format, String requires, IntSupplier getter, IntConsumer setter) {
        return new Setting(key, label, null, choices, format, requires, getter, setter, getter.getAsInt());
    }

    private GraphicsSettings() {
    }

    /** The setting with this key (case does not matter), or the switch with this hotkey; null if there is none. */
    public static Setting find(String keyOrHotkey) {
        for (Setting s : ALL)
            if (s.key().equalsIgnoreCase(keyOrHotkey) || s.hotkey() != null && s.hotkey().equalsIgnoreCase(keyOrHotkey)) return s;
        return null;
    }

    public static void resetToDefaults() {
        for (Setting s : ALL) s.set(s.defaultValue());
    }

    /** Keys 9 and 0: the eight feature switches (not culling or the frame cap). */
    public static void setAllFeatures(boolean enabled) {
        antiAliasing = anisotropicFiltering = linearLighting = waterEffects = shadows = terrainDetail = postEffects = normalMapping = enabled;
    }

    /** One line naming every setting that matters for a picture, e.g. for benchmark reports. */
    public static String summary() {
        List<String> parts = new ArrayList<>();
        parts.add(antiAliasing ? "MSAA " + msaaSamples + "x" : "no anti-aliasing");
        parts.add(anisotropicFiltering ? "anisotropic " + anisotropy + "x" : "no anisotropic filtering");
        parts.add(shadows ? "shadow map " + shadowMapSize : "no shadows");
        parts.add("water buffers " + waterBufferHeight * 16 / 9 + "x" + waterBufferHeight);
        List<String> off = new ArrayList<>();
        for (Setting s : ALL)
            if (s.isSwitch() && s.get() == 0 && !List.of("antiAliasing", "anisotropicFiltering", "shadows", "frameCap").contains(s.key())) off.add(s.label().toLowerCase());
        if (!off.isEmpty()) parts.add("off: " + String.join(", ", off));
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------------------------------------------------------
    // settings file

    /** Takes the values from {@link #FILE} if it exists; values it lacks keep their defaults. */
    public static void load() {
        if (!Files.exists(FILE)) return;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            System.err.println("Could not read " + FILE.toAbsolutePath() + ": " + e.getMessage());
            return;
        }
        for (String name : properties.stringPropertyNames()) {
            Setting setting = find(name);
            if (setting == null || !setting.key().equalsIgnoreCase(name)) {
                System.err.println(FILE + ": unknown setting \"" + name + "\", ignored");
                continue;
            }
            try {
                setting.set(setting.parse(properties.getProperty(name)));
            } catch (IllegalArgumentException e) {
                System.err.println(FILE + ": " + e.getMessage() + "; keeping " + setting.text(setting.get()));
            }
        }
        System.out.println("graphics: settings from " + FILE.toAbsolutePath() + " (" + summary() + ")");
    }

    /** Writes every setting to {@link #FILE}. */
    public static void save() throws IOException {
        List<String> lines = new ArrayList<>(List.of(
                "# Engine 3D graphics settings, written by the scene editor (Graphics tab, Save).",
                "# Loaded at start, also for b-roll recordings and benchmarks. Delete this file to go back to the built-in defaults."));
        for (Setting s : ALL) {
            lines.add("");
            lines.add("# " + s.label() + (s.hotkey() != null ? " (key " + s.hotkey() + ")" : "") + ": " + s.allowed()
                    + " (default " + (s.isSwitch() ? s.text(s.defaultValue()) : s.defaultValue()) + ")");
            lines.add(s.key() + "=" + (s.isSwitch() ? s.text(s.get()) : String.valueOf(s.get())));
        }
        Files.write(FILE, lines, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------------------------
    // keys

    /** Call once per frame: each switch's key flips it, 9 turns all features off, 0 all on. */
    public static void handleKeys() {
        while (Keyboard.next()) {
            if (!Keyboard.getEventKeyState()) continue;
            int key = Keyboard.getEventKey();
            if (key == Keyboard.KEY_9 || key == Keyboard.KEY_0) {
                setAllFeatures(key == Keyboard.KEY_0);
                System.out.println("graphics: all features " + (key == Keyboard.KEY_0 ? "ON" : "OFF"));
                continue;
            }
            String name = Keyboard.getKeyName(key);
            for (Setting s : ALL) {
                if (s.isSwitch() && s.hotkey() != null && s.hotkey().equalsIgnoreCase(name)) {
                    s.set(1 - s.get());
                    System.out.println("graphics: " + s.hotkey() + " " + s.label().toLowerCase() + (s.get() != 0 ? " ON" : " OFF"));
                }
            }
        }
    }
}
