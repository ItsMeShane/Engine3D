package renderEngine;

import org.lwjgl.util.vector.Vector3f;

/**
 * The look of the world around the scene: sky, fog, ambient light, texture scales. A scene changes these in
 * GameScene.configureEnvironment(), which runs before anything is loaded; the defaults are the original engine values.
 */
public final class Environment {

    /** Folder below res/skybox holding front/back/up/down/left/right.png. */
    public static String skybox;
    /** Degrees per second the sky turns around the vertical axis (0 keeps a visible sun where the sun light is). */
    public static float skyRotationSpeed;
    /** The sky fades into the fog colour below this height on the 500-unit sky cube (30 = about 3.5 degrees above the horizon). */
    public static float skyFadeHeight;

    /** Colour distant things fade into; should match the sky near the horizon. */
    public static final Vector3f fogColour = new Vector3f();
    /** Higher = fog starts closer. Visibility is exp(-(distance * density)^5). */
    public static float fogDensity;
    /**
     * How far the camera sees: nothing beyond this distance is drawn. The fog should have hidden everything by
     * then (fogDensity * farPlane of 1.2 or more), or the land ends in a visible straight cut.
     */
    public static float farPlane;

    /** Light arriving from above (the sky) and bounced up from the ground, used by the linear lighting mode. */
    public static final Vector3f ambientSky = new Vector3f(), ambientGround = new Vector3f();

    /** How often the ground textures repeat across one 800-unit terrain tile. */
    public static float terrainTiling;
    /** How often the water ripple texture repeats per 200 world units. */
    public static float waterTiling;
    /** Strength of the sun's sparkle on the water (with linear lighting). */
    public static float waterGlint;
    /** How quickly water hides its bed: at depth d the bed has faded by 1 - exp(-d * murkiness) toward waterDeepColour. 0.1 is clear water. */
    public static float waterMurkiness;
    /** The colour deep water fades into. */
    public static final Vector3f waterDeepColour = new Vector3f();

    // ---- low-poly ground (all off by default: smooth, textured terrain)

    /** Grid points along one side of a terrain tile. 100 gives 8-unit squares; 51 gives 16-unit squares, big enough to read as facets. */
    public static int terrainVertexCount;
    /**
     * How each grid square of the terrain is split into two triangles: 0 = always the same way, 1 = alternating
     * like a chessboard, 2 = along the diagonal whose ends are closer in height, so edges follow shores and ridges.
     */
    public static int terrainDiagonals;
    /** Light every terrain triangle as one flat facet instead of smoothing across them; normal maps are ignored. */
    public static boolean terrainFlatShading;
    /** With flat shading: how much the brightness differs from facet to facet at random (0.05 = up to 5% either way). */
    public static float terrainFacetVariation;
    /**
     * A fifth ground colour, shown where the blend map's alpha is below 1 (0 = only this colour). Blend maps
     * without an alpha channel are opaque, so nothing changes for them.
     */
    public static final Vector3f terrainExtraColour = new Vector3f();

    static {
        reset();
    }

    private Environment() {
    }

    public static void reset() {
        skybox = "day";
        skyRotationSpeed = 1;
        skyFadeHeight = 30;
        fogColour.set(133 / 256f, 166 / 256f, 199 / 256f);
        fogDensity = 0.002f;
        farPlane = 750;
        ambientSky.set(0.22f, 0.29f, 0.42f);
        ambientGround.set(0.13f, 0.12f, 0.09f);
        terrainTiling = 20;
        waterTiling = 4;
        waterGlint = 3;
        waterMurkiness = 0.10f;
        waterDeepColour.set(0.03f, 0.20f, 0.27f);
        terrainVertexCount = 100;
        terrainDiagonals = 0;
        terrainFlatShading = false;
        terrainFacetVariation = 0;
        terrainExtraColour.set(0.5f, 0.5f, 0.5f);
    }
}
