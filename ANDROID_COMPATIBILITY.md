# Android 版本兼容性说明

本文档说明应用在不同Android版本上的兼容性，特别是开机自启动功能。

---

## 📊 Android版本支持矩阵

| Android版本 | API级别 | 开机自启动 | 后台启动Activity | 策略 |
|------------|---------|----------|----------------|------|
| Android 9 及以下 | ≤28 | ✅ 完全支持 | ✅ 无限制 | 直接启动 |
| Android 10 | 29 | ⚠️ 受限 | ❌ 严格限制 | 悬浮窗+通知 |
| Android 11 | 30 | ⚠️ 受限 | ❌ 严格限制 | 悬浮窗+通知 |
| Android 12 | 31 | ⚠️ 受限 | ❌ 严格限制 | 悬浮窗+通知 |
| Android 13 | 33 | ⚠️ 受限 | ❌ 严格限制 | 悬浮窗+通知 |
| Android 14+ | 34+ | ⚠️ 受限 | ❌ 严格限制 | 悬浮窗+通知 |

---

## 🚫 Android 10+ 后台启动限制

### 限制内容

从 **Android 10 (API 29)** 开始，Google引入了严格的后台启动Activity限制：

```
应用不能从后台启动Activity，除非满足以下条件之一：
1. 应用有可见的窗口（前台）
2. 应用有前台服务
3. 应用在最近任务列表中
4. 应用有 SYSTEM_ALERT_WINDOW 权限且正在显示悬浮窗
5. 应用收到通知，用户点击了PendingIntent
```

**广播接收器（BroadcastReceiver）被认为是"后台"上下文**，因此：
- ❌ 从 `BOOT_COMPLETED` 广播直接启动Activity会失败
- ❌ 即使有权限，也可能被系统拦截

---

## 🛡️ 应用的三重启动策略

应用实现了**智能三重启动策略**，确保在所有Android版本都能正常工作：

### 策略 1: 悬浮窗权限绕过 ⭐ 最佳方案

**适用版本**: Android 10+  
**条件**: 用户已授权"后台唤醒权限"（SYSTEM_ALERT_WINDOW）

```java
// 检测是否有悬浮窗权限
if (Settings.canDrawOverlays(context)) {
    // 可以启动Activity（绕过限制）
    Intent intent = new Intent(context, MainActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(intent);  // ✅ 成功
}
```

**优点**:
- ✅ 可以直接启动Activity
- ✅ 应用立即可用
- ✅ 用户无感知

**缺点**:
- ⚠️ 需要用户手动授权悬浮窗权限

---

### 策略 2: 直接启动 ⭐ 传统方案

**适用版本**: Android 9 及以下  
**条件**: 无

```java
// Android 9及以下，无限制
Intent intent = new Intent(context, MainActivity.class);
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
context.startActivity(intent);  // ✅ 成功
```

**优点**:
- ✅ 简单直接
- ✅ 无需额外权限

**缺点**:
- ❌ Android 10+无法使用

---

### 策略 3: 静默后台待命 ⭐ 降级方案

**适用版本**: Android 10+ (无悬浮窗权限时)  
**条件**: 无

```java
// 不启动Activity，不显示通知，完全静默
// WorkManager保活任务已运行，进程保持活跃
// 等待用户手动打开或远程命令唤醒
```

**工作流程**:
```
开机 → 启动WorkManager保活 → 后台静默待命 → 远程命令自动唤醒
```

**优点**:
- ✅ 完全无感知，不打扰用户
- ✅ 进程保持存活（WorkManager + 无障碍服务）
- ✅ 可通过远程命令自动唤醒（WakeUpHelper）
- ✅ 用户手动打开时应用立即可用

**缺点**:
- ⚠️ 应用界面不会自动打开
- ⚠️ 依赖远程命令或手动打开

---

## 🔍 不同场景下的行为

### 场景 1: Android 11 + 有悬浮窗权限

```
系统开机
    ↓
BootReceiver 接收 BOOT_COMPLETED
    ↓
检查悬浮窗权限 ✅
    ↓
使用策略1: 直接启动Activity ✅
    ↓
应用立即在后台运行
    ↓
5秒后自动移到后台
    ↓
✅ 完美运行，用户无感知
```

### 场景 2: Android 11 + 无悬浮窗权限（完全静默）

```
系统开机
    ↓
BootReceiver 接收 BOOT_COMPLETED
    ↓
检查悬浮窗权限 ❌
    ↓
使用策略3: 静默后台待命（不显示任何通知）
    ↓
启动 WorkManager 保活任务 ✅
    ↓
进程保持活跃（无障碍服务提升优先级）
    ↓
情况1: 收到远程命令
    ↓
WakeUpHelper 自动唤醒（获取悬浮窗权限） ✅
    ↓
应用自动打开，执行任务 ✅

情况2: 用户手动打开
    ↓
应用立即可用（保活任务已在运行） ✅
    ↓
✅ 完全无感知，无需任何用户操作
```

### 场景 3: Android 9 及以下

```
系统开机
    ↓
BootReceiver 接收 BOOT_COMPLETED
    ↓
Android版本 < 10
    ↓
使用策略2: 直接启动Activity ✅
    ↓
应用立即在后台运行
    ↓
5秒后自动移到后台
    ↓
✅ 完美运行，无需任何权限
```

---

## 📋 推荐配置

### 车机系统（推荐）

```
✅ 开机自启动：启用
✅ 定时保活：启用
✅ 无障碍服务：启用
✅ 后台唤醒权限：授权  ← 关键！
✅ 钉钉自动启动：启用
```

**效果**: 开机即用，完全自动化

### 普通手机（降级方案）

```
✅ 开机自启动：启用
✅ 定时保活：启用
✅ 无障碍服务：启用
❌ 后台唤醒权限：未授权
✅ 钉钉自动启动：启用
```

**效果**: 开机后静默待命，远程命令自动唤醒（完全无感知）

---

## 🧪 测试方法

### 测试1: 验证开机自启动

```bash
# 1. 重启设备
adb reboot

# 2. 等待开机完成，查看日志
adb logcat -v time | grep BootReceiver

# 3. 预期输出
# Android 9及以下：
BootReceiver: 直接启动 Activity 成功

# Android 10+ 有悬浮窗权限：
BootReceiver: 使用悬浮窗权限成功启动 Activity

# Android 10+ 无悬浮窗权限：
BootReceiver: Activity 无法启动（Android 10+ 后台限制），应用将在后台待命
BootReceiver: 保活任务已运行，等待用户手动打开应用或远程命令唤醒
```

### 测试2: 检查悬浮窗权限

```bash
# 查看应用是否有悬浮窗权限
adb shell appops get com.kooo.evcam SYSTEM_ALERT_WINDOW

# 输出: allow (有权限) 或 ignore (无权限)
```

### 测试3: 模拟开机广播

```bash
# 不重启设备，直接发送开机广播测试
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED

# 查看日志
adb logcat -v time | grep BootReceiver
```

---

## 💡 常见问题

### Q1: 为什么开机后应用没有自动打开？

**A**: Android 10+后台启动限制。两种情况：
1. **有悬浮窗权限**: 会自动打开应用（推荐）
2. **无悬浮窗权限**: 应用在后台静默运行
   - ✅ WorkManager保活任务正常运行
   - ✅ 收到远程命令时自动唤醒（WakeUpHelper）
   - ✅ 或用户手动打开应用即可正常使用
   - ✅ 完全无感知，不需要点击任何通知

### Q2: 悬浮窗权限一定要授权吗？

**A**: 不是必须的，但强烈推荐：
- ✅ 授权后：开机自动打开应用界面，钉钉服务立即可用
- ⚠️ 不授权：开机后应用在后台待命，收到远程命令时自动唤醒
  - WorkManager保活任务保持进程活跃
  - 远程命令通过WakeUpHelper自动唤醒应用
  - 完全无感知，不需要用户操作

### Q3: 车机系统能绕过这些限制吗？

**A**: 取决于车机系统定制程度：
- 深度定制的车机系统可能放宽限制
- 原生Android车机系统同样受限
- 建议授权悬浮窗权限以确保兼容性

### Q4: 没有悬浮窗权限时，应用还能正常工作吗？

**A**: 可以，但体验略有不同：
- ✅ 开机后后台保活任务正常运行
- ✅ 远程命令可以正常唤醒应用
- ⚠️ 开机后需要用户手动打开应用一次，或等待远程命令唤醒
- ✅ 之后完全正常使用，无任何影响

---

## 📚 相关文档

- [Android 后台启动限制官方文档](https://developer.android.com/guide/components/activities/background-starts)
- [SYSTEM_ALERT_WINDOW 权限说明](https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW)
- [前台服务最佳实践](https://developer.android.com/guide/components/foreground-services)

---

## ✅ 总结

应用已针对**所有Android版本**优化：

| 版本 | 策略 | 效果 | 用户体验 |
|------|------|------|---------|
| Android 9- | 直接启动 | ✅ 完美 | 无感知 |
| Android 10+ (有权限) | 悬浮窗绕过 | ✅ 完美 | 无感知 |
| Android 10+ (无权限) | 静默待命 | ✅ 可用 | 远程自动唤醒 |

**推荐**: 授权"后台唤醒权限"以获得最佳体验！
