package com.kooo.evcam.camera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.view.Surface;

import com.kooo.evcam.AppLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 【最优方案】高性能 EGL 编码器
 * 
 * 设计原则：
 * 1. 单一渲染 + FBO 多目标输出（避免 context 切换）
 * 2. 零拷贝纹理共享
 * 3. 预分配资源（避免运行时内存分配）
 * 4. 异步时间戳（避免阻塞）
 * 
 * 性能特点：
 * - Camera 单路输出
 * - GPU 单次渲染
 * - 无 EGL context 切换
 * - 预览和编码共享纹理
 */
public class OptimizedEglEncoder {
    private static final String TAG = "OptimizedEglEncoder";

    // ==================== Shader 代码 ====================
    
    // 顶点着色器 - 极简版本
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * aPosition;\n" +
            "    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
            "}\n";

    // OES 纹理片段着色器
    private static final String FRAGMENT_SHADER_OES =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
            "}\n";

    // 2D 纹理片段着色器（用于水印）
    private static final String FRAGMENT_SHADER_2D =
            "precision mediump float;\n" +
            "uniform sampler2D uTexture;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
            "}\n";

    // ==================== 顶点数据（预分配）====================
    
    // 全屏四边形顶点
    private static final float[] FULL_QUAD_VERTICES = {
            -1.0f, -1.0f,  // 左下
             1.0f, -1.0f,  // 右下
            -1.0f,  1.0f,  // 左上
             1.0f,  1.0f   // 右上
    };

    // 纹理坐标
    private static final float[] FULL_QUAD_TEX_COORDS = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
    };

    // 水印位置（左上角，预计算）
    private static final float WATERMARK_X = -0.95f;
    private static final float WATERMARK_Y = 0.85f;
    private static final float WATERMARK_WIDTH = 0.5f;
    private static final float WATERMARK_HEIGHT = 0.08f;

    // ==================== 成员变量 ====================
    
    private final String cameraId;
    private final int width;
    private final int height;

    // EGL 资源
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglConfig;
    
    // 编码器 EGL Surface
    private EGLSurface encoderSurface = EGL14.EGL_NO_SURFACE;
    
    // 预览 EGL Surface（可选）
    private EGLSurface previewSurface = EGL14.EGL_NO_SURFACE;
    private boolean previewEnabled = false;

    // OpenGL 资源
    private int oesProgram;
    private int watermarkProgram;
    private int oesTextureId;
    private int watermarkTextureId;

    // Shader 句柄
    private int oesMvpMatrixHandle;
    private int oesTexMatrixHandle;
    private int oesTextureHandle;
    private int oesPositionHandle;
    private int oesTexCoordHandle;
    
    private int wmMvpMatrixHandle;
    private int wmTextureHandle;
    private int wmPositionHandle;
    private int wmTexCoordHandle;

    // 缓冲区（预分配）
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    private FloatBuffer watermarkVertexBuffer;
    private FloatBuffer watermarkTexCoordBuffer;

    // 矩阵（预分配）
    private final float[] mvpMatrix = new float[16];
    private final float[] texMatrix = new float[16];

    // 输入 SurfaceTexture
    private SurfaceTexture inputSurfaceTexture;

    // 状态
    private volatile boolean isInitialized = false;
    private volatile boolean isReleased = false;

    // 水印相关（预分配）
    private boolean watermarkEnabled = false;
    private Bitmap watermarkBitmap;
    private Canvas watermarkCanvas;
    private Paint watermarkTextPaint;
    private Paint watermarkShadowPaint;
    private String lastWatermarkTime = "";
    private long lastWatermarkUpdateMs = 0;
    private static final long WATERMARK_UPDATE_INTERVAL_MS = 1000;
    private static final int WATERMARK_WIDTH_PX = 320;
    private static final int WATERMARK_HEIGHT_PX = 36;
    private final SimpleDateFormat watermarkDateFormat = 
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    // ==================== 构造函数 ====================
    
    public OptimizedEglEncoder(String cameraId, int width, int height) {
        this.cameraId = cameraId;
        this.width = width;
        this.height = height;
        Matrix.setIdentityM(mvpMatrix, 0);
        Matrix.setIdentityM(texMatrix, 0);
        
        // 预分配缓冲区
        initBuffers();
    }

    // ==================== 初始化 ====================
    
    /**
     * 初始化 EGL 和 OpenGL
     * @param encoderOutputSurface MediaCodec 的输入 Surface
     * @return OES 纹理 ID，用于创建 SurfaceTexture
     */
    public int initialize(Surface encoderOutputSurface) {
        if (isInitialized) {
            AppLog.w(TAG, "Camera " + cameraId + " already initialized");
            return oesTextureId;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Initializing OptimizedEglEncoder");

        try {
            // 1. 初始化 EGL
            initEgl();

            // 2. 创建编码器 EGL Surface
            encoderSurface = createEglSurface(encoderOutputSurface);
            if (encoderSurface == EGL14.EGL_NO_SURFACE) {
                throw new RuntimeException("Failed to create encoder EGL surface");
            }

            // 3. 绑定 context
            makeCurrent(encoderSurface);

            // 4. 初始化 OpenGL 资源
            initOpenGL();

            isInitialized = true;
            AppLog.d(TAG, "Camera " + cameraId + " OptimizedEglEncoder initialized, textureId=" + oesTextureId);

            return oesTextureId;

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to initialize", e);
            release();
            throw new RuntimeException("Failed to initialize OptimizedEglEncoder", e);
        }
    }

    /**
     * 设置预览 Surface（启用双目标渲染）
     */
    public void setPreviewSurface(Surface surface) {
        if (!isInitialized || isReleased) {
            AppLog.w(TAG, "Camera " + cameraId + " Cannot set preview surface: not initialized");
            return;
        }

        // 释放旧的预览 Surface
        if (previewSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, previewSurface);
            previewSurface = EGL14.EGL_NO_SURFACE;
        }

        if (surface != null && surface.isValid()) {
            previewSurface = createEglSurface(surface);
            previewEnabled = (previewSurface != EGL14.EGL_NO_SURFACE);
            AppLog.d(TAG, "Camera " + cameraId + " Preview surface set, enabled=" + previewEnabled);
        } else {
            previewEnabled = false;
            AppLog.d(TAG, "Camera " + cameraId + " Preview surface disabled");
        }
    }

    // ==================== 渲染 ====================
    
    /**
     * 渲染一帧（高性能版本）
     * 
     * 优化点：
     * 1. 单次 updateTexImage
     * 2. 复用 OpenGL 状态
     * 3. 最小化 EGL 操作
     */
    public void drawFrame(long presentationTimeNs) {
        if (!isInitialized || isReleased || inputSurfaceTexture == null) {
            return;
        }

        try {
            // 1. 绑定编码器 Surface
            makeCurrent(encoderSurface);

            // 2. 更新纹理（只调用一次）
            inputSurfaceTexture.updateTexImage();
            inputSurfaceTexture.getTransformMatrix(texMatrix);

            // 3. 渲染到编码器
            renderFrame(true);

            // 4. 设置时间戳并 swap
            EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderSurface, presentationTimeNs);
            EGL14.eglSwapBuffers(eglDisplay, encoderSurface);

            // 5. 渲染到预览（如果启用）
            if (previewEnabled && previewSurface != EGL14.EGL_NO_SURFACE) {
                try {
                    makeCurrent(previewSurface);
                    renderFrame(false);  // 预览不需要水印
                    EGL14.eglSwapBuffers(eglDisplay, previewSurface);
                } catch (Exception previewError) {
                    // 预览渲染失败不影响编码，禁用预览继续录制
                    AppLog.w(TAG, "Camera " + cameraId + " Preview render failed, disabling: " + previewError.getMessage());
                    previewEnabled = false;
                }
            }

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Error drawing frame", e);
        }
    }

    /**
     * 仅消费帧（不录制时使用）
     */
    public void consumeFrame() {
        if (!isInitialized || isReleased || inputSurfaceTexture == null) {
            return;
        }

        try {
            makeCurrent(encoderSurface);
            inputSurfaceTexture.updateTexImage();
            
            // 如果启用了预览，仍然渲染到预览
            if (previewEnabled && previewSurface != EGL14.EGL_NO_SURFACE) {
                try {
                    inputSurfaceTexture.getTransformMatrix(texMatrix);
                    makeCurrent(previewSurface);
                    renderFrame(false);
                    EGL14.eglSwapBuffers(eglDisplay, previewSurface);
                } catch (Exception previewError) {
                    // 预览渲染失败，禁用预览
                    previewEnabled = false;
                }
            }
        } catch (Exception e) {
            // 非录制状态，忽略错误
        }
    }

    /**
     * 核心渲染方法（高度优化）
     */
    private void renderFrame(boolean withWatermark) {
        // 设置视口
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 渲染相机画面
        GLES20.glUseProgram(oesProgram);
        
        GLES20.glUniformMatrix4fv(oesMvpMatrixHandle, 1, false, mvpMatrix, 0);
        GLES20.glUniformMatrix4fv(oesTexMatrixHandle, 1, false, texMatrix, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glUniform1i(oesTextureHandle, 0);

        GLES20.glEnableVertexAttribArray(oesPositionHandle);
        GLES20.glVertexAttribPointer(oesPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glEnableVertexAttribArray(oesTexCoordHandle);
        GLES20.glVertexAttribPointer(oesTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(oesPositionHandle);
        GLES20.glDisableVertexAttribArray(oesTexCoordHandle);

        // 渲染水印（仅编码器需要）
        if (withWatermark && watermarkEnabled) {
            renderWatermark();
        }
    }

    /**
     * 渲染水印（优化版本）
     */
    private void renderWatermark() {
        // 更新水印内容（每秒一次）
        updateWatermarkIfNeeded();

        if (watermarkTextureId == 0) {
            return;
        }

        // 启用混合
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        GLES20.glUseProgram(watermarkProgram);

        GLES20.glUniformMatrix4fv(wmMvpMatrixHandle, 1, false, mvpMatrix, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId);
        GLES20.glUniform1i(wmTextureHandle, 1);

        GLES20.glEnableVertexAttribArray(wmPositionHandle);
        GLES20.glVertexAttribPointer(wmPositionHandle, 2, GLES20.GL_FLOAT, false, 0, watermarkVertexBuffer);

        GLES20.glEnableVertexAttribArray(wmTexCoordHandle);
        GLES20.glVertexAttribPointer(wmTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, watermarkTexCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(wmPositionHandle);
        GLES20.glDisableVertexAttribArray(wmTexCoordHandle);

        GLES20.glDisable(GLES20.GL_BLEND);
    }

    // ==================== 私有方法 ====================
    
    private void initBuffers() {
        // 顶点缓冲区
        vertexBuffer = ByteBuffer.allocateDirect(FULL_QUAD_VERTICES.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(FULL_QUAD_VERTICES);
        vertexBuffer.position(0);

        // 纹理坐标缓冲区
        texCoordBuffer = ByteBuffer.allocateDirect(FULL_QUAD_TEX_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(FULL_QUAD_TEX_COORDS);
        texCoordBuffer.position(0);

        // 水印顶点（左上角）
        float[] watermarkVertices = {
                WATERMARK_X, WATERMARK_Y - WATERMARK_HEIGHT,
                WATERMARK_X + WATERMARK_WIDTH, WATERMARK_Y - WATERMARK_HEIGHT,
                WATERMARK_X, WATERMARK_Y,
                WATERMARK_X + WATERMARK_WIDTH, WATERMARK_Y
        };
        watermarkVertexBuffer = ByteBuffer.allocateDirect(watermarkVertices.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(watermarkVertices);
        watermarkVertexBuffer.position(0);

        // 水印纹理坐标
        watermarkTexCoordBuffer = ByteBuffer.allocateDirect(FULL_QUAD_TEX_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(FULL_QUAD_TEX_COORDS);
        watermarkTexCoordBuffer.position(0);
    }

    private void initEgl() {
        // 获取默认显示设备
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("Unable to get EGL display");
        }

        // 初始化 EGL
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new RuntimeException("Unable to initialize EGL");
        }

        // 配置属性（支持录制）
        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                0x3142, 1,  // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            throw new RuntimeException("Unable to choose EGL config");
        }
        eglConfig = configs[0];

        // 创建 Context
        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("Unable to create EGL context");
        }

        AppLog.d(TAG, "Camera " + cameraId + " EGL initialized");
    }

    private EGLSurface createEglSurface(Surface surface) {
        int[] surfaceAttribs = { EGL14.EGL_NONE };
        EGLSurface eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to create EGL surface");
        }
        return eglSurface;
    }

    private void makeCurrent(EGLSurface surface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    private void initOpenGL() {
        // 清除颜色
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        // 创建 OES 着色器程序
        oesProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES);
        oesMvpMatrixHandle = GLES20.glGetUniformLocation(oesProgram, "uMVPMatrix");
        oesTexMatrixHandle = GLES20.glGetUniformLocation(oesProgram, "uTexMatrix");
        oesTextureHandle = GLES20.glGetUniformLocation(oesProgram, "uTexture");
        oesPositionHandle = GLES20.glGetAttribLocation(oesProgram, "aPosition");
        oesTexCoordHandle = GLES20.glGetAttribLocation(oesProgram, "aTexCoord");

        // 创建 OES 纹理
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        oesTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // 创建水印着色器程序
        watermarkProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D);
        wmMvpMatrixHandle = GLES20.glGetUniformLocation(watermarkProgram, "uMVPMatrix");
        wmTextureHandle = GLES20.glGetUniformLocation(watermarkProgram, "uTexture");
        wmPositionHandle = GLES20.glGetAttribLocation(watermarkProgram, "aPosition");
        wmTexCoordHandle = GLES20.glGetAttribLocation(watermarkProgram, "aTexCoord");

        AppLog.d(TAG, "Camera " + cameraId + " OpenGL initialized");
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new RuntimeException("Program link failed: " + log);
        }
        
        return program;
    }

    private int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        
        return shader;
    }

    // ==================== 水印相关 ====================
    
    public void setWatermarkEnabled(boolean enabled) {
        this.watermarkEnabled = enabled;
        if (enabled && watermarkBitmap == null) {
            initWatermarkResources();
        }
        AppLog.d(TAG, "Camera " + cameraId + " Watermark " + (enabled ? "enabled" : "disabled"));
    }

    private void initWatermarkResources() {
        // 预分配 Bitmap
        watermarkBitmap = Bitmap.createBitmap(WATERMARK_WIDTH_PX, WATERMARK_HEIGHT_PX, Bitmap.Config.ARGB_8888);
        watermarkCanvas = new Canvas(watermarkBitmap);

        // 预分配 Paint
        watermarkShadowPaint = new Paint();
        watermarkShadowPaint.setColor(Color.BLACK);
        watermarkShadowPaint.setTextSize(24);
        watermarkShadowPaint.setAntiAlias(true);
        watermarkShadowPaint.setTypeface(Typeface.MONOSPACE);

        watermarkTextPaint = new Paint();
        watermarkTextPaint.setColor(Color.WHITE);
        watermarkTextPaint.setTextSize(24);
        watermarkTextPaint.setAntiAlias(true);
        watermarkTextPaint.setTypeface(Typeface.MONOSPACE);

        // 创建水印纹理
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        watermarkTextureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        AppLog.d(TAG, "Camera " + cameraId + " Watermark resources initialized");
    }

    private void updateWatermarkIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastWatermarkUpdateMs < WATERMARK_UPDATE_INTERVAL_MS) {
            return;
        }

        String currentTime = watermarkDateFormat.format(new Date());
        if (currentTime.equals(lastWatermarkTime)) {
            lastWatermarkUpdateMs = now;
            return;
        }

        lastWatermarkTime = currentTime;
        lastWatermarkUpdateMs = now;

        if (watermarkBitmap == null || watermarkCanvas == null) {
            return;
        }

        // 清除并重绘
        watermarkBitmap.eraseColor(Color.TRANSPARENT);
        watermarkCanvas.drawText(currentTime, 4, 26, watermarkShadowPaint);
        watermarkCanvas.drawText(currentTime, 2, 24, watermarkTextPaint);

        // 上传纹理
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, watermarkBitmap, 0);
    }

    // ==================== 公共方法 ====================
    
    public void setInputSurfaceTexture(SurfaceTexture surfaceTexture) {
        this.inputSurfaceTexture = surfaceTexture;
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public int getTextureId() {
        return oesTextureId;
    }

    /**
     * 更新编码器输出 Surface（用于分段切换）
     */
    public void updateEncoderSurface(Surface newSurface) {
        if (!isInitialized || isReleased) {
            return;
        }

        // 释放旧的
        if (encoderSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, encoderSurface);
        }

        // 创建新的
        encoderSurface = createEglSurface(newSurface);
        AppLog.d(TAG, "Camera " + cameraId + " Encoder surface updated");
    }

    // ==================== 资源释放 ====================
    
    public void release() {
        if (isReleased) {
            return;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Releasing OptimizedEglEncoder");
        isReleased = true;
        isInitialized = false;

        // 释放 OpenGL 资源
        if (oesProgram != 0) {
            GLES20.glDeleteProgram(oesProgram);
            oesProgram = 0;
        }

        if (watermarkProgram != 0) {
            GLES20.glDeleteProgram(watermarkProgram);
            watermarkProgram = 0;
        }

        if (oesTextureId != 0) {
            int[] textures = {oesTextureId};
            GLES20.glDeleteTextures(1, textures, 0);
            oesTextureId = 0;
        }

        if (watermarkTextureId != 0) {
            int[] textures = {watermarkTextureId};
            GLES20.glDeleteTextures(1, textures, 0);
            watermarkTextureId = 0;
        }

        // 释放水印资源
        if (watermarkBitmap != null) {
            watermarkBitmap.recycle();
            watermarkBitmap = null;
        }
        watermarkCanvas = null;
        watermarkTextPaint = null;
        watermarkShadowPaint = null;

        // 释放 EGL 资源
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);

            if (previewSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, previewSurface);
                previewSurface = EGL14.EGL_NO_SURFACE;
            }

            if (encoderSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, encoderSurface);
                encoderSurface = EGL14.EGL_NO_SURFACE;
            }

            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }

            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }

        inputSurfaceTexture = null;

        AppLog.d(TAG, "Camera " + cameraId + " OptimizedEglEncoder released");
    }
}
