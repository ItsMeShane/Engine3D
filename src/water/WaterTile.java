package water;

import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector3f;
import toolbox.Maths;

import java.util.Objects;

public final class WaterTile {

	public static final float WATER_SIZE = 100;
	//	public static final float WATER_HEIGHT = -20;
	// -10 floods only the deeper terrain basins, so lakes get natural shorelines instead of square tile edges
	public static final float WATER_HEIGHT = -10;
	private static float level = WATER_HEIGHT;

	/** Water level of the running scene (WATER_HEIGHT unless the scene chose its own). */
	public static float level() {
		return level;
	}

	public static void setLevel(float waterLevel) {
		level = waterLevel;
	}

	private final float centerX;
	private final float centerZ;
	private final float height;
	private final float size; // half-width of the tile in world units

	private final Matrix4f transformationMatrix;

	public WaterTile(float centerX, float centerZ, float height) {
		this(centerX, centerZ, height, WATER_SIZE);
	}

	public WaterTile(float centerX, float centerZ, float height, float size) {
		this.centerX = centerX;
		this.centerZ = centerZ;
		this.height = height;
		this.size = size;
		this.transformationMatrix = Maths.createTransformationMatrix(new Vector3f(centerX(), height(), centerZ()), 0, 0, 0, size);
	}

	public float centerX() {
		return centerX;
	}

	public float centerZ() {
		return centerZ;
	}

	public float height() {
		return height;
	}

	public float size() {
		return size;
	}

	public Matrix4f transformationMatrix() {
		return transformationMatrix;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == this) return true;
		if (obj == null || obj.getClass() != this.getClass()) return false;
		var that = (WaterTile) obj;
		return Float.floatToIntBits(this.centerX) == Float.floatToIntBits(that.centerX) &&
				Float.floatToIntBits(this.centerZ) == Float.floatToIntBits(that.centerZ) &&
				Float.floatToIntBits(this.height) == Float.floatToIntBits(that.height) &&
				Float.floatToIntBits(this.size) == Float.floatToIntBits(that.size);
	}

	@Override
	public int hashCode() {
		return Objects.hash(centerX, centerZ, height, size);
	}

	@Override
	public String toString() {
		return "WaterTile[" +
				"centerX=" + centerX + ", " +
				"centerZ=" + centerZ + ", " +
				"height=" + height + ", " +
				"size=" + size + ']';
	}


}
