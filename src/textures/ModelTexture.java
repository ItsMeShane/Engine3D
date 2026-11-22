package textures;

public class ModelTexture {
	
	private int textureID;

	private float shineDamper = 1;
	private float reflectivity = 0;

	private boolean hasTransparency = false;
	private boolean useFakeLighting = false;

	private int numberOfRows = 1;

	private int normalMap = 0;   // texture id, 0 = none
	private float emissive = 0;  // adds texture colour * emissive on top of the lighting (glows / blooms above 1)

	public ModelTexture(int texture){
		this.setTextureID(texture);
	}
	
	public int getID(){
		return getTextureID();
	}

	public int getTextureID() {
		return textureID;
	}

	public void setTextureID(int textureID) {
		this.textureID = textureID;
	}

	public float getShineDamper() {
		return shineDamper;
	}

	public void setShineDamper(float shineDamper) {
		this.shineDamper = shineDamper;
	}

	public float getReflectivity() {
		return reflectivity;
	}

	public void setReflectivity(float reflectivity) {
		this.reflectivity = reflectivity;
	}

	public boolean hasTransparency() {
		return hasTransparency;
	}

	public void setHasTransparency(boolean hasTransparency) {
		this.hasTransparency = hasTransparency;
	}

	public boolean isUseFakeLighting() {
		return useFakeLighting;
	}

	public void setUseFakeLighting(boolean useFakeLighting) {
		this.useFakeLighting = useFakeLighting;
	}

	private float windBend = 0, windFlutter = 0;
	private boolean twoSidedLighting = false;
	private float translucency = 0;
	private boolean castsShadow = true;

	public boolean castsShadow() {
		return castsShadow;
	}

	/** false keeps the model out of the shadow map: it still receives shadows but throws none. */
	public void setCastsShadow(boolean castsShadow) {
		this.castsShadow = castsShadow;
	}

	public float getWindBend() {
		return windBend;
	}

	public float getWindFlutter() {
		return windFlutter;
	}

	/** Wind animation: bend leans the whole model (more the higher above its base), flutter shakes parts far from the stem. 0 = rigid. */
	public void setWind(float bend, float flutter) {
		this.windBend = bend;
		this.windFlutter = flutter;
	}

	public boolean isTwoSidedLighting() {
		return twoSidedLighting;
	}

	/** Back faces are lit as if their normal pointed the other way; for leaf geometry drawn without culling. */
	public void setTwoSidedLighting(boolean twoSidedLighting) {
		this.twoSidedLighting = twoSidedLighting;
	}

	public float getTranslucency() {
		return translucency;
	}

	/** Share of the light that shines through from behind (0..1); makes leaves glow when backlit. */
	public void setTranslucency(float translucency) {
		this.translucency = translucency;
	}

	public int getNormalMap() {
		return normalMap;
	}

	/** Tangent-space normal map (DirectX convention: green along +v), sharing the texture coordinates of the model. */
	public void setNormalMap(int normalMap) {
		this.normalMap = normalMap;
	}

	public float getEmissive() {
		return emissive;
	}

	public void setEmissive(float emissive) {
		this.emissive = emissive;
	}

	public int getNumberOfRows() {
		return numberOfRows;
	}

	public void setNumberOfRows(int numberOfRows) {
		this.numberOfRows = numberOfRows;
	}
}
