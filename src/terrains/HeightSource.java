package terrains;

/** A terrain shape: the ground height at any world position. */
public interface HeightSource {

    float heightAt(float worldX, float worldZ);
}
