import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

/**
 * Turns the CC0 source packs in tools/asset_sources into engine-ready assets for the demo scene:
 *   res/models/demo/*.obj            one mesh per prop / building, already scaled to world units
 *   res/textures/demo/kenney_atlas.png   the single texture all of them share
 *
 * The engine draws one texture per model and its OBJ loader is strict, so this tool
 *   - merges sub-objects and modular kit pieces (walls + roofs = a house) into single meshes,
 *   - bakes every material into one atlas: each pack's colormap gets a 512px slot, plain MTL colours
 *     get a swatch in a palette slot (nature-kit colours are re-tinted to sit next to the other kits),
 *   - bakes the scale in, so entities use scale 1 (the editor's scale slider only goes up to 10).
 *
 * A few models are not downloads but built here from boxes in the atlas colours: the arched bridge, the
 * shrine's crystal and its plinth (see "generated models" below).
 *
 * Run from the project root:
 *   javac -d out/tools -cp "libs/jars/*" -sourcepath src tools/*.java
 *   java -cp out/tools BuildDemoAssets
 */
public class BuildDemoAssets {

    static final Path SOURCES = Path.of("tools/asset_sources");
    static final Path MODEL_OUT = Path.of("res/models/demo");
    static final Path TEXTURE_OUT = Path.of("res/textures/demo/kenney_atlas.png");

    /** World units per fantasy-town unit (one storey). A villager ends up ~8.5 units tall. */
    static final float K = 12;

    static final Atlas ATLAS = new Atlas();
    static final Pack TOWN = new Pack("kenney_fantasy-town-kit", 1f);
    static final Pack GRAVE = new Pack("kenney_graveyard-kit", 1f);
    static final Pack PEOPLE = new Pack("kenney_mini-characters", 1f);
    static final Pack PIRATE = new Pack("kenney_pirate-kit", 0.4f);
    static final Pack SURVIVAL = new Pack("kenney_survival-kit", 1.8f);
    static final Pack NATURE = new Pack("kenney_nature-kit", 2f);

    static final List<String> report = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        natureTints();

        // ---- village buildings (assembled from wall/roof modules)
        out("house_cottage", house(3, 2, new String[]{"wall"}, false, 1, 11));
        out("house_townhouse", house(3, 2, new String[]{"wall", "wall-wood"}, true, 1, 23));
        out("house_tavern", house(4, 2, new String[]{"wall", "wall-wood"}, false, 2, 37));
        out("house_mill", house(3, 2, new String[]{"wall-wood"}, true, 1, 41));
        out("windmill_tower", house(2, 2, new String[]{"wall", "wall", "wall-wood"}, true, -1, 5));
        // rotor / wheel: axis turned onto z so an entity can spin them with rotZ and still be aimed with rotY
        out("windmill_blades", TOWN.piece("windmill").rotY(90));
        out("water_wheel", TOWN.piece("watermill").rotY(90));

        // ---- village props
        out("fountain", TOWN.piece("fountain-round"));
        out("stall_red", TOWN.piece("stall-red"));
        out("stall_green", TOWN.piece("stall-green"));
        out("cart", TOWN.piece("cart"));
        out("fence", row(TOWN.piece("fence").translate(-0.46f, 0, 0), 4, 0, 1));
        out("bench", GRAVE.piece("bench"));
        out("hay_bale", GRAVE.piece("hay-bale"));
        out("pumpkin", GRAVE.piece("pumpkin").scale(1.3f));
        out("barrel", PIRATE.piece("barrel").scale(0.8f));
        out("crate", PIRATE.piece("crate").scale(0.8f));
        out("chest", PIRATE.piece("chest").scale(0.8f));
        out("signpost", SURVIVAL.piece("signpost"));
        lampPost();
        out("workbench", SURVIVAL.piece("workbench-anvil"));

        // ---- camp
        out("campfire", SURVIVAL.piece("campfire-pit").scale(1.3f));
        out("tent", SURVIVAL.piece("tent-canvas"));
        out("log_stack", NATURE.piece("log_stack"));

        // ---- water side
        outWorld("bridge", bridge());
        out("dock", row(PIRATE.piece("structure-platform-dock"), 3, 0, 2.5f * PIRATE.factor));
        out("rowboat", PIRATE.piece("boat-row-small"));
        out("ship", PIRATE.piece("ship-small"));
        out("canoe", NATURE.piece("canoe"));
        out("lily_pad", NATURE.piece("lily_large").scale(1.5f));
        out("lily_pad_small", NATURE.piece("lily_small").scale(1.5f));

        // ---- trees and plants
        out("tree_round", TOWN.piece("tree"));
        out("tree_tall", TOWN.piece("tree-high"));
        out("tree_crooked", TOWN.piece("tree-high-crooked"));
        out("tree_bushy", TOWN.piece("tree-high-round"));
        out("pine", GRAVE.piece("pine").scale(1.25f));
        out("pine_crooked", GRAVE.piece("pine-crooked").scale(1.25f));
        out("tree_oak", NATURE.piece("tree_detailed"));
        out("tree_autumn", NATURE.piece("tree_oak_fall"));
        out("stump", NATURE.piece("stump_old"));
        out("bush", NATURE.piece("plant_bushLarge").scale(1.4f));
        out("bush_detailed", NATURE.piece("plant_bushDetailed").scale(1.4f));
        out("flower_red", NATURE.piece("flower_redA").scale(0.55f));
        out("flower_yellow", NATURE.piece("flower_yellowB").scale(0.6f));
        out("flower_purple", NATURE.piece("flower_purpleC").scale(0.6f));
        out("mushrooms", NATURE.piece("mushroom_redGroup"));

        // ---- rocks
        out("rock_large", TOWN.piece("rock-large"));
        out("rock_wide", TOWN.piece("rock-wide"));
        out("rock_small", TOWN.piece("rock-small"));
        out("rocks_tall", GRAVE.piece("rocks-tall").scale(1.5f));

        // ---- shrine
        outWorld("shrine_plinth", plinth());
        outWorld("crystal", crystalCluster());
        out("shrine_column", GRAVE.piece("column-large").scale(1.8f));
        out("shrine_column_broken", NATURE.piece("statue_columnDamaged").scale(1.1f));
        out("shrine_obelisk", GRAVE.piece("pillar-obelisk").scale(1.6f));
        out("shrine_altar", GRAVE.piece("altar-stone").scale(1.3f));
        Mesh brazier = GRAVE.piece("pillar-square").scale(1.2f);
        float brazierTop = brazier.bounds()[4];
        out("brazier", brazier.add(GRAVE.piece("fire-basket").scale(1.5f).translate(0, brazierTop, 0)));

        // ---- people
        out("villager_a", person("character-male-a"));
        out("villager_b", person("character-female-b"));
        out("villager_c", person("character-male-d"));
        out("villager_d", person("character-female-e"));
        out("knight", person("character-knight"));

        foliage();

        ATLAS.write(TEXTURE_OUT);
        Files.write(MODEL_OUT.resolve("_sizes.txt"), report);
        report.forEach(System.out::println);
        System.out.println("palette colours used: " + ATLAS.paletteSize() + ", models: " + report.size());
        if (args.length > 0 && args[0].equals("--list-used")) Pack.used.forEach(System.out::println);
    }

    /** Nature-kit colours are neon teal/orange out of the box; pull them toward the fantasy-town palette. */
    static void natureTints() {
        NATURE.tint("grass", 0.42f, 0.70f, 0.33f);
        NATURE.tint("leafsGreen", 0.33f, 0.66f, 0.36f);
        NATURE.tint("leafsDark", 0.20f, 0.48f, 0.34f);
        NATURE.tint("leafsFall", 0.90f, 0.55f, 0.20f);
        NATURE.tint("stone", 0.66f, 0.68f, 0.72f);
        NATURE.tint("stoneDark", 0.50f, 0.52f, 0.57f);
        NATURE.tint("dirt", 0.62f, 0.44f, 0.30f);
        NATURE.tint("dirtDark", 0.50f, 0.35f, 0.24f);
        NATURE.tint("woodBark", 0.55f, 0.36f, 0.24f);
        NATURE.tint("woodBarkDark", 0.45f, 0.30f, 0.22f);
        NATURE.tint("wood", 0.76f, 0.52f, 0.33f);
        NATURE.tint("woodDark", 0.58f, 0.38f, 0.25f);
        NATURE.tint("woodInner", 0.87f, 0.74f, 0.58f);
        NATURE.tint("water", 0.45f, 0.75f, 0.90f);
        NATURE.tint("colorRed", 0.85f, 0.25f, 0.27f);
        NATURE.tint("colorYellow", 0.98f, 0.76f, 0.25f);
        NATURE.tint("colorPurple", 0.62f, 0.50f, 0.95f);
        NATURE.tint("_defaultMat", 0.93f, 0.93f, 0.90f);
    }

    // ------------------------------------------------------------------------------------------ recipes

    /** Repeats a piece {@code count} times along x (stepX) or z (stepZ), centred on the origin. */
    static Mesh row(Mesh piece, int count, float stepX, float stepZ) {
        Mesh result = new Mesh();
        for (int i = 0; i < count; i++) {
            float t = i - (count - 1) / 2f;
            result.add(piece.copy().translate(t * stepX, 0, t * stepZ));
        }
        return result;
    }

    /**
     * A house on an nx-by-nz grid of 1x1 cells (one side must be 2 so the hip roof closes at the ridge).
     * Kit conventions: a wall panel sits on the +x edge of its cell, wall-corner covers +x and +z,
     * roof pieces have their eave on -x (roof-corner: -x and +z).
     *
     * @param storeys   wall style per storey: "wall" (stone + timber) or "wall-wood"
     * @param doorCell  x index of the cell that gets the door, on the -z side (must not be a corner cell)
     */
    static Mesh house(int nx, int nz, String[] storeys, boolean highRoof, int doorCell, long seed) throws IOException {
        Random random = new Random(seed);
        Mesh house = new Mesh();
        String[] windows = {"window-shutters", "window-glass", "window-small", "window-shutters"};

        for (int s = -1; s < storeys.length; s++) { // storey -1 is a buried foundation for sloped ground
            String style = s < 0 ? "wall" : storeys[s];
            for (int i = 0; i < nx; i++)
                for (int j = 0; j < nz; j++) {
                    boolean px = i == nx - 1, mx = i == 0, pz = j == nz - 1, mz = j == 0;
                    float cx = i - (nx - 1) / 2f, cz = j - (nz - 1) / 2f;
                    int sides = (px ? 1 : 0) + (mx ? 1 : 0) + (pz ? 1 : 0) + (mz ? 1 : 0);
                    if (sides == 2) {
                        float rot = px && pz ? 0 : px && mz ? 90 : mx && mz ? 180 : 270;
                        house.add(TOWN.piece(style + "-corner").rotY(rot).translate(cx, s, cz));
                    } else if (sides == 1) {
                        float rot = px ? 0 : mz ? 90 : mx ? 180 : 270;
                        String variant = style;
                        if (s == 0 && mz && i == doorCell) variant = style + "-door";
                        else if (s >= 0 && random.nextFloat() < 0.75f) variant = style + "-" + windows[random.nextInt(windows.length)];
                        house.add(TOWN.piece(variant).rotY(rot).translate(cx, s, cz));
                    }
                }
        }

        String roof = highRoof ? "roof-high" : "roof";
        boolean chimneyPlaced = false;
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                boolean px = i == nx - 1, mx = i == 0, pz = j == nz - 1, mz = j == 0;
                float cx = i - (nx - 1) / 2f, cz = j - (nz - 1) / 2f;
                int sides = (px ? 1 : 0) + (mx ? 1 : 0) + (pz ? 1 : 0) + (mz ? 1 : 0);
                if (sides == 2) {
                    float rot = mx && pz ? 0 : px && pz ? 90 : px && mz ? 180 : 270;
                    house.add(TOWN.piece(roof + "-corner").rotY(rot).translate(cx, storeys.length, cz));
                } else if (sides == 1) {
                    float rot = mx ? 0 : pz ? 90 : px ? 180 : 270;
                    house.add(TOWN.piece(roof).rotY(rot).translate(cx, storeys.length, cz));
                    if (!chimneyPlaced && pz) { // chimney on the back slope
                        house.add(TOWN.piece("chimney").rotY(rot).translate(cx, storeys.length, cz));
                        chimneyPlaced = true;
                    }
                }
            }
        return house;
    }

    /**
     * Mini-characters ship in a T-pose. Their arms are the part of the body mesh beyond |x| = 0.16,
     * so swing that region down around the shoulders for a relaxed stance.
     */
    static Mesh person(String name) throws IOException {
        Mesh body = PEOPLE.piece(name, group -> group.startsWith("body"));
        final float shoulderX = 0.16f, shoulderY = 0.31f;
        double angle = Math.toRadians(68), cos = Math.cos(angle), sin = Math.sin(angle);
        for (float[] c : body.corners) {
            if (Math.abs(c[0]) <= shoulderX) continue;
            float side = Math.signum(c[0]);
            double dx = (c[0] - side * shoulderX) * side, dy = c[1] - shoulderY;       // mirrored into the +x arm
            c[0] = side * (float) (shoulderX + dx * cos + dy * sin);
            c[1] = (float) (shoulderY - dx * sin + dy * cos);
            double nx = c[5] * side, ny = c[6];
            c[5] = side * (float) (nx * cos + ny * sin);
            c[6] = (float) (-nx * sin + ny * cos);
        }
        return body.add(PEOPLE.piece(name, group -> !group.startsWith("body")));
    }

    /**
     * Cut-out foliage for the engine's entity texture atlases: one crossed-quad card mesh whose UVs span 0..1,
     * and a 2x2 atlas of tinted sprites (Kenney Foliage Sprites). An entity picks its plant with setTextureIndex;
     * the texture needs numberOfRows = 2, transparency (no back-face culling) and fake lighting.
     *   0 = tall grass, 1 = wild flowers, 2 = cattail reeds, 3 = leafy plant
     */
    static void foliage() throws IOException {
        Mesh card = new Mesh();
        for (int q = 0; q < 2; q++) {
            float dx = q == 0 ? 0.5f : 0, dz = q == 0 ? 0 : 0.5f;
            float[][] quad = {{-dx, 0, -dz, 0, 0}, {dx, 0, dz, 1, 0}, {dx, 1, dz, 1, 1}, {-dx, 1, -dz, 0, 1}};
            for (int i : new int[]{0, 1, 2, 0, 2, 3})
                card.corners.add(new float[]{quad[i][0], quad[i][1], quad[i][2], quad[i][3], quad[i][4], 0, 1, 0});
        }
        card.scale(10);
        card.write(MODEL_OUT.resolve("foliage_card.obj"), "foliage_card - crossed quads for the 2x2 foliage atlas (tools/BuildDemoAssets)");

        String[] sprites = {"sprite_0056", "sprite_0072", "sprite_0073", "sprite_0060"};
        int[][] bottomTop = {{0x4f9a3a, 0x8fce55}, {0x4f9a3a, 0xf2a7d8}, {0x5d8f45, 0x9a6b3c}, {0x2f7d4a, 0x7fc46a}};
        int cell = 256;
        BufferedImage atlas = new BufferedImage(cell * 2, cell * 2, BufferedImage.TYPE_INT_ARGB);
        for (int s = 0; s < 4; s++) {
            BufferedImage scaled = new BufferedImage(cell, cell, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(ImageIO.read(SOURCES.resolve("kenney_foliage-sprites/" + sprites[s] + ".png").toFile()), 4, 4, cell - 8, cell - 4, null);
            g2.dispose();
            for (int y = 0; y < cell; y++)
                for (int x = 0; x < cell; x++) {
                    int p = scaled.getRGB(x, y), alpha = p >>> 24;
                    float t = (float) Math.pow(1 - y / (float) (cell - 1), 1.6), shade = alpha == 0 ? 1 : 0.55f + 0.45f * ((p >> 8) & 255) / 255f;
                    int rgb = 0;
                    for (int shift = 16; shift >= 0; shift -= 8) {
                        float bottom = (bottomTop[s][0] >> shift) & 255, top = (bottomTop[s][1] >> shift) & 255;
                        rgb |= Math.round((bottom + (top - bottom) * t) * shade) << shift;
                    }
                    // colour is written under transparent pixels too, so filtering never drags in a dark fringe
                    atlas.setRGB((s % 2) * cell + x, (s / 2) * cell + y, (alpha << 24) | rgb);
                }
        }
        ImageIO.write(atlas, "png", TEXTURE_OUT.resolveSibling("foliage_atlas.png").toFile());
    }

    /**
     * The plaza lamp: the Graveyard Kit's light post, with the lantern's glass panes split off into a model of
     * their own. The engine's "emissive" setting brightens a model's whole texture, so only a separate glass
     * model can glow while the post stays dark. Both share one origin: place them at the same spot.
     */
    static void lampPost() throws IOException {
        Mesh all = GRAVE.piece("lightpost-single").scale(1.25f), post = new Mesh(), glass = new Mesh();
        for (int i = 0; i < all.corners.size(); i += 3) {
            boolean glowing = true;
            for (int k = 0; k < 3; k++) {
                Color c = ATLAS.colourAt(all.corners.get(i + k)[3], all.corners.get(i + k)[4]);
                glowing &= c.getRed() > 200 && c.getGreen() > 120 && c.getBlue() < 130; // the panes are the only yellow-orange faces
            }
            for (int k = 0; k < 3; k++) (glowing ? glass : post).corners.add(all.corners.get(i + k));
        }
        if (glass.corners.isEmpty()) throw new IllegalStateException("no glass faces found in lightpost-single");
        out("lamp_post", post);
        out("lamp_glass", glass);
    }

    // ------------------------------------------------------------------------------------------ generated models
    // Built in world units (no K scaling) from flat-shaded boxes; every colour becomes a swatch in the atlas.

    static final float[] WOOD = {0.76f, 0.52f, 0.33f}, WOOD_PALE = {0.83f, 0.61f, 0.40f}, WOOD_DARK = {0.50f, 0.33f, 0.22f};
    static final float[] STONE = {0.66f, 0.68f, 0.72f}, STONE_DARK = {0.50f, 0.52f, 0.57f}, STONE_PALE = {0.78f, 0.80f, 0.83f};

    /**
     * Arched footbridge along x, 68 long and 16 wide. y = 0 is the top of the deck at both ends, to be put at the
     * height of the path; the deck rises 5 in the middle. Stone abutments (to be buried in the banks) carry the
     * ends and two stone piers with pointed cutwaters stand in the river; they reach 18 below the deck ends.
     */
    static Mesh bridge() {
        final float half = 34, halfWidth = 8, plankThickness = 0.9f, beamDepth = 1.6f;
        final int planks = 20, posts = 9;
        Mesh m = new Mesh();

        // deck: planks in two alternating tones on three beams, all following the arch
        float step = 2 * half / planks;
        for (int i = 0; i < planks; i++) {
            float x0 = -half + i * step, x1 = x0 + step, y0 = arch(x0, half), y1 = arch(x1, half);
            slab(m, i % 2 == 0 ? WOOD : WOOD_PALE, x0 + 0.1f, y0 - plankThickness, y0, x1 - 0.1f, y1 - plankThickness, y1, -halfWidth, halfWidth);
            for (float z : new float[]{-6.4f, 0, 6.4f})
                slab(m, WOOD_DARK, x0, y0 - plankThickness - beamDepth, y0 - plankThickness, x1, y1 - plankThickness - beamDepth, y1 - plankThickness, z - 0.8f, z + 0.8f);
        }

        // railings: evenly spaced posts with caps, a hand rail and a lower rail between them
        float spacing = 2 * half / (posts - 1);
        for (int side = -1; side <= 1; side += 2) {
            float z = side * (halfWidth + 0.2f);
            for (int i = 0; i < posts; i++) {
                float x = -half + i * spacing, y = arch(x, half);
                boolean end = i == 0 || i == posts - 1;
                float w = end ? 1.0f : 0.7f, top = y + (end ? 6.2f : 5.4f);
                box(m, WOOD_DARK, x - w, y - 2.2f, z - w, x + w, top, z + w);
                box(m, WOOD, x - w - 0.25f, top, z - w - 0.25f, x + w + 0.25f, top + 0.5f, z + w + 0.25f);
                if (i == posts - 1) continue;
                float nextY = arch(x + spacing, half);
                slab(m, WOOD, x, y + 4.0f, y + 4.7f, x + spacing, nextY + 4.0f, nextY + 4.7f, z - 0.35f, z + 0.35f);
                slab(m, WOOD, x, y + 1.9f, y + 2.5f, x + spacing, nextY + 1.9f, nextY + 2.5f, z - 0.3f, z + 0.3f);
            }
        }

        for (int side = -1; side <= 1; side += 2) {
            // abutment: a stone block under each end of the deck, its river face leaning back, with a paler top course
            float face = side * 23f, back = side * 37f, yFace = arch(23, half) - plankThickness - 0.05f, yBack = -plankThickness - 0.05f, w = halfWidth + 1.6f;
            hexahedron(m, STONE_DARK, STONE,
                    new float[]{side * 21f, -18, -w}, new float[]{back, -18, -w}, new float[]{back, -18, w}, new float[]{side * 21f, -18, w},
                    new float[]{face, yFace - 1.1f, -w}, new float[]{back, yBack - 1.1f, -w}, new float[]{back, yBack - 1.1f, w}, new float[]{face, yFace - 1.1f, w});
            hexahedron(m, STONE_PALE, STONE_PALE,
                    new float[]{face - side * 0.5f, yFace - 1.1f, -w - 0.4f}, new float[]{back, yBack - 1.1f, -w - 0.4f}, new float[]{back, yBack - 1.1f, w + 0.4f}, new float[]{face - side * 0.5f, yFace - 1.1f, w + 0.4f},
                    new float[]{face - side * 0.5f, yFace, -w - 0.4f}, new float[]{back, yBack, -w - 0.4f}, new float[]{back, yBack, w + 0.4f}, new float[]{face - side * 0.5f, yFace, w + 0.4f});

            // pier: tapering stone wall across the flow of the river, pointed at both ends, with a cap the beams rest on
            float px = side * 10f, top = arch(10, half) - plankThickness - beamDepth, pw = halfWidth + 0.6f;
            hexahedron(m, STONE_DARK, STONE,
                    new float[]{px - 2.3f, -18, -pw}, new float[]{px + 2.3f, -18, -pw}, new float[]{px + 2.3f, -18, pw}, new float[]{px - 2.3f, -18, pw},
                    new float[]{px - 1.5f, top - 0.9f, -pw}, new float[]{px + 1.5f, top - 0.9f, -pw}, new float[]{px + 1.5f, top - 0.9f, pw}, new float[]{px - 1.5f, top - 0.9f, pw});
            for (int end = -1; end <= 1; end += 2) { // cutwaters
                float z0 = end * pw, tip = end * (pw + 3.6f);
                float[] a = {px - 2.3f, -18, z0}, b = {px + 2.3f, -18, z0}, c = {px, -18, tip}, d = {px - 1.5f, top - 2.6f, z0}, e = {px + 1.5f, top - 2.6f, z0}, f = {px, top - 2.6f, tip};
                if (end < 0) {
                    quad(m, STONE, b, c, f, e);
                    quad(m, STONE_DARK, c, a, d, f);
                    tri(m, STONE_PALE, d, e, f);
                } else {
                    quad(m, STONE, c, b, e, f);
                    quad(m, STONE_DARK, a, c, f, d);
                    tri(m, STONE_PALE, e, d, f);
                }
            }
            box(m, STONE_PALE, px - 2.0f, top - 0.9f, -pw - 0.5f, px + 2.0f, top, pw + 0.5f);
        }
        return m;
    }

    /** Height of the bridge deck above its ends: level where it meets the banks and at the crown, 5 in the middle. */
    static float arch(float x, float half) {
        return 5f * 0.5f * (1 + (float) Math.cos(Math.PI * Math.max(-1, Math.min(1, x / half))));
    }

    /** Two low octagonal stone steps, 3.2 high in all: the crystal stands on top. */
    static Mesh plinth() {
        Mesh m = new Mesh();
        prism(m, STONE_DARK, STONE, 8, 22.5f, 13.5f, 13f, 0, 1.6f);
        prism(m, STONE, STONE_PALE, 8, 22.5f, 9.8f, 9.4f, 1.6f, 3.2f);
        return m;
    }

    /**
     * The centrepiece of the shrine: a cluster of faceted violet crystals, about 20 tall. Each face has one of
     * three tones, so the facets read even where the model glows (the scene draws it glossy and slightly emissive).
     */
    static Mesh crystalCluster() {
        Mesh cluster = crystal(3.4f, 14f, 6f).rotY(10);
        // {tilt away from the centre, compass direction of the tilt, distance from the centre, radius, shaft height, tip height}
        float[][] others = {{24, 20, 3.6f, 2.3f, 9.5f, 4.2f}, {32, 95, 4.2f, 1.9f, 7.2f, 3.4f}, {20, 165, 3.8f, 2.5f, 10.5f, 4.6f},
                {36, 230, 4.4f, 1.7f, 6.0f, 3.0f}, {27, 295, 3.9f, 2.1f, 8.4f, 3.8f}, {50, 345, 5.2f, 1.2f, 4.0f, 2.2f}, {55, 130, 5.4f, 1.1f, 3.6f, 2.0f}};
        for (float[] o : others)
            cluster.add(crystal(o[3], o[4], o[5]).rotY(o[1] * 0.7f).rotZ(-o[0]).translate(o[2], -0.6f, 0).rotY(o[1]));
        return cluster;
    }

    /** One six-sided crystal standing on the origin: a shaft that widens a little toward the top, then a point. */
    static Mesh crystal(float radius, float shaft, float tip) {
        float[][] tones = {{0.62f, 0.45f, 0.95f}, {0.74f, 0.60f, 1.00f}, {0.50f, 0.34f, 0.86f}};
        Mesh m = new Mesh();
        float[] apex = {0, shaft + tip, 0};
        for (int i = 0; i < 6; i++) {
            double a0 = Math.toRadians(i * 60), a1 = Math.toRadians(i * 60 + 60);
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1), low = radius * 0.72f;
            float[] b0 = {c0 * low, -1.5f, s0 * low}, b1 = {c1 * low, -1.5f, s1 * low}, t0 = {c0 * radius, shaft, s0 * radius}, t1 = {c1 * radius, shaft, s1 * radius};
            quad(m, tones[i % 3], b1, b0, t0, t1);
            tri(m, tones[(i + 1) % 3], t1, t0, apex);
        }
        return m;
    }

    // ---- building blocks: every face is flat-shaded and wound counter-clockwise seen from outside

    static void tri(Mesh m, float[] colour, float[] a, float[] b, float[] c) {
        float[] uv = ATLAS.colourUv(colour);
        float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx, length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-9f) return;
        for (float[] p : new float[][]{a, b, c}) m.corners.add(new float[]{p[0], p[1], p[2], uv[0], uv[1], nx / length, ny / length, nz / length});
    }

    static void quad(Mesh m, float[] colour, float[] a, float[] b, float[] c, float[] d) {
        tri(m, colour, a, b, c);
        tri(m, colour, a, c, d);
    }

    /**
     * A block given by its four bottom corners (p[0..3], going round) and the four top corners above them
     * (p[4..7], in the same order). Either direction round works: the winding is detected.
     */
    static void hexahedron(Mesh m, float[] sides, float[] top, float[]... p) {
        // twice the signed area of the bottom ring in the x-z plane: positive = clockwise seen from above
        float area = 0;
        for (int i = 0; i < 4; i++) area += p[i][0] * p[(i + 1) % 4][2] - p[(i + 1) % 4][0] * p[i][2];
        if (area < 0) p = new float[][]{p[3], p[2], p[1], p[0], p[7], p[6], p[5], p[4]};
        quad(m, top, p[4], p[7], p[6], p[5]);
        quad(m, sides, p[0], p[1], p[2], p[3]);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            quad(m, sides, p[j], p[i], p[4 + i], p[4 + j]);
        }
    }

    static void box(Mesh m, float[] colour, float x0, float y0, float z0, float x1, float y1, float z1) {
        hexahedron(m, colour, colour, new float[]{x0, y0, z0}, new float[]{x1, y0, z0}, new float[]{x1, y0, z1}, new float[]{x0, y0, z1},
                new float[]{x0, y1, z0}, new float[]{x1, y1, z0}, new float[]{x1, y1, z1}, new float[]{x0, y1, z1});
    }

    /** A beam or plank from x0 to x1 whose bottom and top heights differ at the two ends, so it can follow a slope. */
    static void slab(Mesh m, float[] colour, float x0, float bottom0, float top0, float x1, float bottom1, float top1, float z0, float z1) {
        hexahedron(m, colour, colour, new float[]{x0, bottom0, z0}, new float[]{x1, bottom1, z0}, new float[]{x1, bottom1, z1}, new float[]{x0, bottom0, z1},
                new float[]{x0, top0, z0}, new float[]{x1, top1, z0}, new float[]{x1, top1, z1}, new float[]{x0, top0, z1});
    }

    /** Upright prism with a regular base: radius0 at height y0, radius1 at y1, closed on top. */
    static void prism(Mesh m, float[] sides, float[] top, int corners, float startAngle, float radius0, float radius1, float y0, float y1) {
        float[] centre = {0, y1, 0};
        for (int i = 0; i < corners; i++) {
            double a0 = Math.toRadians(startAngle + i * 360.0 / corners), a1 = Math.toRadians(startAngle + (i + 1) * 360.0 / corners);
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float[] b0 = {c0 * radius0, y0, s0 * radius0}, b1 = {c1 * radius0, y0, s1 * radius0}, t0 = {c0 * radius1, y1, s0 * radius1}, t1 = {c1 * radius1, y1, s1 * radius1};
            quad(m, sides, b1, b0, t0, t1);
            tri(m, top, t1, t0, centre);
        }
    }

    /** Writes a model that was built in world units. */
    static void outWorld(String name, Mesh mesh) throws IOException {
        mesh.scale(1 / K);
        out(name, mesh);
    }

    static void out(String name, Mesh mesh) throws IOException {
        mesh.scale(K);
        float[] b = mesh.bounds();
        mesh.write(MODEL_OUT.resolve(name + ".obj"), name + " - built by tools/BuildDemoAssets from CC0 Kenney assets (kenney.nl)");
        report.add(String.format(Locale.ROOT, "%-22s tris=%5d  x[%7.2f,%7.2f] y[%7.2f,%7.2f] z[%7.2f,%7.2f]",
                name, mesh.triangleCount(), b[0], b[3], b[1], b[4], b[2], b[5]));
    }

    // ------------------------------------------------------------------------------------------ packs + atlas

    static class Pack {
        static final TreeSet<String> used = new TreeSet<>();
        final Path dir;
        final float factor; // scales this pack's units to fantasy-town units
        final Map<String, float[]> tints = new HashMap<>();
        private int colormapSlot = -1;

        Pack(String folder, float factor) {
            this.dir = SOURCES.resolve(folder).resolve("obj");
            this.factor = factor;
        }

        void tint(String material, float r, float g, float b) {
            tints.put(material, new float[]{r, g, b});
        }

        /** Loads one model of the pack, UVs already pointing into the shared atlas, in fantasy-town units. */
        Mesh piece(String name) throws IOException {
            return piece(name, group -> true);
        }

        Mesh piece(String name, java.util.function.Predicate<String> groups) throws IOException {
            Path obj = dir.resolve(name + ".obj");
            used.add(obj.toString().replace('\\', '/'));
            used.add(obj.toString().replace('\\', '/').replace(".obj", ".mtl"));
            return Mesh.load(obj, (material, u, v) -> {
                if (material.texture != null) {
                    if (colormapSlot < 0) colormapSlot = ATLAS.addImage(dir.resolve(material.texture));
                    return ATLAS.slotUv(colormapSlot, u, v);
                }
                float[] colour = tints.getOrDefault(material.name, material.kd);
                return ATLAS.colourUv(colour);
            }, groups).scale(factor);
        }
    }

    /** 2048px texture made of 4x4 slots of 512px: pack colormaps plus palette slots of 8x8 colour swatches. */
    static class Atlas {
        static final int SIZE = 2048, SLOT = 512, GRID = SIZE / SLOT, SWATCH = 64, PER_SLOT = (SLOT / SWATCH) * (SLOT / SWATCH);
        private final BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        private final Graphics2D g = image.createGraphics();
        private final Map<Integer, Integer> colourIndex = new LinkedHashMap<>();
        private final List<Integer> paletteSlots = new ArrayList<>();
        private int nextSlot = 0;

        Atlas() {
            g.setColor(new Color(128, 128, 128));
            g.fillRect(0, 0, SIZE, SIZE);
        }

        int addImage(Path file) {
            try {
                int slot = nextSlot++;
                BufferedImage source = ImageIO.read(file.toFile());
                g.drawImage(source, (slot % GRID) * SLOT, (slot / GRID) * SLOT, SLOT, SLOT, null);
                return slot;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        /** OBJ uv (v up, may tile) inside a slot -> atlas uv. */
        float[] slotUv(int slot, float u, float v) {
            if (u < 0 || u > 1) u -= Math.floor(u);
            if (v < 0 || v > 1) v -= Math.floor(v);
            float px = (slot % GRID) * SLOT + u * SLOT, py = (slot / GRID) * SLOT + (1 - v) * SLOT;
            return new float[]{px / SIZE, 1 - py / SIZE};
        }

        /** The atlas colour that a uv of a model points at. */
        Color colourAt(float u, float v) {
            return new Color(image.getRGB(Math.min(SIZE - 1, (int) (u * SIZE)), Math.min(SIZE - 1, (int) ((1 - v) * SIZE))));
        }

        float[] colourUv(float[] rgb) {
            int key = new Color(clamp(rgb[0]), clamp(rgb[1]), clamp(rgb[2])).getRGB();
            Integer index = colourIndex.get(key);
            if (index == null) {
                index = colourIndex.size();
                colourIndex.put(key, index);
                if (index / PER_SLOT >= paletteSlots.size()) paletteSlots.add(nextSlot++);
            }
            int slot = paletteSlots.get(index / PER_SLOT), cell = index % PER_SLOT, perRow = SLOT / SWATCH;
            int x = (slot % GRID) * SLOT + (cell % perRow) * SWATCH, y = (slot / GRID) * SLOT + (cell / perRow) * SWATCH;
            g.setColor(new Color(key));
            g.fillRect(x, y, SWATCH, SWATCH);
            return new float[]{(x + SWATCH / 2f) / SIZE, 1 - (y + SWATCH / 2f) / SIZE}; // swatch centre: safe under mipmapping
        }

        private static float clamp(float f) {
            return Math.max(0, Math.min(1, f));
        }

        int paletteSize() {
            return colourIndex.size();
        }

        void write(Path file) throws IOException {
            if (nextSlot > GRID * GRID) throw new IllegalStateException("atlas full");
            Files.createDirectories(file.getParent());
            ImageIO.write(image, "png", file.toFile());
        }
    }
}
