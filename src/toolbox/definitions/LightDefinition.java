package toolbox.definitions;

import org.lwjgl.util.vector.Vector3f;

import java.io.Serializable;

public record LightDefinition(Vector3f position, Vector3f color, Vector3f attenuation,
                              String name) implements Serializable {


}
