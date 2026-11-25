package renderEngine;

import entities.Entity;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector3f;
import org.lwjgl.util.vector.Vector4f;
import textures.ModelTexture;

/**
 * The space a view can see, as planes, for skipping what lies completely outside it (culling): an object left
 * out costs neither a draw call nor any vertex work. Built from a camera's projection and view (a box for the
 * orthographic shadow view), optionally with the clip plane of a water pass as one more plane.
 * Objects are tested as spheres, so the test is cheap and never removes anything that would have shown.
 */
public final class Frustum {

    private final float[][] planes = new float[7][4]; // a, b, c, d with (a, b, c) of unit length: inside where ax + by + cz + d >= 0
    private int planeCount;
    private final Matrix4f viewProjection = new Matrix4f();

    /** The view of {@code projection * view}, without a clip plane. */
    public void set(Matrix4f projection, Matrix4f view) {
        set(projection, view, null);
    }

    /**
     * @param clipPlane the world-space plane the scene shaders clip against (gl_ClipDistance keeps what is on its
     *                  positive side); null or (0, 0, 0, 0) for none
     */
    public void set(Matrix4f projection, Matrix4f view, Vector4f clipPlane) {
        Matrix4f.mul(projection, view, viewProjection);
        set(viewProjection);
        if (clipPlane != null && (clipPlane.x != 0 || clipPlane.y != 0 || clipPlane.z != 0)) {
            setPlane(planeCount++, clipPlane.x, clipPlane.y, clipPlane.z, clipPlane.w);
        }
    }

    /** The view of a matrix taking world positions to clip space (perspective or orthographic). */
    public void set(Matrix4f m) {
        // Gribb & Hartmann: each side of the clip-space cube (-w <= x, y, z <= w) is the last row of the matrix
        // plus or minus one of the others. LWJGL names elements m<column><row>.
        setPlane(0, m.m03 + m.m00, m.m13 + m.m10, m.m23 + m.m20, m.m33 + m.m30); // left
        setPlane(1, m.m03 - m.m00, m.m13 - m.m10, m.m23 - m.m20, m.m33 - m.m30); // right
        setPlane(2, m.m03 + m.m01, m.m13 + m.m11, m.m23 + m.m21, m.m33 + m.m31); // bottom
        setPlane(3, m.m03 - m.m01, m.m13 - m.m11, m.m23 - m.m21, m.m33 - m.m31); // top
        setPlane(4, m.m03 + m.m02, m.m13 + m.m12, m.m23 + m.m22, m.m33 + m.m32); // near
        setPlane(5, m.m03 - m.m02, m.m13 - m.m12, m.m23 - m.m22, m.m33 - m.m32); // far
        planeCount = 6;
    }

    private void setPlane(int i, float a, float b, float c, float d) {
        float length = (float) Math.sqrt(a * a + b * b + c * c);
        planes[i][0] = a / length;
        planes[i][1] = b / length;
        planes[i][2] = c / length;
        planes[i][3] = d / length;
    }

    /** False only when the sphere lies completely outside (never while culling is switched off, see GraphicsSettings). */
    public boolean contains(Vector3f centre, float radius) {
        if (!GraphicsSettings.culling) return true;
        for (int i = 0; i < planeCount; i++) {
            float[] p = planes[i];
            if (p[0] * centre.x + p[1] * centre.y + p[2] * centre.z + p[3] < -radius) return false;
        }
        return true;
    }

    public boolean contains(Entity entity) {
        return contains(entity.getPosition(), radiusOf(entity));
    }

    /**
     * Radius around an entity's position that holds the whole model, turned and scaled as it is, also while the
     * wind bends it: wind.glsl moves a vertex at height h above the base by at most
     * bend * (0.1 h)^1.5 * 1.25 (the strongest gust) plus flutter * 1.6.
     */
    public static float radiusOf(Entity entity) {
        float radius = entity.getModel().rawModel().radius() * entity.getScale();
        ModelTexture texture = entity.getModel().texture();
        if (texture.getWindBend() > 0 || texture.getWindFlutter() > 0) {
            radius += texture.getWindBend() * (float) Math.pow(0.1 * radius, 1.5) * 1.25f + texture.getWindFlutter() * 1.6f;
        }
        return radius;
    }
}
