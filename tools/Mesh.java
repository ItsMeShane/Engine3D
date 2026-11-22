import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal triangle mesh used by the asset tools: loads any OBJ (quads/n-gons, several objects and
 * materials, missing normals/UVs), can be transformed and merged, and is written back in the strict
 * layout the engine's OBJFileLoader needs (all v/vt/vn first, then one block of v/vt/vn triangles).
 */
public class Mesh {

    /** One corner of a triangle: position (0-2), uv (3-4), normal (5-7). */
    public final List<float[]> corners = new ArrayList<>();

    /** Resolves the final atlas UV for a corner of a face that uses the given material. */
    public interface MaterialMapper {
        float[] map(Material material, float u, float v);
    }

    public static class Material {
        public String name;
        public float[] kd = {1, 1, 1};
        public String texture; // map_Kd, null for plain colours
    }

    public static Map<String, Material> loadMtl(Path mtl) throws IOException {
        Map<String, Material> materials = new LinkedHashMap<>();
        if (!Files.exists(mtl)) return materials;
        Material current = null;
        for (String raw : Files.readAllLines(mtl, StandardCharsets.ISO_8859_1)) {
            String line = raw.trim();
            String[] p = line.split("\\s+");
            if (p[0].equals("newmtl")) {
                current = new Material();
                current.name = p[1];
                materials.put(current.name, current);
            } else if (current != null && p[0].equals("Kd")) {
                current.kd = new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])};
            } else if (current != null && p[0].equals("map_Kd")) {
                current.texture = line.substring(6).trim();
            }
        }
        return materials;
    }

    public static Mesh load(Path obj, MaterialMapper mapper) throws IOException {
        return load(obj, mapper, group -> true);
    }

    /** @param groups only faces whose OBJ group ("g name") passes this filter are loaded */
    public static Mesh load(Path obj, MaterialMapper mapper, java.util.function.Predicate<String> groups) throws IOException {
        List<float[]> v = new ArrayList<>(), vt = new ArrayList<>(), vn = new ArrayList<>();
        Map<String, Material> materials = new HashMap<>();
        Material material = new Material();
        material.name = "_none";
        Mesh mesh = new Mesh();
        boolean groupWanted = true;

        for (String raw : Files.readAllLines(obj, StandardCharsets.ISO_8859_1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] p = line.split("\\s+");
            switch (p[0]) {
                case "mtllib" -> materials.putAll(loadMtl(obj.resolveSibling(line.substring(7).trim())));
                case "g", "o" -> groupWanted = groups.test(p.length > 1 ? p[1] : "");
                case "usemtl" -> {
                    Material m = materials.get(p[1]);
                    if (m == null) {
                        m = new Material();
                        m.name = p[1];
                    }
                    material = m;
                }
                case "v" -> v.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                case "vt" -> vt.add(new float[]{Float.parseFloat(p[1]), p.length > 2 ? Float.parseFloat(p[2]) : 0});
                case "vn" -> vn.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                case "f" -> {
                    if (!groupWanted) continue;
                    int n = p.length - 1;
                    float[][] poly = new float[n][];
                    boolean hasNormals = true;
                    for (int i = 0; i < n; i++) {
                        String[] idx = p[i + 1].split("/");
                        float[] pos = v.get(index(idx[0], v.size()));
                        float[] uv = idx.length > 1 && !idx[1].isEmpty() ? vt.get(index(idx[1], vt.size())) : new float[]{0, 0};
                        float[] nor = idx.length > 2 && !idx[2].isEmpty() ? vn.get(index(idx[2], vn.size())) : null;
                        if (nor == null) hasNormals = false;
                        float[] mapped = mapper.map(material, uv[0], uv[1]);
                        poly[i] = new float[]{pos[0], pos[1], pos[2], mapped[0], mapped[1],
                                nor == null ? 0 : nor[0], nor == null ? 0 : nor[1], nor == null ? 0 : nor[2]};
                    }
                    for (int i = 1; i + 1 < n; i++) { // fan triangulation
                        float[] a = poly[0].clone(), b = poly[i].clone(), c = poly[i + 1].clone();
                        if (!hasNormals) setFaceNormal(a, b, c);
                        mesh.corners.add(a);
                        mesh.corners.add(b);
                        mesh.corners.add(c);
                    }
                }
                default -> {
                }
            }
        }
        return mesh;
    }

    private static int index(String token, int size) {
        int i = Integer.parseInt(token);
        return i < 0 ? size + i : i - 1;
    }

    private static void setFaceNormal(float[] a, float[] b, float[] c) {
        float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-12f) {
            nx = 0;
            ny = 1;
            nz = 0;
            len = 1;
        }
        for (float[] corner : new float[][]{a, b, c}) {
            corner[5] = nx / len;
            corner[6] = ny / len;
            corner[7] = nz / len;
        }
    }

    // ------------------------------------------------------------------------------------------ transforms
    // Rotations use the same convention as the engine's Maths.createTransformationMatrix (degrees).

    public Mesh scale(float s) {
        for (float[] c : corners) {
            c[0] *= s;
            c[1] *= s;
            c[2] *= s;
        }
        return this;
    }

    public Mesh translate(float x, float y, float z) {
        for (float[] c : corners) {
            c[0] += x;
            c[1] += y;
            c[2] += z;
        }
        return this;
    }

    public Mesh rotY(float degrees) {
        double r = Math.toRadians(degrees), cos = Math.cos(r), sin = Math.sin(r);
        for (float[] c : corners)
            for (int o = 0; o <= 5; o += 5) {
                float x = c[o], z = c[o + 2];
                c[o] = (float) (x * cos + z * sin);
                c[o + 2] = (float) (-x * sin + z * cos);
            }
        return this;
    }

    public Mesh rotX(float degrees) {
        double r = Math.toRadians(degrees), cos = Math.cos(r), sin = Math.sin(r);
        for (float[] c : corners)
            for (int o = 0; o <= 5; o += 5) {
                float y = c[o + 1], z = c[o + 2];
                c[o + 1] = (float) (y * cos - z * sin);
                c[o + 2] = (float) (y * sin + z * cos);
            }
        return this;
    }

    public Mesh rotZ(float degrees) {
        double r = Math.toRadians(degrees), cos = Math.cos(r), sin = Math.sin(r);
        for (float[] c : corners)
            for (int o = 0; o <= 5; o += 5) {
                float x = c[o], y = c[o + 1];
                c[o] = (float) (x * cos - y * sin);
                c[o + 1] = (float) (x * sin + y * cos);
            }
        return this;
    }

    public Mesh add(Mesh other) {
        for (float[] c : other.corners) corners.add(c.clone());
        return this;
    }

    public Mesh copy() {
        return new Mesh().add(this);
    }

    /** min x,y,z then max x,y,z */
    public float[] bounds() {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (float[] c : corners)
            for (int i = 0; i < 3; i++) {
                b[i] = Math.min(b[i], c[i]);
                b[i + 3] = Math.max(b[i + 3], c[i]);
            }
        return b;
    }

    /** Moves the mesh so it is centred on x/z and its lowest point sits at y = 0. */
    public Mesh centerOnGround() {
        float[] b = bounds();
        return translate(-(b[0] + b[3]) / 2, -b[1], -(b[2] + b[5]) / 2);
    }

    public int triangleCount() {
        return corners.size() / 3;
    }

    // ------------------------------------------------------------------------------------------ output

    public void write(Path file, String comment) throws IOException {
        Map<String, Integer> lookup = new HashMap<>();
        List<float[]> unique = new ArrayList<>();
        int[] indices = new int[corners.size()];
        for (int i = 0; i < corners.size(); i++) {
            float[] c = corners.get(i);
            String key = String.format(Locale.ROOT, "%.4f %.4f %.4f %.5f %.5f %.3f %.3f %.3f", c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]);
            Integer existing = lookup.get(key);
            if (existing == null) {
                existing = unique.size();
                lookup.put(key, existing);
                unique.add(c);
            }
            indices[i] = existing + 1;
        }
        Files.createDirectories(file.getParent());
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.US_ASCII))) {
            out.print("# " + comment + "\r\n");
            for (float[] c : unique) out.print(String.format(Locale.ROOT, "v %.4f %.4f %.4f\r\n", c[0], c[1], c[2]));
            for (float[] c : unique) out.print(String.format(Locale.ROOT, "vt %.5f %.5f\r\n", c[3], c[4]));
            for (float[] c : unique) out.print(String.format(Locale.ROOT, "vn %.3f %.3f %.3f\r\n", c[5], c[6], c[7]));
            for (int i = 0; i < indices.length; i += 3)
                out.print("f " + tri(indices[i]) + " " + tri(indices[i + 1]) + " " + tri(indices[i + 2]) + "\r\n");
        }
    }

    private static String tri(int i) {
        return i + "/" + i + "/" + i;
    }
}
