import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds 4x4 particle atlases (the engine blends through the 16 cells over a particle's life) from the
 * single white sprites of the CC0 Kenney Particle Pack: each frame is a tinted, scaled, faded sprite.
 *
 * Run from the project root:  java -cp out/tools BuildParticleAtlases
 */
public class BuildParticleAtlases {

    static final Path SPRITES = Path.of("tools/asset_sources/kenney_particle-pack/png");
    static final Path OUT = Path.of("res/particles/demo");
    static final int ROWS = 4, CELL = 128, FRAMES = ROWS * ROWS;

    interface Frame {
        /** @param t 0..1 through the particle's life. Returns {sprite, r, g, b, alpha, scale}. */
        Object[] at(float t);
    }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUT);

        // flame: bright yellow core -> orange -> red -> a wisp of dark smoke
        build("flame", t -> {
            float[] c = gradient(t, new float[][]{{1f, 0.93f, 0.55f}, {1f, 0.66f, 0.18f}, {0.93f, 0.33f, 0.10f}, {0.35f, 0.27f, 0.25f}});
            String sprite = t < 0.4f ? "fire_01" : t < 0.75f ? "fire_02" : "smoke_07";
            return new Object[]{sprite, c[0], c[1], c[2], fade(t, 0.05f, 0.55f) * (t < 0.75f ? 1f : 0.5f), 0.75f + 0.45f * t};
        });

        // smoke: starts small and dark, grows, lightens and thins out
        build("smoke", t -> {
            float grey = 0.38f + 0.42f * t;
            return new Object[]{t < 0.5f ? "smoke_07" : "smoke_04", grey, grey, grey * 1.02f, fade(t, 0.15f, 0.35f) * 0.55f, 0.45f + 0.75f * t};
        });

        // fountain spray: soft white-blue droplets that shrink as they fall
        build("spray", t -> new Object[]{"circle_05", 0.84f + 0.1f * t, 0.94f, 1f, fade(t, 0.05f, 0.7f) * 0.85f, 0.95f - 0.4f * t});

        // shrine sparkle: violet-white stars that twinkle twice
        build("sparkle", t -> {
            float twinkle = 0.55f + 0.45f * (float) Math.abs(Math.sin(t * Math.PI * 2));
            return new Object[]{t < 0.5f ? "star_06" : "star_07", 0.86f, 0.72f, 1f, fade(t, 0.1f, 0.7f) * twinkle, 0.6f + 0.5f * twinkle};
        });

        // fireflies: warm yellow-green glow pulsing slowly
        build("firefly", t -> {
            float pulse = 0.35f + 0.65f * (float) Math.pow(Math.sin(t * Math.PI * 3), 2);
            return new Object[]{"star_05", 0.95f, 1f, 0.45f, fade(t, 0.1f, 0.8f) * pulse, 0.7f + 0.3f * pulse};
        });
    }

    /** 0 at both ends of life, 1 between fadeIn and fadeOut. */
    static float fade(float t, float fadeIn, float fadeOut) {
        if (t < fadeIn) return t / fadeIn;
        if (t > fadeOut) return Math.max(0, (1 - t) / (1 - fadeOut));
        return 1;
    }

    static float[] gradient(float t, float[][] stops) {
        float f = Math.min(0.9999f, Math.max(0, t)) * (stops.length - 1);
        int i = (int) f;
        float k = f - i;
        return new float[]{stops[i][0] + (stops[i + 1][0] - stops[i][0]) * k, stops[i][1] + (stops[i + 1][1] - stops[i][1]) * k, stops[i][2] + (stops[i + 1][2] - stops[i][2]) * k};
    }

    static void build(String name, Frame frame) throws IOException {
        BufferedImage atlas = new BufferedImage(ROWS * CELL, ROWS * CELL, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (int i = 0; i < FRAMES; i++) {
            Object[] f = frame.at(i / (float) (FRAMES - 1));
            BufferedImage sprite = tinted(ImageIO.read(SPRITES.resolve(f[0] + ".png").toFile()), (float) f[1], (float) f[2], (float) f[3]);
            float alpha = Math.max(0, Math.min(1, (float) f[4])), scale = (float) f[5];
            int size = Math.round((CELL - 8) * Math.min(1, scale)); // keep a transparent border so cells never bleed
            int x = (i % ROWS) * CELL + (CELL - size) / 2, y = (i / ROWS) * CELL + (CELL - size) / 2;
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g.drawImage(sprite, x, y, size, size, null);
        }
        ImageIO.write(atlas, "png", OUT.resolve(name + ".png").toFile());
        System.out.println("wrote " + OUT.resolve(name + ".png"));
    }

    /** The pack's sprites are white shapes with alpha: keep the alpha, replace the colour. */
    static BufferedImage tinted(BufferedImage sprite, float r, float g, float b) {
        BufferedImage out = new BufferedImage(sprite.getWidth(), sprite.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();
        g2.drawImage(sprite, 0, 0, null);
        g2.setComposite(AlphaComposite.SrcIn);
        g2.setColor(new Color(Math.min(1, r), Math.min(1, g), Math.min(1, b)));
        g2.fillRect(0, 0, out.getWidth(), out.getHeight());
        g2.dispose();
        return out;
    }
}
