package shaders;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.util.vector.Vector3f;
import org.lwjgl.util.vector.Vector4f;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public abstract class ShaderProgram {

    private final int programID;
    private final int vertexShaderID;
    private final int fragmentShaderID;

    private static final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private final Map<String, Integer> uniformLocations = new HashMap<>();

    public ShaderProgram(String vertexFile, String fragmentFile) {
        vertexShaderID = loadShader(vertexFile, GL20.GL_VERTEX_SHADER);
        fragmentShaderID = loadShader(fragmentFile, GL20.GL_FRAGMENT_SHADER);
        programID = GL20.glCreateProgram();
        GL20.glAttachShader(programID, vertexShaderID);
        GL20.glAttachShader(programID, fragmentShaderID);
        bindAttributes();
        GL20.glLinkProgram(programID);
        if (GL20.glGetProgram(programID, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            System.err.println("Could not link " + vertexFile + " + " + fragmentFile + ":\n" + GL20.glGetProgramInfoLog(programID, 2000));
        }
        GL20.glValidateProgram(programID);
        getAllUniformLocations();
    }

    protected int getUniformLocation(String uniformName) {
        return GL20.glGetUniformLocation(programID, uniformName);
    }

    protected abstract void getAllUniformLocations();

    public void start() {
        GL20.glUseProgram(programID);
    }

    public void stop() {
        GL20.glUseProgram(0);
    }

    public void cleanUp() {
        stop();
        GL20.glDetachShader(programID, vertexShaderID);
        GL20.glDetachShader(programID, fragmentShaderID);
        GL20.glDeleteShader(vertexShaderID);
        GL20.glDeleteShader(fragmentShaderID);
        GL20.glDeleteProgram(programID);
    }

    protected abstract void bindAttributes();

    protected void bindAttribute(int attribute, String variableName) {
        GL20.glBindAttribLocation(programID, attribute, variableName);
    }

    protected void loadFloat(int location, float value) {
        GL20.glUniform1f(location, value);
    }
    protected void loadInteger(int location, int value) {
        GL20.glUniform1i(location, value);
    }

    protected void loadVector(int location, Vector4f vector4f) {
        GL20.glUniform4f(location, vector4f.x, vector4f.y, vector4f.z, vector4f.w);
    }
    protected void loadVector(int location, Vector3f vector3f) {
        GL20.glUniform3f(location, vector3f.x, vector3f.y, vector3f.z);
    }
    protected void loadVector(int location, Vector2f vector3f) {
        GL20.glUniform2f(location, vector3f.x, vector3f.y);
    }

    protected void loadBoolean(int location, boolean value) {
        float toLoad = value ? 1 : 0;
        GL20.glUniform1f(location, toLoad);
    }

    // ---- uniforms by name (location looked up once, then cached); the program must be started first

    private int location(String uniformName) {
        return uniformLocations.computeIfAbsent(uniformName, this::getUniformLocation);
    }

    public void set(String uniformName, float value) {
        GL20.glUniform1f(location(uniformName), value);
    }

    public void set(String uniformName, int value) {
        GL20.glUniform1i(location(uniformName), value);
    }

    /** Booleans travel as 0.0 / 1.0, like loadBoolean. */
    public void set(String uniformName, boolean value) {
        GL20.glUniform1f(location(uniformName), value ? 1 : 0);
    }

    public void set(String uniformName, float x, float y) {
        GL20.glUniform2f(location(uniformName), x, y);
    }

    public void set(String uniformName, Vector3f value) {
        GL20.glUniform3f(location(uniformName), value.x, value.y, value.z);
    }

    public void set(String uniformName, Matrix4f value) {
        loadMatrix(location(uniformName), value);
    }

    protected void loadMatrix(int location, Matrix4f matrix) {
        matrix.store(matrixBuffer);
        matrixBuffer.flip();
        GL20.glUniformMatrix4(location, false, matrixBuffer);
    }

    /** Reads a shader file from the classpath; a line of the form  #include "path/file.glsl"  is replaced by that file. */
    private static String readSource(String file) {
        StringBuilder shaderSource = new StringBuilder();
        try {
            InputStreamReader is = new InputStreamReader(Objects.requireNonNull(ClassLoader.getSystemResourceAsStream(file), file));
            BufferedReader reader = new BufferedReader(is);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("#include")) {
                    shaderSource.append(readSource(line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')))).append("\n");
                } else {
                    shaderSource.append(line).append("\n");
                }
            }
            is.close();
            reader.close();
        } catch (IOException e) {
            System.err.println("Could not read file " + file);
            e.printStackTrace();
            System.exit(-1);
        }
        return shaderSource.toString();
    }

    private static int loadShader(String file, int type) {
        String shaderSource = readSource(file);
        int shaderID = GL20.glCreateShader(type);
        GL20.glShaderSource(shaderID, shaderSource);
        GL20.glCompileShader(shaderID);
        if (GL20.glGetShader(shaderID, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            System.out.println(GL20.glGetShaderInfoLog(shaderID, 2000));
            System.err.println("Could not compile shader " + file);
            System.exit(-1);
        }
        return shaderID;
    }

}
