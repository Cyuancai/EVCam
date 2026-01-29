package com.kooo.evcam.camera;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.kooo.evcam.AppLog;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 【最优方案】高性能视频录制器
 * 
 * 设计原则：
 * 1. 双线程架构（渲染线程 + 写入线程）
 * 2. 生产者-消费者模式（BlockingQueue）
 * 3. 状态机管理（避免混乱的状态同步）
 * 4. 预分配资源（避免 GC）
 * 
 * 线程模型：
 * - renderThread: 处理 Camera 帧、EGL 渲染、编码器输入
 * - writerThread: 处理编码器输出、文件写入
 * 
 * 性能特点：
 * - 渲染和写入完全解耦
 * - 无锁队列通信
 * - 最小化主线程阻塞
 */
public class OptimizedCodecRecorder {
    private static final String TAG = "OptimizedCodecRecorder";

    // ==================== 编码参数 ====================
    
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int I_FRAME_INTERVAL = 1;
    private static final int QUEUE_SIZE = 10;  // 编码数据队列大小
    
    private int frameRate = 30;
    private int bitRate = 3_000_000;

    // ==================== 状态机 ====================
    
    private enum State {
        IDLE,           // 空闲
        PREPARING,      // 准备中
        READY,          // 就绪（可以开始录制）
        RECORDING,      // 录制中
        STOPPING,       // 停止中
        ERROR           // 错误
    }
    
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);

    // ==================== 基本信息 ====================
    
    private final String cameraId;
    private final int width;
    private final int height;

    // ==================== 编码器 ====================
    
    private MediaCodec encoder;
    private Surface encoderInputSurface;
    private MediaCodec.BufferInfo bufferInfo;

    // ==================== Muxer ====================
    
    private MediaMuxer muxer;
    private int videoTrackIndex = -1;
    private volatile boolean muxerStarted = false;
    private MediaFormat savedOutputFormat = null;  // 保存编码器输出格式，用于分段切换

    // ==================== EGL 渲染器 ====================
    
    private OptimizedEglEncoder eglEncoder;
    private SurfaceTexture inputSurfaceTexture;
    private Surface cachedRecordSurface;

    // ==================== 线程 ====================
    
    private HandlerThread renderThread;
    private Handler renderHandler;
    private HandlerThread writerThread;
    private Handler writerHandler;

    // ==================== 编码数据队列 ====================
    
    private static class EncodedFrame {
        ByteBuffer data;
        MediaCodec.BufferInfo info;
        int bufferIndex;
        
        EncodedFrame(int capacity) {
            data = ByteBuffer.allocateDirect(capacity);
            info = new MediaCodec.BufferInfo();
        }
        
        void reset() {
            data.clear();
            info.set(0, 0, 0, 0);
            bufferIndex = -1;
        }
    }
    
    private BlockingQueue<EncodedFrame> encodedFrameQueue;
    private BlockingQueue<EncodedFrame> framePool;  // 帧对象池

    // ==================== 录制参数 ====================
    
    private String currentFilePath;
    private String saveDirectory;
    private String cameraPosition;
    private long segmentDurationMs = 60_000;
    private int segmentIndex = 0;
    
    // 时间戳
    private long firstFrameTimestampNs = -1;
    private long segmentStartTimeNs = 0;
    private long recordedFrameCount = 0;

    // ==================== 分段定时器 ====================
    
    private Runnable segmentRunnable;

    // ==================== 回调 ====================
    
    private RecordCallback callback;
    private boolean watermarkEnabled = false;
    private Surface previewSurface;  // 预览 Surface

    // ==================== 构造函数 ====================
    
    public OptimizedCodecRecorder(String cameraId, int width, int height) {
        this.cameraId = cameraId;
        this.width = width;
        this.height = height;
        
        // 初始化帧队列和对象池
        encodedFrameQueue = new ArrayBlockingQueue<>(QUEUE_SIZE);
        framePool = new ArrayBlockingQueue<>(QUEUE_SIZE);
        
        // 预分配帧对象（避免运行时 GC）
        int frameSize = width * height * 3 / 2;  // 估算最大帧大小
        for (int i = 0; i < QUEUE_SIZE; i++) {
            framePool.offer(new EncodedFrame(frameSize));
        }
    }

    // ==================== 配置方法 ====================
    
    public void setCallback(RecordCallback callback) {
        this.callback = callback;
    }
    
    public void setFrameRate(int fps) {
        this.frameRate = fps;
    }
    
    public void setBitRate(int bitrate) {
        this.bitRate = bitrate;
    }
    
    public void setSegmentDuration(long durationMs) {
        this.segmentDurationMs = durationMs;
    }
    
    public void setWatermarkEnabled(boolean enabled) {
        this.watermarkEnabled = enabled;
        if (eglEncoder != null) {
            eglEncoder.setWatermarkEnabled(enabled);
        }
    }

    /**
     * 设置预览 Surface（启用双目标渲染）
     */
    public void setPreviewSurface(Surface surface) {
        this.previewSurface = surface;
        if (eglEncoder != null) {
            eglEncoder.setPreviewSurface(surface);
        }
    }

    // ==================== 准备录制 ====================
    
    public SurfaceTexture prepareRecording(String filePath) {
        if (!state.compareAndSet(State.IDLE, State.PREPARING)) {
            AppLog.w(TAG, "Camera " + cameraId + " Cannot prepare: state=" + state.get());
            return null;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Preparing recording: " + width + "x" + height);

        try {
            // 保存参数
            this.currentFilePath = filePath;
            this.segmentIndex = 0;
            this.recordedFrameCount = 0;
            this.firstFrameTimestampNs = -1;

            // 解析路径
            File file = new File(filePath);
            this.saveDirectory = file.getParent();
            String fileName = file.getName();
            int idx = fileName.lastIndexOf('_');
            this.cameraPosition = (idx > 0 && fileName.endsWith(".mp4"))
                    ? fileName.substring(idx + 1, fileName.length() - 4)
                    : "unknown";

            // 1. 创建渲染线程
            renderThread = new HandlerThread("Render-" + cameraId);
            renderThread.start();
            renderHandler = new Handler(renderThread.getLooper());

            // 2. 创建写入线程
            writerThread = new HandlerThread("Writer-" + cameraId);
            writerThread.start();
            writerHandler = new Handler(writerThread.getLooper());

            // 3. 在渲染线程上初始化编码器和 EGL
            final SurfaceTexture[] result = {null};
            final Exception[] error = {null};
            
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            
            renderHandler.post(() -> {
                try {
                    // 创建编码器
                    createEncoder();
                    
                    // 创建 Muxer
                    createMuxer(filePath);
                    
                    // 创建 EGL 渲染器
                    eglEncoder = new OptimizedEglEncoder(cameraId, width, height);
                    int textureId = eglEncoder.initialize(encoderInputSurface);
                    
                    // 设置预览 Surface
                    if (previewSurface != null) {
                        eglEncoder.setPreviewSurface(previewSurface);
                    }
                    
                    // 设置水印
                    if (watermarkEnabled) {
                        eglEncoder.setWatermarkEnabled(true);
                    }
                    
                    // 创建 SurfaceTexture
                    inputSurfaceTexture = new SurfaceTexture(textureId);
                    inputSurfaceTexture.setDefaultBufferSize(width, height);
                    
                    // 设置帧回调
                    inputSurfaceTexture.setOnFrameAvailableListener(this::onFrameAvailable, renderHandler);
                    
                    // 设置 EGL 输入
                    eglEncoder.setInputSurfaceTexture(inputSurfaceTexture);
                    
                    result[0] = inputSurfaceTexture;
                    
                } catch (Exception e) {
                    error[0] = e;
                } finally {
                    latch.countDown();
                }
            });

            // 等待初始化完成
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("Timeout waiting for initialization");
            }
            
            if (error[0] != null) {
                throw error[0];
            }

            state.set(State.READY);
            AppLog.d(TAG, "Camera " + cameraId + " Recording prepared");
            
            return result[0];

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to prepare recording", e);
            state.set(State.ERROR);
            release();
            return null;
        }
    }

    // ==================== 开始/停止录制 ====================
    
    public boolean startRecording() {
        if (!state.compareAndSet(State.READY, State.RECORDING)) {
            AppLog.w(TAG, "Camera " + cameraId + " Cannot start: state=" + state.get());
            return false;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Starting recording");
        
        segmentStartTimeNs = System.nanoTime();
        recordedFrameCount = 0;

        // 启动写入循环
        startWriterLoop();

        // 启动分段定时器
        scheduleNextSegment();

        if (callback != null) {
            callback.onRecordStart(cameraId);
        }

        return true;
    }

    public void stopRecording() {
        State currentState = state.get();
        if (currentState != State.RECORDING && currentState != State.READY) {
            AppLog.w(TAG, "Camera " + cameraId + " Cannot stop: state=" + currentState);
            return;
        }

        state.set(State.STOPPING);
        AppLog.d(TAG, "Camera " + cameraId + " Stopping recording");

        // 取消分段定时器
        if (segmentRunnable != null && renderHandler != null) {
            renderHandler.removeCallbacks(segmentRunnable);
            segmentRunnable = null;
        }

        // 等待编码器排空
        if (encoder != null) {
            try {
                encoder.signalEndOfInputStream();
                // 给写入线程时间处理剩余数据
                Thread.sleep(100);
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " Error signaling end of stream", e);
            }
        }

        // 停止 Muxer
        stopMuxer();

        state.set(State.READY);

        if (callback != null) {
            callback.onRecordStop(cameraId);
        }

        AppLog.d(TAG, "Camera " + cameraId + " Recording stopped, frames: " + recordedFrameCount);
    }

    // ==================== 帧处理 ====================
    
    private void onFrameAvailable(SurfaceTexture surfaceTexture) {
        State currentState = state.get();
        
        if (currentState == State.RECORDING) {
            // 录制中：渲染并编码
            processFrame(surfaceTexture);
        } else if (currentState == State.READY || currentState == State.STOPPING) {
            // 非录制状态：只消费帧（保持预览）
            if (eglEncoder != null) {
                eglEncoder.consumeFrame();
            }
        }
    }

    private void processFrame(SurfaceTexture surfaceTexture) {
        try {
            // 获取时间戳
            long timestampNs = surfaceTexture.getTimestamp();
            if (firstFrameTimestampNs < 0) {
                firstFrameTimestampNs = timestampNs;
            }
            long relativeTimestampNs = timestampNs - firstFrameTimestampNs;

            // 渲染帧
            if (eglEncoder != null && eglEncoder.isInitialized()) {
                eglEncoder.drawFrame(relativeTimestampNs);
                recordedFrameCount++;
            }

            // 从编码器获取输出（非阻塞）
            drainEncoderNonBlocking();

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Error processing frame", e);
        }
    }

    // ==================== 编码器处理 ====================
    
    private void drainEncoderNonBlocking() {
        if (encoder == null || bufferInfo == null) {
            return;
        }

        // 非阻塞获取输出
        while (true) {
            int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0);
            
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                handleFormatChanged();
            } else if (outputIndex >= 0) {
                handleEncodedData(outputIndex);
            }
        }
    }

    private void handleFormatChanged() {
        if (muxerStarted) {
            AppLog.w(TAG, "Camera " + cameraId + " Format changed twice");
            return;
        }
        
        MediaFormat format = encoder.getOutputFormat();
        
        // 保存格式，用于分段切换时初始化新 Muxer
        savedOutputFormat = format;
        
        initMuxerWithFormat(format);
    }
    
    /**
     * 使用指定格式初始化 Muxer
     */
    private void initMuxerWithFormat(MediaFormat format) {
        if (muxer == null || muxerStarted) {
            return;
        }
        
        videoTrackIndex = muxer.addTrack(format);
        muxer.start();
        muxerStarted = true;
        
        AppLog.d(TAG, "Camera " + cameraId + " Muxer started, track=" + videoTrackIndex);
    }

    private void handleEncodedData(int outputIndex) {
        ByteBuffer encodedData = encoder.getOutputBuffer(outputIndex);
        
        if (encodedData == null || (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            encoder.releaseOutputBuffer(outputIndex, false);
            return;
        }

        if (bufferInfo.size > 0 && muxerStarted) {
            // 计算 PTS
            long ptsUs = (System.nanoTime() - segmentStartTimeNs) / 1000;
            bufferInfo.presentationTimeUs = ptsUs;

            // 尝试获取帧对象
            EncodedFrame frame = framePool.poll();
            
            // 检查帧大小是否超过预分配缓冲区
            boolean frameTooLarge = (frame != null && bufferInfo.size > frame.data.capacity());
            
            if (frame == null || frameTooLarge) {
                // 队列满了或帧太大，直接写入（降级处理）
                if (frameTooLarge && frame != null) {
                    framePool.offer(frame);  // 归还帧对象
                }
                writeSampleDataDirect(encodedData, bufferInfo);
            } else {
                // 复制数据到帧对象
                frame.reset();
                encodedData.position(bufferInfo.offset);
                encodedData.limit(bufferInfo.offset + bufferInfo.size);
                frame.data.put(encodedData);
                frame.data.flip();
                frame.info.set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags);
                frame.bufferIndex = outputIndex;

                // 放入队列（非阻塞）
                if (!encodedFrameQueue.offer(frame)) {
                    // 队列满了，回收帧对象并直接写入
                    framePool.offer(frame);
                    writeSampleDataDirect(encodedData, bufferInfo);
                }
            }
        }

        encoder.releaseOutputBuffer(outputIndex, false);
    }

    private void writeSampleDataDirect(ByteBuffer data, MediaCodec.BufferInfo info) {
        try {
            data.position(info.offset);
            data.limit(info.offset + info.size);
            muxer.writeSampleData(videoTrackIndex, data, info);
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Error writing sample data", e);
        }
    }

    // ==================== 写入线程 ====================
    
    private volatile boolean writerRunning = false;
    
    private void startWriterLoop() {
        writerRunning = true;
        writerHandler.post(this::writerLoop);
    }

    private void writerLoop() {
        while (writerRunning || !encodedFrameQueue.isEmpty()) {
            try {
                EncodedFrame frame = encodedFrameQueue.poll(10, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    writeFrame(frame);
                    framePool.offer(frame);  // 回收帧对象
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void writeFrame(EncodedFrame frame) {
        if (!muxerStarted || muxer == null) {
            return;
        }

        try {
            muxer.writeSampleData(videoTrackIndex, frame.data, frame.info);
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Error writing frame", e);
        }
    }

    // ==================== 分段处理 ====================
    
    private void scheduleNextSegment() {
        segmentRunnable = () -> {
            if (state.get() == State.RECORDING) {
                switchToNextSegment();
            }
        };
        renderHandler.postDelayed(segmentRunnable, segmentDurationMs);
    }

    private void switchToNextSegment() {
        AppLog.d(TAG, "Camera " + cameraId + " Switching to next segment");

        try {
            // 保存旧分段路径（用于回调）
            String completedPath = currentFilePath;
            
            // 停止当前 Muxer（但不停止 writerLoop）
            stopMuxerOnly();

            // 准备下一段
            segmentIndex++;
            String nextPath = generateSegmentPath();
            currentFilePath = nextPath;

            // 重置时间戳
            segmentStartTimeNs = System.nanoTime();

            // 创建新 Muxer（会自动在下一帧触发 addTrack 和 start）
            createMuxer(nextPath);

            // 调度下一次分段
            scheduleNextSegment();

            if (callback != null) {
                // 回调传递完成的旧分段路径
                callback.onSegmentSwitch(cameraId, segmentIndex, completedPath);
            }

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to switch segment", e);
        }
    }

    private String generateSegmentPath() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return new File(saveDirectory, timestamp + "_" + cameraPosition + ".mp4").getAbsolutePath();
    }

    // ==================== 编码器/Muxer 创建 ====================
    
    private void createEncoder() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

        // 低延迟优化
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }

        // 使用 Baseline Profile
        try {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        } catch (Exception ignored) {}

        encoder = MediaCodec.createEncoderByType(MIME_TYPE);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderInputSurface = encoder.createInputSurface();
        encoder.start();
        bufferInfo = new MediaCodec.BufferInfo();

        AppLog.d(TAG, "Camera " + cameraId + " Encoder created: " + width + "x" + height + 
                " @ " + frameRate + "fps, " + (bitRate / 1000) + "Kbps");
    }

    private void createMuxer(String filePath) throws Exception {
        muxer = new MediaMuxer(filePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        videoTrackIndex = -1;
        muxerStarted = false;
        AppLog.d(TAG, "Camera " + cameraId + " Muxer created: " + filePath);
        
        // 如果已有保存的格式（分段切换场景），直接初始化
        if (savedOutputFormat != null) {
            initMuxerWithFormat(savedOutputFormat);
            AppLog.d(TAG, "Camera " + cameraId + " Muxer initialized with saved format");
        }
    }

    /**
     * 完全停止 Muxer（包括 writerLoop）- 用于录制结束
     */
    private void stopMuxer() {
        writerRunning = false;
        
        // 等待写入线程处理完剩余数据
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {}
        
        stopMuxerOnly();
    }
    
    /**
     * 仅停止 Muxer（不停止 writerLoop）- 用于分段切换
     */
    private void stopMuxerOnly() {
        if (muxer != null) {
            try {
                if (muxerStarted) {
                    muxer.stop();
                }
                muxer.release();
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " Error stopping muxer", e);
            }
            muxer = null;
            muxerStarted = false;
        }
    }

    // ==================== 公共方法 ====================
    
    public Surface getRecordSurface() {
        if (inputSurfaceTexture == null) {
            return null;
        }
        if (cachedRecordSurface == null || !cachedRecordSurface.isValid()) {
            if (cachedRecordSurface != null) {
                cachedRecordSurface.release();
            }
            cachedRecordSurface = new Surface(inputSurfaceTexture);
        }
        return cachedRecordSurface;
    }

    public boolean isRecording() {
        return state.get() == State.RECORDING;
    }

    public String getCurrentFilePath() {
        return currentFilePath;
    }

    // ==================== 资源释放 ====================
    
    public void release() {
        AppLog.d(TAG, "Camera " + cameraId + " Releasing OptimizedCodecRecorder");

        state.set(State.IDLE);
        writerRunning = false;

        // 清理渲染线程任务
        if (renderHandler != null) {
            renderHandler.removeCallbacksAndMessages(null);
        }

        // 释放 EGL
        if (eglEncoder != null) {
            eglEncoder.release();
            eglEncoder = null;
        }

        // 释放 Surface
        if (cachedRecordSurface != null) {
            cachedRecordSurface.release();
            cachedRecordSurface = null;
        }

        // 释放 SurfaceTexture
        if (inputSurfaceTexture != null) {
            inputSurfaceTexture.release();
            inputSurfaceTexture = null;
        }

        // 释放编码器
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (Exception ignored) {}
            encoder.release();
            encoder = null;
        }

        if (encoderInputSurface != null) {
            encoderInputSurface.release();
            encoderInputSurface = null;
        }

        // 释放 Muxer
        stopMuxer();

        // 停止线程
        if (renderThread != null) {
            renderThread.quitSafely();
            try {
                renderThread.join(1000);
            } catch (InterruptedException ignored) {}
            renderThread = null;
            renderHandler = null;
        }

        if (writerThread != null) {
            writerThread.quitSafely();
            try {
                writerThread.join(1000);
            } catch (InterruptedException ignored) {}
            writerThread = null;
            writerHandler = null;
        }

        // 清理队列
        encodedFrameQueue.clear();
        framePool.clear();

        AppLog.d(TAG, "Camera " + cameraId + " OptimizedCodecRecorder released");
    }
}
