import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Converts the photo-scanned CC0 Poly Haven models in tools/asset_sources/polyhaven_models (glTF) into
 * engine-ready props with a colour texture and a normal map:
 *   res/models/demo/real/<name>.obj
 *   res/textures/demo/real/<name>.png, <name>_normal.png
 *
 * Normal maps are converted from the OpenGL convention glTF uses (green = up in the image) to the one the
 * engine's shaders expect (green = down, i.e. along +v), and every model is scaled to world units.
 *
 * Run from the project root:  java -cp out/tools BuildRealisticProps
 */
public class BuildRealisticProps {

    static final Path SOURCES = Path.of("tools/asset_sources/polyhaven_models");
    static final Path MODEL_OUT = Path.of("res/models/demo/real");
    static final Path TEXTURE_OUT = Path.of("res/textures/demo/real");

    /** @param size world-space length of the model's longest horizontal side */
    record Prop(String source, String name, float size, int textureSize) {
    }

    static final Prop[] PROPS = {
            new Prop("tree_stump_01", "stump", 11, 1024),
            new Prop("wine_barrel_01", "barrel", 5.6f, 512),
            new Prop("wooden_crate_02", "crate", 5.5f, 512),
            new Prop("stone_fire_pit", "fire_pit", 9.5f, 512),
    };

    public static void main(String[] args) throws IOException {
        Files.createDirectories(MODEL_OUT);
        Files.createDirectories(TEXTURE_OUT);
        for (Prop prop : PROPS) {
            Path gltf = SOURCES.resolve(prop.source).resolve(prop.source + ".gltf");
            Mesh mesh = GltfReader.load(gltf);
            float[] b = mesh.bounds();
            float longest = Math.max(b[3] - b[0], b[5] - b[2]);
            mesh.scale(prop.size / longest).centerOnGround();
            mesh.write(MODEL_OUT.resolve(prop.name + ".obj"), prop.name + " - Poly Haven '" + prop.source + "' (CC0), converted by tools/BuildRealisticProps");

            String[] textures = GltfReader.textures(gltf);
            BufferedImage colour = resize(ImageIO.read(gltf.resolveSibling(textures[0]).toFile()), prop.textureSize);
            ImageIO.write(colour, "png", TEXTURE_OUT.resolve(prop.name + ".png").toFile());
            BufferedImage normal = resize(ImageIO.read(gltf.resolveSibling(textures[1]).toFile()), prop.textureSize);
            for (int y = 0; y < normal.getHeight(); y++)
                for (int x = 0; x < normal.getWidth(); x++) {
                    int rgb = normal.getRGB(x, y);
                    normal.setRGB(x, y, (rgb & 0xff00ff) | ((255 - ((rgb >> 8) & 255)) << 8)); // flip green: OpenGL -> DirectX convention
                }
            ImageIO.write(normal, "png", TEXTURE_OUT.resolve(prop.name + "_normal.png").toFile());

            float[] s = mesh.bounds();
            System.out.println(String.format(Locale.ROOT, "%-10s tris=%6d  source %.2f x %.2f x %.2f m  ->  x[%6.2f,%6.2f] y[%5.2f,%6.2f] z[%6.2f,%6.2f]",
                    prop.name, mesh.triangleCount(), b[3] - b[0], b[4] - b[1], b[5] - b[2], s[0], s[3], s[1], s[4], s[2], s[5]));
        }
    }

    static BufferedImage resize(BufferedImage source, int size) {
        BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source.getScaledInstance(size, size, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
        g.dispose();
        return result;
    }
}
