package models;

/**
 * A mesh on the graphics card.
 *
 * @param vertexCount number of indices (or vertices, for meshes without an index buffer) to draw
 * @param radius      distance from the model's origin to its farthest vertex: a sphere that holds the model
 *                    however it is turned (see renderEngine.Frustum)
 */
public record RawModel(int vaoID, int vertexCount, float radius)  {}
