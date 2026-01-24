package com.kooo.evcam;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 保活无障碍服务
 * 用途：提高应用后台运行优先级，防止被系统清理
 * 注意：此服务不会监控或操作任何用户界面，仅用于保活
 */
public class KeepAliveAccessibilityService extends AccessibilityService {
    private static final String TAG = "KeepAliveAccessibility";
    private static KeepAliveAccessibilityService instance;
    private static boolean isServiceRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        isServiceRunning = true;
        AppLog.d(TAG, "无障碍服务已启动（保活模式）");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不处理任何无障碍事件，仅用于保活
        // 这样可以避免隐私问题和性能影响
    }

    @Override
    public void onInterrupt() {
        AppLog.d(TAG, "无障碍服务被中断");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppLog.d(TAG, "无障碍服务 onStartCommand");
        return START_STICKY; // 确保被杀后重启
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        isServiceRunning = false;
        AppLog.d(TAG, "无障碍服务已销毁");
    }

    /**
     * 检查服务是否正在运行
     */
    public static boolean isRunning() {
        return isServiceRunning && instance != null;
    }

    /**
     * 获取服务实例
     */
    public static KeepAliveAccessibilityService getInstance() {
        return instance;
    }
}
