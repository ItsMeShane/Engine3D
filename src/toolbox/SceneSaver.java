package toolbox;

import toolbox.definitions.Scene;
import entities.Camera;

import java.io.*;
import java.util.ArrayList;

public class SceneSaver {


    public static Scene loadScene(String savePath) {
        try {

            FileInputStream fileInputStream = new FileInputStream(savePath);
            ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);

            Scene scene = (Scene) objectInputStream.readObject();

            objectInputStream.close();
            fileInputStream.close();

            return scene;

        } catch (IOException | ClassNotFoundException e) {
            System.err.println("ERROR: COULD NOT LOAD SCENE:\t"+savePath);

            return new Scene(new ArrayList<>(), new ArrayList<>(), new Camera());
        }
    }



    public static void saveScene(Scene scene) {
        try {

            String fileName = getNextSaveName();
            FileOutputStream fileOutputStream = new FileOutputStream(fileName);
            ObjectOutputStream out = new ObjectOutputStream(fileOutputStream);

            out.writeObject(scene);

            out.close();
            fileOutputStream.close();
            System.out.println("SCENE SAVED     |     \"" + fileName + "\"");

        } catch (IOException e) {
            System.out.println("fuck");
            throw new RuntimeException(e);
        }
    }


    private static String getNextSaveName() {
        String name = "saveID ";
        int index = 1;
        while (new File(name + index + ".ser").exists()) {
            index++;
        }
        return name + index + ".ser";
    }

}
