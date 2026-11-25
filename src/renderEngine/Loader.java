package renderEngine;

import de.matthiasmann.twl.utils.PNGDecoder;
import models.RawModel;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.newdawn.slick.opengl.Texture;
import org.newdawn.slick.opengl.TextureLoader;
import textures.TextureData;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Loader {

    public static final List<Integer> vaoList = new ArrayList<>();
    public static final List<Integer> vboList = new ArrayList<>();
    public static final List<Integer> textures = new ArrayList<>();
    private static final List<Integer> textures2D = new ArrayList<>(); // the ones anisotropic filtering applies to

    public static RawModel loadToVAO(float[] positions, float[] textureCoords, float[] normals, int[] indices) {
        int vaoID = createVAO();
        bindIndicesBuffer(indices);
        storeDataInAttributesList(0, 3, positions);
        storeDataInAttributesList(1, 2, textureCoords);
        storeDataInAttributesList(2, 3, normals);
        unbindVAO();
        return new RawModel(vaoID, indices.length, radius(positions, 3));
    }
    public static RawModel loadToVAO(float[] positions, int dimensions) {
        int vaoID = createVAO();
        storeDataInAttributesList(0, dimensions, positions);
        unbindVAO();
        return new RawModel(vaoID, positions.length / dimensions, radius(positions, dimensions));
    }

    /** Distance from the origin to the farthest of the points (each {@code dimensions} floats). */
    private static float radius(float[] positions, int dimensions) {
        double max = 0;
        for (int i = 0; i + dimensions <= positions.length; i += dimensions) {
            double sum = 0;
            for (int j = 0; j < dimensions; j++) sum += positions[i + j] * positions[i + j];
            max = Math.max(max, sum);
        }
        return (float) Math.sqrt(max);
    }

    public static int createEmptyVBO(int floatCount) {
        int vbo = GL15.glGenBuffers();
        vboList.add(vbo);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, floatCount * 4L, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        return vbo;
    }

    public static void addInstancedAttribute(int vao, int vbo, int attribute, int dataSize, int instancedDataLength, int offset) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL30.glBindVertexArray(vao);
        GL20.glVertexAttribPointer(attribute, dataSize, GL11.GL_FLOAT, false, instancedDataLength * 4, offset * 4L);
        GL33.glVertexAttribDivisor(attribute, 1);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    public static void updateVbo(int vbo, float[] data, FloatBuffer buffer) {
        buffer.clear();
        buffer.put(data);
        buffer.flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer.capacity() * 4L, GL15.GL_STREAM_DRAW);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, buffer);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    public static int loadTexture(String filePath) {
        Texture texture;
        try {
            texture = TextureLoader.getTexture("PNG", Objects.requireNonNull(Loader.class.getClassLoader().getResourceAsStream(filePath)));
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
//            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, -0.4f);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        int textureID = texture.getTextureID();
        textures.add(textureID);
        textures2D.add(textureID);
        applyAnisotropy(textureID, anisotropyAmount());
        return textureID;
    }

    /** Creates a texture from RGBA pixels generated at runtime (first row = v 0). Clamped at the edges, with mipmaps. */
    public static int loadTexture(int width, int height, ByteBuffer rgba) {
        int textureID = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureID);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        textures.add(textureID);
        return textureID;
    }

    /**
     * Call once per frame on the render thread: when the anisotropic filtering settings changed, sets the new level
     * (or, with the setting off, none) on every loaded 2D texture.
     */
    public static void updateAnisotropy() {
        float amount = anisotropyAmount();
        if (amount == appliedAnisotropy) return;
        appliedAnisotropy = amount;
        for (int textureID : textures2D) applyAnisotropy(textureID, amount);
    }

    private static float appliedAnisotropy = -1;

    /** The level the settings ask for, as far as the graphics card allows; 1 = none. */
    private static float anisotropyAmount() {
        if (!GraphicsSettings.anisotropicFiltering || !GLContext.getCapabilities().GL_EXT_texture_filter_anisotropic) return 1;
        return Math.min(GraphicsSettings.anisotropy, GL11.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT));
    }

    private static void applyAnisotropy(int textureID, float amount) {
        if (!GLContext.getCapabilities().GL_EXT_texture_filter_anisotropic) return;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureID);
        GL11.glTexParameterf(GL11.GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, amount);
    }

    public static int loadCubeMap(String[] textureFileNames) {
        int texID = GL11.glGenTextures();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, texID);
        for (int i = 0; i < textureFileNames.length; i++) {
            TextureData data = decodeTextureFile(textureFileNames[i]);
            GL11.glTexImage2D(GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X + i, 0, GL11.GL_RGBA, data.width(), data.height(), 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data.buffer());
        }
        textures.add(texID);
        GL11.glTexParameteri(GL13.GL_TEXTURE_CUBE_MAP, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL13.GL_TEXTURE_CUBE_MAP, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);

        GL11.glTexParameteri(GL13.GL_TEXTURE_CUBE_MAP, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL13.GL_TEXTURE_CUBE_MAP, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL13.GL_TEXTURE_CUBE_MAP, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return texID;
    }

    private static TextureData decodeTextureFile(String fileName) {
        int width = 0;
        int height = 0;
        ByteBuffer buffer = null;
        try {
            FileInputStream in = new FileInputStream(fileName);
            PNGDecoder decoder = new PNGDecoder(in);
            width = decoder.getWidth();
            height = decoder.getHeight();
            buffer = ByteBuffer.allocateDirect(4 * width * height);
            decoder.decode(buffer, width * 4, PNGDecoder.Format.RGBA);
            buffer.flip();
            in.close();
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Tried to load texture " + fileName + ", didn't work");
            System.exit(-1);
        }
        return new TextureData(buffer, width, height);
    }


    public static void cleanUp() {
        for (int vao : vaoList)
            GL30.glDeleteVertexArrays(vao);
        for (int vbo : vboList)
            GL15.glDeleteBuffers(vbo);
        for (int txt : textures)
            GL15.glDeleteBuffers(txt);

    }

    private static int createVAO() {
        int vaoID = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoID);
        return vaoID;
    }

    private static void storeDataInAttributesList(int attributeNumber, int coordinateSize, float[] data) {
        int vboID = GL15.glGenBuffers();
        vboList.add(vboID);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboID);
        FloatBuffer buffer = storeDataInFloatBuffer(data);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(attributeNumber, coordinateSize, GL11.GL_FLOAT, false, 0,0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private static void unbindVAO() {
        GL30.glBindVertexArray(0);
    }

    private static void bindIndicesBuffer(int[] indices) {
        int vboID = GL15.glGenBuffers();
        vboList.add(vboID);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, vboID);
        IntBuffer buffer = storeDataInIntBuffer(indices);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);
    }

    private static IntBuffer storeDataInIntBuffer(int[] data) {
        IntBuffer buffer = BufferUtils.createIntBuffer(data.length);
        buffer.put(data);
        buffer.flip();
        return buffer;
    }

    private static FloatBuffer storeDataInFloatBuffer(float[] data) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(data.length);
        buffer.put(data);
        buffer.flip();
        return buffer;
    }

}
