/*
 * Copyright (c) 2009-2020 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.jme3.system.lwjgl;

import com.jme3.input.JoyInput;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.TouchInput;
import com.jme3.input.lwjgl.GlfwJoystickInput;
import com.jme3.input.lwjgl.GlfwKeyInput;
import com.jme3.input.lwjgl.GlfwMouseInput;
import com.jme3.math.Vector2f;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.system.JmeSystem;
import com.jme3.system.NanoTimer;
import com.jme3.util.BufferUtils;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.lwjgl.Version;
import org.lwjgl.glfw.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.GL_FALSE;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * A wrapper class over the GLFW framework in LWJGL 3.
 *
 * @author Daniel Johansson
 */
public abstract class LwjglWindow extends LwjglContext implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(LwjglWindow.class.getName());

    private static final EnumSet<JmeContext.Type> SUPPORTED_TYPES = EnumSet.of(
            JmeContext.Type.Display,
            JmeContext.Type.Canvas,
            JmeContext.Type.OffscreenSurface);

    private static final Map<String, Runnable> RENDER_CONFIGS = new HashMap<>();

    static {
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL30, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL31, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL32, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL33, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL40, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL41, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL42, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL43, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL44, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 4);
        });
        RENDER_CONFIGS.put(AppSettings.LWJGL_OPENGL45, () -> {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
        });
    }

    protected final AtomicBoolean needClose = new AtomicBoolean(false);
    protected final AtomicBoolean needRestart = new AtomicBoolean(false);

    private final JmeContext.Type type;

    private GLFWErrorCallback errorCallback;
    private GLFWWindowSizeCallback windowSizeCallback;
    private GLFWFramebufferSizeCallback framebufferSizeCallback;
    private GLFWWindowFocusCallback windowFocusCallback;

    private Thread mainThread;

    private long window = NULL;
    private int frameRateLimit = -1;

    protected boolean wasActive = false;
    protected boolean autoFlush = true;
    protected boolean allowSwapBuffers = false;

    public LwjglWindow(final JmeContext.Type type) {

        if (!SUPPORTED_TYPES.contains(type)) {
            throw new IllegalArgumentException("Unsupported type '" + type.name() + "' provided");
        }

        this.type = type;
    }

    /**
     * @return Type.Display or Type.Canvas
     */
    @Override
    public JmeContext.Type getType() {
        return type;
    }

    /**
     * Set the title if it's a windowed display
     *
     * @param title the title to set
     */
    @Override
    public void setTitle(final String title) {
        if (created.get() && window != NULL) {
            glfwSetWindowTitle(window, title);
        }
    }

    /**
     * Restart if it's a windowed or full-screen display.
     */
    @Override
    public void restart() {
        if (created.get()) {
            needRestart.set(true);
        } else {
            LOGGER.warning("Display is not created, cannot restart window.");
        }
    }

    /**
     * Apply the settings, changing resolution, etc.
     *
     * @param settings the settings to apply when creating the context.
     */
    protected void createContext(final AppSettings settings) {
        glfwSetErrorCallback(errorCallback = new GLFWErrorCallback() {
            @Override
            public void invoke(int error, long description) {
                final String message = GLFWErrorCallback.getDescription(description);
                listener.handleError(message, new Exception(message));
            }
        });

        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwDefaultWindowHints();

        final String renderer = settings.getRenderer();

        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

        RENDER_CONFIGS.computeIfAbsent(renderer, s -> () -> {
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_FALSE);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_ANY_PROFILE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
        }).run();

        if (settings.getBoolean("RendererDebug")) {
            glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
        }

        if (settings.isGammaCorrection()) {
            glfwWindowHint(GLFW_SRGB_CAPABLE, GLFW_TRUE);
        }

        glfwWindowHint(GLFW_VISIBLE, GL_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, settings.isResizable() ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_DEPTH_BITS, settings.getDepthBits());
        glfwWindowHint(GLFW_STENCIL_BITS, settings.getStencilBits());
        glfwWindowHint(GLFW_SAMPLES, settings.getSamples());
        glfwWindowHint(GLFW_STEREO, settings.useStereo3D() ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_REFRESH_RATE, settings.getFrequency()<=0?GLFW_DONT_CARE:settings.getFrequency());
        glfwWindowHint(GLFW_COCOA_RETINA_FRAMEBUFFER, settings.isUseRetinaFrameBuffer() ? GLFW_TRUE : GLFW_FALSE);

        if (settings.getBitsPerPixel() == 24) {
            glfwWindowHint(GLFW_RED_BITS, 8);
            glfwWindowHint(GLFW_GREEN_BITS, 8);
            glfwWindowHint(GLFW_BLUE_BITS, 8);
        } else if (settings.getBitsPerPixel() == 16) {
            glfwWindowHint(GLFW_RED_BITS, 5);
            glfwWindowHint(GLFW_GREEN_BITS, 6);
            glfwWindowHint(GLFW_BLUE_BITS, 5);
        }

        glfwWindowHint(GLFW_ALPHA_BITS, settings.getAlphaBits());

        // TODO: Add support for monitor selection
        long monitor = NULL;

        if (settings.isFullscreen()) {
            monitor = glfwGetPrimaryMonitor();
        }

        final GLFWVidMode videoMode = glfwGetVideoMode(glfwGetPrimaryMonitor());

        if (settings.getWidth() <= 0 || settings.getHeight() <= 0) {
            settings.setResolution(videoMode.width(), videoMode.height());
        }

        window = glfwCreateWindow(settings.getWidth(), settings.getHeight(), settings.getTitle(), monitor, NULL);

        if (window == NULL) {
            throw new RuntimeException("Failed to create the GLFW window");
        }

        glfwSetWindowFocusCallback(window, windowFocusCallback = new GLFWWindowFocusCallback() {

            @Override
            public void invoke(final long window, final boolean focus) {
                if (wasActive != focus) {
                    if (!wasActive) {
                        listener.gainFocus();
                        timer.reset();
                    } else {
                        listener.loseFocus();
                    }
                    wasActive = !wasActive;
                }
            }
        });

        if (!settings.isFullscreen()) {
            if (settings.getCenterWindow()) {
                // Center the window
                glfwSetWindowPos(window,
                        (videoMode.width() - settings.getWidth()) / 2,
                        (videoMode.height() - settings.getHeight()) / 2);
            } else {
                glfwSetWindowPos(window,
                        settings.getWindowXPosition(),
                        settings.getWindowYPosition());
            }
        }

        // Make the OpenGL context current
        glfwMakeContextCurrent(window);

        // Enable vsync
        if (settings.isVSync()) {
            glfwSwapInterval(1);
        } else {
            glfwSwapInterval(0);
        }

        setWindowIcon(settings);
        showWindow();

        // Windows resize callback
        glfwSetWindowSizeCallback(window, windowSizeCallback = new GLFWWindowSizeCallback() {

            @Override
            public void invoke(final long window, final int width, final int height) {

                // This is the window size, never to passed to any pixel based stuff!
                // https://www.glfw.org/docs/latest/window_guide.html#window_size
                onWindowSizeChanged(width, height);
            }
        });

        // Add a framebuffer resize callback which delegates to the listener
        glfwSetFramebufferSizeCallback(window, framebufferSizeCallback = new GLFWFramebufferSizeCallback() {

            @Override
            public void invoke(final long window, final int width, final int height) {

                // The window size might be also changed, but the window size callback might not trigger
                // Maybe a bug in graphics drivers or LWJGL 3...? So make sure we emulate the original JME behavior here
                IntBuffer windowWidth = BufferUtils.createIntBuffer(1);
                IntBuffer windowHeight = BufferUtils.createIntBuffer(1);
                glfwGetWindowSize(window, windowWidth, windowHeight);
                onWindowSizeChanged(windowWidth.get(), windowHeight.get());

                // https://www.glfw.org/docs/latest/window_guide.html#window_fbsize
                listener.reshape(width, height);
            }
        });

        allowSwapBuffers = settings.isSwapBuffers();

        // Create OpenCL
        if (settings.isOpenCLSupport()) {
            initOpenCL(window);
        }

        framesAfterContextStarted = 0;
    }

    private void onWindowSizeChanged(final int width, final int height) {
        settings.setResolution(width, height);
    }

    protected void showWindow() {
        glfwShowWindow(window);
    }

    /**
     * Set custom icons to the window of this application.
     *
     * @param settings settings for getting the icons
     */
    protected void setWindowIcon(final AppSettings settings) {

        final Object[] icons = settings.getIcons();
        if (icons == null) return;

        final GLFWImage[] images = imagesToGLFWImages(icons);

        try (final GLFWImage.Buffer iconSet = GLFWImage.malloc(images.length)) {

            for (int i = images.length - 1; i >= 0; i--) {
                final GLFWImage image = images[i];
                iconSet.put(i, image);
            }

            glfwSetWindowIcon(window, iconSet);
        }
    }

    /**
     * Convert array of images to array of {@link GLFWImage}.
     */
    private GLFWImage[] imagesToGLFWImages(final Object[] images) {

        final GLFWImage[] out = new GLFWImage[images.length];

        for (int i = 0; i < images.length; i++) {
            final BufferedImage image = (BufferedImage) images[i];
            out[i] = imageToGLFWImage(image);
        }

        return out;
    }

    /**
     * Convert the {@link BufferedImage} to the {@link GLFWImage}.
     */
    private GLFWImage imageToGLFWImage(BufferedImage image) {

        if (image.getType() != BufferedImage.TYPE_INT_ARGB_PRE) {

            final BufferedImage convertedImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB_PRE);
            final Graphics2D graphics = convertedImage.createGraphics();

            final int targetWidth = image.getWidth();
            final int targetHeight = image.getHeight();

            graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
            graphics.dispose();

            image = convertedImage;
        }

        final ByteBuffer buffer = BufferUtils.createByteBuffer(image.getWidth() * image.getHeight() * 4);

        for (int i = 0; i < image.getHeight(); i++) {
            for (int j = 0; j < image.getWidth(); j++) {
                int colorSpace = image.getRGB(j, i);
                buffer.put((byte) ((colorSpace << 8) >> 24));
                buffer.put((byte) ((colorSpace << 16) >> 24));
                buffer.put((byte) ((colorSpace << 24) >> 24));
                buffer.put((byte) (colorSpace >> 24));
            }
        }

        buffer.flip();

        final GLFWImage result = GLFWImage.create();
        result.set(image.getWidth(), image.getHeight(), buffer);

        return result;
    }

    /**
     * Destroy the context.
     */
    protected void destroyContext() {
        try {
            if (renderer != null) {
                renderer.cleanup();
            }

            if (errorCallback != null) {

                // We need to specifically set this to null as we might set a new callback before we reinit GLFW
                glfwSetErrorCallback(null);

                errorCallback.close();
                errorCallback = null;
            }

            if (windowSizeCallback != null) {
                windowSizeCallback.close();
                windowSizeCallback = null;
            }

            if (framebufferSizeCallback != null) {
                framebufferSizeCallback.close();
                framebufferSizeCallback = null;
            }

            if (windowFocusCallback != null) {
                windowFocusCallback.close();
                windowFocusCallback = null;
            }

            if (window != NULL) {
                glfwDestroyWindow(window);
                window = NULL;
            }

        } catch (final Exception ex) {
            listener.handleError("Failed to destroy context", ex);
        }
    }

    @Override
    public void create(boolean waitFor) {
        if (created.get()) {
            LOGGER.warning("create() called when display is already created!");
            return;
        }

        // NOTE: this is required for Mac OS X!
        mainThread = Thread.currentThread();
        mainThread.setName("jME3 Main");
        run();
    }

    /**
     * Does LWJGL display initialization in the OpenGL thread
     *
     * @return returns {@code true} if the context initialization was successful
     */
    protected boolean initInThread() {
        try {
            if (!JmeSystem.isLowPermissions()) {
                // Enable uncaught exception handler only for current thread
                Thread.currentThread().setUncaughtExceptionHandler((thread, thrown) -> {
                    listener.handleError("Uncaught exception thrown in " + thread.toString(), thrown);
                    if (needClose.get()) {
                        // listener.handleError() has requested the
                        // context to close. Satisfy request.
                        deinitInThread();
                    }
                });
            }

            timer = new NanoTimer();

            // For canvas, this will create a PBuffer,
            // allowing us to query information.
            // When the canvas context becomes available, it will
            // be replaced seamlessly.
            createContext(settings);
            printContextInitInfo();

            created.set(true);
            super.internalCreate();
        } catch (Exception ex) {
            try {
                if (window != NULL) {
                    glfwDestroyWindow(window);
                    window = NULL;
                }
            } catch (Exception ex2) {
                LOGGER.log(Level.WARNING, null, ex2);
            }

            listener.handleError("Failed to create display", ex);
            return false; // if we failed to create display, do not continue
        }

        listener.initialize();
        return true;
    }

    private int framesAfterContextStarted = 0;

    /**
     * execute one iteration of the render loop in the OpenGL thread
     */
    protected void runLoop() {
        // If a restart is required, lets recreate the context.
        if (needRestart.getAndSet(false)) {
            restartContext();
        }

        if (!created.get()) {
            throw new IllegalStateException();
        }

        // Update the frame buffer size from 2nd frame since the initial value
        // of frame buffer size from glfw maybe incorrect when HiDPI display is in use
        if (framesAfterContextStarted < 2) {
            framesAfterContextStarted++;
            if (framesAfterContextStarted == 2) {
                int[] width = new int[1];
                int[] height = new int[1];
                glfwGetFramebufferSize(window, width, height);

                if (settings.getWidth() != width[0] || settings.getHeight() != height[0]) {
                    listener.reshape(width[0], height[0]);
                }
            }
        }

        listener.update();

        // All this does is call swap buffers
        // If the canvas is not active, there's no need to waste time
        // doing that ..
        if (renderable.get()) {
            // calls swap buffers, etc.
            try {
                if (allowSwapBuffers && autoFlush) {
                    glfwSwapBuffers(window);
                }
            } catch (Throwable ex) {
                listener.handleError("Error while swapping buffers", ex);
            }
        }

        // Subclasses just call GLObjectManager clean up objects here
        // it is safe .. for now.
        if (renderer != null) {
            renderer.postFrame();
        }

        if (autoFlush) {
            if (frameRateLimit != getSettings().getFrameRate()) {
                setFrameRateLimit(getSettings().getFrameRate());
            }
        } else if (frameRateLimit != 20) {
            setFrameRateLimit(20);
        }

        Sync.sync(frameRateLimit);

        glfwPollEvents();
    }

    private void restartContext() {
        try {
            destroyContext();
            createContext(settings);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Failed to set display settings!", ex);
        }
        // Reinitialize context flags and such
        reinitContext();

        // We need to reinit the mouse and keyboard input as they are tied to a window handle
        if (keyInput != null && keyInput.isInitialized()) {
            keyInput.resetContext();
        }
        if (mouseInput != null && mouseInput.isInitialized()) {
            mouseInput.resetContext();
        }

        LOGGER.fine("Display restarted.");
    }

    private void setFrameRateLimit(int frameRateLimit) {
        this.frameRateLimit = frameRateLimit;
    }

    /**
     * De-initialize in the OpenGL thread.
     */
    protected void deinitInThread() {
        listener.destroy();

        destroyContext();
        super.internalDestroy();
        glfwTerminate();

        LOGGER.fine("Display destroyed.");
    }

    @Override
    public void run() {
        if (listener == null) {
            throw new IllegalStateException("SystemListener is not set on context!"
                    + "Must set with JmeContext.setSystemListener().");
        }

        LOGGER.log(Level.FINE, "Using LWJGL {0}", Version.getVersion());

        if (!initInThread()) {
            LOGGER.log(Level.SEVERE, "Display initialization failed. Cannot continue.");
            return;
        }

        while (true) {

            runLoop();

            if (needClose.get()) {
                break;
            }

            if (glfwWindowShouldClose(window)) {
                listener.requestClose(false);
            }
        }

        deinitInThread();
    }

    @Override
    public JoyInput getJoyInput() {
        if (joyInput == null) {
            joyInput = new GlfwJoystickInput();
        }
        return joyInput;
    }

    @Override
    public MouseInput getMouseInput() {
        if (mouseInput == null) {
            mouseInput = new GlfwMouseInput(this);
        }
        return mouseInput;
    }

    @Override
    public KeyInput getKeyInput() {
        if (keyInput == null) {
            keyInput = new GlfwKeyInput(this);
        }
        return keyInput;
    }

    @Override
    public TouchInput getTouchInput() {
        return null;
    }

    @Override
    public void setAutoFlushFrames(boolean enabled) {
        this.autoFlush = enabled;
    }

    @Override
    public void destroy(boolean waitFor) {
        needClose.set(true);

        if (mainThread == Thread.currentThread()) {
            // Ignore waitFor.
            return;
        }

        if (waitFor) {
            waitFor(false);
        }
    }

    public long getWindowHandle() {
        return window;
    }

    /**
     * Get the window content scale, for HiDPI support.
     *
     * The content scale is the ratio between the current DPI and the platform's default DPI.
     * This is especially important for text and any UI elements. If the pixel dimensions of
     * your UI scaled by this look appropriate on your machine then it should appear at a
     * reasonable size on other machines regardless of their DPI and scaling settings. This
     * relies on the system DPI and scaling settings being somewhat correct.
     *
     * @param store A vector2f to store the result
     * @return The window content scale
     * @see <a href="https://www.glfw.org/docs/latest/window_guide.html#window_scale">Window content scale</a>
     */
    public Vector2f getWindowContentScale(Vector2f store) {
        float[] xScale = new float[1];
        float[] yScale = new float[1];
        glfwGetWindowContentScale(window, xScale, yScale);

        if (store != null) {
            return store.set(xScale[0], yScale[0]);
        }
        return new Vector2f(xScale[0], yScale[0]);
    }
}
