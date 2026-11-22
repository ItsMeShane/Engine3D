package entities;

import editor.Editable;
import editor.Editor;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.util.vector.Vector3f;
import renderEngine.DisplayManager;
import terrains.HeightSource;
import terrains.Terrain;
import water.WaterTile;

import javax.swing.*;
import java.io.Serializable;

public class Camera implements Serializable, Editable {

	// pinned to the value of the existing saves, so adding methods doesn't break loading "saveID *.ser" files
	private static final long serialVersionUID = -3525352014066738590L;

	private float distanceFromFocusPoint = 75;
	private float angleAroundFocusPoint = 0;
	private float angleAboveFocusPoint = 0;

//	private SmoothFloat angleAroundPlayer = new SmoothFloat(0, 10);


	public static float FOV = 70;
	private final Vector3f viewPointPosition = new Vector3f();
	private float pitch = 25;	// rotation around the Y axis
	private float yaw; 			// rotation around the Z axis

	/** The camera never gets closer than this to the ground or the water surface. */
	private static final float GROUND_CLEARANCE = 1.5f;
	// set each frame by keepAboveGround (transient: not part of saved scenes)
	private transient float groundTilt;   // added to the pitch while the camera is held up by the ground, so it keeps facing its target
	private transient boolean grounded;   // the camera is resting on the ground: it cannot be tilted further down

	private final FocusPoint focusPoint = new FocusPoint(new Vector3f(1200, 0, 1200));

	public Camera() {

	}


	/**
	 * @param terrain the terrain tile under the focus point
	 * @param ground  ground height at any world position, used to keep the camera from sinking into hills or
	 *                the lake; null switches that off
	 */
	public void move(Terrain terrain, HeightSource ground) {

		focusPoint.move(terrain, angleAroundFocusPoint);

		calculateZoom();
		calculatePitch();
		calculateYaw();

		float horizonDistance = calculateHorizonDistance();
		float verticalDistance = calculateVerticalDistance();
		calculateCameraPosition(horizonDistance, verticalDistance);
		keepAboveGround(ground, horizonDistance);
		setYaw(180 - angleAroundFocusPoint);
	}

	/**
	 * Places the camera from explicit values, ignoring mouse and keyboard; used by scripted camera moves.
	 * The camera looks at a point 5 above (focusX, focusY, focusZ) from {@code distance} away.
	 */
	public void setView(float focusX, float focusY, float focusZ, float angleAround, float tilt, float distance, HeightSource ground) {
		Mouse.getDWheel(); // throw away input that arrived meanwhile, so it isn't applied in one jump afterwards
		Mouse.getDX();
		Mouse.getDY();
		focusPoint.getPosition().set(focusX, focusY, focusZ);
		angleAroundFocusPoint = angleAround;
		distanceFromFocusPoint = distance;
		pitch = tilt; // set directly: going through the editor's sliders would round the angles to whole degrees
		float horizonDistance = calculateHorizonDistance();
		calculateCameraPosition(horizonDistance, calculateVerticalDistance());
		keepAboveGround(ground, horizonDistance);
		yaw = 180 - angleAroundFocusPoint;
	}

	public float getAngleAroundFocusPoint() {
		return angleAroundFocusPoint;
	}

	/** Degrees the orbit is tilted above the horizontal (the pitch before any correction by the ground). */
	public float getTilt() {
		return pitch;
	}

	public float getDistanceFromFocusPoint() {
		return distanceFromFocusPoint;
	}

	/**
	 * If the orbit would put the camera under the terrain or the water, it is lifted to just above the surface
	 * and tilted so it still looks at the point it was orbiting (5 above the focus point).
	 */
	private void keepAboveGround(HeightSource ground, float horizontalDistance) {
		groundTilt = 0;
		grounded = false;
		if (ground == null) return;
		float lowest = Math.max(ground.heightAt(viewPointPosition.x, viewPointPosition.z), WaterTile.level()) + GROUND_CLEARANCE;
		if (viewPointPosition.y >= lowest) return;

		viewPointPosition.y = lowest;
		grounded = true;
		if (horizontalDistance > 1) {
			float targetHeight = focusPoint.getPosition().y + 5;
			groundTilt = (float) Math.toDegrees(Math.atan2(lowest - targetHeight, horizontalDistance)) - pitch;
		}
	}

	private void calculateCameraPosition(float horizontalDistance, float verticalDistance) {
		float theta = angleAroundFocusPoint;
		float offsetX = (float) (horizontalDistance * Math.sin(Math.toRadians(theta)));
		float offsetZ = (float) (horizontalDistance * Math.cos(Math.toRadians(theta)));
		viewPointPosition.x = focusPoint.getPosition().x - offsetX;
		viewPointPosition.z = focusPoint.getPosition().z - offsetZ;
		viewPointPosition.y = focusPoint.getPosition().y + verticalDistance + 5;
	}


	private void calculateZoom() {
		float zoomLevel = Mouse.getDWheel() * 0.1f;
		distanceFromFocusPoint -= zoomLevel;
//		distanceFromFocusPoint = Math.min(distanceFromFocusPoint, 125);// max distance from focus point
//		distanceFromFocusPoint = Math.max(distanceFromFocusPoint, 25); // min distance from focus point

	}

	private void calculatePitch() {
		if (Mouse.isButtonDown(0)) {
			float pitchChange = Mouse.getDY() * 0.1f;
			if (grounded && pitchChange > 0) return; // already on the ground: dragging further down does nothing
			setPitch(pitch - pitchChange);
//			pitch = Math.min(pitch, 70);// max view angle
//			pitch = Math.max(pitch, 0); // min view angle
		}
	}

	private void calculateYaw() {
		if (Mouse.isButtonDown(0)) {
			float angleChange = Mouse.getDX() * 0.3f;
			angleAroundFocusPoint -= angleChange;
		}
	}

	private float calculateHorizonDistance() {
		return (float) (distanceFromFocusPoint * Math.cos(Math.toRadians(pitch)));
	}

	private float calculateVerticalDistance() {
		return (float) (distanceFromFocusPoint * Math.sin(Math.toRadians(pitch)));
	}


	public Vector3f getViewPointPosition() {
		return viewPointPosition;
	}

	public FocusPoint getFocusPoint() {
		return focusPoint;
	}

	/** The angle the camera looks down at: the orbit angle, corrected while the ground is holding the camera up. */
	public float getPitch() {
		return pitch + groundTilt;
	}

	public void setDistanceFromFocusPoint(float distanceFromFocusPoint) {
		this.distanceFromFocusPoint = distanceFromFocusPoint;
	}

	public void setAngleAroundFocusPoint(float angleAroundFocusPoint) {
		this.angleAroundFocusPoint = angleAroundFocusPoint;
	}

	public void setAngleAboveFocusPoint(float angleAboveFocusPoint) {
		this.pitch = angleAboveFocusPoint;
	}

	public void setYaw(float yaw) {
		this.yaw = yaw;
		((JSlider) Editor.settingsMap.get(this).getComponents().get(2)).setValue(
				((int) angleAroundFocusPoint % 360) < 0 ? 358 - ((int) angleAroundFocusPoint % 360) : (int) angleAroundFocusPoint % 360);
	}

	public void setPitch(float pitch) {
		this.pitch = pitch;
		((JSlider) Editor.settingsMap.get(this).getComponents().get(4)).setValue(
				((int) pitch % 360) < 0 ? 358 - ((int) pitch % 360) : (int) pitch % 360);

	}


	public float getYaw() {
		return yaw;
	}

	public void invertPitch() {
		pitch = -pitch;
		groundTilt = -groundTilt;
	}

	// point camera follows
	public static class FocusPoint implements Serializable{

		private float currentSpeedX = 0;
		private float currentSpeedZ = 0;
		private final Vector3f position;
		private final Vector3f positionTranslation = new Vector3f();

		private FocusPoint(Vector3f position) {
			this.position = position;
		}

		public void move(Terrain terrain, float cameraRotation) {
			checkInputs();

			float delta = DisplayManager.getFrameTimeSeconds();

			// Calculate translation components for X axis
			float dx = positionTranslation.x * delta;
			float dz = positionTranslation.z * delta;


			// Calculate rotation components
			float sinRotation = (float) Math.sin(Math.toRadians(-cameraRotation));
			float cosRotation = (float) Math.cos(Math.toRadians(-cameraRotation));

			// Calculate translation components for Z axis using currentSpeedZ
			float translatedDx = dx * cosRotation - dz * sinRotation;
			float translatedDz = dx * sinRotation + dz * cosRotation;

			increasePosition(translatedDx, 0, translatedDz);
			// glide over the water surface instead of sinking to the lake bed
			getPosition().y = Math.max(terrain.getHeightOfTerrain(getPosition().x, getPosition().z), WaterTile.level());
		}

		private void increasePosition(float dx, float dy, float dz) {
//			this.position.x += dx;
//			this.position.y += dy;
//			this.position.z += dz;
			position.translate(dx, dy, dz);
		}

		private void checkInputs() {
			handleInput(Keyboard.KEY_W, Keyboard.KEY_S, true);
			handleInput(Keyboard.KEY_A, Keyboard.KEY_D, false);
		}

		private void handleInput(int positiveKey, int negativeKey, boolean isZAxis) {
			final float MAX_CAMERA_SPEED = 100f;
			if (Keyboard.isKeyDown(positiveKey)) accelerate(isZAxis, MAX_CAMERA_SPEED);
			else if (Keyboard.isKeyDown(negativeKey)) accelerate(isZAxis, -MAX_CAMERA_SPEED);
			else decelerate(isZAxis);
		}

		private void accelerate(boolean isZAxis, float targetSpeed) {
			// determine axis
			float currentSpeed = isZAxis ? currentSpeedZ : currentSpeedX;
			final float ACCELERATION_SPEED = 2.5f;

			// determine speed
			if (currentSpeed < targetSpeed) currentSpeed += ACCELERATION_SPEED;
			else if (currentSpeed > targetSpeed) currentSpeed -= ACCELERATION_SPEED;

			// assign speed
			if (isZAxis) currentSpeedZ = currentSpeed;
			else currentSpeedX = currentSpeed;

			// set position
			setPositionTranslation(isZAxis, currentSpeed);
		}

		private void decelerate(boolean isZAxis) {
			// determine axis
			float currentSpeed = isZAxis ? currentSpeedZ : currentSpeedX;
			final float DECELERATION_SPEED = 5f;

			// determine speed
			if (currentSpeed > 0) {
				currentSpeed -= DECELERATION_SPEED;
				if (currentSpeed < 0) {
					currentSpeed = 0;
				}
			} else if (currentSpeed < 0) {
				currentSpeed += DECELERATION_SPEED;
				if (currentSpeed > 0) {
					currentSpeed = 0;
				}
			}

			// assign speed
			if (isZAxis) currentSpeedZ = currentSpeed;
			else currentSpeedX = currentSpeed;

			// set position
			setPositionTranslation(isZAxis, currentSpeed);
		}

		private void setPositionTranslation(boolean isZAxis, float currentSpeed) {
			if (isZAxis) {
				positionTranslation.z = currentSpeed;
			} else {
				positionTranslation.x = currentSpeed;
			}
		}


		public Vector3f getPosition() {
			return position;
		}
	}



}
