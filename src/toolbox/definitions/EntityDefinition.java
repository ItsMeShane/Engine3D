package toolbox.definitions;

import org.lwjgl.util.vector.Vector3f;

import java.io.Serializable;

public record EntityDefinition(String objPath, String texturePath, Vector3f position, float rotX, float rotY,
                               float rotZ, float scale, float reflectivity, float shineDamper, boolean useFakeLighting,
                               String name) implements Serializable {


}

