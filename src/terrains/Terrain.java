package terrains;

import models.RawModel;
import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.util.vector.Vector3f;
import renderEngine.Environment;
import renderEngine.Loader;
import textures.TerrainTexture;
import textures.TerrainTexturePack;
import toolbox.Maths;


public class Terrain {

    public final static int SIZE = 800;

    private final float x;
    private final float z;
    private final RawModel model;
    private final TerrainTexturePack texturePack;
    private final TerrainTexture blendMap;

    private float[][] terrainHeights;
    /** Per grid square: true = split along the top-left to bottom-right diagonal instead of the usual one (see Environment.terrainDiagonals). */
    private boolean[][] otherDiagonal;
    private final HeightSource heightSource;
    private int vertexCount;

    public Terrain(int gridX, int gridZ, TerrainTexturePack texturePack, TerrainTexture blendMap) {
        this(gridX, gridZ, texturePack, blendMap, null);
    }

    /** @param heightSource the shape of the ground; null = the default procedural hills (HeightsGenerator) */
    public Terrain(int gridX, int gridZ, TerrainTexturePack texturePack, TerrainTexture blendMap, HeightSource heightSource) {
        this.heightSource = heightSource;
        this.texturePack = texturePack;
        this.blendMap = blendMap;
        this.z = gridX * SIZE;
        this.x = gridZ * SIZE;
        this.model = generateTerrain();
    }

    public float getHeightOfTerrain(float x, float z) {
        float terrainX = x - getX();
        float terrainZ = z - getZ();
        float gridSqr = SIZE / (float) (terrainHeights.length - 1);
        int gridX = (int) Math.floor(terrainX / gridSqr);
        int gridZ = (int) Math.floor(terrainZ / gridSqr);
        if (gridX >= terrainHeights.length - 1 || gridZ >= terrainHeights.length - 1 || gridX < 0 || gridZ < 0)
            return -100;
        float xCoord = (terrainX % gridSqr) / gridSqr;
        float zCoord = (terrainZ % gridSqr) / gridSqr;
        if (otherDiagonal[gridX][gridZ]) {
            if (xCoord >= zCoord) {
                return Maths.barryCentric(new Vector3f(0, terrainHeights[gridX][gridZ], 0), new Vector3f(1,
                        terrainHeights[gridX + 1][gridZ], 0), new Vector3f(1,
                        terrainHeights[gridX + 1][gridZ + 1], 1), new Vector2f(xCoord, zCoord));
            }
            return Maths.barryCentric(new Vector3f(0, terrainHeights[gridX][gridZ], 0), new Vector3f(1,
                    terrainHeights[gridX + 1][gridZ + 1], 1), new Vector3f(0,
                    terrainHeights[gridX][gridZ + 1], 1), new Vector2f(xCoord, zCoord));
        }
        if (xCoord <= (1 - zCoord)) {
            return  Maths.barryCentric(new Vector3f(0, terrainHeights[gridX][gridZ], 0), new Vector3f(1,
                            terrainHeights[gridX + 1][gridZ], 0), new Vector3f(0,
                            terrainHeights[gridX][gridZ + 1], 1), new Vector2f(xCoord, zCoord));
        } else {
            return Maths.barryCentric(new Vector3f(1, terrainHeights[gridX + 1][gridZ], 0),
                    new Vector3f(1, terrainHeights[gridX + 1][gridZ + 1], 1),
                    new Vector3f(0, terrainHeights[gridX][gridZ + 1], 1), new Vector2f(xCoord, zCoord));
        }
    }

    private RawModel generateTerrain(){

        final int VERTEX_COUNT = Environment.terrainVertexCount; // 100 unless the scene asked for bigger facets
        vertexCount = VERTEX_COUNT;

        HeightsGenerator generator = new HeightsGenerator((int) (getX() / SIZE), (int) (getZ() / SIZE), VERTEX_COUNT);

        terrainHeights = new float[VERTEX_COUNT][VERTEX_COUNT];
        int count = VERTEX_COUNT * VERTEX_COUNT;
        float[] vertices = new float[count * 3];
        float[] normals = new float[count * 3];
        float[] textureCoords = new float[count*2];
        int[] indices = new int[6*(VERTEX_COUNT-1)*(VERTEX_COUNT-1)];
        int vertexPointer = 0;
        for(int i=0;i<VERTEX_COUNT;i++){
            for(int j=0;j<VERTEX_COUNT;j++){
                vertices[vertexPointer*3] = (float)j/((float)VERTEX_COUNT - 1) * SIZE;
                float height = getHeight(j, i, generator);
                terrainHeights[j][i] = height;
                vertices[vertexPointer*3+1] = height; // terrain height
                vertices[vertexPointer*3+2] = (float)i/((float)VERTEX_COUNT - 1) * SIZE;
                Vector3f normal = calculateNormal(j, i, generator);
                normals[vertexPointer*3]   = normal.x;
                normals[vertexPointer*3+1] = normal.y;
                normals[vertexPointer*3+2] = normal.z;
                textureCoords[vertexPointer*2] = (float)j/((float)VERTEX_COUNT - 1);
                textureCoords[vertexPointer*2+1] = (float)i/((float)VERTEX_COUNT - 1);
                vertexPointer++;
            }
        }
        otherDiagonal = new boolean[VERTEX_COUNT - 1][VERTEX_COUNT - 1];
        int pointer = 0;
        for(int gz=0;gz<VERTEX_COUNT-1;gz++){
            for(int gx=0;gx<VERTEX_COUNT-1;gx++){
                int topLeft = (gz*VERTEX_COUNT)+gx;
                int topRight = topLeft + 1;
                int bottomLeft = ((gz+1)*VERTEX_COUNT)+gx;
                int bottomRight = bottomLeft + 1;
                if (otherDiagonal[gx][gz] = useOtherDiagonal(gx, gz)) {
                    indices[pointer++] = topLeft;
                    indices[pointer++] = bottomLeft;
                    indices[pointer++] = bottomRight;
                    indices[pointer++] = topLeft;
                    indices[pointer++] = bottomRight;
                    indices[pointer++] = topRight;
                    continue;
                }
                indices[pointer++] = topLeft;
                indices[pointer++] = bottomLeft;
                indices[pointer++] = topRight;
                indices[pointer++] = topRight;
                indices[pointer++] = bottomLeft;
                indices[pointer++] = bottomRight;
            }
        }
        return Loader.loadToVAO(vertices, textureCoords, normals, indices);
    }



    /**
     * Whether a grid square is split from its top-left to its bottom-right corner. getHeightOfTerrain uses the same
     * answer, so things placed on the ground stand on the triangles that are drawn.
     */
    private boolean useOtherDiagonal(int gx, int gz) {
        return splitsOtherWay(terrainHeights[gx][gz], terrainHeights[gx + 1][gz], terrainHeights[gx][gz + 1], terrainHeights[gx + 1][gz + 1], gx, gz);
    }

    /**
     * The rule behind Environment.terrainDiagonals, for one grid square given the heights of its corners (top = the
     * side with the smaller z) and its position in the tile's grid. Public so a scene that paints its blend map from
     * the shape of the ground can follow the same triangles.
     */
    public static boolean splitsOtherWay(float topLeft, float topRight, float bottomLeft, float bottomRight, int gx, int gz) {
        boolean chessboard = (gx + gz) % 2 != 0;
        switch (Environment.terrainDiagonals) {
            case 1:
                return chessboard;
            case 2:
                // the shorter (in height) of the two diagonals becomes the shared edge, so facets line up with the
                // contours instead of cutting across them; on level or evenly sloping squares fall back to the chessboard
                float usual = Math.abs(bottomLeft - topRight);
                float other = Math.abs(topLeft - bottomRight);
                if (Math.abs(usual - other) < 0.05f) return chessboard;
                return other < usual;
            default:
                return false;
        }
    }

    private float getHeight(int x, int z, HeightsGenerator generator) {
        if (heightSource != null) {
            float step = SIZE / (float) (vertexCount - 1);
            return heightSource.heightAt(getX() + x * step, getZ() + z * step);
        }
        return generator.generateHeight(x, z);
    }

    private Vector3f calculateNormal(int x, int z, HeightsGenerator generator) {
        float heightU = getHeight(x, z + 1, generator);
        float heightD = getHeight(x, z - 1, generator);
        float heightL = getHeight(x - 1, z, generator);
        float heightR = getHeight(x + 1, z, generator);
        Vector3f normal = new Vector3f(heightL - heightR, 2f, heightD - heightU);
        normal.normalise();
        return normal;
    }

    public float getX() {
        return x;
    }

    public float getZ() {
        return z;
    }

    public RawModel getModel() {
        return model;
    }

    public TerrainTexture getBlendMap() {
        return blendMap;
    }

    public TerrainTexturePack getTexturePack() {
        return texturePack;
    }
}
