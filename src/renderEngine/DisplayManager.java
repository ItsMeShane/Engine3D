package renderEngine;

import org.lwjgl.LWJGLException;
import org.lwjgl.Sys;
import org.lwjgl.opengl.ContextAttribs;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.PixelFormat;

import java.util.Locale;

public class DisplayManager {

	private static final int WIDTH = 1920;
	private static final int HEIGHT = 1080;
	private static final String TITLE = "Engine 3D";

	private static long lastFrameTime;
	private static float delta;
	private static float time;
	private static float fixedFrameTime; // 0 = real time
	private static int renderWidth, renderHeight; // 0 = the window's size

	// frame counter shown in the window title
	private static final long COUNTER_PERIOD = 500_000_000L; // ns between title updates
	private static long counterStart, counterIdle;
	private static int counterFrames;

	/**
	 * Size the scene is rendered at. Normally the window's; a recorder sets its own (before the renderers are
	 * created) to render off-screen at a size independent of the window.
	 */
	public static void setRenderSize(int width, int height) {
		renderWidth = width;
		renderHeight = height;
	}

	public static int renderWidth() {
		return renderWidth > 0 ? renderWidth : Display.getWidth();
	}

	public static int renderHeight() {
		return renderHeight > 0 ? renderHeight : Display.getHeight();
	}

	/**
	 * From now on every frame counts as exactly this many seconds, however long it really took, and the frame
	 * rate is no longer capped. Used to render video frame by frame.
	 */
	public static void setFixedFrameTime(float seconds) {
		fixedFrameTime = seconds;
		delta = seconds;
	}

	/** Lets one fixed frame of time pass without showing anything. */
	public static void advanceFixedFrame() {
		time += fixedFrameTime;
	}

	public static void createDisplay(){
		ContextAttribs attribs = new ContextAttribs(3,3).withForwardCompatible(true).withProfileCore(true);

		try {
			Display.setDisplayMode(new DisplayMode(WIDTH,HEIGHT));
			Display.create(new PixelFormat(), attribs);
			Display.setTitle(TITLE);
		} catch (LWJGLException e) {
			e.printStackTrace();
		}

		GL11.glViewport(0,0, WIDTH, HEIGHT);
		lastFrameTime = getCurrentTime();
	}

	public static void updateDisplay(){

		if (fixedFrameTime > 0) {
			Display.update();
			delta = fixedFrameTime;
			time += delta;
			lastFrameTime = getCurrentTime();
			return;
		}

		long idleStart = System.nanoTime();
		if (GraphicsSettings.frameCap) Display.sync(GraphicsSettings.fpsCap);
		counterIdle += System.nanoTime() - idleStart;
		Display.update();
		countFrame();

		long currentFrameTime = getCurrentTime();
//		delta = (currentFrameTime - lastFrameTime) / 1000f;
		delta = (currentFrameTime - lastFrameTime) * 0.001f;
		lastFrameTime = currentFrameTime;
		time += delta;
	}

	/**
	 * Twice a second: frames per second and the time a frame really takes in the title. "busy" leaves out the
	 * time spent waiting for the frame cap, so it shows what a frame costs even while capped.
	 */
	private static void countFrame() {
		long now = System.nanoTime();
		if (counterStart == 0) {
			counterStart = now;
			counterIdle = 0;
			return;
		}
		counterFrames++;
		long elapsed = now - counterStart;
		if (elapsed < COUNTER_PERIOD) return;
		double fps = counterFrames * 1e9 / elapsed;
		double busyMs = (elapsed - counterIdle) / 1e6 / counterFrames;
		String cap = GraphicsSettings.frameCap ? " (capped at " + GraphicsSettings.fpsCap + ", U to uncap)" : " (uncapped)";
		Display.setTitle(String.format(Locale.ROOT, "%s   |   %.0f fps%s   |   %.1f ms busy per frame   |   %,d draw calls, %.1f M triangles",
				TITLE, fps, cap, busyMs, FrameProfiler.lastFrameDraws(), FrameProfiler.lastFrameTriangles() / 1e6));
		counterStart = now;
		counterIdle = 0;
		counterFrames = 0;
	}

	/** Seconds since the display was created (drives shader animation such as wind). */
	public static float getTime() {
		return time;
	}

	public static float getFrameTimeSeconds() {
		return delta;
	}

	public static void closeDisplay(){

		Display.destroy();

	}

	private static long getCurrentTime() {
		return Sys.getTime() * 1000 / Sys.getTimerResolution();
	}


}
