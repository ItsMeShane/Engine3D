import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Builds the assets of the "forest" scene from CC0 Poly Haven scans in tools/asset_sources/polyhaven_forest:
 *
 *   trees      generated here: a bark-textured trunk mesh plus a foliage mesh of alpha cut-out cards. The card
 *              textures (fir boughs, pine tufts, leaf sprays) are composed from scanned twig and leaf sprites.
 *   grass      patches of crossed cards from scanned grass tufts, some with dandelions
 *   plants, rocks, props   converted from glTF (several variants per pack, multi-material models into one atlas)
 *   ground     colour + normal maps for forest floor, shore, dirt
 *   sky        a panorama photo turned into the six cube faces, rotated so its sun sits where the scene wants it
 *   particles  ember, dust mote, falling leaf and mist atlases
 *
 * Everything is scaled to the forest scene's 3.5 world units per metre.
 * Run from the project root:  javac -d out/tools tools/*.java  &&  java -cp out/tools BuildForestAssets
 */
public class BuildForestAssets {

    static final Path SRC = Path.of("tools/asset_sources/polyhaven_forest");
    static final Path MODELS = Path.of("res/models/forest"), TEXTURES = Path.of("res/textures/forest");
    static final Path PARTICLES = Path.of("res/particles/forest"), SKY = Path.of("res/skybox/golden");
    static final Path KENNEY_PARTICLES = Path.of("tools/asset_sources/kenney_particle-pack/png");

    /** World units per metre. */
    static final float K = 3.5f;
    /** Direction the sun of the sky photo is turned to, as a compass-like angle: atan2(z, x) of the direction toward the sun. */
    static final double SUN_AZIMUTH = Math.toRadians(-112);

    static final List<String> report = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        for (Path p : new Path[]{MODELS, TEXTURES, PARTICLES, SKY}) Files.createDirectories(p);
        sky();
        ground();
        foliageTextures();
        trees();
        grass();
        plantsAndRocks();
        props();
        particles();
        Files.write(MODELS.resolve("_sizes.txt"), report);
        report.forEach(System.out::println);
    }

    // =================================================================================================== sky

    /** Equirectangular panorama -> cube faces named for the engine's loader (front/back = +x/-x, up/down, left/right = +z/-z). */
    static void sky() throws IOException {
        BufferedImage pano = ImageIO.read(SRC.resolve("sky/kloppenheim_06_puresky.jpg").toFile());
        int w = pano.getWidth(), h = pano.getHeight();

        // find the sun: the column and row where the upper half is brightest
        double[] column = new double[w], row = new double[h / 2];
        for (int y = 0; y < h / 2; y += 2)
            for (int x = 0; x < w; x += 2) {
                double l = Math.pow(luma(pano.getRGB(x, y)) / 255.0, 6);
                column[x] += l;
                row[y] += l;
            }
        int sunX = argmax(smooth(column, w / 90)), sunY = argmax(smooth(row, h / 180));
        double sunLon = (sunX + 0.5) / w * 2 * Math.PI - Math.PI, sunElevation = Math.PI / 2 - (sunY + 0.5) / h * Math.PI;
        double rotation = sunLon - SUN_AZIMUTH; // added to every lookup so the photo's sun lands on SUN_AZIMUTH

        String[] names = {"front", "back", "up", "down", "left", "right"};
        int size = 1024;
        for (int face = 0; face < 6; face++) {
            BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            for (int py = 0; py < size; py++)
                for (int px = 0; px < size; px++) {
                    double s = 2 * (px + 0.5) / size - 1, t = 2 * (py + 0.5) / size - 1, x, y, z;
                    switch (face) {
                        case 0 -> { x = 1; y = -t; z = -s; }
                        case 1 -> { x = -1; y = -t; z = s; }
                        case 2 -> { x = s; y = 1; z = t; }
                        case 3 -> { x = s; y = -1; z = -t; }
                        case 4 -> { x = s; y = -t; z = 1; }
                        default -> { x = -s; y = -t; z = -1; }
                    }
                    double length = Math.sqrt(x * x + y * y + z * z);
                    double lon = Math.atan2(z, x) + rotation, lat = Math.asin(y / length);
                    out.setRGB(px, py, bilinear(pano, (lon / (2 * Math.PI) + 0.5) * w - 0.5, (0.5 - lat / Math.PI) * h - 0.5));
                }
            ImageIO.write(out, "png", SKY.resolve(names[face] + ".png").toFile());
        }

        // colours near the horizon, for the scene's fog
        long[] away = new long[4], toward = new long[4];
        for (int y = (int) (h * (0.5 - 7 / 180.0)); y < (int) (h * (0.5 - 1.5 / 180.0)); y += 2)
            for (int x = 0; x < w; x += 4) {
                int dx = Math.abs(x - sunX);
                dx = Math.min(dx, w - dx);
                long[] sum = dx < w / 10 ? toward : dx > w / 4 ? away : null;
                if (sum == null) continue;
                int rgb = pano.getRGB(x, y);
                sum[0] += (rgb >> 16) & 255;
                sum[1] += (rgb >> 8) & 255;
                sum[2] += rgb & 255;
                sum[3]++;
            }
        report.add(String.format(Locale.ROOT, "sky: sun elevation in the photo %.1f deg, turned to azimuth %.0f deg", Math.toDegrees(sunElevation), Math.toDegrees(SUN_AZIMUTH)));
        report.add(String.format(Locale.ROOT, "sky: horizon colour away from the sun (%.3f, %.3f, %.3f), toward the sun (%.3f, %.3f, %.3f)",
                away[0] / 255.0 / away[3], away[1] / 255.0 / away[3], away[2] / 255.0 / away[3], toward[0] / 255.0 / toward[3], toward[1] / 255.0 / toward[3], toward[2] / 255.0 / toward[3]));
    }

    static double[] smooth(double[] values, int radius) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++)
            for (int k = -radius; k <= radius; k++) out[i] += values[Math.floorMod(i + k, values.length)];
        return out;
    }

    static int argmax(double[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) if (values[i] > values[best]) best = i;
        return best;
    }

    static double luma(int rgb) {
        return 0.2126 * ((rgb >> 16) & 255) + 0.7152 * ((rgb >> 8) & 255) + 0.0722 * (rgb & 255);
    }

    static int bilinear(BufferedImage image, double x, double y) {
        int w = image.getWidth(), h = image.getHeight();
        int x0 = (int) Math.floor(x), y0 = Math.max(0, Math.min(h - 1, (int) Math.floor(y))), y1 = Math.min(h - 1, y0 + 1);
        double fx = x - x0, fy = Math.max(0, Math.min(1, y - y0));
        int a = image.getRGB(Math.floorMod(x0, w), y0), b = image.getRGB(Math.floorMod(x0 + 1, w), y0);
        int c = image.getRGB(Math.floorMod(x0, w), y1), d = image.getRGB(Math.floorMod(x0 + 1, w), y1);
        int rgb = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            double top = ((a >> shift) & 255) * (1 - fx) + ((b >> shift) & 255) * fx, bottom = ((c >> shift) & 255) * (1 - fx) + ((d >> shift) & 255) * fx;
            rgb |= (int) Math.round(top * (1 - fy) + bottom * fy) << shift;
        }
        return rgb;
    }

    // =================================================================================================== ground

    static void ground() throws IOException {
        graded("maps/forest_leaves_02_Diffuse.jpg", "ground_forest", 0.6f, 0.62f);
        graded("../ambientcg/Grass005_1K-JPG_Color.jpg", "ground_grass", 0.74f, 0.66f);
        ImageIO.write(ImageIO.read(SRC.resolve("../ambientcg/Grass005_1K-JPG_NormalDX.jpg").toFile()), "png", TEXTURES.resolve("ground_grass_normal.png").toFile());
        graded("maps/rocky_trail_Diffuse.jpg", "ground_shore", 0.85f, 0.95f);
        graded("maps/forest_ground_04_Diffuse.jpg", "ground_dirt", 0.9f, 0.9f);
        for (String[] n : new String[][]{{"forest_leaves_02", "ground_forest"}, {"rocky_trail", "ground_shore"}, {"forest_ground_04", "ground_dirt"}})
            copyAsPng("maps/" + n[0] + "_nor_dx.jpg", n[1] + "_normal", 1024);
        // bark for the generated trunks
        graded("maps/pine_tree_01_bark_diff.jpg", "bark_conifer", 1f, 1.05f);
        copyAsPng("maps/pine_tree_01_bark_nor_dx.jpg", "bark_conifer_normal", 1024);
        graded("maps/island_tree_01_branches_diff.jpg", "bark_broadleaf", 0.9f, 0.9f);
        copyAsPng("maps/island_tree_01_branches_nor_dx.jpg", "bark_broadleaf_normal", 1024);
    }

    static void graded(String source, String name, float saturation, float brightness) throws IOException {
        BufferedImage in = ImageIO.read(SRC.resolve(source).toFile());
        BufferedImage out = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_RGB);
        float[] hsb = new float[3];
        for (int y = 0; y < in.getHeight(); y++)
            for (int x = 0; x < in.getWidth(); x++) {
                int rgb = in.getRGB(x, y);
                Color.RGBtoHSB((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, hsb);
                out.setRGB(x, y, Color.HSBtoRGB(hsb[0], Math.min(1, hsb[1] * saturation), Math.min(1, hsb[2] * brightness)));
            }
        ImageIO.write(out, "png", TEXTURES.resolve(name + ".png").toFile());
    }

    static void copyAsPng(String source, String name, int size) throws IOException {
        ImageIO.write(resize(ImageIO.read(SRC.resolve(source).toFile()), size, size, false), "png", TEXTURES.resolve(name + ".png").toFile());
    }

    static BufferedImage resize(BufferedImage source, int width, int height, boolean alpha) {
        BufferedImage result = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.drawImage(source.getScaledInstance(width, height, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
        g.dispose();
        return result;
    }

    // =================================================================================================== sprites

    /** A cut-out piece of a scanned atlas: where it attaches (anchor) and which way it grows (angle, image coordinates). */
    static class Sprite {
        BufferedImage image;
        double anchorX, anchorY, angle, length;
    }

    static BufferedImage rgba(String diffuse, String alpha) throws IOException {
        BufferedImage d = ImageIO.read(SRC.resolve(diffuse).toFile()), a = ImageIO.read(SRC.resolve(alpha).toFile());
        BufferedImage out = new BufferedImage(d.getWidth(), d.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < d.getHeight(); y++)
            for (int x = 0; x < d.getWidth(); x++)
                out.setRGB(x, y, ((a.getRGB(x, y) & 255) << 24) | (d.getRGB(x, y) & 0xffffff));
        return out;
    }

    /**
     * The connected opaque shape containing (or nearest to) the seed pixel, cropped. Pixels within {@code join}
     * pixels of each other count as connected, so thin needles stay attached to their stem.
     *
     * @param anchorSide where the stem is: 'b' bottom, 'l' left, 'r' right, 't' top of the cropped shape
     */
    static Sprite sprite(BufferedImage atlas, int seedX, int seedY, int join, char anchorSide) {
        int w = atlas.getWidth(), h = atlas.getHeight();
        boolean[] solid = new boolean[w * h], near = new boolean[w * h], member = new boolean[w * h];
        for (int i = 0; i < w * h; i++) solid[i] = (atlas.getRGB(i % w, i / w) >>> 24) > 100;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (solid[y * w + x])
                    for (int j = -join; j <= join; j++)
                        for (int i = -join; i <= join; i++) {
                            int xx = x + i, yy = y + j;
                            if (xx >= 0 && yy >= 0 && xx < w && yy < h) near[yy * w + xx] = true;
                        }
        int seed = -1;
        for (int r = 0; r < 40 && seed < 0; r++)
            for (int j = -r; j <= r && seed < 0; j++)
                for (int i = -r; i <= r && seed < 0; i++) {
                    int xx = seedX + i, yy = seedY + j;
                    if (xx >= 0 && yy >= 0 && xx < w && yy < h && solid[yy * w + xx]) seed = yy * w + xx;
                }
        if (seed < 0) throw new IllegalStateException("no opaque pixel near seed " + seedX + "," + seedY);

        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(seed);
        member[seed] = true;
        int x0 = w, x1 = 0, y0 = h, y1 = 0, count = 0;
        while (!queue.isEmpty()) {
            int p = queue.poll(), x = p % w, y = p / w;
            if (solid[p]) {
                count++;
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
            }
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int xx = x + d[0], yy = y + d[1];
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                int n = yy * w + xx;
                if (near[n] && !member[n]) {
                    member[n] = true;
                    queue.add(n);
                }
            }
        }
        if (count > w * h * 0.3) throw new IllegalStateException("sprite at " + seedX + "," + seedY + " leaked into the background");

        Sprite sprite = new Sprite();
        int sw = x1 - x0 + 1, sh = y1 - y0 + 1;
        sprite.image = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        double cx = 0, cy = 0, ax = 0, ay = 0;
        int n = 0, an = 0;
        for (int y = 0; y < sh; y++)
            for (int x = 0; x < sw; x++) {
                int p = (y + y0) * w + x + x0;
                if (!member[p] || !solid[p]) continue;
                sprite.image.setRGB(x, y, atlas.getRGB(x + x0, y + y0));
                cx += x;
                cy += y;
                n++;
                boolean atAnchor = switch (anchorSide) {
                    case 'b' -> y > sh * 0.94;
                    case 't' -> y < sh * 0.06;
                    case 'l' -> x < sw * 0.06;
                    default -> x > sw * 0.94;
                };
                if (atAnchor) {
                    ax += x;
                    ay += y;
                    an++;
                }
            }
        sprite.anchorX = ax / an;
        sprite.anchorY = ay / an;
        sprite.angle = Math.atan2(cy / n - sprite.anchorY, cx / n - sprite.anchorX);
        double dx = Math.cos(sprite.angle), dy = Math.sin(sprite.angle);
        for (int y = 0; y < sh; y += 2)
            for (int x = 0; x < sw; x += 2)
                if ((sprite.image.getRGB(x, y) >>> 24) > 100) sprite.length = Math.max(sprite.length, (x - sprite.anchorX) * dx + (y - sprite.anchorY) * dy);
        return sprite;
    }

    /** Draws the sprite with its anchor at (x, y), growing in direction {@code angle} (radians, image coordinates) for {@code length} pixels. */
    static void stamp(Graphics2D g, Sprite sprite, double x, double y, double angle, double length, boolean mirror, float brightness) {
        AffineTransform t = new AffineTransform();
        t.translate(x, y);
        t.rotate(angle);
        if (mirror) t.scale(1, -1);
        t.rotate(-sprite.angle);
        double scale = length / sprite.length;
        t.scale(scale, scale);
        t.translate(-sprite.anchorX, -sprite.anchorY);
        g.drawImage(brightness == 1 ? sprite.image : shaded(sprite.image, brightness), t, null);
    }

    static BufferedImage shaded(BufferedImage image, float brightness) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) {
                int p = image.getRGB(x, y);
                if ((p >>> 24) == 0) continue;
                int r = Math.min(255, Math.round(((p >> 16) & 255) * brightness)), gr = Math.min(255, Math.round(((p >> 8) & 255) * brightness)), b = Math.min(255, Math.round((p & 255) * brightness));
                out.setRGB(x, y, (p & 0xff000000) | (r << 16) | (gr << 8) | b);
            }
        return out;
    }

    static Graphics2D canvas(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    /**
     * Makes a cut-out texture safe for mipmapping and alpha testing: firmer alpha (thin needles survive when
     * the texture is shrunk) and the average foliage colour under transparent pixels (no dark fringes).
     */
    static void finishCutout(BufferedImage image, float alphaGain) {
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) {
                int p = image.getRGB(x, y);
                if ((p >>> 24) < 200) continue;
                r += (p >> 16) & 255;
                g += (p >> 8) & 255;
                b += p & 255;
                n++;
            }
        int average = n == 0 ? 0 : (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) {
                int p = image.getRGB(x, y), alpha = Math.min(255, Math.round((p >>> 24) * alphaGain));
                image.setRGB(x, y, alpha < 8 ? average : (alpha << 24) | (p & 0xffffff));
            }
    }

    static void save(BufferedImage image, String name) throws IOException {
        ImageIO.write(image, "png", TEXTURES.resolve(name + ".png").toFile());
    }

    // =================================================================================================== foliage textures

    static Sprite[] leafSprites;

    /** Three 1024px atlases of four 512px cards each: fir boughs, pine tufts, broadleaf sprays. */
    static void foliageTextures() throws IOException {
        Random random = new Random(11);

        BufferedImage firAtlas = rgba("maps/fir_tree_01_twig_diff.jpg", "maps/fir_tree_01_twig_alpha.jpg");
        Sprite[] fir = {sprite(firAtlas, 813, 300, 3, 'b'), sprite(firAtlas, 316, 250, 3, 'b'), sprite(firAtlas, 486, 700, 3, 'b'), sprite(firAtlas, 815, 760, 3, 'b'), sprite(firAtlas, 575, 380, 3, 'b')};
        BufferedImage firOut = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB);
        for (int cell = 0; cell < 4; cell++) {
            Graphics2D g = canvas(firOut);
            g.translate((cell % 2) * 512, (cell / 2) * 512);
            g.setClip(0, 0, 512, 512);
            bough(g, fir, random, cell == 2 ? 0.55 : 1.0, cell == 2 ? 34 : 58, new Color(58, 44, 30));
            g.dispose();
        }
        finishCutout(firOut, 1.7f);
        save(firOut, "foliage_fir");

        BufferedImage pineAtlas = rgba("maps/pine_tree_01_twig_diff.jpg", "maps/pine_tree_01_twig_alpha.jpg");
        Sprite[] pine = {sprite(pineAtlas, 760, 455, 2, 'l')};
        BufferedImage pineOut = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB);
        for (int cell = 0; cell < 4; cell++) {
            Graphics2D g = canvas(pineOut);
            g.translate((cell % 2) * 512, (cell / 2) * 512);
            g.setClip(0, 0, 512, 512);
            pineCluster(g, pine, random);
            g.dispose();
        }
        for (int y = 0; y < 1024; y++) // the scan's needles are yellowing; make them a living green
            for (int x = 0; x < 1024; x++) {
                int p = pineOut.getRGB(x, y);
                pineOut.setRGB(x, y, (p & 0xff000000) | (((p >> 16) & 255) * 82 / 100) << 16 | (p & 0xff00) | ((p & 255) * 84 / 100));
            }
        finishCutout(pineOut, 1.8f);
        save(pineOut, "foliage_pine");

        BufferedImage leafAtlas = rgba("maps/island_tree_01_leaves_diff.jpg", "maps/island_tree_01_leaves_alpha.jpg");
        int[][] seeds = {{85, 250}, {256, 200}, {772, 220}, {431, 200}, {597, 210}, {292, 800}, {503, 820}, {101, 830}};
        leafSprites = new Sprite[seeds.length];
        for (int i = 0; i < seeds.length; i++) leafSprites[i] = sprite(leafAtlas, seeds[i][0], seeds[i][1], 1, 'b');
        BufferedImage leafOut = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB);
        for (int cell = 0; cell < 4; cell++) {
            Graphics2D g = canvas(leafOut);
            g.translate((cell % 2) * 512, (cell / 2) * 512);
            g.setClip(0, 0, 512, 512);
            leafSpray(g, leafSprites, random);
            g.dispose();
        }
        for (int y = 0; y < 1024; y++) // late-summer green rather than the scan's yellow-olive
            for (int x = 0; x < 1024; x++) {
                int p = leafOut.getRGB(x, y);
                leafOut.setRGB(x, y, (p & 0xff000000) | (((p >> 16) & 255) * 80 / 100) << 16 | (((p >> 8) & 255) * 94 / 100) << 8 | ((p & 255) * 80 / 100));
            }
        finishCutout(leafOut, 1.25f);
        save(leafOut, "foliage_leaves");
    }

    /** A conifer bough: a stem from the bottom centre to the top with twigs branching off left and right. */
    static void bough(Graphics2D g, Sprite[] twigs, Random random, double lengthFactor, double sideAngle, Color wood) {
        double baseX = 256, baseY = 504, tipX = 256 + (random.nextDouble() - 0.5) * 50, tipY = 504 - 480 * lengthFactor;
        double bend = (random.nextDouble() - 0.5) * 60;
        g.setColor(wood);
        for (int i = 0; i < 40; i++) {
            double t0 = i / 40.0, t1 = (i + 1) / 40.0;
            g.setStroke(new BasicStroke((float) (9 - 7 * t0), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new java.awt.geom.Line2D.Double(spineX(baseX, tipX, bend, t0), baseY + (tipY - baseY) * t0, spineX(baseX, tipX, bend, t1), baseY + (tipY - baseY) * t1));
        }
        int count = (int) (26 * lengthFactor) + 6;
        for (int i = 0; i < count; i++) {
            double t = 0.06 + 0.9 * i / (count - 1.0);
            double x = spineX(baseX, tipX, bend, t), y = baseY + (tipY - baseY) * t;
            boolean left = i % 2 == 0;
            double angle = Math.toRadians(-90 + (left ? -1 : 1) * (sideAngle - 18 * t + (random.nextDouble() - 0.5) * 16));
            double length = (235 - 135 * t) * (0.85 + random.nextDouble() * 0.3) * Math.min(1, lengthFactor + 0.25);
            stamp(g, twigs[random.nextInt(twigs.length)], x, y, angle, length, random.nextBoolean(), 0.78f + random.nextFloat() * 0.3f);
        }
        stamp(g, twigs[random.nextInt(twigs.length)], tipX, tipY + 30, Math.toRadians(-90), 110, false, 1.05f);
    }

    static double spineX(double baseX, double tipX, double bend, double t) {
        return baseX + (tipX - baseX) * t + bend * Math.sin(t * Math.PI);
    }

    /** One twig of a composed branch: start, direction (radians, image coordinates), length, thickness. */
    record Twig(double x, double y, double angle, double length, double width) {
        double px(double t) {
            return x + Math.cos(angle) * length * t;
        }

        double py(double t) {
            return y + Math.sin(angle) * length * t;
        }
    }

    /** A main stem from the bottom centre with side twigs and sub-twigs; returns all of them, thickest first. */
    static List<Twig> branch(Random random, double mainLength, int sides, double sideLength, int subs, double subLength, double sideAngle) {
        List<Twig> twigs = new ArrayList<>();
        Twig main = new Twig(256, 506, Math.toRadians(-90 + (random.nextDouble() - 0.5) * 10), mainLength, 7);
        twigs.add(main);
        for (int i = 0; i < sides; i++) {
            double t = 0.1 + 0.82 * i / (sides - 1.0);
            double angle = main.angle + Math.toRadians((i % 2 == 0 ? -1 : 1) * (sideAngle - 14 * t + random.nextDouble() * 16));
            Twig side = new Twig(main.px(t), main.py(t), angle, sideLength * (1 - 0.55 * t) * (0.8 + random.nextDouble() * 0.4), 4);
            twigs.add(side);
            for (int k = 0; k < subs; k++) {
                double s = 0.25 + 0.7 * k / Math.max(1, subs - 1.0);
                double a2 = side.angle + Math.toRadians((k % 2 == 0 ? -1 : 1) * (38 + random.nextDouble() * 22));
                twigs.add(new Twig(side.px(s), side.py(s), a2, subLength * (1 - 0.4 * s) * (0.75 + random.nextDouble() * 0.5), 2.2));
            }
        }
        return twigs;
    }

    static void drawTwigs(Graphics2D g, List<Twig> twigs, Color wood) {
        g.setColor(wood);
        for (Twig twig : twigs) {
            g.setStroke(new BasicStroke((float) twig.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new java.awt.geom.Line2D.Double(twig.x, twig.y, twig.px(1), twig.py(1)));
        }
    }

    /** A pine branch end: twigs carrying many small needle tufts, densest at the tips. */
    static void pineCluster(Graphics2D g, Sprite[] tufts, Random random) {
        List<Twig> twigs = branch(random, 330, 9, 190, 3, 80, 62);
        drawTwigs(g, twigs, new Color(64, 50, 38));
        for (Twig twig : twigs) {
            int count = twig.width > 5 ? 5 : twig.width > 3 ? 4 : 3;
            for (int i = 0; i < count; i++) {
                double t = count == 1 ? 1 : 0.45 + 0.55 * i / (count - 1.0);
                double angle = twig.angle + Math.toRadians((random.nextDouble() - 0.5) * (i == count - 1 ? 30 : 110));
                stamp(g, tufts[0], twig.px(t), twig.py(t), angle, 78 + random.nextDouble() * 34, random.nextBoolean(), 0.72f + random.nextFloat() * 0.4f);
            }
        }
    }

    /** A leafy branch: leaves of a believable size (a card is about 3 m across) alternating along every twig. */
    static void leafSpray(Graphics2D g, Sprite[] leaves, Random random) {
        List<Twig> twigs = branch(random, 400, 10, 215, 4, 82, 56);
        drawTwigs(g, twigs, new Color(74, 60, 44));
        for (int pass = 0; pass < 2; pass++) // inner (darker) leaves first, then the lit outer ones on top
            for (Twig twig : twigs) {
                if (twig.width > 5) continue;
                int count = (int) (twig.length / 15) + 1;
                for (int i = 1; i <= count; i++) {
                    if ((i + pass) % 2 == 1) continue;
                    double t = i / (double) count;
                    double angle = twig.angle + Math.toRadians((i % 4 < 2 ? -1 : 1) * (45 + random.nextDouble() * 30));
                    if (i == count) angle = twig.angle + Math.toRadians((random.nextDouble() - 0.5) * 30);
                    stamp(g, leaves[random.nextInt(leaves.length)], twig.px(t), twig.py(t), angle, 40 + random.nextDouble() * 16, random.nextBoolean(), (pass == 0 ? 0.62f : 0.86f) + random.nextFloat() * 0.3f);
                }
            }
    }

    // =================================================================================================== meshes

    static float[] v(double x, double y, double z) {
        return new float[]{(float) x, (float) y, (float) z};
    }

    static float[] add(float[] a, float[] b, double scale) {
        return v(a[0] + b[0] * scale, a[1] + b[1] * scale, a[2] + b[2] * scale);
    }

    static float[] cross(float[] a, float[] b) {
        return v(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]);
    }

    static float[] unit(float[] a) {
        double length = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return length < 1e-9 ? v(0, 1, 0) : v(a[0] / length, a[1] / length, a[2] / length);
    }

    static float[] corner(float[] p, double u, double vv, float[] n) {
        return new float[]{p[0], p[1], p[2], (float) u, (float) vv, n[0], n[1], n[2]};
    }

    /** Adds a triangle, wound so its front faces along {@code outward}. */
    static void tri(Mesh mesh, float[] a, float[] b, float[] c, float[] outward) {
        float[] n = cross(v(b[0] - a[0], b[1] - a[1], b[2] - a[2]), v(c[0] - a[0], c[1] - a[1], c[2] - a[2]));
        boolean flip = n[0] * outward[0] + n[1] * outward[1] + n[2] * outward[2] < 0;
        mesh.corners.add(a);
        mesh.corners.add(flip ? c : b);
        mesh.corners.add(flip ? b : c);
    }

    /** A tube along a path of points with a radius at each; bark texture wraps {@code uRepeat} times around. */
    static void tube(Mesh mesh, float[][] path, float[] radii, int sides, float uRepeat, float vScale) {
        float[][][] rings = new float[path.length][sides + 1][];
        float along = 0;
        for (int i = 0; i < path.length; i++) {
            float[] axis = unit(v(path[Math.min(i + 1, path.length - 1)][0] - path[Math.max(i - 1, 0)][0], path[Math.min(i + 1, path.length - 1)][1] - path[Math.max(i - 1, 0)][1],
                    path[Math.min(i + 1, path.length - 1)][2] - path[Math.max(i - 1, 0)][2]));
            float[] side = unit(cross(axis, Math.abs(axis[1]) > 0.9 ? v(1, 0, 0) : v(0, 1, 0))), other = cross(axis, side);
            if (i > 0) along += (float) Math.sqrt(Math.pow(path[i][0] - path[i - 1][0], 2) + Math.pow(path[i][1] - path[i - 1][1], 2) + Math.pow(path[i][2] - path[i - 1][2], 2));
            for (int k = 0; k <= sides; k++) {
                double a = 2 * Math.PI * k / sides;
                float[] normal = unit(v(side[0] * Math.cos(a) + other[0] * Math.sin(a), side[1] * Math.cos(a) + other[1] * Math.sin(a), side[2] * Math.cos(a) + other[2] * Math.sin(a)));
                rings[i][k] = corner(add(path[i], normal, radii[i]), k / (double) sides * uRepeat, along * vScale, normal);
            }
        }
        for (int i = 0; i + 1 < path.length; i++)
            for (int k = 0; k < sides; k++) {
                float[] out = v(rings[i][k][5], rings[i][k][6], rings[i][k][7]);
                tri(mesh, rings[i][k], rings[i][k + 1], rings[i + 1][k + 1], out);
                tri(mesh, rings[i][k], rings[i + 1][k + 1], rings[i + 1][k], out);
            }
    }

    /** Trunk from 2 units below the ground to the top, with a root flare and a slight lean; returns the centre line. */
    static float[][] trunk(Mesh mesh, Random random, float height, float baseRadius, float topRadius, float lean) {
        int rings = 12;
        double direction = random.nextDouble() * Math.PI * 2, wobble = random.nextDouble() * Math.PI * 2;
        float[][] path = new float[rings][];
        float[] radii = new float[rings];
        for (int i = 0; i < rings; i++) {
            double t = i / (rings - 1.0), y = i == 0 ? -2 : height * Math.pow((i - 1) / (rings - 2.0), 1.25);
            double offset = lean * t * t + Math.sin(t * 5 + wobble) * lean * 0.12;
            path[i] = v(Math.cos(direction) * offset, y, Math.sin(direction) * offset);
            double flare = 1 + 0.4 * Math.exp(-Math.max(y, 0) / (height * 0.02));
            radii[i] = (float) ((baseRadius + (topRadius - baseRadius) * Math.pow(Math.max(y, 0) / height, 0.85)) * flare);
        }
        tube(mesh, path, radii, 9, 2, 2 / (float) (2 * Math.PI * baseRadius));
        return path;
    }

    static float[] centreAt(float[][] path, float y) {
        for (int i = 0; i + 1 < path.length; i++)
            if (y <= path[i + 1][1]) {
                float t = (y - path[i][1]) / Math.max(1e-6f, path[i + 1][1] - path[i][1]);
                return v(path[i][0] + (path[i + 1][0] - path[i][0]) * t, y, path[i][2] + (path[i + 1][2] - path[i][2]) * t);
            }
        return v(path[path.length - 1][0], y, path[path.length - 1][2]);
    }

    /** UV rectangle of card {@code cell} (0-3) in a 2x2 atlas: {u0, v0 (card base), u1, v1 (card tip)} in OBJ convention. */
    static float[] cell(int cell) {
        float u0 = (cell % 2) * 0.5f + 0.004f, top = (cell / 2) * 0.5f + 0.004f;
        return new float[]{u0, 1 - (top + 0.492f), u0 + 0.492f, 1 - top};
    }

    /**
     * A foliage card running through {@code spine} (base to tip) with half-width {@code halfWidth} along {@code side};
     * its edges are pushed along {@code fold} so the card is a shallow tent rather than a flat sheet.
     */
    static void ribbon(Mesh mesh, float[][] spine, float[] side, float halfWidth, float[] fold, float foldAmount, float[] uv, java.util.function.Function<float[], float[]> normal) {
        float[][][] rows = new float[spine.length][3][];
        for (int i = 0; i < spine.length; i++) {
            double t = i / (spine.length - 1.0), vv = uv[1] + (uv[3] - uv[1]) * t;
            float[] left = add(add(spine[i], side, -halfWidth), fold, foldAmount), right = add(add(spine[i], side, halfWidth), fold, foldAmount);
            rows[i][0] = corner(left, uv[0], vv, normal.apply(left));
            rows[i][1] = corner(spine[i], (uv[0] + uv[2]) / 2, vv, normal.apply(spine[i]));
            rows[i][2] = corner(right, uv[2], vv, normal.apply(right));
        }
        for (int i = 0; i + 1 < spine.length; i++)
            for (int k = 0; k < 2; k++) {
                mesh.corners.add(rows[i][k]);
                mesh.corners.add(rows[i][k + 1]);
                mesh.corners.add(rows[i + 1][k + 1]);
                mesh.corners.add(rows[i][k]);
                mesh.corners.add(rows[i + 1][k + 1]);
                mesh.corners.add(rows[i + 1][k]);
            }
    }

    /** Two cards crossed along {@code axis}, from {@code base} to base + axis * length. */
    static void crossedCards(Mesh mesh, Random random, float[] base, float[] axis, float length, float width, int cell, java.util.function.Function<float[], float[]> normal) {
        float[] side = unit(cross(axis, v(random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() - 0.5))), other = cross(axis, side);
        float[][] spine = {base, add(base, axis, length * 0.5), add(base, axis, length)};
        ribbon(mesh, spine, side, width / 2, other, 0, cell(cell), normal);
        ribbon(mesh, spine, other, width / 2, side, 0, cell((cell + 1) % 4), normal);
    }

    static void writeTree(String name, Mesh trunk, Mesh leaves) throws IOException {
        trunk.write(MODELS.resolve(name + "_trunk.obj"), name + " trunk - generated by tools/BuildForestAssets");
        leaves.write(MODELS.resolve(name + "_leaves.obj"), name + " foliage - generated by tools/BuildForestAssets");
        float[] b = leaves.bounds();
        report.add(String.format(Locale.ROOT, "%-16s trunk tris=%5d, foliage tris=%5d, height %.1f, crown width %.1f", name, trunk.triangleCount(), leaves.triangleCount(), b[4], b[3] - b[0]));
    }

    // =================================================================================================== trees

    static void trees() throws IOException {
        fir("fir_a", 1, 78, 1.0f);
        fir("fir_b", 2, 64, 1.12f);
        fir("fir_c", 3, 47, 0.95f);
        fir("fir_young", 4, 24, 1.05f);
        pine("pine_a", 5, 76);
        pine("pine_b", 6, 62);
        broadleaf("broadleaf_a", 7, 52, 1.0f);
        broadleaf("broadleaf_b", 8, 41, 1.15f);
        bush("bush_a", 9, 13);
        bush("bush_b", 10, 8.5f);
    }

    /** Spruce/fir: whorls of drooping boughs getting shorter toward a pointed top. */
    static void fir(String name, long seed, float height, float spread) throws IOException {
        Random random = new Random(seed);
        Mesh trunkMesh = new Mesh(), leaves = new Mesh();
        float[][] path = trunk(trunkMesh, random, height, height * 0.017f + 0.35f, 0.12f, height * 0.02f);
        float crownStart = height * (0.13f + random.nextFloat() * 0.07f), longest = height * 0.2f * spread;
        float[] up = v(0, 1, 0);

        for (float y = crownStart; y < height * 0.95f; ) {
            float t = (y - crownStart) / (height - crownStart), length = longest * (float) Math.pow(1 - t, 0.8) + 1.4f;
            int boughs = length > longest * 0.55f ? 6 : 5;
            double start = random.nextDouble() * Math.PI * 2;
            for (int b = 0; b < boughs; b++) {
                double angle = start + 2 * Math.PI * b / boughs + (random.nextDouble() - 0.5) * 0.5;
                float[] out = v(Math.cos(angle), 0, Math.sin(angle)), side = cross(up, out);
                double droop = Math.toRadians(-30 + 46 * t + (random.nextDouble() - 0.5) * 12), tipDroop = droop + Math.toRadians(16);
                float l = length * (0.8f + random.nextFloat() * 0.35f);
                float[] base = add(centreAt(path, y + (random.nextFloat() - 0.5f) * 1.2f), out, 0.1);
                float[] middle = add(add(base, out, Math.cos(droop) * l * 0.5), up, Math.sin(droop) * l * 0.5);
                float[] tip = add(add(middle, out, Math.cos(tipDroop) * l * 0.5), up, Math.sin(tipDroop) * l * 0.5);
                float[] centre = centreAt(path, y);
                ribbon(leaves, new float[][]{base, middle, tip}, side, l * 0.36f, up, -l * 0.11f, cell(new int[]{0, 1, 3}[random.nextInt(3)]),
                        p -> unit(v((p[0] - centre[0]) * 0.55, Math.abs(p[1] - centre[1]) * 0.2 + 3.2, (p[2] - centre[2]) * 0.55)));
            }
            y += (2.0f + random.nextFloat() * 0.9f) * (1 - 0.35f * t) * (height / 70f + 0.45f);
        }
        // the pointed top: three crossed upright sprays
        float[] top = centreAt(path, height * 0.86f);
        for (int i = 0; i < 3; i++) {
            double angle = Math.PI * i / 3 + random.nextDouble() * 0.4;
            float[] side = v(Math.cos(angle), 0, Math.sin(angle));
            ribbon(leaves, new float[][]{top, add(top, up, height * 0.09), add(top, up, height * 0.18)}, side, height * 0.07f, up, 0, cell(2), p -> unit(v(p[0] - top[0], 2.5, p[2] - top[2])));
        }
        writeTree(name, trunkMesh, leaves);
    }

    /** Scots-pine shape: a long bare trunk, upswept limbs near the top carrying tufted needle clusters. */
    static void pine(String name, long seed, float height) throws IOException {
        Random random = new Random(seed);
        Mesh trunkMesh = new Mesh(), leaves = new Mesh();
        float[][] path = trunk(trunkMesh, random, height, height * 0.016f + 0.3f, 0.22f, height * 0.05f);
        float[] crown = centreAt(path, height * 0.8f), up = v(0, 1, 0);
        java.util.function.Function<float[], float[]> normal = p -> unit(v(p[0] - crown[0], (p[1] - crown[1]) * 0.6 + height * 0.1, p[2] - crown[2]));
        float cluster = height * 0.115f;

        int limbs = 14 + random.nextInt(4);
        for (int i = 0; i < limbs; i++) {
            float t = i / (limbs - 1f), y = height * (0.5f + 0.44f * t);
            double angle = i * 2.4 + random.nextDouble() * 0.8, rise = Math.toRadians(12 + 30 * t + random.nextDouble() * 14);
            float length = height * (0.24f - 0.13f * t) * (0.75f + random.nextFloat() * 0.5f);
            float[] out = v(Math.cos(angle) * Math.cos(rise), Math.sin(rise), Math.sin(angle) * Math.cos(rise)), start = centreAt(path, y);
            float[] mid = add(add(start, out, length * 0.5), up, -length * 0.05), end = add(add(start, out, length), up, length * 0.08);
            float radius = (0.22f + (1 - t) * 0.35f) * height / 70f;
            tube(trunkMesh, new float[][]{start, mid, end}, new float[]{radius, radius * 0.6f, radius * 0.2f}, 5, 1, 0.25f);
            int clusters = 7 + random.nextInt(3);
            for (int c = 0; c < clusters; c++) {
                float s = 0.3f + 0.7f * c / (clusters - 1f);
                float[] on = s < 0.5f ? add(start, v(mid[0] - start[0], mid[1] - start[1], mid[2] - start[2]), s * 2) : add(mid, v(end[0] - mid[0], end[1] - mid[1], end[2] - mid[2]), (s - 0.5f) * 2);
                float[] axis = unit(v(out[0] + (random.nextDouble() - 0.5) * 1.6, out[1] * 0.4 + 0.2 + random.nextDouble() * 0.9, out[2] + (random.nextDouble() - 0.5) * 1.6));
                crossedCards(leaves, random, add(on, axis, -cluster * 0.25), axis, cluster * (0.85f + random.nextFloat() * 0.4f), cluster * 0.95f, random.nextInt(4), normal);
            }
        }
        float[] top = centreAt(path, height * 0.93f);
        for (int i = 0; i < 9; i++) {
            float[] axis = unit(v((random.nextDouble() - 0.5) * 1.9, 0.9, (random.nextDouble() - 0.5) * 1.9));
            crossedCards(leaves, random, top, axis, cluster * 1.1f, cluster, random.nextInt(4), normal);
        }
        writeTree(name, trunkMesh, leaves);
    }

    /** Broadleaf tree: a trunk that divides into limbs, with leaf sprays filling an egg-shaped crown. */
    static void broadleaf(String name, long seed, float height, float spread) throws IOException {
        Random random = new Random(seed);
        Mesh trunkMesh = new Mesh(), leaves = new Mesh();
        float[][] path = trunk(trunkMesh, random, height * 0.8f, height * 0.024f + 0.4f, 0.3f, height * 0.06f);
        float[] crown = v(path[path.length - 1][0] * 0.6, height * 0.66f, path[path.length - 1][2] * 0.6), up = v(0, 1, 0);
        float radius = height * 0.34f * spread, radiusY = height * 0.33f, spray = height * 0.2f;
        java.util.function.Function<float[], float[]> normal = p -> unit(v(p[0] - crown[0], (p[1] - crown[1]) * 0.8 + height * 0.06, p[2] - crown[2]));
        List<float[]> tips = new ArrayList<>();

        int limbs = 6 + random.nextInt(3);
        for (int i = 0; i < limbs; i++) {
            float t = i / (limbs - 1f), y = height * (0.3f + 0.4f * t);
            double angle = i * 2.4 + random.nextDouble(), rise = Math.toRadians(28 + 30 * t + random.nextDouble() * 12);
            float length = radius * (1.05f - 0.3f * t) * (0.8f + random.nextFloat() * 0.35f);
            float[] out = v(Math.cos(angle) * Math.cos(rise), Math.sin(rise), Math.sin(angle) * Math.cos(rise)), start = centreAt(path, y);
            float[] mid = add(start, out, length * 0.5), end = add(add(start, out, length), up, length * 0.15);
            float r = (0.75f - 0.4f * t) * height / 50f;
            tube(trunkMesh, new float[][]{start, mid, end}, new float[]{r, r * 0.6f, r * 0.18f}, 5, 1, 0.2f);
            tips.add(end);
            for (int s = 0; s < 2; s++) { // two side limbs
                double a2 = angle + (s == 0 ? -1 : 1) * (0.6 + random.nextDouble() * 0.5);
                float[] o2 = v(Math.cos(a2) * 0.8, 0.45 + random.nextDouble() * 0.4, Math.sin(a2) * 0.8), e2 = add(mid, o2, length * 0.55);
                tube(trunkMesh, new float[][]{mid, add(mid, o2, length * 0.28), e2}, new float[]{r * 0.5f, r * 0.3f, r * 0.1f}, 4, 1, 0.3f);
                tips.add(e2);
                tips.add(add(mid, o2, length * 0.3));
            }
        }
        for (float[] tip : tips)
            for (int k = 0; k < 3; k++) {
                float[] axis = unit(v(tip[0] - crown[0] + (random.nextDouble() - 0.5) * radius, (tip[1] - crown[1]) * 0.6 + (random.nextDouble() - 0.35) * radius * 0.8, tip[2] - crown[2] + (random.nextDouble() - 0.5) * radius));
                crossedCards(leaves, random, add(tip, axis, -spray * 0.3), axis, spray * (0.9f + random.nextFloat() * 0.35f), spray * 0.95f, random.nextInt(4), normal);
            }
        // fill the crown's outer shell so no bare gaps show
        for (int i = 0; i < 70; i++) {
            double a = random.nextDouble() * Math.PI * 2, e = Math.asin(random.nextDouble() * 1.7 - 0.7), shell = 0.62 + random.nextDouble() * 0.38;
            float[] direction = v(Math.cos(a) * Math.cos(e), Math.sin(e), Math.sin(a) * Math.cos(e));
            float[] at = v(crown[0] + direction[0] * radius * shell, crown[1] + direction[1] * radiusY * shell, crown[2] + direction[2] * radius * shell);
            float[] axis = unit(v(direction[0] + (random.nextDouble() - 0.5) * 0.9, direction[1] * 0.5 - 0.15 + (random.nextDouble() - 0.5) * 0.6, direction[2] + (random.nextDouble() - 0.5) * 0.9));
            crossedCards(leaves, random, add(at, axis, -spray * 0.45), axis, spray * (0.9f + random.nextFloat() * 0.3f), spray * 0.95f, random.nextInt(4), normal);
        }
        writeTree(name, trunkMesh, leaves);
    }

    /** Understory bush / sapling: a few thin stems with leaf sprays. */
    static void bush(String name, long seed, float height) throws IOException {
        Random random = new Random(seed);
        Mesh trunkMesh = new Mesh(), leaves = new Mesh();
        float[] crown = v(0, height * 0.55f, 0);
        java.util.function.Function<float[], float[]> normal = p -> unit(v(p[0] - crown[0], (p[1] - crown[1]) * 0.6 + height * 0.35, p[2] - crown[2]));
        float spray = height * 0.42f;
        for (int i = 0; i < 7; i++) {
            double angle = i * 0.9 + random.nextDouble() * 0.6, lean = 0.25 + random.nextDouble() * 0.5;
            float[] out = unit(v(Math.cos(angle) * lean, 1, Math.sin(angle) * lean)), start = v(Math.cos(angle) * 0.3, -1, Math.sin(angle) * 0.3);
            float length = height * (0.6f + random.nextFloat() * 0.35f);
            float[] mid = add(start, out, length * 0.5 + 1), end = add(start, out, length + 1);
            tube(trunkMesh, new float[][]{start, mid, end}, new float[]{0.2f, 0.13f, 0.05f}, 4, 1, 0.5f);
            for (int k = 0; k < 5; k++) {
                float[] at = add(start, out, (length + 1) * (0.3 + 0.7 * k / 4.0));
                float[] axis = unit(v(out[0] + (random.nextDouble() - 0.5) * 1.5, out[1] * 0.6 + random.nextDouble() * 0.4, out[2] + (random.nextDouble() - 0.5) * 1.5));
                crossedCards(leaves, random, add(at, axis, -spray * 0.2), axis, spray * (0.8f + random.nextFloat() * 0.4f), spray * 0.9f, random.nextInt(4), normal);
            }
        }
        writeTree(name, trunkMesh, leaves);
    }

    // =================================================================================================== grass

    /** Grass atlas (4x2 cells of 256px) and patch meshes of crossed tuft cards. */
    static void grass() throws IOException {
        BufferedImage source = rgba("maps/grass_medium_01_Diffuse.jpg", "maps/grass_medium_01_Alpha.jpg");
        int[][] tufts = {{200, 762, 508, 902}, {592, 768, 838, 880}, {25, 888, 186, 1010}, {240, 912, 510, 1014}, {580, 884, 805, 1014}};
        BufferedImage dandelions = rgba("maps/dandelion_01_Diffuse.jpg", "maps/dandelion_01_Alpha.jpg");
        Sprite flower = sprite(dandelions, 700, 140, 1, 'b');
        Random random = new Random(21);

        BufferedImage atlas = new BufferedImage(1024, 512, BufferedImage.TYPE_INT_ARGB);
        for (int cellIndex = 0; cellIndex < 8; cellIndex++) {
            int[] r = tufts[cellIndex % tufts.length];
            Graphics2D g = canvas(atlas);
            g.translate((cellIndex % 4) * 256, (cellIndex / 4) * 256);
            g.setClip(0, 0, 256, 256);
            int w = r[2] - r[0], h = r[3] - r[1];
            double scale = Math.min(248.0 / w, 250.0 / h);
            int dw = (int) (w * scale), dh = (int) (h * scale);
            boolean mirror = cellIndex >= 5;
            g.drawImage(source.getSubimage(r[0], r[1], w, h), mirror ? (256 + dw) / 2 : (256 - dw) / 2, 256 - dh, mirror ? -dw : dw, dh, null);
            if (cellIndex >= 6) // meadow flowers: a few dandelion heads above the blades
                for (int i = 0; i < 3; i++) {
                    g.setColor(new Color(96, 120, 52));
                    g.setStroke(new BasicStroke(2.2f));
                    int fx = 60 + random.nextInt(136), fy = 256 - dh + 10 + random.nextInt(50);
                    g.drawLine(fx, 250, fx, fy);
                    stamp(g, flower, fx, fy + 16, Math.toRadians(-90), 34, false, 1.1f);
                }
            g.dispose();
        }
        for (int y = 0; y < 512; y++)
            for (int x = 0; x < 1024; x++) {
                int p = atlas.getRGB(x, y), row = y % 256;
                int alpha = row > 244 ? 0 : (p >>> 24); // the lowest rows hold streaks from the scan, not grass
                int r = Math.min(255, ((p >> 16) & 255) * 114 / 100), gr = Math.min(255, ((p >> 8) & 255) * 127 / 100), b = Math.min(255, (p & 255) * 110 / 100);
                atlas.setRGB(x, y, (alpha << 24) | (r << 16) | (gr << 8) | b);
            }
        finishCutout(atlas, 1.6f);
        save(atlas, "grass");

        grassPatch("grass_patch_a", 31, 6.5f, 26, 0, 6, 1.0f);
        grassPatch("grass_patch_b", 32, 6.5f, 22, 0, 6, 1.25f);
        grassPatch("grass_flowers", 33, 6.5f, 24, 0, 8, 1.05f);
        grassPatch("grass_clump", 34, 1.6f, 5, 0, 6, 1.35f);
        grassPatch("sedge", 35, 2.2f, 7, 2, 5, 2.5f);
    }

    /** Tufts scattered in a disc; each is two or three cards crossed around the vertical. Cells firstCell..lastCell-1 of the grass atlas. */
    static void grassPatch(String name, long seed, float radius, int count, int firstCell, int lastCell, float size) throws IOException {
        Random random = new Random(seed);
        Mesh mesh = new Mesh();
        float[] up = v(0, 1, 0);
        for (int i = 0; i < count; i++) {
            double a = random.nextDouble() * Math.PI * 2, d = Math.sqrt(random.nextDouble()) * radius;
            float[] base = v(Math.cos(a) * d, -0.12, Math.sin(a) * d);
            int cellIndex = firstCell + random.nextInt(lastCell - firstCell);
            float u0 = (cellIndex % 4) * 0.25f + 0.003f, top = (cellIndex / 4) * 0.5f + 0.004f;
            float[] uv = {u0, 1 - (top + 0.492f), u0 + 0.244f, 1 - top};
            float width = (1.9f + random.nextFloat() * 1.2f) * size, height = width * (0.8f + random.nextFloat() * 0.35f);
            double rotation = random.nextDouble() * Math.PI;
            int cards = random.nextInt(3) == 0 ? 3 : 2;
            for (int c = 0; c < cards; c++) {
                double angle = rotation + Math.PI * c / cards;
                float[] side = v(Math.cos(angle), 0, Math.sin(angle));
                ribbon(mesh, new float[][]{base, add(base, up, height * 0.5), add(base, up, height)}, side, width / 2, up, 0, uv, p -> v(0, 1, 0));
            }
        }
        mesh.write(MODELS.resolve(name + ".obj"), name + " - generated by tools/BuildForestAssets");
        report.add(String.format(Locale.ROOT, "%-16s tris=%5d", name, mesh.triangleCount()));
    }

    // =================================================================================================== converted models

    static void plantsAndRocks() throws IOException {
        cutoutTexture("fern_02", "fern", 1.15f);
        variant("fern_02", "fern_02_b", "fern_a", K, false);
        variant("fern_02", "fern_02_c", "fern_b", K, false);
        variant("fern_02", "fern_02_a", "fern_c", K, false);
        cutoutTexture("shrub_03", "herb", 1.15f);
        variant("shrub_03", "shrub_03_a", "herb_a", K * 2.2f, false);
        variant("shrub_03", "shrub_03_b", "herb_b", K * 2.2f, false);

        textured("rock_moss_set_01", 0, "mossrock", 1024);
        variant("rock_moss_set_01", "rock_moss_set_01_rock01", "mossrock_a", K, true);
        variant("rock_moss_set_01", "rock_moss_set_01_rock03", "mossrock_b", K, true);
        variant("rock_moss_set_01", "rock_moss_set_01_rock04", "mossrock_c", K, true);
        variant("rock_moss_set_01", "rock_moss_set_01_rock06", "mossrock_d", K, true);

        textured("dead_quiver_trunk", 0, "log", 512);
        ImageIO.write(shaded(ImageIO.read(TEXTURES.resolve("log.png").toFile()), 0.62f), "png", TEXTURES.resolve("log.png").toFile()); // weathered grey, not chalk white
        Mesh log = GltfReader.load(gltf("dead_quiver_trunk")).rotZ(90).scale(K * 2.3f).centerOnGround();
        restOnUnderside(log);
        write(log, "log");

        textured("dry_branches_medium_01", 0, "firewood", 512);
        Mesh stick = GltfReader.load(gltf("dry_branches_medium_01"), n -> n.endsWith("_b"), false, (m, u, vv) -> new float[]{u, vv}).scale(K * 1.1f).centerOnGround();
        Mesh pile = new Mesh();
        Random random = new Random(5);
        for (int i = 0; i < 6; i++)
            pile.add(stick.copy().rotY(random.nextFloat() * 50 - 25 + (i % 2) * 90).translate((random.nextFloat() - 0.5f) * 1.2f, (i / 2) * 0.42f, (random.nextFloat() - 0.5f) * 1.2f));
        write(pile.centerOnGround(), "firewood");
    }

    /**
     * A trunk lying on its side touches the model's lowest point only with a knot or a flared end, leaving the
     * rest in the air. This lowers it until most of its underside is at y = 0 (the knots then dig into the ground).
     */
    static void restOnUnderside(Mesh mesh) {
        float[] b = mesh.bounds();
        int slices = Math.max(1, (int) Math.ceil(b[3] - b[0]));
        float[] lowest = new float[slices];
        java.util.Arrays.fill(lowest, Float.MAX_VALUE);
        for (float[] c : mesh.corners) {
            int slice = Math.min(slices - 1, (int) (c[0] - b[0]));
            lowest[slice] = Math.min(lowest[slice], c[1]);
        }
        java.util.Arrays.sort(lowest);
        mesh.translate(0, -lowest[(int) (slices * 0.7f)], 0);
    }

    static void props() throws IOException {
        for (String[] p : new String[][]{{"wooden_axe", "axe"}, {"folding_wooden_stool", "stool"}, {"brass_pot_01", "pot"}, {"wooden_bucket_01", "bucket"}}) {
            textured(p[0], 0, p[1], 512);
            write(GltfReader.load(gltf(p[0])).scale(K).centerOnGround(), p[1]);
        }
        atlasModel("wooden_picnic_table", "picnic_table", n -> true, null);
        atlasModel("modular_wooden_pier", "pier", n -> n.contains("section_01") || n.contains("section_02") || n.contains("section_03"), null);
        // the lantern's glass panes become a warm, bright colour: with an emissive material they glow and bloom
        atlasModel("wooden_lantern_01", "lantern", n -> true, new Color(255, 196, 96));
    }

    static Path gltf(String id) {
        return SRC.resolve("models/" + id + "/" + id + ".gltf");
    }

    static void write(Mesh mesh, String name) throws IOException {
        mesh.write(MODELS.resolve(name + ".obj"), name + " - Poly Haven scan (CC0), converted by tools/BuildForestAssets");
        float[] b = mesh.bounds();
        report.add(String.format(Locale.ROOT, "%-16s tris=%5d  x[%6.2f,%6.2f] y[%5.2f,%6.2f] z[%6.2f,%6.2f]", name, mesh.triangleCount(), b[0], b[3], b[1], b[4], b[2], b[5]));
    }

    /** One variant (node) of a multi-variant pack, moved to its own origin and scaled. */
    static void variant(String id, String node, String name, float scale, boolean sinkSlightly) throws IOException {
        Mesh mesh = GltfReader.load(gltf(id), n -> n.equals(node), false, (m, u, vv) -> new float[]{u, vv}).scale(scale).centerOnGround();
        if (sinkSlightly) mesh.translate(0, -0.03f * (mesh.bounds()[4]), 0);
        write(mesh, name);
    }

    /** Colour texture + normal map (converted to the DirectX convention the shaders expect) of one material. */
    static void textured(String id, int material, String name, int size) throws IOException {
        String[] files = GltfReader.textures(gltf(id), material);
        ImageIO.write(resize(ImageIO.read(gltf(id).resolveSibling(files[0]).toFile()), size, size, false), "png", TEXTURES.resolve(name + ".png").toFile());
        ImageIO.write(flipGreen(resize(ImageIO.read(gltf(id).resolveSibling(files[1]).toFile()), size, size, false)), "png", TEXTURES.resolve(name + "_normal.png").toFile());
    }

    static BufferedImage flipGreen(BufferedImage normal) {
        for (int y = 0; y < normal.getHeight(); y++)
            for (int x = 0; x < normal.getWidth(); x++) {
                int rgb = normal.getRGB(x, y);
                normal.setRGB(x, y, (rgb & 0xff00ff) | ((255 - ((rgb >> 8) & 255)) << 8));
            }
        return normal;
    }

    /** Leaf textures ship as colour + separate alpha map; the engine wants one RGBA image. */
    static void cutoutTexture(String id, String name, float alphaGain) throws IOException {
        BufferedImage image = rgba("maps/" + id + "_Diffuse.jpg", "maps/" + id + "_Alpha.jpg");
        finishCutout(image, alphaGain);
        save(image, name);
    }

    /** A model with two materials: both textures side by side in one image, UVs squeezed into their half. */
    static void atlasModel(String id, String name, java.util.function.Predicate<String> nodes, Color secondMaterialColour) throws IOException {
        Mesh mesh = GltfReader.load(gltf(id), nodes, true, (material, u, vv) -> new float[]{(Math.min(material, 1) + (u - (float) Math.floor(u)) * 0.998f + 0.001f) / 2, vv - (float) Math.floor(vv)});
        write(mesh.scale(K).centerOnGround(), name);
        BufferedImage colour = new BufferedImage(1024, 512, BufferedImage.TYPE_INT_RGB), normal = new BufferedImage(1024, 512, BufferedImage.TYPE_INT_RGB);
        for (int m = 0; m < 2; m++) {
            String[] files = GltfReader.textures(gltf(id), m);
            Graphics2D g = colour.createGraphics();
            if (m == 1 && secondMaterialColour != null) {
                g.setColor(secondMaterialColour);
                g.fillRect(512, 0, 512, 512);
            } else g.drawImage(resize(ImageIO.read(gltf(id).resolveSibling(files[0]).toFile()), 512, 512, false), m * 512, 0, null);
            g.dispose();
            Graphics2D gn = normal.createGraphics();
            gn.drawImage(flipGreen(resize(ImageIO.read(gltf(id).resolveSibling(files[1]).toFile()), 512, 512, false)), m * 512, 0, null);
            gn.dispose();
        }
        if (secondMaterialColour != null) // dim the first material: the emissive term multiplies the whole texture
            for (int y = 0; y < 512; y++)
                for (int x = 0; x < 512; x++) {
                    int rgb = colour.getRGB(x, y);
                    colour.setRGB(x, y, (((rgb >> 16) & 255) * 5 / 10) << 16 | (((rgb >> 8) & 255) * 5 / 10) << 8 | ((rgb & 255) * 5 / 10));
                }
        ImageIO.write(colour, "png", TEXTURES.resolve(name + ".png").toFile());
        ImageIO.write(normal, "png", TEXTURES.resolve(name + "_normal.png").toFile());
    }

    // =================================================================================================== particles

    interface Frame {
        /** Draws frame t (0..1 through the particle's life) into a 128px cell whose origin is already set. */
        void draw(Graphics2D g, float t) throws IOException;
    }

    static void particles() throws IOException {
        BufferedImage dot = ImageIO.read(KENNEY_PARTICLES.resolve("circle_05.png").toFile()), puff = ImageIO.read(KENNEY_PARTICLES.resolve("smoke_04.png").toFile());

        // embers: hot yellow-white sparks cooling to red while they shrink
        particleAtlas("ember", (g, t) -> {
            float size = 46 * (1 - 0.6f * t);
            drawTinted(g, dot, 64, 64, size, new Color(1f, 0.85f - 0.55f * t, 0.45f - 0.4f * t), fade(t, 0.05f, 0.5f));
        });
        // dust motes / pollen in the sunlight: tiny soft dots that glimmer
        particleAtlas("mote", (g, t) -> drawTinted(g, dot, 64, 64, 30, new Color(1f, 0.95f, 0.78f), fade(t, 0.2f, 0.75f) * (0.45f + 0.55f * (float) Math.pow(Math.sin(t * Math.PI * 4), 2))));
        // mist: big faint puffs that swell and thin out
        particleAtlas("mist", (g, t) -> drawTinted(g, dot, 64, 64, 118, new Color(0.93f, 0.95f, 1f), fade(t, 0.4f, 0.6f) * 0.085f)); // a soft featureless veil, not puffs
        // falling leaves: a leaf tumbling (turning and foreshortening) as it drops
        particleAtlas("leaf", (g, t) -> {
            Sprite leaf = leafSprites[1];
            AffineTransform old = g.getTransform();
            g.translate(64, 64);
            g.rotate(t * Math.PI * 5);
            g.scale(1, 0.25 + 0.75 * Math.abs(Math.cos(t * Math.PI * 7)));
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fade(t, 0.04f, 0.9f)));
            double scale = 70.0 / leaf.image.getHeight();
            g.drawImage(shaded(leaf.image, 1.1f), (int) (-leaf.image.getWidth() * scale / 2), -35, (int) (leaf.image.getWidth() * scale), 70, null);
            g.setTransform(old);
        });
    }

    static float fade(float t, float in, float out) {
        return t < in ? t / in : t > out ? Math.max(0, (1 - t) / (1 - out)) : 1;
    }

    static void drawTinted(Graphics2D g, BufferedImage sprite, float cx, float cy, float size, Color colour, float alpha) {
        BufferedImage tinted = new BufferedImage(sprite.getWidth(), sprite.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D t = tinted.createGraphics();
        t.drawImage(sprite, 0, 0, null);
        t.setComposite(AlphaComposite.SrcIn);
        t.setColor(colour);
        t.fillRect(0, 0, tinted.getWidth(), tinted.getHeight());
        t.dispose();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, Math.min(1, alpha))));
        g.drawImage(tinted, Math.round(cx - size / 2), Math.round(cy - size / 2), Math.round(size), Math.round(size), null);
    }

    /** 4x4 atlas of 128px frames; the engine blends through them over a particle's life. */
    static void particleAtlas(String name, Frame frame) throws IOException {
        BufferedImage atlas = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 16; i++) {
            Graphics2D g = canvas(atlas);
            g.translate((i % 4) * 128, (i / 4) * 128);
            g.setClip(2, 2, 124, 124);
            frame.draw(g, i / 15f);
            g.dispose();
        }
        ImageIO.write(atlas, "png", PARTICLES.resolve(name + ".png").toFile());
    }
}
