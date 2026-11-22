import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the geometry of a glTF 2.0 file (.gltf + external .bin) into a {@link Mesh}: positions, normals and
 * the first UV set of every triangle primitive, with node transforms applied. Enough for static props such as
 * the Poly Haven models; no skins, animations, sparse accessors or embedded (base64 / .glb) buffers.
 *
 * glTF stores v downward, OBJ upward, so v is flipped here to keep {@link Mesh} in OBJ convention.
 */
public class GltfReader {

    private final Map<String, Object> root;
    private final List<ByteBuffer> buffers = new ArrayList<>();
    private final Mesh mesh = new Mesh();
    private final java.util.function.Predicate<String> nodeFilter;
    private final boolean applyNodeTransforms;
    private final MaterialUv materialUv;

    /** Maps a glTF texture coordinate (v downward) of a face with the given material index, e.g. into an atlas. */
    public interface MaterialUv {
        float[] map(int material, float u, float v);
    }

    public static Mesh load(Path gltf) throws IOException {
        return load(gltf, name -> true, true, (material, u, v) -> new float[]{u, v});
    }

    /**
     * @param nodeFilter          only meshes of nodes whose name passes are loaded (asset packs often hold several variants)
     * @param applyNodeTransforms false drops the nodes' placement, so each variant is centred on its own origin
     */
    public static Mesh load(Path gltf, java.util.function.Predicate<String> nodeFilter, boolean applyNodeTransforms, MaterialUv materialUv) throws IOException {
        return new GltfReader(gltf, nodeFilter, applyNodeTransforms, materialUv).mesh;
    }

    /** Relative paths of the base-colour and normal textures of one material: {colour, normal} (either may be null). */
    public static String[] textures(Path gltf, int materialIndex) throws IOException {
        Map<String, Object> root = object(new Json(Files.readString(gltf, StandardCharsets.UTF_8)).value());
        Map<String, Object> material = object(list(root.get("materials")).get(materialIndex));
        Map<String, Object> pbr = object(material.get("pbrMetallicRoughness"));
        return new String[]{imageUri(root, pbr == null ? null : pbr.get("baseColorTexture")), imageUri(root, material.get("normalTexture"))};
    }

    /** Relative paths of the base-colour and normal textures of the first material: {colour, normal} (either may be null). */
    public static String[] textures(Path gltf) throws IOException {
        Map<String, Object> root = object(new Json(Files.readString(gltf, StandardCharsets.UTF_8)).value());
        Map<String, Object> material = object(list(root.get("materials")).get(0));
        Map<String, Object> pbr = object(material.get("pbrMetallicRoughness"));
        return new String[]{imageUri(root, pbr == null ? null : pbr.get("baseColorTexture")), imageUri(root, material.get("normalTexture"))};
    }

    private static String imageUri(Map<String, Object> root, Object textureInfo) {
        if (textureInfo == null) return null;
        Map<String, Object> texture = object(list(root.get("textures")).get(integer(object(textureInfo).get("index"))));
        return (String) object(list(root.get("images")).get(integer(texture.get("source")))).get("uri");
    }

    private GltfReader(Path gltf, java.util.function.Predicate<String> nodeFilter, boolean applyNodeTransforms, MaterialUv materialUv) throws IOException {
        this.nodeFilter = nodeFilter;
        this.applyNodeTransforms = applyNodeTransforms;
        this.materialUv = materialUv;
        root = object(new Json(Files.readString(gltf, StandardCharsets.UTF_8)).value());
        for (Object buffer : list(root.get("buffers"))) {
            String uri = (String) object(buffer).get("uri");
            if (uri == null || uri.startsWith("data:")) throw new IOException("embedded buffers are not supported: " + gltf);
            buffers.add(ByteBuffer.wrap(Files.readAllBytes(gltf.resolveSibling(uri))).order(ByteOrder.LITTLE_ENDIAN));
        }
        int sceneIndex = root.containsKey("scene") ? integer(root.get("scene")) : 0;
        for (Object node : list(object(list(root.get("scenes")).get(sceneIndex)).get("nodes")))
            node(integer(node), identity());
    }

    // ------------------------------------------------------------------------------------------ scene graph

    private void node(int index, double[] parent) {
        Map<String, Object> node = object(list(root.get("nodes")).get(index));
        double[] world = applyNodeTransforms ? multiply(parent, local(node)) : parent;
        String name = node.containsKey("name") ? (String) node.get("name") : "";
        if (node.containsKey("mesh") && nodeFilter.test(name)) {
            for (Object primitive : list(object(list(root.get("meshes")).get(integer(node.get("mesh")))).get("primitives")))
                primitive(object(primitive), world);
        }
        if (node.containsKey("children")) for (Object child : list(node.get("children"))) node(integer(child), world);
    }

    private void primitive(Map<String, Object> primitive, double[] world) {
        if (primitive.containsKey("mode") && integer(primitive.get("mode")) != 4) return; // triangles only
        Map<String, Object> attributes = object(primitive.get("attributes"));
        float[] positions = floats(integer(attributes.get("POSITION")), 3);
        float[] normals = attributes.containsKey("NORMAL") ? floats(integer(attributes.get("NORMAL")), 3) : null;
        float[] uvs = attributes.containsKey("TEXCOORD_0") ? floats(integer(attributes.get("TEXCOORD_0")), 2) : null;
        int material = primitive.containsKey("material") ? integer(primitive.get("material")) : 0;
        int[] indices;
        if (primitive.containsKey("indices")) indices = indices(integer(primitive.get("indices")));
        else {
            indices = new int[positions.length / 3];
            for (int i = 0; i < indices.length; i++) indices[i] = i;
        }

        for (int i = 0; i + 2 < indices.length; i += 3) {
            float[][] triangle = new float[3][];
            for (int k = 0; k < 3; k++) {
                int v = indices[i + k];
                double x = positions[v * 3], y = positions[v * 3 + 1], z = positions[v * 3 + 2];
                float[] corner = new float[8];
                corner[0] = (float) (world[0] * x + world[4] * y + world[8] * z + world[12]);
                corner[1] = (float) (world[1] * x + world[5] * y + world[9] * z + world[13]);
                corner[2] = (float) (world[2] * x + world[6] * y + world[10] * z + world[14]);
                if (uvs != null) {
                    float[] mapped = materialUv.map(material, uvs[v * 2], uvs[v * 2 + 1]);
                    corner[3] = mapped[0];
                    corner[4] = 1 - mapped[1];
                }
                if (normals != null) { // rotation part only; fine for the uniform scales these models use
                    double nx = normals[v * 3], ny = normals[v * 3 + 1], nz = normals[v * 3 + 2];
                    double rx = world[0] * nx + world[4] * ny + world[8] * nz, ry = world[1] * nx + world[5] * ny + world[9] * nz, rz = world[2] * nx + world[6] * ny + world[10] * nz;
                    double length = Math.sqrt(rx * rx + ry * ry + rz * rz);
                    if (length > 1e-12) {
                        corner[5] = (float) (rx / length);
                        corner[6] = (float) (ry / length);
                        corner[7] = (float) (rz / length);
                    }
                }
                triangle[k] = corner;
            }
            if (normals == null) faceNormal(triangle);
            mesh.corners.add(triangle[0]);
            mesh.corners.add(triangle[1]);
            mesh.corners.add(triangle[2]);
        }
    }

    private static void faceNormal(float[][] t) {
        float ux = t[1][0] - t[0][0], uy = t[1][1] - t[0][1], uz = t[1][2] - t[0][2];
        float vx = t[2][0] - t[0][0], vy = t[2][1] - t[0][1], vz = t[2][2] - t[0][2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-12f) {
            ny = 1;
            length = 1;
        }
        for (float[] corner : t) {
            corner[5] = nx / length;
            corner[6] = ny / length;
            corner[7] = nz / length;
        }
    }

    // ------------------------------------------------------------------------------------------ accessors

    private float[] floats(int accessorIndex, int components) {
        Map<String, Object> accessor = object(list(root.get("accessors")).get(accessorIndex));
        if (integer(accessor.get("componentType")) != 5126) throw new IllegalStateException("only float vertex attributes are supported");
        int count = integer(accessor.get("count"));
        Map<String, Object> view = object(list(root.get("bufferViews")).get(integer(accessor.get("bufferView"))));
        ByteBuffer data = buffers.get(integer(view.get("buffer")));
        int start = offset(view) + offset(accessor);
        int stride = view.containsKey("byteStride") ? integer(view.get("byteStride")) : components * 4;
        float[] values = new float[count * components];
        for (int i = 0; i < count; i++)
            for (int c = 0; c < components; c++)
                values[i * components + c] = data.getFloat(start + i * stride + c * 4);
        return values;
    }

    private int[] indices(int accessorIndex) {
        Map<String, Object> accessor = object(list(root.get("accessors")).get(accessorIndex));
        int type = integer(accessor.get("componentType")), count = integer(accessor.get("count"));
        Map<String, Object> view = object(list(root.get("bufferViews")).get(integer(accessor.get("bufferView"))));
        ByteBuffer data = buffers.get(integer(view.get("buffer")));
        int start = offset(view) + offset(accessor);
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = switch (type) {
                case 5121 -> data.get(start + i) & 0xff;
                case 5123 -> data.getShort(start + i * 2) & 0xffff;
                case 5125 -> data.getInt(start + i * 4);
                default -> throw new IllegalStateException("unsupported index type " + type);
            };
        }
        return values;
    }

    private static int offset(Map<String, Object> o) {
        return o.containsKey("byteOffset") ? integer(o.get("byteOffset")) : 0;
    }

    // ------------------------------------------------------------------------------------------ matrices (column-major 4x4)

    private static double[] identity() {
        return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static double[] local(Map<String, Object> node) {
        if (node.containsKey("matrix")) {
            List<Object> m = list(node.get("matrix"));
            double[] result = new double[16];
            for (int i = 0; i < 16; i++) result[i] = number(m.get(i));
            return result;
        }
        double[] t = vector(node.get("translation"), new double[]{0, 0, 0});
        double[] r = vector(node.get("rotation"), new double[]{0, 0, 0, 1});
        double[] s = vector(node.get("scale"), new double[]{1, 1, 1});
        double x = r[0], y = r[1], z = r[2], w = r[3];
        return new double[]{
                (1 - 2 * (y * y + z * z)) * s[0], (2 * (x * y + z * w)) * s[0], (2 * (x * z - y * w)) * s[0], 0,
                (2 * (x * y - z * w)) * s[1], (1 - 2 * (x * x + z * z)) * s[1], (2 * (y * z + x * w)) * s[1], 0,
                (2 * (x * z + y * w)) * s[2], (2 * (y * z - x * w)) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0,
                t[0], t[1], t[2], 1};
    }

    private static double[] multiply(double[] a, double[] b) {
        double[] result = new double[16];
        for (int column = 0; column < 4; column++)
            for (int row = 0; row < 4; row++) {
                double sum = 0;
                for (int k = 0; k < 4; k++) sum += a[k * 4 + row] * b[column * 4 + k];
                result[column * 4 + row] = sum;
            }
        return result;
    }

    private static double[] vector(Object value, double[] fallback) {
        if (value == null) return fallback;
        List<Object> list = list(value);
        double[] result = new double[list.size()];
        for (int i = 0; i < result.length; i++) result[i] = number(list.get(i));
        return result;
    }

    // ------------------------------------------------------------------------------------------ JSON

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static double number(Object value) {
        return (Double) value;
    }

    private static int integer(Object value) {
        return (int) Math.round((Double) value);
    }

    /** Minimal recursive-descent JSON parser: objects -> Map, arrays -> List, numbers -> Double. */
    private static class Json {
        private final String text;
        private int position;

        Json(String text) {
            this.text = text;
        }

        Object value() {
            skipSpace();
            char c = text.charAt(position);
            if (c == '{') {
                Map<String, Object> map = new LinkedHashMap<>();
                position++;
                skipSpace();
                if (text.charAt(position) == '}') {
                    position++;
                    return map;
                }
                while (true) {
                    skipSpace();
                    String key = string();
                    skipSpace();
                    position++; // ':'
                    map.put(key, value());
                    skipSpace();
                    if (text.charAt(position++) == '}') return map;
                }
            }
            if (c == '[') {
                List<Object> list = new ArrayList<>();
                position++;
                skipSpace();
                if (text.charAt(position) == ']') {
                    position++;
                    return list;
                }
                while (true) {
                    list.add(value());
                    skipSpace();
                    if (text.charAt(position++) == ']') return list;
                }
            }
            if (c == '"') return string();
            if (text.startsWith("true", position)) {
                position += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", position)) {
                position += 5;
                return Boolean.FALSE;
            }
            if (text.startsWith("null", position)) {
                position += 4;
                return null;
            }
            int start = position;
            while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) position++;
            return Double.parseDouble(text.substring(start, position));
        }

        private String string() {
            StringBuilder result = new StringBuilder();
            position++; // opening quote
            while (text.charAt(position) != '"') {
                char c = text.charAt(position++);
                if (c == '\\') {
                    char escaped = text.charAt(position++);
                    switch (escaped) {
                        case 'n' -> result.append('\n');
                        case 't' -> result.append('\t');
                        case 'r' -> result.append('\r');
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'u' -> {
                            result.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                            position += 4;
                        }
                        default -> result.append(escaped);
                    }
                } else result.append(c);
            }
            position++; // closing quote
            return result.toString();
        }

        private void skipSpace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++;
        }
    }
}
