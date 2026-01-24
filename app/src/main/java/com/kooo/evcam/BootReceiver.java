package com.kooo.evcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import com.kooo.evcam.dingtalk.DingTalkConfig;

/**
 * 开机启动广播接收器
 * 监听系统开机广播，自动启动必要的服务
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        AppLog.d(TAG, "收到广播: " + action);

        // 监听开机完成广播
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            AppLog.d(TAG, "系统开机完成，开始初始化应用服务...");
            
            // 检查用户是否启用了开机自启动
            AppConfig appConfig = new AppConfig(context);
            if (!appConfig.isAutoStartOnBoot()) {
                AppLog.d(TAG, "用户已禁用开机自启动，跳过初始化");
                return;
            }
            
            AppLog.d(TAG, "开机自启动已启用，开始初始化...");
            
            // 1. 启动 WorkManager 定时保活任务（如果启用了保活）
            if (appConfig.isKeepAliveEnabled()) {
                KeepAliveManager.startKeepAliveWork(context);
                AppLog.d(TAG, "WorkManager 定时保活任务已启动");
            } else {
                AppLog.d(TAG, "保活服务已禁用，跳过 WorkManager 启动");
            }

            // 2. 检查钉钉配置，如果启用了自动启动，则启动应用
            DingTalkConfig dingTalkConfig = new DingTalkConfig(context);
            boolean shouldStartApp = dingTalkConfig.isConfigured() && dingTalkConfig.isAutoStart();
            
            if (shouldStartApp) {
                AppLog.d(TAG, "钉钉服务配置为自动启动，准备启动应用...");
                
                // 使用多重策略确保在Android 10+也能正常启动
                boolean started = tryStartApplication(context);
                
                if (started) {
                    AppLog.d(TAG, "应用启动成功（开机自启动模式）");
                } else {
                    AppLog.w(TAG, "Activity无法启动（Android 10+后台限制，且无悬浮窗权限）");
                    AppLog.d(TAG, "应用在后台静默待命，远程命令可自动唤醒（无需用户操作）");
                }
            } else {
                AppLog.d(TAG, "钉钉服务未配置或未启用自动启动，仅启动保活任务");
            }

            AppLog.d(TAG, "开机自启动初始化完成");
        }
    }
    
    /**
     * 尝试启动应用（双重策略）
     * 策略1: 使用悬浮窗权限启动（Android 10+推荐）
     * 策略2: 直接启动Activity（Android 9及以下）
     * 策略3: 静默待命（不显示任何通知，等待远程命令唤醒）
     * 
     * @param context 上下文
     * @return true 表示Activity启动成功，false表示静默待命
     */
    private boolean tryStartApplication(Context context) {
        boolean activityStarted = false;
        
        // 策略1: 如果有悬浮窗权限，尝试启动（绕过Android 10+限制）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context)) {
            AppLog.d(TAG, "检测到悬浮窗权限，尝试启动 Activity（绕过后台限制）");
            try {
                Intent mainIntent = new Intent(context, MainActivity.class);
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mainIntent.putExtra("auto_start_from_boot", true);
                
                context.startActivity(mainIntent);
                activityStarted = true;
                AppLog.d(TAG, "使用悬浮窗权限成功启动 Activity");
            } catch (Exception e) {
                AppLog.e(TAG, "使用悬浮窗权限启动失败", e);
            }
        }
        
        // 策略2: Android 9及以下，或策略1失败时，直接启动Activity
        if (!activityStarted && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            AppLog.d(TAG, "Android 9 及以下，直接启动 Activity");
            try {
                Intent mainIntent = new Intent(context, MainActivity.class);
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mainIntent.putExtra("auto_start_from_boot", true);
                
                context.startActivity(mainIntent);
                activityStarted = true;
                AppLog.d(TAG, "直接启动 Activity 成功");
            } catch (Exception e) {
                AppLog.e(TAG, "直接启动 Activity 失败", e);
            }
        }
        
        // 策略3: 如果Activity无法启动（Android 10+无悬浮窗权限），静默待命
        if (!activityStarted) {
            AppLog.d(TAG, "Activity 无法启动（Android 10+ 后台限制），应用将在后台静默待命");
            AppLog.d(TAG, "WorkManager保活任务已运行，等待远程命令唤醒（WakeUpHelper）");
            
            // 完全静默，不启动前台服务，不显示通知
            // WorkManager 保活任务已在运行，足以保持进程活跃
            // 收到远程命令时，WakeUpHelper 会自动获取悬浮窗权限并唤醒应用
            // 或用户手动打开应用后一切正常
        }
        
        return activityStarted;
    }
}
