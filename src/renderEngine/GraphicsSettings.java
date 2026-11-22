package renderEngine;

import org.lwjgl.input.Keyboard;

/**
 * Switches for the optional rendering features. Keys 1-8 flip them while the game runs (see handleKeys),
 * which makes before/after comparisons easy; with everything off the picture is the plain forward renderer.
 */
public final class GraphicsSettings {

    /** 1: 4x multisampling of the main view. */
    public static boolean antiAliasing = true;
    /** 2: anisotropic texture filtering (sharp ground textures at shallow angles). */
    public static boolean anisotropicFiltering = true;
    /** 3: lighting in linear colour with sky/ground ambient, tone mapping and gamma correction. */
    public static boolean linearLighting = true;
    /** 4: soft shorelines, depth tint and fresnel on the water. */
    public static boolean waterEffects = true;
    /** 5: shadows cast by the sun (the first light). */
    public static boolean shadows = true;
    /** 6: two-scale terrain texturing that hides the tiling grid. */
    public static boolean terrainDetail = true;
    /** 7: bloom, colour grade and vignette. */
    public static boolean postEffects = true;
    /** 8: normal maps on the terrain and on models that have one. */
    public static boolean normalMapping = true;

    public static final int MSAA_SAMPLES = 4;
    public static final float MAX_ANISOTROPY = 8;

    private GraphicsSettings() {
    }

    public static void setAll(boolean enabled) {
        antiAliasing = anisotropicFiltering = linearLighting = waterEffects = shadows = terrainDetail = postEffects = normalMapping = enabled;
        Loader.applyAnisotropy();
    }

    /** Call once per frame: number keys 1-8 toggle one feature each, 9 turns everything off, 0 everything on. */
    public static void handleKeys() {
        while (Keyboard.next()) {
            if (!Keyboard.getEventKeyState()) continue;
            switch (Keyboard.getEventKey()) {
                case Keyboard.KEY_1 -> report("1 anti-aliasing", antiAliasing = !antiAliasing);
                case Keyboard.KEY_2 -> {
                    report("2 anisotropic filtering", anisotropicFiltering = !anisotropicFiltering);
                    Loader.applyAnisotropy();
                }
                case Keyboard.KEY_3 -> report("3 linear lighting + ambient + tone mapping", linearLighting = !linearLighting);
                case Keyboard.KEY_4 -> report("4 water effects", waterEffects = !waterEffects);
                case Keyboard.KEY_5 -> report("5 shadows", shadows = !shadows);
                case Keyboard.KEY_6 -> report("6 terrain detail", terrainDetail = !terrainDetail);
                case Keyboard.KEY_7 -> report("7 bloom + colour grade", postEffects = !postEffects);
                case Keyboard.KEY_8 -> report("8 normal mapping", normalMapping = !normalMapping);
                case Keyboard.KEY_9 -> {
                    setAll(false);
                    System.out.println("graphics: all features OFF");
                }
                case Keyboard.KEY_0 -> {
                    setAll(true);
                    System.out.println("graphics: all features ON");
                }
                default -> {
                }
            }
        }
    }

    private static void report(String feature, boolean enabled) {
        System.out.println("graphics: " + feature + (enabled ? " ON" : " OFF"));
    }
}
