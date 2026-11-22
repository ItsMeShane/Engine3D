package engineTester;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The scenes the engine can start with. Which one runs is decided, in this order, by
 *   1. the first program argument           (IntelliJ: Run > Edit Configurations > Program arguments:  village)
 *   2. the JVM option  -Dscene=<name>
 *   3. {@link #DEFAULT}
 * To add a scene: write a class that implements {@link GameScene} and register it in the static block below.
 */
public final class Scenes {

    /** Scene used when none is named on the command line. */
    public static final String DEFAULT = "village";

    private static final Map<String, Supplier<GameScene>> SCENES = new LinkedHashMap<>();

    static {
        SCENES.put("village", DemoScene::new);   // low-poly lakeside village in a ring of wooded hills
        SCENES.put("forest", ForestScene::new);  // realistic forest clearing by a lake, late afternoon
    }

    private static String selectedName = DEFAULT;

    private Scenes() {
    }

    /** Name of the scene {@link #select} returned last. */
    public static String selectedName() {
        return selectedName;
    }

    public static GameScene select(String[] args) {
        String requested = System.getProperty("scene", DEFAULT);
        for (String arg : args) {
            if (!arg.startsWith("--")) { // options such as --record belong to other parts of the program
                requested = arg;
                break;
            }
        }
        String name = requested.trim().toLowerCase();
        if (!SCENES.containsKey(name)) {
            System.err.println("Unknown scene \"" + requested + "\". Available: " + String.join(", ", SCENES.keySet()) + ". Loading \"" + DEFAULT + "\".");
            name = DEFAULT;
        }
        System.out.println("scene: " + name + "   (available: " + String.join(", ", SCENES.keySet()) + ")");
        selectedName = name;
        return SCENES.get(name).get();
    }
}
