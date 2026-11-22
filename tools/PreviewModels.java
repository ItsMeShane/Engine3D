import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Offline contact sheet of converted models (software rasteriser, no OpenGL window needed).
 * Red line = +x, blue line = +z, both starting at the model origin; handy for picking rotations.
 *
 *   java -cp out/tools PreviewModels <out.png> <texture.png> <cellSize> <columns> <model.obj>...
 */
public class PreviewModels {

    public static void main(String[] args) throws IOException {
        BufferedImage texture = ImageIO.read(new File(args[1]));
        int cell = Integer.parseInt(args[2]), columns = Integer.parseInt(args[3]);
        List<String> models = new ArrayList<>(Arrays.asList(args).subList(4, args.length));
        int rows = (models.size() + columns - 1) / columns;
        BufferedImage sheet = new BufferedImage(columns * cell, rows * cell, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        for (int i = 0; i < models.size(); i++) {
            BufferedImage image = render(Path.of(models.get(i)), texture, cell);
            int x = (i % columns) * cell, y = (i / columns) * cell;
            g.drawImage(image, x, y, null);
            g.setColor(Color.WHITE);
            g.drawString(new File(models.get(i)).getName().replace(".obj", ""), x + 5, y + 15);
        }
        ImageIO.write(sheet, "png", new File(args[0]));
    }

    static BufferedImage render(Path obj, BufferedImage texture, int size) throws IOException {
        List<float[]> v = new ArrayList<>(), vt = new ArrayList<>(), vn = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();
        for (String line : Files.readAllLines(obj)) {
            String[] p = line.trim().split("\\s+");
            switch (p[0]) {
                case "v" -> v.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                case "vt" -> vt.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2])});
                case "vn" -> vn.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                case "f" -> faces.add(new int[]{Integer.parseInt(p[1].split("/")[0]) - 1, Integer.parseInt(p[2].split("/")[0]) - 1, Integer.parseInt(p[3].split("/")[0]) - 1});
                default -> {
                }
            }
        }

        // camera: looking from +x/+z, 30 degrees above the horizon (orthographic)
        double az = Math.toRadians(35), el = Math.toRadians(28);
        double[] right = {Math.cos(az), 0, -Math.sin(az)};
        double[] forward = {-Math.sin(az) * Math.cos(el), -Math.sin(el), -Math.cos(az) * Math.cos(el)};
        double[] up = {-Math.sin(az) * Math.sin(el), Math.cos(el), -Math.cos(az) * Math.sin(el)};

        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        double[][] projected = new double[v.size()][3];
        for (int i = 0; i < v.size(); i++) {
            float[] p = v.get(i);
            projected[i][0] = p[0] * right[0] + p[1] * right[1] + p[2] * right[2];
            projected[i][1] = p[0] * up[0] + p[1] * up[1] + p[2] * up[2];
            projected[i][2] = p[0] * forward[0] + p[1] * forward[1] + p[2] * forward[2];
            minX = Math.min(minX, projected[i][0]);
            maxX = Math.max(maxX, projected[i][0]);
            minY = Math.min(minY, projected[i][1]);
            maxY = Math.max(maxY, projected[i][1]);
        }
        double scale = (size - 40) / Math.max(maxX - minX, maxY - minY);
        double offX = size / 2.0 - (minX + maxX) / 2 * scale, offY = size / 2.0 + 8 + (minY + maxY) / 2 * scale;

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        float[] depth = new float[size * size];
        Arrays.fill(depth, Float.MAX_VALUE);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) image.setRGB(x, y, ((x / 20 + y / 20) % 2 == 0) ? 0x4a4f5c : 0x444956);

        double[] light = normalize(new double[]{0.5, 0.8, 0.35});
        for (int[] f : faces) {
            double[] a = projected[f[0]], b = projected[f[1]], c = projected[f[2]];
            double ax = a[0] * scale + offX, ay = offY - a[1] * scale, bx = b[0] * scale + offX, by = offY - b[1] * scale, cx = c[0] * scale + offX, cy = offY - c[1] * scale;
            double area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
            if (Math.abs(area) < 1e-9) continue;
            int x0 = (int) Math.max(0, Math.floor(Math.min(ax, Math.min(bx, cx)))), x1 = (int) Math.min(size - 1, Math.ceil(Math.max(ax, Math.max(bx, cx))));
            int y0 = (int) Math.max(0, Math.floor(Math.min(ay, Math.min(by, cy)))), y1 = (int) Math.min(size - 1, Math.ceil(Math.max(ay, Math.max(by, cy))));
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++) {
                    double px = x + 0.5, py = y + 0.5;
                    double w0 = ((bx - px) * (cy - py) - (cx - px) * (by - py)) / area;
                    double w1 = ((cx - px) * (ay - py) - (ax - px) * (cy - py)) / area;
                    double w2 = 1 - w0 - w1;
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                    float z = (float) (w0 * a[2] + w1 * b[2] + w2 * c[2]);
                    if (z >= depth[y * size + x]) continue;
                    float[] ta = vt.get(f[0]), tb = vt.get(f[1]), tc = vt.get(f[2]);
                    double u = w0 * ta[0] + w1 * tb[0] + w2 * tc[0], tv = w0 * ta[1] + w1 * tb[1] + w2 * tc[1];
                    int tx = Math.floorMod((int) Math.floor(u * texture.getWidth()), texture.getWidth());
                    int ty = Math.floorMod((int) Math.floor((1 - tv) * texture.getHeight()), texture.getHeight());
                    int rgb = texture.getRGB(tx, ty);
                    if ((rgb >>> 24) < 128) continue; // alpha cut-out
                    depth[y * size + x] = z;
                    float[] na = vn.get(f[0]);
                    double shade = 0.45 + 0.6 * Math.max(0, na[0] * light[0] + na[1] * light[1] + na[2] * light[2]);
                    int r = (int) Math.min(255, ((rgb >> 16) & 255) * shade), gg = (int) Math.min(255, ((rgb >> 8) & 255) * shade), bl = (int) Math.min(255, (rgb & 255) * shade);
                    image.setRGB(x, y, (r << 16) | (gg << 8) | bl);
                }
        }

        // axes from the origin: +x red, +z blue
        Graphics2D g = image.createGraphics();
        double len = Math.max(maxX - minX, maxY - minY) * 0.45;
        int ox = (int) offX, oy = (int) offY;
        g.setColor(Color.RED);
        g.drawLine(ox, oy, (int) (right[0] * len * scale + offX), (int) (offY - up[0] * len * scale));
        g.setColor(new Color(80, 140, 255));
        g.drawLine(ox, oy, (int) (right[2] * len * scale + offX), (int) (offY - up[2] * len * scale));
        return image;
    }

    static double[] normalize(double[] d) {
        double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        return new double[]{d[0] / len, d[1] / len, d[2] / len};
    }
}
