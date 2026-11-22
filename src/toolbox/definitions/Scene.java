package toolbox.definitions;

import entities.Camera;

import java.io.Serializable;
import java.util.List;

public record Scene(List<EntityDefinition> entities, List<LightDefinition> lights, Camera camera) implements Serializable {

}
